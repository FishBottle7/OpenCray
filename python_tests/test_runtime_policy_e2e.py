import json
import pathlib
import subprocess
import sys
from typing import TypedDict, cast


class RunnerResult(TypedDict):
    status: str
    exit_code: int | None
    stdout: str
    stderr: str
    error_code: str | None
    error_message: str | None
    started_at_epoch_ms: int
    finished_at_epoch_ms: int
    metadata: dict[str, str]
    schema_version: int


def _run_runner(*args: str) -> RunnerResult:
    completed = subprocess.run(
        [sys.executable, "-m", "python_runner.runner", *args],
        capture_output=True,
        text=True,
        check=False,
        timeout=180,
    )
    assert completed.returncode == 0, completed.stderr
    return cast(RunnerResult, json.loads(completed.stdout))


def _install_runtime(workspace: pathlib.Path) -> RunnerResult:
    return _run_runner(
        "install",
        "--workspace",
        str(workspace),
        "--timeout-seconds",
        "120.0",
        "pip",
    )


def _normalized_result(result: RunnerResult) -> dict[str, object]:
    normalized = dict(result)
    _ = normalized.pop("started_at_epoch_ms")
    _ = normalized.pop("finished_at_epoch_ms")
    return normalized


def test_runtime_exec_timeout_returns_normalized_timeout_envelope(
    workspace: pathlib.Path,
) -> None:
    install_result = _install_runtime(workspace)
    assert install_result["status"] == "success"

    script_path = workspace / "sleep_forever.py"
    _ = script_path.write_text("import time\ntime.sleep(5)\n", encoding="utf-8")

    result = _run_runner(
        "exec",
        "--workspace",
        str(workspace),
        "--script",
        script_path.name,
        "--timeout-seconds",
        "0.2",
    )

    assert result["status"] == "timeout"
    assert result["exit_code"] is None
    assert result["error_code"] == "TIMEOUT"
    assert result["error_message"] == "Operation exceeded timeout of 0.2 seconds."
    assert result["stdout"] == ""
    assert result["stderr"] == ""
    assert result["metadata"]["operation"] == "exec"
    assert result["metadata"]["workspace_root"] == str(workspace)
    assert result["metadata"]["script_path"] == str(script_path.resolve())
    assert result["metadata"]["timeout_seconds"] == "0.2"
    assert result["metadata"]["python_executable"].endswith(
        ("Scripts\\python.exe", "bin/python")
    )
    assert result["started_at_epoch_ms"] <= result["finished_at_epoch_ms"]
    assert result["schema_version"] == 1


def test_runtime_policy_denial_output_stays_deterministic_across_runner_restarts(
    workspace: pathlib.Path,
) -> None:
    blocked_script = workspace.parent / "outside_workspace_script.py"
    _ = blocked_script.write_text("print('blocked')\n", encoding="utf-8")

    first = _run_runner(
        "exec",
        "--workspace",
        str(workspace),
        "--script",
        str(blocked_script),
        "--timeout-seconds",
        "30.0",
    )
    second = _run_runner(
        "exec",
        "--workspace",
        str(workspace),
        "--script",
        str(blocked_script),
        "--timeout-seconds",
        "30.0",
    )

    assert first["status"] == "denied"
    assert first["exit_code"] is None
    assert first["error_code"] == "DENY_PATH_ESCAPE"
    assert first["error_message"] == "Script path is not allowed."
    assert first["stdout"] == ""
    assert first["stderr"] == ""
    assert set(first["metadata"]) == {"operation", "workspace_root", "timeout_seconds"}
    assert first["metadata"]["operation"] == "exec"
    assert first["metadata"]["workspace_root"] == str(workspace)
    assert first["metadata"]["timeout_seconds"] == "30.0"
    assert first["started_at_epoch_ms"] <= first["finished_at_epoch_ms"]
    assert first["schema_version"] == 1

    assert second["started_at_epoch_ms"] <= second["finished_at_epoch_ms"]
    assert second["schema_version"] == 1
    assert _normalized_result(first) == _normalized_result(second)


def test_runtime_repeated_exec_across_fresh_runner_processes_has_no_hidden_duplicate_side_effects(
    workspace: pathlib.Path,
) -> None:
    install_result = _install_runtime(workspace)
    assert install_result["status"] == "success"

    script_path = workspace / "append_once.py"
    log_path = workspace / "side_effects.log"
    _ = script_path.write_text(
        "\n".join(
            [
                "from pathlib import Path",
                "import sys",
                "",
                "log_path = Path('side_effects.log')",
                "token = sys.argv[1]",
                "with log_path.open('a', encoding='utf-8') as handle:",
                "    handle.write(f'{token}\\n')",
                "print(len(log_path.read_text(encoding='utf-8').splitlines()))",
                "",
            ]
        ),
        encoding="utf-8",
    )

    first = _run_runner(
        "exec",
        "--workspace",
        str(workspace),
        "--script",
        script_path.name,
        "--timeout-seconds",
        "30.0",
        "--",
        "run-1",
    )
    second = _run_runner(
        "exec",
        "--workspace",
        str(workspace),
        "--script",
        script_path.name,
        "--timeout-seconds",
        "30.0",
        "--",
        "run-2",
    )

    assert first["status"] == "success"
    assert first["exit_code"] == 0
    assert first["error_code"] is None
    assert first["stdout"] == "1\n"
    assert first["stderr"] == ""

    assert second["status"] == "success"
    assert second["exit_code"] == 0
    assert second["error_code"] is None
    assert second["stdout"] == "2\n"
    assert second["stderr"] == ""

    assert log_path.read_text(encoding="utf-8").splitlines() == ["run-1", "run-2"]
