from __future__ import annotations

import argparse
import dataclasses
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from typing import Any, Mapping, Sequence


SCHEMA_VERSION = 1


class Status:
    SUCCESS = "success"
    FAILED = "failed"
    DENIED = "denied"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class ErrorCode:
    INVALID_WORKSPACE = "INVALID_WORKSPACE"
    DENY_PATH_TRAVERSAL = "DENY_PATH_TRAVERSAL"
    DENY_PATH_ESCAPE = "DENY_PATH_ESCAPE"
    SCRIPT_NOT_FOUND = "SCRIPT_NOT_FOUND"
    ENV_NOT_INITIALIZED = "ENV_NOT_INITIALIZED"
    INSTALL_ERROR = "INSTALL_ERROR"
    MANIFEST_WRITE_ERROR = "MANIFEST_WRITE_ERROR"
    VENV_CREATE_ERROR = "VENV_CREATE_ERROR"
    PIP_FREEZE_ERROR = "PIP_FREEZE_ERROR"
    TIMEOUT = "TIMEOUT"
    EXEC_ERROR = "EXEC_ERROR"


def _now_ms() -> int:
    return int(time.time() * 1000)


def _coerce_text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


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


def _opencray_dir(workspace_root: Path) -> Path:
    return workspace_root / ".opencray" / "python"


def _venv_dir(workspace_root: Path) -> Path:
    return _opencray_dir(workspace_root) / "venv"


def _venv_python_executable(workspace_root: Path) -> Path:
    venv = _venv_dir(workspace_root)
    if os.name == "nt":
        return venv / "Scripts" / "python.exe"
    return venv / "bin" / "python"


def _wheelhouse_dir(workspace_root: Path) -> Path:
    return _opencray_dir(workspace_root) / "wheelhouse"


def _manifest_path(workspace_root: Path) -> Path:
    return _opencray_dir(workspace_root) / "manifest.json"


def _temp_dir(workspace_root: Path) -> Path:
    return _opencray_dir(workspace_root) / "tmp"


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    os.replace(tmp, path)


def _ensure_venv(workspace_root: Path, timeout_seconds: float) -> tuple[bool, str, str]:
    """Ensure venv exists. Returns (ok, stdout, stderr)."""
    venv_dir = _venv_dir(workspace_root)
    if venv_dir.exists():
        return True, "", ""

    venv_dir.parent.mkdir(parents=True, exist_ok=True)
    try:
        proc = subprocess.run(
            [sys.executable, "-m", "venv", "--without-pip", str(venv_dir)],
            cwd=str(workspace_root),
            env=_base_env(workspace_root),
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as e:
        return False, _coerce_text(e.stdout), _coerce_text(e.stderr)

    return proc.returncode == 0, proc.stdout, proc.stderr


def _venv_site_packages_dir(workspace_root: Path) -> Path:
    venv_dir = _venv_dir(workspace_root)
    if os.name == "nt":
        return venv_dir / "Lib" / "site-packages"
    version_tag = f"python{sys.version_info.major}.{sys.version_info.minor}"
    return venv_dir / "lib" / version_tag / "site-packages"


def _bundled_pip_wheel_path() -> Path | None:
    try:
        import ensurepip
    except Exception:
        return None
    bundled_dir = Path(ensurepip.__file__).resolve().parent / "_bundled"
    wheels = sorted(bundled_dir.glob("pip-*.whl"))
    return wheels[-1] if wheels else None


def _bootstrap_pip_into_venv(workspace_root: Path) -> tuple[bool, str, str]:
    site_packages_dir = _venv_site_packages_dir(workspace_root)
    site_packages_dir.mkdir(parents=True, exist_ok=True)
    if any(site_packages_dir.glob("pip-*.dist-info")) and (site_packages_dir / "pip").exists():
        return True, "", ""

    wheel_path = _bundled_pip_wheel_path()
    if wheel_path is None or not wheel_path.exists():
        return False, "", "Bundled pip wheel is unavailable."

    for path in site_packages_dir.glob("pip*"):
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
        else:
            path.unlink(missing_ok=True)

    with zipfile.ZipFile(wheel_path) as wheel_archive:
        wheel_archive.extractall(site_packages_dir)
    return True, "", ""


def _base_env(workspace_root: Path | None = None) -> dict[str, str]:
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    env["PYTHONNOUSERSITE"] = "1"
    # Ignore global/user pip config for determinism.
    env["PIP_CONFIG_FILE"] = os.devnull
    env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
    if workspace_root is not None:
        temp_dir = _temp_dir(workspace_root)
        temp_dir.mkdir(parents=True, exist_ok=True)
        temp_dir_value = str(temp_dir)
        env["TEMP"] = temp_dir_value
        env["TMP"] = temp_dir_value
        env["TMPDIR"] = temp_dir_value
    return env


def _parse_pip_freeze(freeze_output: str) -> tuple[dict[str, str], list[str]]:
    pinned: dict[str, str] = {}
    other: list[str] = []
    for raw in freeze_output.splitlines():
        line = raw.strip()
        if not line:
            continue
        if "==" in line and not line.startswith("-e ") and " @ " not in line:
            name, version = line.split("==", 1)
            pinned[name] = version
        else:
            other.append(line)
    return pinned, sorted(other)


def install_requirements(
    *,
    workspace_root: Path,
    requirements: Sequence[str],
    timeout_seconds: float = 120.0,
) -> OperationResult:
    started = _now_ms()
    metadata: dict[str, str] = {
        "operation": "install",
        "workspace_root": str(workspace_root),
        "timeout_seconds": str(timeout_seconds),
    }

    try:
        ws = workspace_root.resolve()
        if not ws.exists() or not ws.is_dir():
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.INVALID_WORKSPACE,
                error_message="Workspace root does not exist or is not a directory.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        # Ensure workspace-managed directories exist.
        _opencray_dir(ws).mkdir(parents=True, exist_ok=True)
        wheelhouse = _wheelhouse_dir(ws)
        wheelhouse.mkdir(parents=True, exist_ok=True)
        metadata["wheelhouse"] = str(wheelhouse)
        metadata["manifest_path"] = str(_manifest_path(ws))

        ok, venv_out, venv_err = _ensure_venv(ws, timeout_seconds=timeout_seconds)
        if not ok:
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout=venv_out,
                stderr=venv_err,
                error_code=ErrorCode.VENV_CREATE_ERROR,
                error_message="Failed to create venv in workspace.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        python_exe = _venv_python_executable(ws)
        metadata["python_executable"] = str(python_exe)
        if not python_exe.exists():
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.ENV_NOT_INITIALIZED,
                error_message="Python environment is not initialized (venv python missing).",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        bootstrap_ok, bootstrap_out, bootstrap_err = _bootstrap_pip_into_venv(ws)
        if not bootstrap_ok:
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout=bootstrap_out,
                stderr=bootstrap_err,
                error_code=ErrorCode.VENV_CREATE_ERROR,
                error_message="Failed to bootstrap pip in workspace venv.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        env = _base_env(ws)

        pip_install_cmd = [
            str(python_exe),
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-input",
            "--no-cache-dir",
            "--progress-bar",
            "off",
            "--no-index",
            "--find-links",
            str(wheelhouse),
            *list(requirements),
        ]
        metadata["pip_install_command"] = " ".join(pip_install_cmd)

        try:
            proc = subprocess.run(
                pip_install_cmd,
                cwd=str(ws),
                env=env,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
            )
        except subprocess.TimeoutExpired as e:
            finished = _now_ms()
            return OperationResult(
                status=Status.TIMEOUT,
                exit_code=None,
                stdout=_coerce_text(e.stdout),
                stderr=_coerce_text(e.stderr),
                error_code=ErrorCode.TIMEOUT,
                error_message=f"Operation exceeded timeout of {timeout_seconds} seconds.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        if proc.returncode != 0:
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=proc.returncode,
                stdout=proc.stdout,
                stderr=proc.stderr,
                error_code=ErrorCode.INSTALL_ERROR,
                error_message="pip install failed.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        freeze_cmd = [str(python_exe), "-m", "pip", "freeze", "--all"]
        metadata["pip_freeze_command"] = " ".join(freeze_cmd)
        try:
            freeze_proc = subprocess.run(
                freeze_cmd,
                cwd=str(ws),
                env=env,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
            )
        except subprocess.TimeoutExpired as e:
            finished = _now_ms()
            return OperationResult(
                status=Status.TIMEOUT,
                exit_code=None,
                stdout=(proc.stdout or "") + _coerce_text(e.stdout),
                stderr=(proc.stderr or "") + _coerce_text(e.stderr),
                error_code=ErrorCode.TIMEOUT,
                error_message=f"Operation exceeded timeout of {timeout_seconds} seconds.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        if freeze_proc.returncode != 0:
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=freeze_proc.returncode,
                stdout=proc.stdout + freeze_proc.stdout,
                stderr=proc.stderr + freeze_proc.stderr,
                error_code=ErrorCode.PIP_FREEZE_ERROR,
                error_message="pip freeze failed.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        pinned, other = _parse_pip_freeze(freeze_proc.stdout)
        manifest_obj = {
            "schema_version": SCHEMA_VERSION,
            "python_version": platform.python_version(),
            "requested": list(requirements),
            "packages": pinned,
            "unparsed_freeze": other,
        }

        try:
            _atomic_write_text(
                _manifest_path(ws),
                json.dumps(manifest_obj, ensure_ascii=False, sort_keys=True, indent=2)
                + "\n",
            )
        except Exception as e:
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout=proc.stdout + freeze_proc.stdout,
                stderr=proc.stderr + freeze_proc.stderr,
                error_code=ErrorCode.MANIFEST_WRITE_ERROR,
                error_message=str(e),
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        finished = _now_ms()
        combined_stdout = (proc.stdout or "") + (freeze_proc.stdout or "")
        combined_stderr = (proc.stderr or "") + (freeze_proc.stderr or "")
        return OperationResult(
            status=Status.SUCCESS,
            exit_code=0,
            stdout=combined_stdout,
            stderr=combined_stderr,
            error_code=None,
            error_message=None,
            started_at_epoch_ms=started,
            finished_at_epoch_ms=finished,
            metadata=metadata,
        )

    except Exception as e:  # pragma: no cover
        finished = _now_ms()
        return OperationResult(
            status=Status.FAILED,
            exit_code=None,
            stdout="",
            stderr="",
            error_code=ErrorCode.EXEC_ERROR,
            error_message=str(e),
            started_at_epoch_ms=started,
            finished_at_epoch_ms=finished,
            metadata=metadata,
        )


@dataclasses.dataclass(frozen=True)
class OperationResult:
    status: str
    exit_code: int | None
    stdout: str
    stderr: str
    error_code: str | None
    error_message: str | None
    started_at_epoch_ms: int
    finished_at_epoch_ms: int
    metadata: Mapping[str, str]
    schema_version: int = SCHEMA_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "exit_code": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "error_code": self.error_code,
            "error_message": self.error_message,
            "started_at_epoch_ms": self.started_at_epoch_ms,
            "finished_at_epoch_ms": self.finished_at_epoch_ms,
            "metadata": dict(self.metadata),
            "schema_version": self.schema_version,
        }


def exec_script(
    *,
    workspace_root: Path,
    script_path: Path,
    args: Sequence[str] = (),
    timeout_seconds: float = 30.0,
) -> OperationResult:
    started = _now_ms()
    metadata: dict[str, str] = {
        "operation": "exec",
        "workspace_root": str(workspace_root),
        "timeout_seconds": str(timeout_seconds),
    }

    try:
        ws = workspace_root.resolve()
        if not ws.exists() or not ws.is_dir():
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.INVALID_WORKSPACE,
                error_message="Workspace root does not exist or is not a directory.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        resolved_script = _resolve_in_workspace(ws, script_path)
        metadata["script_path"] = str(resolved_script)
        if not resolved_script.exists() or not resolved_script.is_file():
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.SCRIPT_NOT_FOUND,
                error_message="Script path not found.",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        python_exe = _venv_python_executable(ws)
        metadata["python_executable"] = str(python_exe)
        if not python_exe.exists():
            finished = _now_ms()
            return OperationResult(
                status=Status.FAILED,
                exit_code=None,
                stdout="",
                stderr="",
                error_code=ErrorCode.ENV_NOT_INITIALIZED,
                error_message="Python environment is not initialized (venv missing).",
                started_at_epoch_ms=started,
                finished_at_epoch_ms=finished,
                metadata=metadata,
            )

        cmd = [str(python_exe), str(resolved_script), *list(args)]
        env = os.environ.copy()
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        proc = subprocess.run(
            cmd,
            cwd=str(ws),
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )

        finished = _now_ms()
        return OperationResult(
            status=Status.SUCCESS if proc.returncode == 0 else Status.FAILED,
            exit_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
            error_code=None if proc.returncode == 0 else ErrorCode.EXEC_ERROR,
            error_message=None
            if proc.returncode == 0
            else "Python script exited non-zero.",
            started_at_epoch_ms=started,
            finished_at_epoch_ms=finished,
            metadata=metadata,
        )

    except subprocess.TimeoutExpired as e:
        finished = _now_ms()
        return OperationResult(
            status=Status.TIMEOUT,
            exit_code=None,
            stdout=_coerce_text(e.stdout),
            stderr=_coerce_text(e.stderr),
            error_code=ErrorCode.TIMEOUT,
            error_message=f"Operation exceeded timeout of {timeout_seconds} seconds.",
            started_at_epoch_ms=started,
            finished_at_epoch_ms=finished,
            metadata=metadata,
        )
    except ValueError as e:
        finished = _now_ms()
        code = str(e)
        if code not in (ErrorCode.DENY_PATH_TRAVERSAL, ErrorCode.DENY_PATH_ESCAPE):
            code = ErrorCode.EXEC_ERROR
        return OperationResult(
            status=Status.DENIED,
            exit_code=None,
            stdout="",
            stderr="",
            error_code=code,
            error_message="Script path is not allowed.",
            started_at_epoch_ms=started,
            finished_at_epoch_ms=finished,
            metadata=metadata,
        )
    except Exception as e:  # pragma: no cover
        finished = _now_ms()
        return OperationResult(
            status=Status.FAILED,
            exit_code=None,
            stdout="",
            stderr="",
            error_code=ErrorCode.EXEC_ERROR,
            error_message=str(e),
            started_at_epoch_ms=started,
            finished_at_epoch_ms=finished,
            metadata=metadata,
        )


def _parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python_runner")
    sub = parser.add_subparsers(dest="command", required=True)

    exec_p = sub.add_parser(
        "exec", help="Execute a Python script inside the workspace venv"
    )
    exec_p.add_argument("--workspace", required=True)
    exec_p.add_argument("--script", required=True)
    exec_p.add_argument("--timeout-seconds", type=float, default=30.0)
    exec_p.add_argument("args", nargs=argparse.REMAINDER)

    install_p = sub.add_parser(
        "install", help="Install Python dependencies into workspace-managed venv"
    )
    install_p.add_argument("--workspace", required=True)
    install_p.add_argument("--timeout-seconds", type=float, default=120.0)
    install_p.add_argument("requirements", nargs="*")

    return parser.parse_args(list(argv))


def main(argv: Sequence[str] | None = None) -> int:
    ns = _parse_args(sys.argv[1:] if argv is None else argv)

    if ns.command == "exec":
        extra_args = list(ns.args)
        if extra_args and extra_args[0] == "--":
            extra_args = extra_args[1:]

        result = exec_script(
            workspace_root=Path(ns.workspace),
            script_path=Path(ns.script),
            args=extra_args,
            timeout_seconds=float(ns.timeout_seconds),
        )
        sys.stdout.write(json.dumps(result.to_dict(), ensure_ascii=False))
        sys.stdout.write("\n")
        return 0

    if ns.command == "install":
        result = install_requirements(
            workspace_root=Path(ns.workspace),
            requirements=list(ns.requirements),
            timeout_seconds=float(ns.timeout_seconds),
        )
        sys.stdout.write(json.dumps(result.to_dict(), ensure_ascii=False))
        sys.stdout.write("\n")
        return 0

    raise AssertionError("unreachable")


if __name__ == "__main__":
    raise SystemExit(main())
