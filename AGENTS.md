# Repository Guidelines

## Project Structure & Module Organization
OpenCray is a multi-module Android project. `app/` contains the Android entry point, shell activities, and instrumented tests. Shared business logic lives in `core/`, `runtime/`, `filesystem/`, `skills/`, `mcp/`, `llm/`, `policy/`, and `persistence/`. Reusable UI building blocks and screen classes live in `ui/`. Python runtime helpers are in `python_runner/`, with pytest suites in `python_tests/`. Design and release references live in `docs/`; mobile UI work must follow `docs/mobile-ui-layout-spec.md`.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repo root.

- `.\gradlew.bat test` runs JVM unit tests across modules.
- `.\gradlew.bat connectedDebugAndroidTest` runs Android instrumentation tests on a connected device or emulator.
- `python -m pytest` runs the Python integration and smoke tests from `python_tests/`.
- `.\build-apk.ps1 -Variant debug` builds a debug APK and copies it to `build/apk/OpenCray-debug.apk`.
- `.\gradlew.bat clean` removes generated build output.

## Coding Style & Naming Conventions
Follow the existing Kotlin style: 2-space indentation, concise methods, and package names under `com.opencray.*`. Use `PascalCase` for classes, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Resource files should stay lowercase snake case, for example `ic_chat_send.xml`. No repo-wide formatter or linter is currently enforced, so use Android Studio's standard reformatting and keep imports tidy. Reuse shared UI tokens in `ui/.../design/` instead of hardcoding spacing or sizing.

## Testing Guidelines
Place JVM tests under each module's `src/test/kotlin` and Android UI or integration tests under `app/src/androidTest/kotlin`. Name test files after the subject plus `Test`, for example `CommandExecutorTest.kt`. Add or update tests for behavior changes, especially around runtime policy, persistence, file operations, and navigation. For UI changes, verify layout behavior against `docs/mobile-ui-layout-spec.md` on phone-sized screens before merging.

## Commit & Pull Request Guidelines
Git history uses Conventional Commit prefixes such as `feat:` and `fix:`. In this repository, larger changes should be committed with a Chinese, standards-compliant summary, for example `feat: 重构文件工作台移动端布局`. Keep commits focused and include tests with the change when practical. Pull requests should describe the user-visible impact, list verification commands, link related issues, and include screenshots or recordings for UI updates.

## Security & Configuration Notes
Do not commit secrets, local SDK paths, or device-specific config. Treat workspace, policy, and credential flows as security-sensitive areas and preserve existing safeguards when editing them.
