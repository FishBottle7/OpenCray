import pathlib
import shutil
from uuid import uuid4

import pytest


@pytest.fixture
def workspace() -> pathlib.Path:
    repo_root = pathlib.Path(__file__).resolve().parents[1]
    base_dir = repo_root / ".pytest_local"
    base_dir.mkdir(exist_ok=True)
    path = base_dir / f"workspace_{uuid4().hex}"
    path.mkdir()
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)
