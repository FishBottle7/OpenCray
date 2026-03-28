from __future__ import annotations

import argparse
import json
import os
import time
import traceback
from pathlib import Path

from python_runner.p4a_bridge import run_request_file

SERVICE_STATE_SCHEMA_VERSION = 1
SERVICE_HEARTBEAT_INTERVAL_MS = 1000
SERVICE_START_ARGUMENT_FILE_NAME = "service-start-argument.json"


def _now_ms() -> int:
    return int(time.time() * 1000)


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    tmp_path.write_text(content, encoding="utf-8")
    os.replace(tmp_path, path)


def _write_json(path: Path, payload: dict[str, object]) -> None:
    _atomic_write_text(
        path,
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
    )


def _normalize_service_argument(raw_argument: str) -> dict[str, object]:
    raw_argument = raw_argument.strip()
    if not raw_argument:
        return {}
    try:
        payload = json.loads(raw_argument)
    except json.JSONDecodeError:
        return {"runtimeRoot": raw_argument}
    if isinstance(payload, dict):
        return payload
    return {"runtimeRoot": str(payload)}


def _service_state_dir(runtime_root: Path) -> Path:
    return runtime_root / "service_state"


def _service_start_argument_path(runtime_root: Path) -> Path:
    return _service_state_dir(runtime_root) / SERVICE_START_ARGUMENT_FILE_NAME


def _read_json_dict(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}


def _append_unique_path(candidates: list[Path], seen: set[str], path: Path) -> None:
    candidate_key = str(path)
    if candidate_key and candidate_key not in seen:
        seen.add(candidate_key)
        candidates.append(path)


def _append_files_root_candidates_from_path(
    raw_path: Path | None,
    candidates: list[Path],
    seen: set[str],
) -> None:
    if raw_path is None:
        return
    try:
        path = raw_path.resolve()
    except Exception:
        path = raw_path
    path_parts = path.parts
    for index, part in enumerate(path_parts):
        if part != "files":
            continue
        candidate = Path(path_parts[0], *path_parts[1 : index + 1]) if path_parts else path
        _append_unique_path(candidates, seen, candidate)
    if path.name == "files":
        _append_unique_path(candidates, seen, path)
    elif path.parent.name == "files":
        _append_unique_path(candidates, seen, path.parent)


def _android_files_root_candidates() -> list[Path]:
    candidates: list[Path] = []
    seen: set[str] = set()
    for env_name in ("ANDROID_ARGUMENT", "ANDROID_PRIVATE"):
        raw_value = os.environ.get(env_name, "").strip()
        if not raw_value:
            continue
        _append_files_root_candidates_from_path(Path(raw_value), candidates, seen)

    # Some p4a service builds do not surface ANDROID_ARGUMENT/ANDROID_PRIVATE.
    # In that case, recover the files root from the extracted service script itself.
    _append_files_root_candidates_from_path(
        Path(globals().get("__file__", "")) if globals().get("__file__") else None,
        candidates,
        seen,
    )
    _append_files_root_candidates_from_path(Path.cwd(), candidates, seen)
    return candidates


def _runtime_root_candidates() -> list[Path]:
    candidates: list[Path] = []
    seen: set[str] = set()

    explicit_runtime_root = os.environ.get("OPENCRAY_P4A_RUNTIME_ROOT", "").strip()
    if explicit_runtime_root:
        explicit_path = Path(explicit_runtime_root)
        seen.add(str(explicit_path))
        candidates.append(explicit_path)

    for files_root in _android_files_root_candidates():
        runtime_root = files_root / "python_runtime"
        runtime_root_key = str(runtime_root)
        if runtime_root_key not in seen:
            seen.add(runtime_root_key)
            candidates.append(runtime_root)
    return candidates


def _fallback_service_argument_payload() -> dict[str, object]:
    for runtime_root in _runtime_root_candidates():
        start_argument_path = _service_start_argument_path(runtime_root)
        if start_argument_path.exists():
            payload = _read_json_dict(start_argument_path)
            if payload:
                return payload
    return {}


def _service_argument_payload() -> dict[str, object]:
    raw_argument = os.environ.get("PYTHON_SERVICE_ARGUMENT", "").strip()
    if raw_argument:
        return _normalize_service_argument(raw_argument)
    return _fallback_service_argument_payload()


def _default_runtime_root() -> str:
    payload = _service_argument_payload()
    runtime_root = str(payload.get("runtimeRoot", "")).strip()
    if runtime_root:
        return runtime_root
    explicit_runtime_root = os.environ.get("OPENCRAY_P4A_RUNTIME_ROOT", "").strip()
    if explicit_runtime_root:
        return explicit_runtime_root
    for candidate in _runtime_root_candidates():
        return str(candidate)
    return ""


def _default_poll_interval_ms() -> int:
    payload = _service_argument_payload()
    raw_value = payload.get("pollIntervalMs")
    if raw_value is None:
        return 25
    try:
        return int(raw_value)
    except (TypeError, ValueError):
        return 25


def _default_run_once() -> bool:
    payload = _service_argument_payload()
    raw_value = payload.get("once")
    if isinstance(raw_value, bool):
        return raw_value
    if isinstance(raw_value, str):
        normalized = raw_value.strip().lower()
        if normalized in {"1", "true", "yes", "on"}:
            return True
        if normalized in {"0", "false", "no", "off"}:
            return False
    return False


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-root", default=_default_runtime_root())
    parser.add_argument("--poll-interval-ms", type=int, default=_default_poll_interval_ms())
    parser.add_argument("--once", action="store_true", default=_default_run_once())
    return parser


def _service_state_paths(runtime_root: Path) -> tuple[Path, Path]:
    state_dir = _service_state_dir(runtime_root)
    return state_dir / "service-state.json", state_dir / "service-ready.json"


def _load_json_if_present(path: Path) -> dict[str, object]:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}


def _write_service_state(
    runtime_root: Path,
    *,
    state: str,
    started_at_epoch_ms: int,
    poll_interval_ms: int,
    startup_request_id: str,
    current_request_id: str | None = None,
    claimed_request_id: str | None = None,
    execution_started_at_epoch_ms: int | None = None,
    last_observed_request_id: str | None = None,
    last_processed_request_id: str | None = None,
    last_processed_status: str | None = None,
    last_error: str | None = None,
    last_traceback: str | None = None,
) -> None:
    state_path, ready_path = _service_state_paths(runtime_root)
    existing = _load_json_if_present(state_path)
    payload: dict[str, object] = {
        "schemaVersion": SERVICE_STATE_SCHEMA_VERSION,
        "runtimeRoot": str(runtime_root),
        "pid": os.getpid(),
        "state": state,
        "startedAtEpochMs": int(existing.get("startedAtEpochMs", started_at_epoch_ms)),
        "updatedAtEpochMs": _now_ms(),
        "pollIntervalMs": poll_interval_ms,
        "startupRequestId": startup_request_id,
        "currentRequestId": current_request_id,
        "claimedRequestId": claimed_request_id,
        "executionStartedAtEpochMs": execution_started_at_epoch_ms,
        "lastObservedRequestId": last_observed_request_id
        if last_observed_request_id is not None
        else existing.get("lastObservedRequestId"),
        "lastProcessedRequestId": last_processed_request_id
        if last_processed_request_id is not None
        else existing.get("lastProcessedRequestId"),
        "lastProcessedStatus": last_processed_status
        if last_processed_status is not None
        else existing.get("lastProcessedStatus"),
        "lastError": last_error if last_error is not None else existing.get("lastError"),
        "lastTraceback": last_traceback if last_traceback is not None else existing.get("lastTraceback"),
    }
    _write_json(state_path, payload)
    _write_json(
        ready_path,
        {
            "schemaVersion": SERVICE_STATE_SCHEMA_VERSION,
            "runtimeRoot": str(runtime_root),
            "pid": os.getpid(),
            "state": state,
            "startedAtEpochMs": payload["startedAtEpochMs"],
            "updatedAtEpochMs": payload["updatedAtEpochMs"],
            "pollIntervalMs": poll_interval_ms,
            "startupRequestId": startup_request_id,
            "currentRequestId": current_request_id,
            "claimedRequestId": claimed_request_id,
            "executionStartedAtEpochMs": execution_started_at_epoch_ms,
        },
    )


def _ensure_runtime_dirs(runtime_root: Path) -> None:
    requests_dir = runtime_root / "requests"
    results_dir = runtime_root / "results"
    logs_dir = runtime_root / "logs"
    requests_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)
    (_service_state_paths(runtime_root)[0]).parent.mkdir(parents=True, exist_ok=True)


def _result_status(result_path: Path) -> str | None:
    if not result_path.exists():
        return None
    try:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    status = payload.get("status")
    return str(status).strip() if status is not None else None


def _safe_unlink(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except Exception:
        pass


def _pending_request_paths(
    requests_dir: Path,
    results_dir: Path,
    startup_request_id: str,
    *,
    limit: int | None = None,
) -> list[Path]:
    pending = [
        request_path
        for request_path in sorted(requests_dir.glob("*.json"))
        if not (results_dir / f"{request_path.stem}.json").exists()
    ]
    if not startup_request_id:
        return pending

    startup_path = next(
        (request_path for request_path in pending if request_path.stem == startup_request_id),
        None,
    )
    if startup_path is None:
        return pending[:limit] if limit is not None else pending
    ordered = [startup_path, *(request_path for request_path in pending if request_path != startup_path)]
    return ordered[:limit] if limit is not None else ordered


def _process_pending_requests(
    runtime_root: Path,
    *,
    started_at_epoch_ms: int,
    poll_interval_ms: int,
    startup_request_id: str,
    limit: int | None = None,
) -> int:
    _ensure_runtime_dirs(runtime_root)
    requests_dir = runtime_root / "requests"
    results_dir = runtime_root / "results"
    logs_dir = runtime_root / "logs"

    processed = 0
    for request_path in _pending_request_paths(
        requests_dir=requests_dir,
        results_dir=results_dir,
        startup_request_id=startup_request_id,
        limit=limit,
    ):
        request_id = request_path.stem
        result_path = results_dir / f"{request_id}.json"
        log_path = logs_dir / f"{request_id}.log"
        execution_started_at = _now_ms()
        _write_service_state(
            runtime_root,
            state="processing",
            started_at_epoch_ms=started_at_epoch_ms,
            poll_interval_ms=poll_interval_ms,
            startup_request_id=startup_request_id,
            current_request_id=request_id,
            claimed_request_id=request_id,
            execution_started_at_epoch_ms=execution_started_at,
            last_observed_request_id=request_id,
        )
        run_request_file(
            request_path=request_path,
            result_path=result_path,
            log_path=log_path,
            execution_started_at_epoch_ms=execution_started_at,
        )
        _write_service_state(
            runtime_root,
            state="idle",
            started_at_epoch_ms=started_at_epoch_ms,
            poll_interval_ms=poll_interval_ms,
            startup_request_id=startup_request_id,
            current_request_id=None,
            claimed_request_id=None,
            execution_started_at_epoch_ms=None,
            last_observed_request_id=request_id,
            last_processed_request_id=request_id,
            last_processed_status=_result_status(result_path),
        )
        _safe_unlink(request_path)
        processed += 1
    return processed


def main(argv: list[str] | None = None) -> int:
    ns = _build_arg_parser().parse_args(argv)
    runtime_root_raw = str(ns.runtime_root).strip()
    if not runtime_root_raw:
        raise SystemExit("PYTHON_SERVICE_ARGUMENT or --runtime-root must provide runtimeRoot.")

    runtime_root = Path(runtime_root_raw)
    started_at_epoch_ms = _now_ms()
    poll_interval_ms = max(int(ns.poll_interval_ms), 25)
    startup_request_id = str(_service_argument_payload().get("requestId", "")).strip()

    try:
        _ensure_runtime_dirs(runtime_root)
        _write_service_state(
            runtime_root,
            state="ready",
            started_at_epoch_ms=started_at_epoch_ms,
            poll_interval_ms=poll_interval_ms,
            startup_request_id=startup_request_id,
            current_request_id=None,
            claimed_request_id=None,
            execution_started_at_epoch_ms=None,
        )

        if ns.once:
            _process_pending_requests(
                runtime_root,
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
                limit=1,
            )
            _write_service_state(
                runtime_root,
                state="idle",
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
                current_request_id=None,
                claimed_request_id=None,
                execution_started_at_epoch_ms=None,
            )
            return 0

        next_heartbeat_epoch_ms = 0
        while True:
            now = _now_ms()
            if now >= next_heartbeat_epoch_ms:
                _write_service_state(
                    runtime_root,
                    state="idle",
                    started_at_epoch_ms=started_at_epoch_ms,
                    poll_interval_ms=poll_interval_ms,
                    startup_request_id=startup_request_id,
                    current_request_id=None,
                    claimed_request_id=None,
                    execution_started_at_epoch_ms=None,
                )
                next_heartbeat_epoch_ms = now + max(SERVICE_HEARTBEAT_INTERVAL_MS, poll_interval_ms)
            _process_pending_requests(
                runtime_root,
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
            )
            time.sleep(poll_interval_ms / 1000.0)
    except Exception as exc:
        try:
            _ensure_runtime_dirs(runtime_root)
            _write_service_state(
                runtime_root,
                state="startup_error",
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
                current_request_id=None,
                claimed_request_id=None,
                execution_started_at_epoch_ms=None,
                last_error=str(exc),
                last_traceback=traceback.format_exc(),
            )
        except Exception:
            pass
        raise


if __name__ == "__main__":
    raise SystemExit(main())
