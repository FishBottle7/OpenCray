from __future__ import annotations

import contextlib
import io
import json
import os
import runpy
import sys
import threading
import time
import traceback
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1


class Status:
    SUCCESS = "success"
    FAILED = "failed"
    DENIED = "denied"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class ErrorCode:
    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_WORKSPACE = "INVALID_WORKSPACE"
    DENY_PATH_TRAVERSAL = "DENY_PATH_TRAVERSAL"
    DENY_PATH_ESCAPE = "DENY_PATH_ESCAPE"
    SCRIPT_NOT_FOUND = "SCRIPT_NOT_FOUND"
    CANCELLED = "CANCELLED"
    TIMEOUT = "TIMEOUT"
    EXEC_ERROR = "EXEC_ERROR"
    RESULT_WRITE_ERROR = "RESULT_WRITE_ERROR"
    UNSUPPORTED_SCHEMA = "UNSUPPORTED_SCHEMA"


@dataclass(frozen=True)
class BridgeRequest:
    schema_version: int
    request_id: str
    task_id: str
    workspace_root: str
    script_path: str
    args: list[str]
    timeout_ms: int
    requested_at_epoch_ms: int
    execution_started_at_epoch_ms: int = 0
    cancel_path: str | None = None

    @classmethod
    def from_json_dict(cls, payload: dict[str, Any]) -> "BridgeRequest":
        return cls(
            schema_version=int(payload.get("schemaVersion", SCHEMA_VERSION)),
            request_id=str(payload["requestId"]),
            task_id=str(payload["taskId"]),
            workspace_root=str(payload["workspaceRoot"]),
            script_path=str(payload["scriptPath"]),
            args=[str(item) for item in payload.get("args", [])],
            timeout_ms=int(payload.get("timeoutMs", 0)),
            requested_at_epoch_ms=int(payload.get("requestedAtEpochMs", 0)),
            execution_started_at_epoch_ms=int(payload.get("executionStartedAtEpochMs", 0)),
            cancel_path=str(payload["cancelPath"]) if payload.get("cancelPath") else None,
        )


@dataclass(frozen=True)
class BridgeResult:
    request_id: str
    task_id: str
    status: str
    exit_code: int | None
    stdout: str
    stderr: str
    error_code: str | None
    error_message: str | None
    started_at_epoch_ms: int
    finished_at_epoch_ms: int
    metadata: dict[str, str]
    schema_version: int = SCHEMA_VERSION

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "requestId": self.request_id,
            "taskId": self.task_id,
            "status": self.status,
            "exitCode": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "errorCode": self.error_code,
            "errorMessage": self.error_message,
            "startedAtEpochMs": self.started_at_epoch_ms,
            "finishedAtEpochMs": self.finished_at_epoch_ms,
            "metadata": self.metadata,
        }


def _now_ms() -> int:
    return int(time.time() * 1000)


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    tmp_path.write_text(content, encoding="utf-8")
    os.replace(tmp_path, path)


def _write_log(path: Path | None, content: str) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _contains_traversal_segment(path: Path) -> bool:
    return any(part == ".." for part in path.parts)


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _resolve_in_workspace(workspace_root: Path, candidate: Path) -> Path:
    if _contains_traversal_segment(candidate):
        raise ValueError(ErrorCode.DENY_PATH_TRAVERSAL)
    resolved = candidate if candidate.is_absolute() else workspace_root / candidate
    resolved = resolved.resolve()
    if not _is_relative_to(resolved, workspace_root):
        raise ValueError(ErrorCode.DENY_PATH_ESCAPE)
    return resolved


def _base_metadata(
    request: BridgeRequest,
    *,
    resolved_script: Path | None = None,
    execution_started_at_epoch_ms: int | None = None,
) -> dict[str, str]:
    metadata = {
        "runtimeBackend": "p4a-python",
        "runtimeTransport": "file_json_bridge",
        "workspaceRoot": request.workspace_root,
        "scriptPath": request.script_path,
        "timeoutMs": str(request.timeout_ms),
        "timeoutEnforcement": "bridge_trace_hook",
        "cancellationModel": "file_marker_trace_hook",
        "executionModel": "runpy",
        "pythonVersion": sys.version.split()[0],
    }
    if request.requested_at_epoch_ms > 0:
        metadata["requestedAtEpochMs"] = str(request.requested_at_epoch_ms)
    if execution_started_at_epoch_ms is not None and execution_started_at_epoch_ms > 0:
        metadata["executionStartedAtEpochMs"] = str(execution_started_at_epoch_ms)
        metadata["timeoutClockStartEpochMs"] = str(execution_started_at_epoch_ms)
        if request.requested_at_epoch_ms > 0:
            metadata["queueDelayMs"] = str(
                max(0, execution_started_at_epoch_ms - request.requested_at_epoch_ms)
            )
    if resolved_script is not None:
        metadata["resolvedScriptPath"] = str(resolved_script)
    if request.cancel_path:
        metadata["cancelPath"] = request.cancel_path
    return metadata


class _ExecutionCancelled(Exception):
    pass


class _ExecutionTimedOut(Exception):
    pass


class _ExecutionGuard:
    def __init__(
        self,
        request: BridgeRequest,
        *,
        timeout_started_at_epoch_ms: int,
    ):
        self._cancel_path = Path(request.cancel_path).resolve() if request.cancel_path else None
        self._deadline_epoch_ms = None
        if request.timeout_ms > 0:
            self._deadline_epoch_ms = timeout_started_at_epoch_ms + request.timeout_ms
        self._previous_trace = None
        self._previous_thread_trace = None

    def _check(self) -> None:
        if self._cancel_path is not None and self._cancel_path.exists():
            raise _ExecutionCancelled()
        if self._deadline_epoch_ms is not None and _now_ms() > self._deadline_epoch_ms:
            raise _ExecutionTimedOut()

    def _trace(self, frame: Any, event: str, arg: Any) -> Any:
        if event == "line":
            self._check()
        return self._trace

    def __enter__(self) -> "_ExecutionGuard":
        self._check()
        self._previous_trace = sys.gettrace()
        get_thread_trace = getattr(threading, "gettrace", None)
        if callable(get_thread_trace):
            self._previous_thread_trace = get_thread_trace()
        sys.settrace(self._trace)
        threading.settrace(self._trace)
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> bool:
        sys.settrace(self._previous_trace)
        threading.settrace(self._previous_thread_trace)
        return False


def execute_request(request: BridgeRequest) -> BridgeResult:
    started_at = request.execution_started_at_epoch_ms if request.execution_started_at_epoch_ms > 0 else _now_ms()
    metadata = _base_metadata(
        request,
        execution_started_at_epoch_ms=started_at,
    )

    try:
        if request.schema_version != SCHEMA_VERSION:
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.UNSUPPORTED_SCHEMA,
                error_message=f"Unsupported schemaVersion {request.schema_version}.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )

        workspace_root = Path(request.workspace_root).resolve()
        if not workspace_root.exists() or not workspace_root.is_dir():
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.INVALID_WORKSPACE,
                error_message="Workspace root does not exist or is not a directory.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )

        resolved_script = _resolve_in_workspace(workspace_root, Path(request.script_path))
        metadata = _base_metadata(
            request,
            resolved_script=resolved_script,
            execution_started_at_epoch_ms=started_at,
        )
        if not resolved_script.exists() or not resolved_script.is_file():
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.SCRIPT_NOT_FOUND,
                error_message="Script path not found.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )

        stdout_buffer = io.StringIO()
        stderr_buffer = io.StringIO()
        previous_cwd = Path.cwd()
        previous_argv = sys.argv[:]
        inserted_paths: list[str] = []

        try:
            os.chdir(workspace_root)
            sys.argv = [str(resolved_script), *request.args]
            for candidate in (str(resolved_script.parent), str(workspace_root)):
                if candidate in sys.path:
                    continue
                sys.path.insert(0, candidate)
                inserted_paths.append(candidate)

            with contextlib.redirect_stdout(stdout_buffer), contextlib.redirect_stderr(stderr_buffer):
                with _ExecutionGuard(
                    request,
                    timeout_started_at_epoch_ms=started_at,
                ):
                    runpy.run_path(str(resolved_script), run_name="__main__")

            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.SUCCESS,
                exit_code=0,
                stdout=stdout_buffer.getvalue(),
                stderr=stderr_buffer.getvalue(),
                error_code=None,
                error_message=None,
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )
        except _ExecutionCancelled:
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.CANCELLED,
                exit_code=130,
                stdout=stdout_buffer.getvalue(),
                stderr=stderr_buffer.getvalue(),
                error_code=ErrorCode.CANCELLED,
                error_message="Python script cancelled.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )
        except _ExecutionTimedOut:
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.TIMEOUT,
                exit_code=124,
                stdout=stdout_buffer.getvalue(),
                stderr=stderr_buffer.getvalue(),
                error_code=ErrorCode.TIMEOUT,
                error_message="Python script exceeded timeout.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )
        except SystemExit as exit_signal:
            exit_code = exit_signal.code if isinstance(exit_signal.code, int) else 1
            if exit_signal.code not in (None, 0) and not isinstance(exit_signal.code, int):
                stderr_buffer.write(f"{exit_signal.code}\n")
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.SUCCESS if exit_code == 0 else Status.FAILED,
                exit_code=exit_code,
                stdout=stdout_buffer.getvalue(),
                stderr=stderr_buffer.getvalue(),
                error_code=None if exit_code == 0 else ErrorCode.EXEC_ERROR,
                error_message=None if exit_code == 0 else "Python script exited non-zero.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )
        except ValueError as path_error:
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.DENIED,
                exit_code=None,
                stdout=stdout_buffer.getvalue(),
                stderr=stderr_buffer.getvalue(),
                error_code=str(path_error),
                error_message="Script path is not allowed.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )
        except Exception:
            traceback.print_exc(file=stderr_buffer)
            finished_at = _now_ms()
            return BridgeResult(
                request_id=request.request_id,
                task_id=request.task_id,
                status=Status.FAILED,
                exit_code=1,
                stdout=stdout_buffer.getvalue(),
                stderr=stderr_buffer.getvalue(),
                error_code=ErrorCode.EXEC_ERROR,
                error_message="Python script raised an exception.",
                started_at_epoch_ms=started_at,
                finished_at_epoch_ms=finished_at,
                metadata=metadata,
            )
        finally:
            sys.argv = previous_argv
            os.chdir(previous_cwd)
            for candidate in inserted_paths:
                with contextlib.suppress(ValueError):
                    sys.path.remove(candidate)
    except ValueError as path_error:
        finished_at = _now_ms()
        return BridgeResult(
            request_id=request.request_id,
            task_id=request.task_id,
            status=Status.DENIED,
            exit_code=None,
            stdout="",
            stderr="",
            error_code=str(path_error),
            error_message="Script path is not allowed.",
            started_at_epoch_ms=started_at,
            finished_at_epoch_ms=finished_at,
            metadata=metadata,
        )
    except Exception as exc:
        finished_at = _now_ms()
        return BridgeResult(
            request_id=request.request_id,
            task_id=request.task_id,
            status=Status.FAILED,
            exit_code=None,
            stdout="",
            stderr="",
            error_code=ErrorCode.EXEC_ERROR,
            error_message=str(exc),
            started_at_epoch_ms=started_at,
            finished_at_epoch_ms=finished_at,
            metadata=metadata,
        )


def _invalid_request_result(
    *,
    request_id: str,
    task_id: str,
    error_message: str,
) -> BridgeResult:
    timestamp = _now_ms()
    return BridgeResult(
        request_id=request_id,
        task_id=task_id,
        status=Status.FAILED,
        exit_code=None,
        stdout="",
        stderr="",
        error_code=ErrorCode.INVALID_REQUEST,
        error_message=error_message,
        started_at_epoch_ms=timestamp,
        finished_at_epoch_ms=timestamp,
        metadata={
            "runtimeBackend": "p4a-python",
            "runtimeTransport": "file_json_bridge",
        },
    )


def run_request_file(
    *,
    request_path: Path,
    result_path: Path,
    log_path: Path | None = None,
    execution_started_at_epoch_ms: int | None = None,
) -> int:
    request_id = request_path.stem
    task_id = "unknown-task"
    try:
        payload = json.loads(request_path.read_text(encoding="utf-8"))
        if isinstance(payload, dict):
            request_id = str(payload.get("requestId", request_id))
            task_id = str(payload.get("taskId", task_id))
            if execution_started_at_epoch_ms is not None and execution_started_at_epoch_ms > 0:
                payload["executionStartedAtEpochMs"] = execution_started_at_epoch_ms
        request = BridgeRequest.from_json_dict(payload)
        result = execute_request(request)
    except Exception as exc:
        result = _invalid_request_result(
            request_id=request_id,
            task_id=task_id,
            error_message=str(exc),
        )

    serialized_result = json.dumps(
        result.to_json_dict(),
        ensure_ascii=False,
        indent=2,
    ) + "\n"
    log_content = (
        f"request={request_path}\n"
        f"result={result_path}\n"
        f"status={result.status}\n"
        f"error_code={result.error_code or ''}\n"
        f"error_message={result.error_message or ''}\n"
        f"stderr:\n{result.stderr}"
    )

    try:
        _atomic_write_text(result_path, serialized_result)
        _write_log(log_path, log_content)
        return 0
    except Exception as exc:
        fallback_result = _invalid_request_result(
            request_id=request_id,
            task_id=task_id,
            error_message=f"{ErrorCode.RESULT_WRITE_ERROR}: {exc}",
        )
        _atomic_write_text(
            result_path,
            json.dumps(fallback_result.to_json_dict(), ensure_ascii=False, indent=2) + "\n",
        )
        _write_log(log_path, f"result_write_error={exc}\n")
        return 1
