import pathlib

import pytest


@pytest.fixture
def workspace(tmp_path: pathlib.Path) -> pathlib.Path:
    ws = tmp_path / "workspace"
    ws.mkdir()
    return ws
