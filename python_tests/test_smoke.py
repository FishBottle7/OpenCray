def test_workspace_fixture_creates_directory(workspace):
    assert workspace.exists()
    assert workspace.is_dir()
