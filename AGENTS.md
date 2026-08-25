# Repository Guidelines

## Project Structure & Module Organization
OpenCray is a multi-module Android project. `app/` contains the Android entry point, shell activities, and instrumented tests. Shared business logic lives in `core/`, `runtime/`, `filesystem/`, `skills/`, `mcp/`, `llm/`, `policy/`, and `persistence/`. Reusable UI building blocks and screen classes live in `ui/`. Python runtime helpers are in `python_runner/`, with pytest suites in `python_tests/`. Design and release references live in `docs/`; mobile UI work must follow `docs/mobile-ui-layout-spec.md`.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repo root.

- `.\gradlew.bat test` runs JVM unit tests across modules.
- `.\gradlew.bat connectedDebugAndroidTest` runs Android instrumentation tests on a connected device or emulator.
- `cd flutter_app && flutter test` runs Flutter widget and unit tests for the embedded Flutter module.
- `python -m pytest` runs the Python integration and smoke tests from `python_tests/`.
- `.\build-apk.ps1 -Variant debug` builds a debug APK and copies it to `build/apk/OpenCray-debug.apk`.
- `.\gradlew.bat clean` removes generated build output.

## Coding Style & Naming Conventions
Follow the existing Kotlin style: 2-space indentation, concise methods, and package names under `com.opencray.*`. Use `PascalCase` for classes, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Resource files should stay lowercase snake case, for example `ic_chat_send.xml`. No repo-wide formatter or linter is currently enforced, so use Android Studio's standard reformatting and keep imports tidy. Reuse shared UI tokens in `ui/.../design/` instead of hardcoding spacing or sizing.

## Testing Guidelines
Place JVM tests under each module's `src/test/kotlin` and Android UI or integration tests under `app/src/androidTest/kotlin`. Name test files after the subject plus `Test`, for example `CommandExecutorTest.kt`. Add or update tests for behavior changes, especially around runtime policy, persistence, file operations, and navigation. For UI changes, verify layout behavior against `docs/mobile-ui-layout-spec.md` on phone-sized screens before merging.
In the current Codex sandbox environment, `flutter` tool commands such as `flutter --version`, `flutter test`, and `flutter test --help` may hang without producing output. If that happens, rerun the Flutter command outside the sandbox with approval instead of treating it as a test failure. `dart analyze flutter_app` usually still works inside the sandbox and is a good first-pass verification step.

## Commit & Pull Request Guidelines
Git history uses Conventional Commit prefixes such as `feat:` and `fix:`. In this repository, larger changes should be committed with a Chinese, standards-compliant summary, for example `feat: 重构文件工作台移动端布局`. Keep commits focused and include tests with the change when practical. Pull requests should describe the user-visible impact, list verification commands, link related issues, and include screenshots or recordings for UI updates.
When Codex completes code or documentation changes for a user request, run the practical verification for that change and create a focused git commit before ending the turn, unless the user explicitly asks not to commit.
When the user asks Codex to complete changes "in a branch", create or use a `.codex` worktree for that branch and do the work there. Do not check out feature branches in the repository root; keep the root checkout on `master`.

## Security & Configuration Notes
Do not commit secrets, local SDK paths, or device-specific config. Treat workspace, policy, and credential flows as security-sensitive areas and preserve existing safeguards when editing them.

## Tool Policy Pipeline
New runtime tools that cross filesystem, process, or network boundaries must go through `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt`.
Normalize the tool surface first, resolve targets before execution, evaluate policy through the shared pipeline, and emit shared policy metadata from the pipeline result.
If a tool crosses an execution or process-lifecycle boundary, add an explicit runtime intent model and emit its metadata through the same pipeline instead of relying on the tool name downstream.
If a tool returns bounded output, emit the shared result-limit metadata from the pipeline path instead of inventing handler-local truncation keys.
If a tool is session-state only, such as `TodoWrite`, still emit shared common/result metadata through the pipeline helpers instead of returning a bare metadata map.
Do not hand-roll approval or deny results, or duplicate policy metadata assembly, inside individual tool handlers.

## Error Code Registry
Cross-boundary error codes stay as UPPER_SNAKE_CASE strings on `ExecutionResult`/`AgentToolResult.errorCode`; the short user-facing code (E + 4 digits, for example `E1001`) is a presentation-layer mapping derived from that string.
Every error code string that can reach a user-visible failure must be registered in `core/src/main/kotlin/com/opencray/core/error/UserFacingErrorCodes.kt` with a unique short code in the matching segment: E0 policy/approval, E1 command/process execution, E2 LLM/provider, E3 session orchestration, E4 filesystem, E5 skills, E6 MCP (reserved), E7 terminal environment, E8 subagent, E9 unknown.
When adding or renaming an error code, register it and update `docs/error-codes.md` in the same change; keep the uniqueness and format guarantees covered by `UserFacingErrorCodesTest`.
User-visible failure copy must be rendered through the shared formatters (`HostRuntimeStrings.agentFailed` or `AppAgentSessionTaskRuntimeFactory.transcriptAgentFailedText`). Never hand-roll short-code prefixes or failure copy inside individual tool handlers.
Unregistered codes intentionally fall back to plain failure copy without a bracketed short code; do not knowingly ship new user-visible codes without registering them.

## UI Prototype Implementation Rules
When implementing the mobile UI from the Pencil prototype, treat the Pencil design as the source of truth and refactor the app UI to match it as closely as practical. If the existing app contains a UI element, state, interaction, or visual treatment that is not present in the approved Pencil prototype, stop and ask the user before keeping, changing, or removing it. If any product, interaction, copy, navigation, or state detail is uncertain during implementation, ask the user instead of deciding independently. Do not fill in missing UI behavior or visuals based on assumption when the prototype or prior user direction does not make the requirement explicit.
