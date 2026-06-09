import json
import pathlib
from typing import TypedDict, cast

from python_tests.subprocess_helpers import run_repo_module


FAKE_PACKAGE = "opencray-invalid-package-7f4f8d2f6a4b-never-published"


class RunnerMetadata(TypedDict):
    operation: str
    workspace_root: str
    manifest_path: str
    pip_install_command: str


class RunnerResult(TypedDict):
    status: str
    exit_code: int | None
    stdout: str
    stderr: str
    error_code: str | None
    error_message: str | None
    started_at_epoch_ms: int
    finished_at_epoch_ms: int
    metadata: RunnerMetadata
    schema_version: int


def _run_runner(*args: str) -> RunnerResult:
    completed = run_repo_module(
        "python_runner.runner",
        *args,
        capture_output=True,
        text=True,
        check=False,
        timeout=180,
    )
    assert completed.returncode == 0, completed.stderr
    return cast(RunnerResult, json.loads(completed.stdout))


def test_runtime_install_invalid_package_fails_cleanly(
    workspace: pathlib.Path,
) -> None:
    manifest_path = workspace / ".opencray" / "python" / "manifest.json"

    result = _run_runner(
        "install",
        "--workspace",
        str(workspace),
        "--timeout-seconds",
        "120",
        FAKE_PACKAGE,
    )

    assert result["status"] == "failed"
    assert result["exit_code"] not in (0, None)
    assert result["error_code"] == "INSTALL_ERROR"
    assert result["error_message"] == "pip install failed."
    assert result["metadata"]["operation"] == "install"
    assert result["metadata"]["workspace_root"] == str(workspace)
    assert result["metadata"]["manifest_path"] == str(manifest_path)
    assert "--no-index" in result["metadata"]["pip_install_command"]
    assert FAKE_PACKAGE in result["metadata"]["pip_install_command"]
    assert result["started_at_epoch_ms"] <= result["finished_at_epoch_ms"]
    assert result["schema_version"] == 1

    assert isinstance(result["stdout"], str)
    assert isinstance(result["stderr"], str)
    assert result["stdout"] or result["stderr"]
    assert FAKE_PACKAGE in f"{result['stdout']}\n{result['stderr']}"

    if manifest_path.exists():
        manifest = cast(
            dict[str, object], json.loads(manifest_path.read_text(encoding="utf-8"))
        )
        requested = manifest.get("requested", [])
        packages = manifest.get("packages", {})
        assert isinstance(requested, list)
        assert isinstance(packages, dict)
        assert FAKE_PACKAGE not in requested
        assert FAKE_PACKAGE not in packages
    else:
        assert not manifest_path.exists()
