# Android Embedded Python Dependency Policy

Rules for this runtime:

1. Prefer the Python standard library first.
2. `requirements.lock` is the approved default package baseline for Android builds.
3. The lock file may contain both `p4a` recipe names and approved pure-Python package names.
4. Native or compiled dependencies are allowed only when they are backed by an existing `p4a` recipe and are explicitly approved.
5. Packages with high compatibility risk should be grouped and documented as experimental instead of being added ad hoc.
6. Do not design this runtime around `pip install` inside the shipped app.

The intended model is "bundle once, execute many", not "ship a package manager".
