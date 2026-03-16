# Android `p4a` Python Runtime Scaffold

This directory holds the Python-side scaffold for the Android embedded runtime.

Current scope:

- Mirror the Kotlin request/result JSON contract used by `P4aPythonRuntime`
- Provide a Python entrypoint that can run a workspace-local script with `runpy`
- Keep dependency policy explicit and static

Not in scope:

- Runtime `pip install`
- `venv`
- Dynamic downloads from PyPI
- Native extension build tooling inside the app module

Contents:

- `runtime/opencray_runtime_main.py`: Python entrypoint for request/result execution
- `requirements.lock`: pinned pure-Python packages to bundle into the runtime later
- `dependency-policy.md`: rules for future dependency additions

Host-side smoke command:

```bash
python tools/android_python_runtime_p4a/runtime/opencray_runtime_main.py --request request.json --result result.json
```
