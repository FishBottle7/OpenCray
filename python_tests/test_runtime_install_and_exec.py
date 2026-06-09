import json
import pathlib
from typing import TypedDict, cast

from python_tests.subprocess_helpers import run_repo_module


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


class Manifest(TypedDict):
    schema_version: int
    requested: list[str]
    packages: dict[str, str]
    unparsed_freeze: list[str]


def _run_runner(*args: str) -> dict[str, object]:
    completed = run_repo_module(
        "python_runner.runner",
        *args,
        capture_output=True,
        text=True,
        check=False,
        timeout=180,
    )
    assert completed.returncode == 0, completed.stderr
    return cast(dict[str, object], json.loads(completed.stdout))


def test_runtime_install_persists_manifest_and_executes_script(
    workspace: pathlib.Path,
) -> None:
    script_path = workspace / "hello_runtime.py"
    _ = script_path.write_text(
        "import sys\nprint(f'runtime hello {sys.argv[1]}')\n",
        encoding="utf-8",
    )

    install_result = cast(
        RunnerResult,
        cast(
            object,
            _run_runner(
                "install",
                "--workspace",
                str(workspace),
                "--timeout-seconds",
                "120",
                "pip",
            ),
        ),
    )

    assert install_result["status"] == "success"
    assert install_result["exit_code"] == 0
    assert install_result["error_code"] is None
    assert install_result["metadata"]["operation"] == "install"

    manifest_path = workspace / ".opencray" / "python" / "manifest.json"
    assert manifest_path.exists()
    assert install_result["metadata"]["manifest_path"] == str(manifest_path)

    manifest = cast(Manifest, json.loads(manifest_path.read_text(encoding="utf-8")))
    assert manifest["schema_version"] == 1
    assert manifest["requested"] == ["pip"]
    assert "pip" in manifest["packages"]
    assert manifest["packages"]["pip"]
    assert isinstance(manifest["unparsed_freeze"], list)

    exec_result = cast(
        RunnerResult,
        cast(
            object,
            _run_runner(
                "exec",
                "--workspace",
                str(workspace),
                "--script",
                script_path.name,
                "--timeout-seconds",
                "30",
                "--",
                "OpenCray",
            ),
        ),
    )

    assert exec_result["status"] == "success"
    assert exec_result["exit_code"] == 0
    assert exec_result["error_code"] is None
    assert exec_result["stdout"] == "runtime hello OpenCray\n"
    assert exec_result["stderr"] == ""
    assert exec_result["metadata"]["operation"] == "exec"
    assert exec_result["metadata"]["script_path"] == str(script_path.resolve())
    assert exec_result["metadata"]["workspace_root"] == str(workspace)
    assert exec_result["metadata"]["python_executable"].endswith(
        ("Scripts\\python.exe", "bin/python")
    )
    assert exec_result["started_at_epoch_ms"] <= exec_result["finished_at_epoch_ms"]
    assert exec_result["schema_version"] == 1
