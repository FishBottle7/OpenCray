# OpenCray Harmony Portability Architecture

Last updated: 2026-03-12

## Status

Planning document

## Purpose

This document defines how OpenCray should evolve if the long-term goal is:

- keep the agent core reusable
- reduce future HarmonyOS migration cost
- ideally limit platform migration work to:
  - UI
  - host adapters

It also evaluates whether Flutter is a good choice for the UI layer under that goal.

This document is not about immediate product UI changes. It is about reducing architectural lock-in before the codebase grows further.

## Executive Summary

OpenCray is not currently in a state where HarmonyOS migration would be "UI only".

Today, the project is closer to:

- Android app as the product shell
- Android app as runtime owner
- Android app as settings and persistence host
- partially reusable agent/runtime logic inside shared modules

That means a future HarmonyOS migration would require more than UI adaptation unless the architecture is refactored first.

### The practical target

The realistic target should be:

- future migration requires changing only:
  - UI layer
  - platform host adapters
- while reusing:
  - agent runtime
  - context management
  - policy
  - transcript logic
  - memory logic
  - skills logic
  - most application orchestration

This is achievable.

The wrong target is:

- "only rewrite UI and everything else stays exactly as-is"

That is not realistic with the current code structure.

## Current State Assessment

## What is already somewhat portable

Several modules already look closer to shared logic than app-only logic.

Examples:

- `core`
- `runtime`
- `policy`
- parts of `persistence`
- parts of `skills`
- parts of `llm`

Representative code:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`

These areas are mostly:

- state machines
- orchestration logic
- queueing
- policy evaluation
- tool dispatch
- process execution abstractions

They are not inherently Android UI logic.

## What is still tightly bound to Android

The problem is not only UI widgets. The runtime ownership path is also Android-bound.

Examples:

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/TelemetrySettingsStore.kt`
- `app/src/main/kotlin/com/opencray/app/shell/AppShellStateStore.kt`

Current Android-bound characteristics:

- `Activity` owns live runtime creation
- `Context` is used directly for persistence and directory resolution
- `SharedPreferences` is used directly in app stores
- `filesDir` is treated as runtime-local infrastructure
- UI layer directly wires runtime, session, persistence, and prompt resolution

This means the current app layer is doing too much host work.

## Current filesystem and permission lock-in

There is also Android-specific filesystem semantics in the current architecture.

Example:

- `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`

This is a valid Android implementation, but it is not a platform-neutral workspace gateway.

That distinction matters:

- Android SAF is one adapter
- it should not define the shared filesystem abstraction for all platforms

## Current build-level lock-in

Even modules that look reusable are still built as Android libraries.

Examples:

- `core/build.gradle.kts`
- `runtime/build.gradle.kts`

This is important because "code uses few Android APIs" is not enough.

If the module itself is still compiled as Android-first infrastructure, it is not yet a clean portable core.

## The Real Portability Goal

If HarmonyOS portability is important, OpenCray should target this rule:

### Platform-independent layers

- agent runtime
- session runtime management
- context management
- memory
- soul
- skills
- prompt assembly
- policy
- transcript persistence model

### Platform-dependent layers

- UI
- app lifecycle
- host storage adapter
- workspace permission adapter
- command execution adapter
- Python runtime adapter
- secrets/settings adapter
- platform notifications/background tasks

This is the correct boundary.

The goal should be:

- UI is replaceable
- host adapter is replaceable
- the rest remains stable

## Recommended Target Architecture

## Layered model

```text
Presentation Layer
- Android UI
- Flutter UI
- Harmony UI

Application Layer
- Chat use cases
- Session orchestration
- Settings orchestration
- Agent interaction facade
- View-model independent state coordinators

Agent Core
- AgentLoop
- SessionQueue
- SessionRuntimeManager
- ContextAssembler
- TranscriptWindowBuilder
- Prompt layer system
- Memory recall/write policy
- Soul resolver
- Skill capsule logic
- Subagent orchestration
- Compaction/pruning

Ports / Interfaces
- SessionStore
- TranscriptStore
- KeyValueStore
- WorkspaceGateway
- WorkspacePermissionGateway
- CommandRunner
- PythonRunner
- SkillRootProvider
- SecretStore
- Clock
- IdGenerator
- FilePicker / AttachmentProvider

Platform Adapters
- Android implementations
- Harmony implementations
- Desktop implementations
```

## Architecture principle

No UI class should:

- build the runtime directly
- own prompt assembly
- access transcript persistence directly
- depend on `filesDir` or platform storage semantics
- know how command execution actually works

UI should only interact with a higher-level facade such as:

- `ChatSessionCoordinator`
- `AgentFacade`
- `SettingsFacade`

## What Must Change Before Harmony Migration Is Cheap

## 1. Move runtime ownership out of `AppShellActivity`

Current problem:

- `AppShellActivity` is both screen host and runtime owner

Needed change:

- introduce `SessionRuntimeManager`
- UI only submits intents and subscribes to events

Target rule:

- page lifecycle does not define agent lifecycle

## 2. Introduce explicit host ports

These interfaces should exist in shared logic:

- `WorkspaceGateway`
- `WorkspacePermissionGateway`
- `CommandRunner`
- `PythonRunner`
- `SessionStore`
- `TranscriptStore`
- `KeyValueStore`
- `SecretStore`

Android should implement them now.
Harmony should implement them later.

The shared core should depend only on the interfaces.

## 3. Separate transcript model from app-local UI storage

Today, local chat storage is still app-driven.

Target:

- transcript becomes agent/runtime infrastructure
- UI reads through application services, not raw local files

Why this matters:

- Harmony migration then replaces storage adapters, not transcript logic

## 4. Separate filesystem abstraction from SAF implementation

Target:

- define generic workspace access semantics
- keep SAF in `android` adapter layer only

Example abstraction:

```kotlin
interface WorkspaceGateway {
  fun list(path: String): WorkspaceListResult
  fun read(path: String): WorkspaceReadResult
  fun write(path: String, content: String): WorkspaceWriteResult
  fun move(from: String, to: String): WorkspaceMoveResult
  fun delete(path: String): WorkspaceDeleteResult
}
```

Then:

- Android adapter may internally use SAF
- Harmony adapter may use Harmony file APIs
- desktop adapter may use direct filesystem APIs

## 5. Treat command execution and Python as host capabilities

Do not model them as globally guaranteed platform features.

Instead model them as:

- available
- unavailable
- limited

Why this matters:

- Android, Harmony, desktop, and sandboxed builds may differ
- the agent should adapt through capability descriptors, not hard assumptions

Recommended abstractions:

- `CommandRunner`
- `PythonRunner`
- `ExecutionCapabilities`

## 6. Finish context architecture before portability work expands

This is critical.

If context assembly remains tied to Activity code and Android stores, Harmony migration will force logic rewrites.

The work already identified in:

- `docs/context-management-design.md`

is also portability work.

In other words:

- building `SessionRuntimeManager`
- building `ContextAssembler`
- building transcript reconstruction
- building prompt layers

is not only better agent architecture

it is also what reduces platform lock-in

## Portability Strategy Options

There are three realistic directions.

## Option A: Keep native Android UI now, add Harmony UI later

### Description

- Android keeps current native UI stack
- shared application and agent core are extracted
- Harmony gets a separate native UI later

### Advantages

- lowest short-term disruption
- avoids immediate UI rewrite
- preserves current velocity on Android
- forces the right architecture boundary first

### Disadvantages

- two UI implementations later
- no shared widget layer

### Best fit

Use this if:

- your immediate priority is solidifying agent architecture
- UI is still changing quickly
- you do not want to pay Flutter migration cost right now

## Option B: Move UI to Flutter, keep native host adapters

### Description

- Flutter becomes the main presentation layer
- Android and Harmony each provide thin native host adapters
- shared agent core remains outside Flutter

### Advantages

- one shared UI codebase across Android and Harmony targets if Flutter support path is acceptable
- better long-term UI consistency
- future visual changes can be cheaper

### Disadvantages

- immediate rewrite cost
- platform channels / host bridges still must exist
- does not remove need to refactor runtime ownership
- if architecture is bad underneath, Flutter only hides the problem

### Important warning

Flutter does not solve portability of:

- runtime lifecycle
- storage
- filesystem permissions
- command execution
- Python runtime
- background work
- local host integrations

Flutter only helps primarily with the presentation layer.

If you migrate to Flutter before cleaning host boundaries, you will still carry the same portability debt underneath.

### Best fit

Use this if:

- you strongly value shared UI
- the product UI is now stable enough to justify rewrite cost
- you are willing to build clean host bridges in parallel

## Option C: Keep UI native, expose agent core as local service boundary

### Description

- app UI is just a shell
- agent runtime is packaged behind a local service / facade / bridge
- platforms call the same engine contract

### Advantages

- strongest platform separation
- best long-term portability
- easiest to test core independently

### Disadvantages

- highest architecture work
- more indirection
- slower short-term progress

### Best fit

Use this if:

- you want OpenCray to become a durable multi-platform agent product, not just a mobile app

## Flutter-Specific Evaluation

You said you are considering Flutter as the UI.

Here is the practical evaluation.

## What Flutter would help with

- shared page structure
- chat UI
- settings UI
- navigation shell
- visual consistency
- animation and responsive layout

## What Flutter would not help with

- agent runtime lifecycle ownership
- session persistence model
- command execution
- Python execution
- filesystem permission bridging
- attachment access
- secrets/settings backend
- background jobs
- platform policy constraints

## The real architectural implication

If you choose Flutter, the correct shape is:

```text
Flutter UI
  -> Method channel / FFI / host bridge
  -> Application facade
  -> Shared agent core
  -> Platform adapters
```

Not:

```text
Flutter page
  -> directly owns runtime and persistence logic
```

That second shape would recreate the same coupling you already have in Android.

## Recommendation on Flutter timing

My recommendation is:

- do not choose Flutter as the first portability step
- first extract shared runtime and host boundaries
- then decide whether UI should stay native or move to Flutter

Why:

- architecture debt is below the UI layer
- rewriting UI first will not remove it
- once host boundaries are clean, the Flutter decision becomes much easier and lower risk

## Recommended Decision Rule

Use this rule:

### Choose Flutter if

- you want one long-term shared UI
- the product UI is becoming stable
- your team is comfortable maintaining platform channels
- you are willing to treat Flutter as presentation only

### Do not choose Flutter yet if

- current app architecture still has UI owning runtime and storage
- agent/context/runtime layers are still moving fast
- you mainly want to reduce future Harmony migration cost

In that case, architecture extraction gives a better return first.

## Suggested Migration Roadmap

## Phase 0: portability baseline

Goal:

- stop making platform coupling worse

Tasks:

- avoid adding new Android-only logic into shared runtime modules
- stop putting runtime ownership into Activities
- keep all new host interactions behind interfaces

## Phase 1: extract shared application and runtime services

Goal:

- UI no longer owns the agent

Tasks:

- add `SessionRuntimeManager`
- add `AgentFacade`
- add `ChatSessionCoordinator`
- move prompt/context assembly out of `AppShellActivity`
- move transcript reconstruction into runtime/application layer

## Phase 2: define host adapter interfaces

Goal:

- shared core stops depending on Android semantics

Tasks:

- define `WorkspaceGateway`
- define `WorkspacePermissionGateway`
- define `CommandRunner`
- define `PythonRunner`
- define `SessionStore`
- define `TranscriptStore`
- define `KeyValueStore`
- define `SecretStore`

## Phase 3: Android becomes just one adapter set

Goal:

- Android is no longer the architectural default

Tasks:

- implement all host ports with Android adapters
- move SAF logic behind workspace gateway
- move SharedPreferences logic behind key-value interfaces
- move `filesDir` directory decisions behind storage adapters

## Phase 4: choose UI strategy

Decision point:

- native Android plus native Harmony
- or Flutter shared UI

At this point, both choices are viable.

Before this point, the choice is premature.

## Phase 5: add Harmony adapters

Goal:

- enable runtime reuse with platform-specific host integrations

Tasks:

- implement Harmony storage adapters
- implement Harmony workspace permission adapters
- implement Harmony command/python capability adapters
- wire the chosen Harmony UI to the shared application facade

## Concrete Refactoring Targets In Current Codebase

## Highest priority files to stop platform lock-in

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/TelemetrySettingsStore.kt`
- `app/src/main/kotlin/com/opencray/app/shell/AppShellStateStore.kt`
- `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`

## High priority modules to normalize

- `core`
- `runtime`
- `policy`
- `skills`
- `persistence`

## Architectural modules that should be added

- `runtime/session/SessionRuntimeManager.kt`
- `runtime/context/ContextAssembler.kt`
- `runtime/history/TranscriptWindowBuilder.kt`
- `runtime/host/WorkspaceGateway.kt`
- `runtime/host/CommandRunner.kt`
- `runtime/host/PythonRunner.kt`
- `runtime/store/SessionStore.kt`
- `runtime/store/TranscriptStore.kt`
- `runtime/store/KeyValueStore.kt`

## Design Rules Going Forward

These rules should guide all new work if Harmony portability is a real goal.

### Rule 1

No new Android framework type should enter shared runtime logic unless it is behind an adapter.

### Rule 2

No UI class should create or own the actual agent runtime directly.

### Rule 3

All filesystem and permission semantics must be expressed in platform-neutral interfaces first.

### Rule 4

All settings and lightweight persistence must depend on abstract stores, not `SharedPreferences`.

### Rule 5

Context, memory, skills, and session logic are shared-core concerns, not app-shell concerns.

### Rule 6

Flutter, if chosen, must remain a presentation layer and must not become the new place where host logic accumulates.

## Final Recommendation

If your real goal is:

- "later move to Harmony and mostly reuse the architecture"

then the best path is:

1. first extract runtime and host boundaries
2. then decide UI strategy
3. only after that choose between:
   - native Harmony UI
   - Flutter shared UI

### My direct recommendation

Right now, I would not make the Flutter decision first.

I would first make OpenCray compatible with the following statement:

"The UI does not own the agent. The host does."

Once that is true, your Flutter decision becomes tactical.

Before that, it is premature, because Flutter can reduce UI migration cost but cannot rescue a platform-coupled runtime architecture.

## Recommended next document

After this document, the most useful follow-up would be a task breakdown file such as:

- `docs/harmony-portability-issues.md`

containing:

- concrete issue-style refactoring tasks
- file-level ownership
- acceptance criteria
- priority order

That would make the portability plan executable rather than only directional.
