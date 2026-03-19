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
- `requirements.lock`: approved default runtime package baseline
- `dependency-policy.md`: rules for future dependency additions

Default dependency behavior:

- `build-p4a-service-library.sh` always includes `python3`
- if `P4A_REQUIREMENTS` is unset, the script also reads `requirements.lock`
- `P4A_REQUIREMENTS` can still override the full requirement list for one-off builds

Host-side smoke command:

```bash
python tools/android_python_runtime_p4a/runtime/opencray_runtime_main.py --request request.json --result result.json
```
