import json
import pathlib
import subprocess
import sys


SCRIPT_PATH = (
    pathlib.Path(__file__)
    .resolve()
    .parents[1]
    / "tools"
    / "android_python_runtime_p4a"
    / "runtime"
    / "opencray_runtime_main.py"
)


def _write_request(
    path: pathlib.Path,
    *,
    task_id: str,
    workspace_root: pathlib.Path,
    script_path: pathlib.Path,
    args: list[str] | None = None,
) -> None:
    payload = {
        "schemaVersion": 1,
        "requestId": path.stem,
        "taskId": task_id,
        "workspaceRoot": str(workspace_root),
        "scriptPath": str(script_path),
        "args": args or [],
        "timeoutMs": 30000,
        "requestedAtEpochMs": 123,
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _run_bridge(
    *,
    request_path: pathlib.Path,
    result_path: pathlib.Path,
    log_path: pathlib.Path,
) -> dict[str, object]:
    completed = subprocess.run(
        [
            sys.executable,
            str(SCRIPT_PATH),
            "--request",
            str(request_path),
            "--result",
            str(result_path),
            "--log",
            str(log_path),
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
    )
    assert completed.returncode == 0, completed.stderr
    return json.loads(result_path.read_text(encoding="utf-8"))


def test_android_p4a_bridge_executes_workspace_script(workspace: pathlib.Path) -> None:
    request_path = workspace / "request.json"
    result_path = workspace / "result.json"
    log_path = workspace / "runtime.log"
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
    request_path = workspace / "request-outside.json"
    result_path = workspace / "result-outside.json"
    log_path = workspace / "runtime-outside.log"
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
    request_path = workspace / "request-error.json"
    result_path = workspace / "result-error.json"
    log_path = workspace / "runtime-error.log"
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
