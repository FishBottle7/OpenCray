# OpenCray Flutter App

This Flutter project is the product entrypoint for OpenCray.

## Android development

- `flutter run` from `flutter_app/` uses the real Android host under `flutter_app/android`.
- The Android host reuses the existing OpenCray host runtime and shared modules from the repository root.
- On Android, Flutter now prefers the direct host bridge and will show a host initialization failure instead of silently falling back to the seed bridge.

## Notes

- The legacy root `app/` host remains available during migration.
- `flutter_app/.android` is legacy module output and is no longer the intended runtime host.
