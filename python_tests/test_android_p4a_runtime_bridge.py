import json
import os
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
    requested_at_epoch_ms: int | None = None,
    execution_started_at_epoch_ms: int | None = None,
) -> None:
    payload = {
        "schemaVersion": 1,
        "requestId": path.stem,
        "taskId": task_id,
        "workspaceRoot": str(workspace_root),
        "scriptPath": str(script_path),
        "args": args or [],
        "timeoutMs": timeout_ms,
        "requestedAtEpochMs": requested_at_epoch_ms or int(time.time() * 1000),
    }
    if execution_started_at_epoch_ms is not None:
        payload["executionStartedAtEpochMs"] = execution_started_at_epoch_ms
    if cancel_path is not None:
        payload["cancelPath"] = str(cancel_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _run_bridge(
    *,
    request_path: pathlib.Path,
    result_path: pathlib.Path,
    log_path: pathlib.Path,
    env: dict[str, str] | None = None,
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
        env={**os.environ, **(env or {})},
    )
    assert completed.returncode == 0, completed.stderr
    return json.loads(result_path.read_text(encoding="utf-8"))


def _bridge_paths(workspace: pathlib.Path, name: str) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    runtime_root = workspace / ".p4a-bridge-tests" / name
    request_path = runtime_root / "requests" / f"{name}.json"
    result_path = runtime_root / "results" / f"{name}.json"
    log_path = runtime_root / "logs" / f"{name}.log"
    return request_path, result_path, log_path


def _service_state_paths(workspace: pathlib.Path, name: str) -> tuple[pathlib.Path, pathlib.Path]:
    runtime_root = workspace / ".p4a-bridge-tests" / name
    state_dir = runtime_root / "service_state"
    return state_dir / "service-state.json", state_dir / "service-ready.json"


def _write_service_start_argument(
    runtime_root: pathlib.Path,
    *,
    request_id: str,
    request_path: pathlib.Path,
    result_path: pathlib.Path,
    log_path: pathlib.Path,
    poll_interval_ms: int = 50,
    once: bool = True,
) -> pathlib.Path:
    path = runtime_root / "service_state" / "service-start-argument.json"
    payload = {
        "schemaVersion": 1,
        "runtimeRoot": str(runtime_root),
        "requestId": request_id,
        "requestPath": str(request_path),
        "resultPath": str(result_path),
        "logPath": str(log_path),
        "pollIntervalMs": poll_interval_ms,
        "once": once,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


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
    assert not request_path.exists()


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


def test_android_p4a_bridge_uses_execution_start_for_timeout_budget(
    workspace: pathlib.Path,
) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-execution-start")
    script_path = workspace / "execution_start.py"
    script_path.write_text("print('execution start budget ok')\n", encoding="utf-8")
    now_ms = int(time.time() * 1000)
    _write_request(
        request_path,
        task_id="task-execution-start",
        workspace_root=workspace,
        script_path=script_path,
        timeout_ms=200,
        requested_at_epoch_ms=now_ms - 2_000,
        execution_started_at_epoch_ms=now_ms,
    )

    result = _run_bridge(
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    assert result["status"] == "success"
    assert result["stdout"] == "execution start budget ok\n"
    assert result["metadata"]["executionStartedAtEpochMs"] == str(now_ms)
    assert result["metadata"]["timeoutClockStartEpochMs"] == str(now_ms)
    assert int(result["metadata"]["queueDelayMs"]) >= 2_000


def test_android_p4a_service_writes_ready_and_state_markers(workspace: pathlib.Path) -> None:
    request_path, result_path, log_path = _bridge_paths(workspace, "request-service-state")
    state_path, ready_path = _service_state_paths(workspace, "request-service-state")
    script_path = workspace / "state_probe.py"
    script_path.write_text("print('service state ok')\n", encoding="utf-8")
    _write_request(
        request_path,
        task_id="task-service-state",
        workspace_root=workspace,
        script_path=script_path,
    )

    result = _run_bridge(
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    assert result["status"] == "success"
    assert state_path.exists()
    assert ready_path.exists()

    service_state = json.loads(state_path.read_text(encoding="utf-8"))
    service_ready = json.loads(ready_path.read_text(encoding="utf-8"))
    assert service_state["state"] == "idle"
    assert service_state["lastProcessedRequestId"] == "request-service-state"
    assert service_state["lastProcessedStatus"] == "success"
    assert service_state["claimedRequestId"] is None
    assert service_state["executionStartedAtEpochMs"] is None
    assert service_ready["state"] == "idle"
    assert service_ready["claimedRequestId"] is None
    assert service_ready["executionStartedAtEpochMs"] is None


def test_android_p4a_service_once_mode_only_processes_startup_request(workspace: pathlib.Path) -> None:
    runtime_root = workspace / ".p4a-bridge-tests" / "request-startup-priority"
    request_slow = runtime_root / "requests" / "request-slow.json"
    request_fast = runtime_root / "requests" / "request-fast.json"
    result_slow = runtime_root / "results" / "request-slow.json"
    result_fast = runtime_root / "results" / "request-fast.json"
    log_fast = runtime_root / "logs" / "request-fast.log"

    slow_script = workspace / "slow_request.py"
    slow_script.write_text(
        "import time\n"
        "time.sleep(0.3)\n"
        "print('slow request done')\n",
        encoding="utf-8",
    )
    fast_script = workspace / "fast_request.py"
    fast_script.write_text("print('fast request done')\n", encoding="utf-8")

    _write_request(
        request_slow,
        task_id="task-slow",
        workspace_root=workspace,
        script_path=slow_script,
    )
    _write_request(
        request_fast,
        task_id="task-fast",
        workspace_root=workspace,
        script_path=fast_script,
    )

    fast_result = _run_bridge(
        request_path=request_fast,
        result_path=result_fast,
        log_path=log_fast,
        env={
            "PYTHON_SERVICE_ARGUMENT": json.dumps(
                {
                    "runtimeRoot": str(runtime_root),
                    "requestId": "request-fast",
                    "pollIntervalMs": 50,
                }
            )
        },
    )

    assert fast_result["status"] == "success"
    assert fast_result["stdout"] == "fast request done\n"
    assert not request_fast.exists()
    assert not result_slow.exists()
    assert request_slow.exists()

    slow_result = _run_bridge(
        request_path=request_slow,
        result_path=result_slow,
        log_path=runtime_root / "logs" / "request-slow.log",
        env={
            "PYTHON_SERVICE_ARGUMENT": json.dumps(
                {
                    "runtimeRoot": str(runtime_root),
                    "requestId": "request-slow",
                    "pollIntervalMs": 50,
                }
            )
        },
    )

    assert slow_result["status"] == "success"
    assert slow_result["stdout"] == "slow request done\n"


def test_android_p4a_service_reads_fallback_start_argument_when_env_argument_missing(
    workspace: pathlib.Path,
) -> None:
    files_root = workspace / ".android-files" / "files"
    runtime_root = files_root / "python_runtime"
    request_id = "request-fallback-start-argument"
    request_path = runtime_root / "requests" / f"{request_id}.json"
    result_path = runtime_root / "results" / f"{request_id}.json"
    log_path = runtime_root / "logs" / f"{request_id}.log"
    script_path = workspace / "fallback_start_argument.py"
    script_path.write_text("print('fallback start argument ok')\n", encoding="utf-8")
    _write_request(
        request_path,
        task_id="task-fallback-start-argument",
        workspace_root=workspace,
        script_path=script_path,
    )
    _write_service_start_argument(
        runtime_root,
        request_id=request_id,
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )
    android_argument_path = files_root / "app"
    android_argument_path.mkdir(parents=True, exist_ok=True)

    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            SCRIPT_MODULE,
            "--once",
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
        env={
            **os.environ,
            "PYTHON_SERVICE_ARGUMENT": "",
            "OPENCRAY_P4A_RUNTIME_ROOT": "",
            "ANDROID_ARGUMENT": str(android_argument_path),
            "ANDROID_PRIVATE": "",
        },
    )

    assert completed.returncode == 0, completed.stderr
    result = json.loads(result_path.read_text(encoding="utf-8"))
    assert result["status"] == "success"
    assert result["stdout"] == "fallback start argument ok\n"


def test_android_p4a_service_reads_fallback_start_argument_from_service_file_location(
    workspace: pathlib.Path,
) -> None:
    from python_runner import p4a_service_main

    files_root = workspace / ".android-files" / "files"
    runtime_root = files_root / "python_runtime"
    request_id = "request-file-location-fallback"
    request_path = runtime_root / "requests" / f"{request_id}.json"
    result_path = runtime_root / "results" / f"{request_id}.json"
    log_path = runtime_root / "logs" / f"{request_id}.log"
    script_path = workspace / "file_location_fallback.py"
    script_path.write_text("print('file location fallback ok')\n", encoding="utf-8")
    _write_request(
        request_path,
        task_id="task-file-location-fallback",
        workspace_root=workspace,
        script_path=script_path,
    )
    _write_service_start_argument(
        runtime_root,
        request_id=request_id,
        request_path=request_path,
        result_path=result_path,
        log_path=log_path,
    )

    fake_service_path = files_root / "app" / "python_runner" / "p4a_service_main.py"
    fake_service_path.parent.mkdir(parents=True, exist_ok=True)
    fake_service_path.write_text("# synthetic service path for fallback resolution\n", encoding="utf-8")

    original_module_file = p4a_service_main.__file__
    original_cwd = pathlib.Path.cwd()
    original_env = {
        "PYTHON_SERVICE_ARGUMENT": os.environ.get("PYTHON_SERVICE_ARGUMENT"),
        "OPENCRAY_P4A_RUNTIME_ROOT": os.environ.get("OPENCRAY_P4A_RUNTIME_ROOT"),
        "ANDROID_ARGUMENT": os.environ.get("ANDROID_ARGUMENT"),
        "ANDROID_PRIVATE": os.environ.get("ANDROID_PRIVATE"),
    }
    try:
        p4a_service_main.__file__ = str(fake_service_path)
        os.chdir(workspace)
        os.environ["PYTHON_SERVICE_ARGUMENT"] = ""
        os.environ["OPENCRAY_P4A_RUNTIME_ROOT"] = ""
        os.environ["ANDROID_ARGUMENT"] = ""
        os.environ["ANDROID_PRIVATE"] = ""
        exit_code = p4a_service_main.main(["--once"])
    finally:
        p4a_service_main.__file__ = original_module_file
        os.chdir(original_cwd)
        for key, value in original_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    assert exit_code == 0
    result = json.loads(result_path.read_text(encoding="utf-8"))
    assert result["status"] == "success"
    assert result["stdout"] == "file location fallback ok\n"
