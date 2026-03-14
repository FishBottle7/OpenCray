# OpenCray Flutter UI Migration Architecture

Last updated: 2026-03-12

## Status

Decision-backed architecture document

## Decision

OpenCray will migrate its UI to Flutter.

This is now a design constraint, not an open option.

The architectural goal is:

- Flutter becomes the primary presentation layer
- Android native code becomes a host and adapter layer
- future HarmonyOS migration should reuse:
  - Flutter UI
  - shared application logic
  - agent core
- platform-specific work should be limited as much as possible to:
  - host adapters
  - platform capability bridges

## Purpose

This document defines how to execute the Flutter UI migration without repeating the current Android coupling mistakes.

It answers:

1. What Flutter should and should not own
2. Which parts remain native host responsibilities
3. How OpenCray should be layered after the migration
4. How current Android UI code maps into future Flutter modules
5. What migration sequence minimizes rewrite risk

## Related Documents

- `docs/harmony-portability-architecture.md`
- `docs/context-management-design.md`
- `docs/done/design-p0-session-runtime-manager.md`
- `docs/done/design-p0-prompt-layer-architecture.md`
- `docs/agent-runtime-roadmap.md`

## Executive Summary

Flutter can solve the UI portability problem.

Flutter does not solve the runtime portability problem.

That distinction must be enforced in the architecture.

If Flutter is allowed to directly own:

- runtime creation
- session persistence
- prompt assembly
- file permission semantics
- command execution semantics

then the codebase will simply move its platform coupling from Android Views to Flutter pages.

That would be a mistake.

### Correct target shape

```text
Flutter UI
  -> typed bridge / facade
  -> application layer
  -> shared agent core
  -> host capability adapters
  -> native platform
```

### Wrong target shape

```text
Flutter page
  -> directly drives runtime creation
  -> directly owns storage and settings
  -> directly understands permission and host semantics
```

The second shape is easier in the short term and more expensive forever.

## Current State

## Current UI ownership problem

The current Android app still mixes together:

- UI rendering
- navigation
- runtime creation
- prompt resolution
- session state mutation
- settings persistence
- workspace handling

The clearest example is:

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

This file currently acts as:

- app shell host
- chat controller
- settings controller
- runtime owner
- persistence assembler
- prompt resolver

That is exactly what Flutter must not become.

## Current UI surfaces that will be replaced

Current UI surfaces include:

- chat in `flutter_app/lib/features/chat/chat_feature_screen.dart`
- skills in `flutter_app/lib/features/skills/skills_feature.dart`
- files in `flutter_app/lib/features/files/files_feature.dart`
- settings in `flutter_app/lib/features/settings/settings_feature.dart`

These are useful as product references and state-shape references, but not as the future architecture shape.

## Current shared logic that should survive the migration

The Flutter migration should preserve and increasingly centralize:

- `core`
- `runtime`
- `policy`
- `skills`
- `persistence`
- `llm`

Representative files:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`

## Architectural Principle

After the migration, Flutter is only the presentation layer.

Flutter must not directly own:

- agent runtime lifecycle
- transcript reconstruction
- prompt assembly
- memory recall/write logic
- skill loading semantics
- filesystem permission semantics
- command execution semantics
- Python process orchestration

Flutter may own:

- visual rendering
- page composition
- interaction capture
- transient local widget state
- animations
- navigation presentation

## Target Architecture

## Final layered model

```text
Layer 1. Flutter Presentation
- chat screens
- skills screens
- files screens
- settings screens
- view models and UI state mappers

Layer 2. Bridge / Facade
- ChatFacade
- SettingsFacade
- FilesFacade
- SkillsFacade
- Event stream bridge
- Request/response DTO codecs

Layer 3. Application Layer
- ChatSessionCoordinator
- SessionRuntimeManager
- AgentFacade
- SettingsCoordinator
- FilesWorkbenchCoordinator
- PersonalizationCoordinator

Layer 4. Shared Agent Core
- AgentLoop
- SessionQueue
- ContextAssembler
- TranscriptWindowBuilder
- Prompt layers
- Memory subsystem
- Soul subsystem
- Skill capsule subsystem
- Compaction/pruning
- Tool dispatch

Layer 5. Host Ports
- SessionStore
- TranscriptStore
- KeyValueStore
- SecretStore
- WorkspaceGateway
- WorkspacePermissionGateway
- CommandRunner
- PythonRunner
- AttachmentProvider
- NotificationGateway

Layer 6. Platform Adapters
- Android implementations now
- Harmony implementations later
```

## Why this shape matters

With this split:

- Flutter UI can stay mostly unchanged across Android and Harmony
- host capability changes stay in adapter code
- agent behavior remains in shared modules
- the bridge becomes stable and testable

## Flutter Bridge Design

## Core rule

Flutter should talk to one high-level bridge API, not a pile of low-level native methods.

Do not expose raw implementation details like:

- `createRuntime`
- `appendPromptTemplate`
- `resolveFilesDir`
- `startProcessBuilder`

Instead expose product-facing and application-facing operations.

## Recommended bridge groups

### `ChatBridge`

Responsibilities:

- list sessions
- open session
- send user message
- cancel in-flight run
- retry message
- receive stream of session events

### `SettingsBridge`

Responsibilities:

- load settings state
- save LLM settings
- save telemetry/privacy settings
- save personalization settings

### `FilesBridge`

Responsibilities:

- read workbench state
- request workspace authorization
- list files
- read file
- write file

### `SkillsBridge`

Responsibilities:

- list installed skills
- read skill detail
- enable/disable skills
- install/remove skills

### `RuntimeEventsBridge`

Responsibilities:

- deliver typed runtime event stream to Flutter

Examples:

- user message appended
- assistant delta
- tool call started
- tool call finished
- approval required
- run completed
- run failed

## Bridge transport recommendation

Use a typed bridge approach.

The exact tool can vary, but the architecture should behave as if:

- requests and responses are strongly typed
- event streams are explicit
- DTO versions are controlled

Avoid ad-hoc stringly-typed method channels spread across pages.

The bridge should feel like one API surface, not many disconnected RPC calls.

## Suggested bridge DTO categories

### Requests

- `SendMessageRequest`
- `CancelRunRequest`
- `UpdateLlmSettingsRequest`
- `RequestWorkspaceGrantRequest`
- `ReadSkillRequest`

### Responses

- `ChatScreenSnapshot`
- `SettingsSnapshot`
- `FilesWorkbenchSnapshot`
- `SkillsSnapshot`

### Events

- `SessionUpdatedEvent`
- `RunProgressEvent`
- `ToolCallEvent`
- `ApprovalPromptEvent`
- `RunCompletedEvent`
- `RunFailedEvent`

## Native Host Responsibilities After Flutter Migration

Android native code should remain responsible for:

- bridge implementation
- platform lifecycle integration
- file permission requests
- filesystem access
- process execution
- Python runtime integration
- settings storage implementation
- secure storage implementation
- notifications and background work

This means Android native code becomes:

- thinner than today
- but still important

It does not disappear.

## Shared Application Layer Responsibilities

The application layer should become the stable contract that Flutter talks to.

Suggested services:

- `AgentFacade`
- `ChatSessionCoordinator`
- `SessionRuntimeManager`
- `SettingsFacade`
- `FilesWorkbenchCoordinator`
- `SkillsFacade`

These services should own:

- state transitions
- event ordering
- orchestration between stores and runtime

Flutter should never need to understand how `AgentLoop` or `SessionQueue` work internally.

## Current-to-Future Mapping

## Chat

Current ownership:

- chat presentation in `flutter_app/lib/features/chat/chat_feature_screen.dart`
- chat orchestration in `AppShellActivity`
- runtime creation in `AppShellActivity`

Future ownership:

- Flutter chat page owns rendering only
- `ChatFacade` exposes state and actions
- `SessionRuntimeManager` owns runtime execution

## Skills

Current ownership:

- Flutter skills surface
- `AppSkillsStorage`
- `AppShellActivity` / Flutter host wiring

Future ownership:

- Flutter skills pages own rendering only
- `SkillsFacade` owns interaction state
- host adapter handles actual install/remove filesystem work

## Files

Current ownership:

- files workbench state and permission handling are tightly linked with Android-side implementation

Future ownership:

- Flutter files screens render state only
- `FilesWorkbenchCoordinator` owns workbench state
- `WorkspaceGateway` and `WorkspacePermissionGateway` handle platform differences

## Settings

Current ownership:

- large settings screen tree assembled directly in `AppShellActivity`

Future ownership:

- Flutter settings pages render state only
- `SettingsFacade` provides snapshots and mutation methods
- platform-native settings stores stay behind interfaces

## What Must Be Refactored Before Flutter Rewrite Expands

The following items should be treated as prerequisites, not optional cleanup.

## 1. Extract `SessionRuntimeManager`

Reason:

- Flutter must not create runtime per page action

Without this:

- Flutter code will inherit the same coupling as `AppShellActivity`

## 2. Extract prompt and context assembly out of app shell code

Reason:

- Flutter should consume already-assembled state
- it should never become the place where prompt logic leaks upward

This connects directly to:

- `docs/context-management-design.md`

## 3. Introduce host port interfaces

At minimum:

- `SessionStore`
- `TranscriptStore`
- `KeyValueStore`
- `SecretStore`
- `WorkspaceGateway`
- `WorkspacePermissionGateway`
- `CommandRunner`
- `PythonRunner`

## 4. Reduce Android `Context` spread

Any place where shared logic needs `Context`, `filesDir`, or `SharedPreferences` directly should be considered portability debt.

## 5. Freeze product state models before rewriting UI

Before Flutter pages are implemented, stabilize the state shapes for:

- chat
- skills
- files
- settings

This prevents the Flutter layer from becoming a moving target during migration.

## State Model Strategy

Flutter migration will go better if the native UI state classes are treated as transitional contracts.

Examples worth mining for state structure:

- `OpenCrayChatSnapshot`
- `ChatFeatureState`
- `ChatComposerState` in Flutter
- skills list item states
- files workbench state

But these state models should be moved into shared application DTOs, not remain Android View-specific models.

## Recommended DTO approach

Shared DTOs should be:

- platform-neutral
- serializable
- versionable
- independent of Android `View`, `Context`, or resource ids

For example:

```text
ChatScreenSnapshot
  sessionHeader
  sessionList
  messageList
  composer
  approvalPrompt
  runtimeStatus
```

Flutter renders from this snapshot.
Android native bridge serializes and delivers it.

## Migration Strategy

## Phase 0: lock the architecture direction

Goal:

- prevent new UI-driven coupling

Rules:

- no new runtime ownership in UI classes
- no new direct persistence access from UI
- no new Android-only assumptions added to shared runtime logic

## Phase 1: build facades and bridge-ready DTOs

Goal:

- make current native UI a consumer of facades first

Tasks:

- add `ChatFacade`
- add `SettingsFacade`
- add `FilesFacade`
- add `SkillsFacade`
- add shared DTOs and event models

Important:

- use the existing Android UI as the first client of the facades
- do not jump straight to Flutter before these exist

## Phase 2: move runtime ownership and context assembly fully below the facade

Goal:

- native UI and Flutter UI both consume the same application contract

Tasks:

- add `SessionRuntimeManager`
- move prompt/context logic out of `AppShellActivity`
- move session reconstruction out of the UI path

## Phase 3: implement Flutter shell

Goal:

- Flutter becomes a second client of the application contract

Tasks:

- create Flutter navigation shell
- implement chat screen
- implement settings screen
- implement skills screen
- implement files screen
- wire typed bridge

## Phase 4: switch Android product UI to Flutter

Goal:

- native Android view layer becomes legacy or debug-only

Tasks:

- keep only host adapters native
- remove duplicated screen logic from Android views

## Phase 5: prepare Harmony host

Goal:

- Flutter UI reused
- only host adapter set changes

Tasks:

- implement Harmony storage adapters
- implement Harmony workspace and permission adapters
- implement Harmony command/python capability adapters
- connect Flutter shell to Harmony host bridge

## Flutter Migration Risks

## Risk 1: rewriting UI before stabilizing application contracts

Result:

- Flutter pages become tightly coupled to unstable bridge APIs

Mitigation:

- build facades and DTOs first

## Risk 2: putting business logic into Flutter state management

Result:

- portability improves visually but architecture gets worse

Mitigation:

- keep Flutter state thin
- put orchestration in shared application services

## Risk 3: leaking platform semantics into Flutter

Examples:

- SAF-specific path semantics
- Android permission result assumptions
- Android storage directory assumptions

Mitigation:

- convert them into generic domain states before crossing the bridge

## Risk 4: treating Flutter as a runtime host

Result:

- command, Python, workspace, and permission semantics end up reimplemented badly

Mitigation:

- keep runtime hosting native
- keep Flutter as presentation only

## Recommendation on State Management in Flutter

The exact package choice is secondary to architecture.

What matters is:

- screen state comes from facades
- long-lived streams come from runtime events
- business orchestration does not live in widget trees

The Flutter side should ideally have:

- feature pages
- screen-level controllers / view models
- DTO-to-view-state mappers
- event subscriptions

It should not contain:

- prompt assembly logic
- transcript persistence logic
- tool dispatch logic
- session runtime lifecycle logic

## Minimum Definition of Success

The Flutter migration is architecturally successful only if all of the following become true:

1. Flutter chat page does not create `OpenCrayAgentRuntime`
2. Flutter does not directly read or write session persistence
3. Flutter does not know about `filesDir`, SAF, or `SharedPreferences`
4. runtime/context/memory/skills remain below the bridge
5. replacing Android host with Harmony host does not require rewriting agent logic

If these are not true, then the migration is only a UI rewrite, not a portability upgrade.

## Immediate Next Steps

Now that Flutter is the chosen UI direction, the next useful document should be:

- `docs/flutter-ui-migration-issues.md`

That file should contain:

- issue-style migration tasks
- ownership boundaries
- prerequisites
- acceptance criteria
- sequencing

It should be treated as the execution plan for the migration.

## Final Recommendation

The right sequence is:

1. stabilize facades and application contracts
2. extract runtime ownership and host ports
3. implement Flutter UI against those contracts
4. only then remove native Android UI surfaces

This gives you the best chance of reaching the real objective:

- Flutter shared UI
- reusable OpenCray agent architecture
- lower-cost future HarmonyOS migration
