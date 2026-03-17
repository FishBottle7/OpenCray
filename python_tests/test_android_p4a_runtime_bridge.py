import json
import pathlib
import subprocess
import sys
import time


SCRIPT_MODULE = "python_runner.p4a_service_main"


def _write_request(
    path: pathlib.Path,
    *,
    task_id: str,
    workspace_root: pathlib.Path,
    script_path: pathlib.Path,
    args: list[str] | None = None,
    cancel_path: pathlib.Path | None = None,
    timeout_ms: int = 30000,
) -> None:
    payload = {
        "schemaVersion": 1,
        "requestId": path.stem,
        "taskId": task_id,
        "workspaceRoot": str(workspace_root),
        "scriptPath": str(script_path),
        "args": args or [],
        "timeoutMs": timeout_ms,
        "requestedAtEpochMs": int(time.time() * 1000),
    }
    if cancel_path is not None:
        payload["cancelPath"] = str(cancel_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _run_bridge(
    *,
    request_path: pathlib.Path,
    result_path: pathlib.Path,
    log_path: pathlib.Path,
) -> dict[str, object]:
    runtime_root = request_path.parent.parent
    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            SCRIPT_MODULE,
            "--runtime-root",
            str(runtime_root),
            "--once",
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
    )
    assert completed.returncode == 0, completed.stderr
    return json.loads(result_path.read_text(encoding="utf-8"))


def _bridge_paths(workspace: pathlib.Path, name: str) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    runtime_root = workspace / ".p4a-bridge-tests" / name
    request_path = runtime_root / "requests" / f"{name}.json"
    result_path = runtime_root / "results" / f"{name}.json"
    log_path = runtime_root / "logs" / f"{name}.log"
    return request_path, result_path, log_path


def test_android_p4a_bridge_executes_workspace_script(workspace: pathlib.Path) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-success")
    script_path = workspace / "hello_android_runtime.py"
    script_path.write_text(
        "import sys\nprint(f'hello {sys.argv[1]}')\n",
        encoding="utf-8",
    )
    _write_request(
        request_path,
        task_id="task-success",
        workspace_root=workspace,
        script_path=script_path,
        args=["OpenCray"],
    )

    result = _run_bridge(
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    assert result["status"] == "success"
    assert result["exitCode"] == 0
    assert result["stdout"] == "hello OpenCray\n"
    assert result["stderr"] == ""
    assert result["errorCode"] is None
    assert result["metadata"]["runtimeTransport"] == "file_json_bridge"
    assert result["metadata"]["resolvedScriptPath"] == str(script_path.resolve())
    assert log_path.exists()


def test_android_p4a_bridge_denies_script_outside_workspace(workspace: pathlib.Path, tmp_path: pathlib.Path) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-outside")
    outside_script = tmp_path / "outside.py"
    outside_script.write_text("print('outside')\n", encoding="utf-8")
    _write_request(
        request_path,
        task_id="task-denied",
        workspace_root=workspace,
        script_path=outside_script,
    )

    result = _run_bridge(
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    assert result["status"] == "denied"
    assert result["errorCode"] == "DENY_PATH_ESCAPE"


def test_android_p4a_bridge_reports_script_exception(workspace: pathlib.Path) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-error")
    script_path = workspace / "boom.py"
    script_path.write_text(
        "raise RuntimeError('boom')\n",
        encoding="utf-8",
    )
    _write_request(
        request_path,
        task_id="task-error",
        workspace_root=workspace,
        script_path=script_path,
    )

    result = _run_bridge(
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    assert result["status"] == "failed"
    assert result["exitCode"] == 1
    assert result["errorCode"] == "EXEC_ERROR"
    assert result["errorMessage"] == "Python script raised an exception."
    assert "RuntimeError: boom" in result["stderr"]


def test_android_p4a_bridge_cancels_when_cancel_marker_is_created(workspace: pathlib.Path) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-cancel")
    cancel_path = request_path.parent.parent / "cancels" / "request-cancel.cancel"
    started_path = workspace / "cancel-started.txt"
    script_path = workspace / "wait_for_cancel.py"
    script_path.write_text(
        "\n".join(
            [
                "from pathlib import Path",
                f"started_path = Path(r'{started_path}')",
                "started_path.write_text('started', encoding='utf-8')",
                f"cancel_path = Path(r'{cancel_path}')",
                "while not cancel_path.exists():",
                "    pass",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    _write_request(
        request_path,
        task_id="task-cancel",
        workspace_root=workspace,
        script_path=script_path,
        cancel_path=cancel_path,
        timeout_ms=5000,
    )

    completed = subprocess.Popen(
        [
            sys.executable,
            "-m",
            SCRIPT_MODULE,
            "--runtime-root",
            str(request_path.parent.parent),
            "--once",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    deadline = time.time() + 10
    while not started_path.exists():
        assert time.time() < deadline, "timed out waiting for Python script to start"
        time.sleep(0.01)

    cancel_path.parent.mkdir(parents=True, exist_ok=True)
    cancel_path.write_text("cancelled\n", encoding="utf-8")
    stdout, stderr = completed.communicate(timeout=20)
    assert completed.returncode == 0, f"{stderr}\n{stdout}"

    result = json.loads(result_path.read_text(encoding="utf-8"))
    assert result["status"] == "cancelled"
    assert result["exitCode"] == 130
    assert result["errorCode"] == "CANCELLED"
    assert result["errorMessage"] == "Python script cancelled."
    assert result["metadata"]["cancelPath"] == str(cancel_path)


def test_android_p4a_bridge_times_out_long_running_script(workspace: pathlib.Path) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-timeout")
    script_path = workspace / "never_finishes.py"
    script_path.write_text(
        "while True:\n    pass\n",
        encoding="utf-8",
    )
    _write_request(
        request_path,
        task_id="task-timeout",
        workspace_root=workspace,
        script_path=script_path,
        timeout_ms=100,
    )

    result = _run_bridge(
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    assert result["status"] == "timeout"
    assert result["exitCode"] == 124
    assert result["errorCode"] == "TIMEOUT"
    assert result["errorMessage"] == "Python script exceeded timeout."
