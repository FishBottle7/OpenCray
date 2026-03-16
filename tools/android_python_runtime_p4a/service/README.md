# `p4a` Service Scaffold

This directory is the future `python-for-android` service entrypoint.

Current behavior:

- Reads `OPENCRAY_P4A_RUNTIME_ROOT` or `--runtime-root`
- Watches `requests/*.json`
- Writes `results/<requestId>.json`
- Writes `logs/<requestId>.log`

This keeps the Android launcher contract simple:

- Kotlin writes a request file
- Android starts the Python service
- Python service drains pending requests from the runtime root

The actual `p4a` packaging command and generated Android service class are still deferred.
