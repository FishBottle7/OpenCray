# Contributing to OpenCray

Thanks for your interest in contributing! OpenCray is an Android-first AI agent runtime. This guide covers how to contribute effectively.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Branches & Worktrees](#branches--worktrees)
- [Commit Guidelines](#commit-guidelines)
- [Style Guidelines](#style-guidelines)
- [Testing Guidelines](#testing-guidelines)
- [Pull Request Guidelines](#pull-request-guidelines)
- [Docs & UI Changes](#docs--ui-changes)

## Code of Conduct

Be respectful and constructive. Harassment, trolling, or flaming will not be tolerated. This is a collaborative open-source project.

## Getting Started

1. Read the [README](../README.md) for project structure and build instructions.
2. Set up your environment: Windows is the primary development environment; you need Android Studio / Android SDK (`compileSdk = 36`, min Android 8.0 / API 26), JDK 17, the Flutter SDK, and Python 3.
3. Look for issues labeled `good first issue` or `help wanted` if you are new.

```powershell
git clone https://github.com/FishBottle7/OpenCray.git
cd OpenCray
```

## Branches & Worktrees

Keep the repository root checkout on `master`. Use a dedicated branch (or a `.codex` worktree) for feature work:

```text
feat/your-feature-name
fix/your-fix-name
docs/your-docs-change
```

Do not commit directly to `master`.

## Commit Guidelines

Commits follow the [Conventional Commit](https://www.conventionalcommits.org/) prefix format:

```text
feat: 重构文件工作台移动端布局
fix: 收口运行时工具策略元数据
docs: 补充 OpenCray 项目 README
```

- Keep commits focused on a single logical change.
- Larger changes in this repository commonly use Chinese summaries, but English is also welcome.
- Include tests with the change when practical.

## Style Guidelines

Follow the existing Kotlin style:

- 2-space indentation, concise methods.
- Package names under `com.opencray.*` / `org.opencray.*`.
- `PascalCase` for classes, `camelCase` for functions and properties, `UPPER_SNAKE_CASE` for constants.
- Android resource files stay lowercase snake case (e.g. `ic_chat_send.xml`).
- Reuse shared UI tokens in `ui/.../design/` instead of hardcoding spacing or sizing.

## Testing Guidelines

Place JVM tests under each module's `src/test/kotlin`, and Android UI / integration tests under `app/src/androidTest/kotlin`. Name test files after the subject plus `Test` (e.g. `CommandExecutorTest.kt`).

Run the relevant test suites:

```powershell
# JVM unit tests across modules
.\gradlew.bat test

# Android instrumentation tests (device/emulator)
.\gradlew.bat connectedDebugAndroidTest

# Python runtime & integration smoke tests
python -m pytest

# Flutter widget / unit tests
cd flutter_app
flutter test
```

> Note: In some sandboxed environments `flutter` commands may hang; `dart analyze flutter_app` is a good first-pass static check.

## Pull Request Guidelines

Every PR should describe:

- The user-visible impact.
- Key implementation boundaries.
- Verified commands you ran.
- Related issue or design-doc links.
- Screenshots or recordings for UI changes.

Larger changes should be committed with a standards-compliant summary and include tests where practical.

## Docs & UI Changes

- Follow `docs/mobile-ui-layout-spec.md` for any mobile UI work.
- When adding or renaming a user-facing error code, register it in `core/src/main/kotlin/com/opencray/core/error/UserFacingErrorCodes.kt` and update `docs/error-codes.md` in the same change.
- New runtime tools that cross filesystem, process, or network boundaries must go through `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt`.

Thanks again for helping improve OpenCray!