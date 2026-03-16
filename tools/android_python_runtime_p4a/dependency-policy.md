# Android Embedded Python Dependency Policy

Rules for this runtime:

1. Only pinned versions are allowed.
2. Prefer the Python standard library first.
3. Only approved pure-Python packages may be added to `requirements.lock`.
4. Native extensions are excluded unless separately approved.
5. Do not design this runtime around `pip install` inside the shipped app.

The intended model is "bundle once, execute many", not "ship a package manager".
