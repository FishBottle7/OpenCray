from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Mapping


REPO_ROOT = Path(__file__).resolve().parents[1]


def _repo_module_env(extra_env: Mapping[str, str] | None = None) -> dict[str, str]:
    env = os.environ.copy()
    pythonpath_parts = [str(REPO_ROOT)]

    existing_pythonpath = env.get("PYTHONPATH")
    if existing_pythonpath:
        pythonpath_parts.append(existing_pythonpath)

    extra_pythonpath = extra_env.get("PYTHONPATH") if extra_env is not None else None
    if extra_pythonpath:
        pythonpath_parts.append(extra_pythonpath)

    env["PYTHONPATH"] = os.pathsep.join(pythonpath_parts)

    if extra_env is not None:
        for key, value in extra_env.items():
            if key != "PYTHONPATH":
                env[key] = value

    return env


def run_repo_module(
    module: str,
    *args: str,
    env: Mapping[str, str] | None = None,
    **kwargs: Any,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", module, *args],
        cwd=str(REPO_ROOT),
        env=_repo_module_env(env),
        **kwargs,
    )


def popen_repo_module(
    module: str,
    *args: str,
    env: Mapping[str, str] | None = None,
    **kwargs: Any,
) -> subprocess.Popen[str]:
    return subprocess.Popen(
        [sys.executable, "-m", module, *args],
        cwd=str(REPO_ROOT),
        env=_repo_module_env(env),
        **kwargs,
    )
