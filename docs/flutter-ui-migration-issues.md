# Flutter UI Migration Issue Backlog

Last updated: 2026-03-12

## Purpose

This document turns `docs/flutter-ui-migration-architecture.md` into an implementation backlog that can be copied into GitHub Issues, Linear, Jira, or another tracker with minimal editing.

It is written for the current OpenCray repository state on 2026-03-12.

The main goal is not only to "move screens to Flutter". The goal is to:

- keep runtime ownership out of Flutter
- preserve reuse value for future HarmonyOS work
- prevent the current Android `Activity` coupling from being recreated inside Flutter pages
- establish a stable host-and-core boundary before large UI work begins

## Non-Negotiable Architecture Rules

These rules should be repeated in implementation PRs and issue descriptions.

### Rule 1: Flutter is the presentation layer, not the runtime owner

Flutter must not directly own:

- runtime creation
- prompt assembly
- context compaction
- session persistence
- workspace permission semantics
- command execution
- Python orchestration
- secrets or credential storage

Flutter should only own:

- screen composition
- view state rendering
- user input capture
- navigation state
- UI-side optimistic interaction state when explicitly needed

### Rule 2: The host must expose typed application services, not ad hoc page-specific methods

Do not build the bridge as:

- `sendChatMessage()`
- `openFilesPage()`
- `toggleSkillEnabled()`

without a typed application boundary underneath.

First create host-owned facades and runtime managers. Then expose those to Flutter.

### Rule 3: Context assembly must live below the bridge

Flutter should not compose:

- system prompt layers
- transcript replay
- memory recall blocks
- soul material
- skill inventory prompt text

Flutter may display them for debugging, but it must not define them.

### Rule 4: Android-specific filesystem and permission behavior must be isolated behind ports

`SafWorkspaceBridge` is an Android adapter.

It must not become the de facto shared workspace abstraction for all future platforms.

### Rule 5: The current Android UI must be strangled gradually, not rewritten blindly

The lowest-risk path is:

1. extract facades and runtime ownership
2. make the current Android UI consume those abstractions
3. expose the same abstractions to Flutter
4. move screens one by one
5. remove duplicated native presentation logic at the end

## Recommended Delivery Strategy

Use an add-to-app migration first.

That means:

- keep Android as the host app for now
- embed Flutter as the primary UI implementation gradually
- keep platform adapters on Android native side
- keep future HarmonyOS support as a later host-adapter project, not a blocker for Flutter migration

This is lower risk than trying to immediately replatform the entire product around Flutter-owned runtime code.

## Suggested Labels

Use these labels consistently where they fit:

- `flutter`
- `android-shell`
- `runtime`
- `app-layer`
- `bridge`
- `context`
- `skills`
- `workspace`
- `persistence`
- `p0`
- `p1`
- `p2`

## Reference Reading Order

Before implementing the first Flutter issue, engineers should study these files in this order.

### OpenCray current code

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`
- `docs/context-management-design.md`
- `docs/harmony-portability-architecture.md`
- `docs/flutter-ui-migration-architecture.md`

### OpenClaw patterns worth studying

- `D:/codes/Opensource/openclaw/src/config/sessions/store.ts`
- `D:/codes/Opensource/openclaw/src/config/sessions/transcript.ts`
- `D:/codes/Opensource/openclaw/src/agents/system-prompt.ts`
- `D:/codes/Opensource/openclaw/src/agents/subagent-spawn.ts`

Study these for:

- session store ownership
- transcript append and session file discipline
- sectioned system prompt assembly
- reduced-context subagent rules

Do not copy:

- Node-specific file layouts or runtime assumptions
- prompt wording as-is
- gateway-specific implementation details

Copy the patterns, not the stack.

### AstrBot patterns worth studying

- `D:/codes/Opensource/AstrBot/astrbot/core/conversation_mgr.py`
- `D:/codes/Opensource/AstrBot/astrbot/core/agent/context/manager.py`
- `D:/codes/Opensource/AstrBot/astrbot/core/astr_main_agent.py`
- `D:/codes/Opensource/AstrBot/astrbot/core/skills/skill_manager.py`

Study these for:

- conversation and active-session separation
- context compression boundary
- staged main-agent assembly
- skills inventory injection

Do not copy:

- AstrBot's plugin-first product assumptions
- provider-specific branching in UI-facing layers
- Python service topology directly

## Issue Sequencing

The correct sequence is:

1. extract host application boundary
2. extract host capability ports
3. move context and prompt assembly downwards
4. convert current Android screens to use the new boundary
5. define bridge DTOs and events
6. bring in Flutter shell
7. migrate features one by one
8. remove duplicated native presentation logic

If Flutter screens are started before steps 1 to 5 are done, the project will very likely recreate the current coupling in a new language.

## P0 Issues

### Issue P0-1: Introduce host-owned session runtime manager and application facades

#### Priority

P0

#### Suggested labels

- `flutter`
- `runtime`
- `app-layer`
- `android-shell`
- `p0`

#### Problem

`AppShellActivity` currently owns too much:

- session state wiring
- runtime construction
- chat execution flow
- UI composition
- settings composition
- files workflow decisions
- parts of personalization and skills wiring

This makes it impossible to expose a clean Flutter-facing API.

#### Why this matters

Without a host-owned application boundary, Flutter integration will become a thin wrapper around `Activity` logic. That would preserve current technical debt and make future Harmony migration harder.

#### Scope

- Introduce a session runtime manager keyed by chat session id.
- Introduce host-facing facades such as:
  - `ChatFacade`
  - `FilesFacade`
  - `SettingsFacade`
  - `SkillsFacade`
  - `PersonalizationFacade`
- Move non-UI flow logic out of `AppShellActivity`.
- Make the facades stable enough to be consumed by both native Android UI and Flutter bridge code.

#### Out of scope

- Flutter screen implementation
- Harmony host adapter implementation
- full prompt redesign

#### Suggested implementation areas

- new: `app/src/main/kotlin/com/opencray/app/runtime/AgentSessionRuntimeManager.kt`
- new: `app/src/main/kotlin/com/opencray/app/facade/ChatFacade.kt`
- new: `app/src/main/kotlin/com/opencray/app/facade/FilesFacade.kt`
- new: `app/src/main/kotlin/com/opencray/app/facade/SettingsFacade.kt`
- new: `app/src/main/kotlin/com/opencray/app/facade/SkillsFacade.kt`
- new: `app/src/main/kotlin/com/opencray/app/facade/PersonalizationFacade.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Reference code to study

- OpenCray:
  - `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- OpenClaw:
  - `D:/codes/Opensource/openclaw/src/config/sessions/store.ts`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/conversation_mgr.py`

#### Implementation guidance

- Model the runtime manager as the owner of:
  - submit
  - cancel
  - retry
  - resume
  - observe session events
- Keep the facade return types UI-neutral. Do not return Android Views, Compose state objects, or Flutter-specific structures.
- Treat facades as application services, not repositories with random methods.
- Use the same facade methods from current Android UI first. This is the strangler step that proves the boundary is correct.

#### Acceptance criteria

- `AppShellActivity` no longer directly constructs the full runtime path for chat submission.
- Session runtime ownership is keyed by session id and reusable across repeated interactions.
- Current Android UI can submit chat actions through `ChatFacade`.
- Unit tests cover session runtime lookup and lifecycle transitions.

#### Dependencies

None

### Issue P0-2: Define host capability ports for storage, workspace, permissions, commands, and Python

#### Priority

P0

#### Suggested labels

- `flutter`
- `runtime`
- `workspace`
- `persistence`
- `p0`

#### Problem

The current code mixes shared runtime concerns with Android-hosted implementations. This is especially visible in:

- local storage
- filesystem access
- SAF permission handling
- command execution
- Python runtime hosting

#### Why this matters

Flutter can only remain portable if host-specific behavior is isolated behind replaceable interfaces.

#### Scope

- Introduce platform-neutral ports such as:
  - `SessionStore`
  - `TranscriptStore`
  - `KeyValueStore`
  - `SecretStore`
  - `WorkspaceGateway`
  - `WorkspacePermissionGateway`
  - `CommandRunner`
  - `PythonRunner`
- Rebind current Android implementations to those ports.
- Ensure application facades depend on ports, not directly on Android primitives.

#### Out of scope

- Rewriting all persistence internals
- HarmonyOS implementation
- Flutter channel wiring

#### Suggested implementation areas

- new: `core` or `runtime` port package for shared interfaces
- `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`

#### Reference code to study

- OpenCray:
  - `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`
  - `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
  - `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- OpenClaw:
  - `D:/codes/Opensource/openclaw/src/config/sessions/transcript.ts`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/conversation_mgr.py`

#### Implementation guidance

- Do not move Android-specific code into Flutter plugins as the primary abstraction. Keep the canonical abstraction in the host/core codebase first.
- `SafWorkspaceBridge` should become one `WorkspaceGateway` implementation, not the interface the whole product is designed around.
- Be strict about naming. If an interface is Android-specific, say so in the name. If it is meant to be shared, keep it platform-neutral.

#### Acceptance criteria

- Facades and runtime manager depend on ports instead of Android `Context`, `SharedPreferences`, or direct SAF classes.
- Android-specific filesystem and permission logic is reachable through replaceable interfaces.
- The project has a documented list of host ports with one Android implementation each.

#### Dependencies

- P0-1

### Issue P0-3: Move transcript replay, prompt layers, and context assembly below the UI boundary

#### Priority

P0

#### Suggested labels

- `flutter`
- `runtime`
- `context`
- `p0`

#### Problem

OpenCray currently has a minimal runtime loop, but not a mature context pipeline. If Flutter starts sending ad hoc prompt inputs directly, the codebase will get a second copy of context logic in the bridge layer.

#### Why this matters

Context management is one of the core product differentiators of an agent system. It must not be fragmented across Android activities, Flutter pages, and runtime internals.

#### Scope

- Create a host-side context assembly pipeline that combines:
  - bounded transcript history
  - prompt layers
  - soul and personalization material
  - memory recall blocks when available
  - skill inventory material when relevant
- Expose this only as runtime-ready input or a debug-inspection model.
- Remove remaining UI-owned prompt concatenation.

#### Out of scope

- full long-term memory rollout
- final memory ranking algorithm
- subagent implementation

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/context/ContextAssembler.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptAssembler.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptLayers.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Reference code to study

- OpenClaw:
  - `D:/codes/Opensource/openclaw/src/agents/system-prompt.ts`
  - `D:/codes/Opensource/openclaw/src/config/sessions/transcript.ts`
  - `D:/codes/Opensource/openclaw/src/agents/subagent-spawn.ts`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/agent/context/manager.py`
  - `D:/codes/Opensource/AstrBot/astrbot/core/astr_main_agent.py`

#### Implementation guidance

- Follow OpenClaw's general pattern of explicit prompt sections, not one growing string blob.
- Follow AstrBot's principle that context compression belongs to a dedicated manager, not random call sites.
- Keep debug visibility. Engineers should be able to inspect which layers were included for a run.
- Treat transcript replay and context compaction as application/runtime concerns, not UI concerns.

#### Acceptance criteria

- Runtime input is assembled through named context and prompt layers.
- The UI boundary no longer owns transcript replay or system prompt composition.
- Tests verify bounded replay and deterministic layer inclusion.

#### Dependencies

- P0-1
- P0-2

### Issue P0-4: Convert current Android screens to consume facades and DTOs only

#### Priority

P0

#### Suggested labels

- `flutter`
- `android-shell`
- `app-layer`
- `p0`

#### Problem

If the current Android UI is not first converted to use the extracted boundary, there is no proof that the boundary is complete. Flutter integration would then be forced to bypass it.

#### Why this matters

This issue is the bridge-quality test. If Android can consume the facades cleanly, Flutter can too.

#### Scope

- Refactor current Android screen logic to call facades and observe DTO/event outputs.
- Stop reading stores and runtime internals directly inside UI-heavy classes.
- Keep visual behavior stable while changing the internal ownership model.

#### Out of scope

- Flutter implementation
- UI redesign

#### Suggested implementation areas

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/features/skills/skills_feature.dart`
- `app/src/main/kotlin/com/opencray/app/facade/skills/SkillsFacade.kt`

#### Reference code to study

- OpenCray:
  - `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/astr_main_agent.py`

#### Implementation guidance

- Do not optimize for elegance inside the old Android UI. Optimize for boundary clarity.
- It is acceptable for the old Android UI to become a thin shell temporarily, as long as business logic moves downward.
- DTOs should be serializable and bridge-ready from the start.

#### Acceptance criteria

- Android UI flow uses facades instead of directly orchestrating runtime and storage details.
- Event subscription is routed through typed DTOs or state objects that do not depend on Flutter.
- Existing Android tests can be updated with limited behavioral changes.

#### Dependencies

- P0-1
- P0-2
- P0-3

### Issue P0-5: Define bridge-ready DTOs, commands, and event stream contracts

#### Priority

P0

#### Suggested labels

- `flutter`
- `bridge`
- `app-layer`
- `p0`

#### Problem

Without a typed contract, Flutter integration will drift into stringly typed method channels and page-specific request shapes.

#### Why this matters

The bridge contract is what protects the architecture from UI-driven leakage.

#### Scope

- Define DTOs for:
  - chat sessions
  - transcript items
  - runtime events
  - timeline/tool call events
  - approvals
  - workspace grants
  - settings snapshots
  - skills catalog entries
- Define command payloads for:
  - submit prompt
  - cancel run
  - retry run
  - select session
  - update settings
  - request workspace grant
  - enable or disable skill
- Define event channel behavior for:
  - state snapshot
  - incremental updates
  - terminal completion
  - error delivery

#### Out of scope

- Final Flutter widget implementation
- transport choice beyond Android host to Flutter embedding

#### Suggested implementation areas

- new: `app/src/main/kotlin/com/opencray/app/bridge/model/...`
- new: `app/src/main/kotlin/com/opencray/app/bridge/events/...`
- new: `app/src/main/kotlin/com/opencray/app/bridge/commands/...`
- facade packages from P0-1

#### Reference code to study

- OpenClaw:
  - `D:/codes/Opensource/openclaw/src/config/sessions/store.ts`
  - `D:/codes/Opensource/openclaw/src/config/sessions/transcript.ts`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/conversation_mgr.py`

#### Implementation guidance

- Prefer explicit versioned envelopes for events.
- Distinguish:
  - snapshot events
  - append events
  - patch events
  - terminal events
- Avoid leaking internal persistence model classes directly over the bridge.
- Design for resumable UI attachment. Flutter should be able to reconnect and obtain the latest state snapshot.

#### Acceptance criteria

- DTOs are serializable and independent from Android UI types.
- There is a documented contract for commands and event streams.
- The bridge contract can represent chat, settings, files, and skills flows without page-specific hacks.

#### Dependencies

- P0-1
- P0-4

## P1 Issues

### Issue P1-1: Create Flutter shell module and host embedding strategy

#### Priority

P1

#### Suggested labels

- `flutter`
- `android-shell`
- `bridge`
- `p1`

#### Problem

The project has chosen Flutter as the UI direction, but there is not yet a concrete module strategy for embedding, build integration, and host bootstrapping.

#### Why this matters

Without an explicit embedding strategy, early Flutter work will turn into disconnected experiments rather than a production migration path.

#### Scope

- Create a Flutter module or app structure appropriate for Android-hosted embedding.
- Define how Flutter is launched from the Android host.
- Define how the bridge layer is initialized and authenticated within the host process.
- Decide the folder location and ownership model for Flutter code.

#### Out of scope

- Full feature migration
- HarmonyOS support

#### Suggested implementation areas

- new: `flutter_app/` or another explicitly chosen Flutter root
- Android app host bootstrapping code
- project docs for build and run instructions

#### Reference code to study

- OpenCray:
  - `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- External references:
  - use official Flutter add-to-app guidance for embedding mechanics

#### Implementation guidance

- Keep the initial Flutter shell thin.
- The first success criterion is not visual completeness. It is proving that Flutter can render a host-provided screen using real bridge data.
- Choose one integration style and document it. Do not let multiple experimental patterns coexist for long.

#### Acceptance criteria

- The repository contains a committed Flutter module with repeatable local build instructions.
- Android can launch the Flutter shell inside the existing app.
- The bridge can return at least one host-backed state snapshot to Flutter.

#### Dependencies

- P0-5

### Issue P1-2: Implement Flutter chat shell on top of host chat facade

#### Priority

P1

#### Suggested labels

- `flutter`
- `bridge`
- `runtime`
- `p1`

#### Problem

Chat is the highest-value workflow and the most tightly coupled to runtime state. It is also where bridge mistakes become obvious.

#### Why this matters

If the Flutter chat shell works correctly against the host boundary, the rest of the migration becomes much more straightforward.

#### Scope

- Build Flutter chat session list and active conversation rendering.
- Render:
  - user messages
  - assistant messages
  - tool traces
  - approval prompts
  - in-flight session state
- Send chat commands through the bridge.
- Subscribe to runtime and transcript events from the host.

#### Out of scope

- file workbench
- skills management
- deep settings migration

#### Suggested implementation areas

- Flutter chat feature package
- host bridge handlers for chat commands and streams
- `ChatFacade` from P0-1

#### Reference code to study

- OpenCray:
  - `flutter_app/lib/features/chat/chat_feature_screen.dart`
  - `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- OpenClaw:
  - `D:/codes/Opensource/openclaw/src/config/sessions/transcript.ts`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/conversation_mgr.py`

#### Implementation guidance

- Keep transcript ordering and session restoration host-driven.
- Do not let Flutter infer authoritative session state from local widget memory.
- Design the chat page around stream reconciliation:
  - initial snapshot
  - append event
  - tool progress
  - approval update
  - terminal completion

#### Acceptance criteria

- Flutter chat can send prompts and render real host-backed response progress.
- Reopening the Flutter chat screen restores the latest session state from the host.
- Approval prompts and tool trace items render without Flutter-side business logic forks.

#### Dependencies

- P0-1
- P0-5
- P1-1

### Issue P1-3: Implement Flutter files and workspace permission flows through host adapters

#### Priority

P1

#### Suggested labels

- `flutter`
- `workspace`
- `android-shell`
- `p1`

#### Problem

Workspace access is highly platform-specific, especially under Android SAF semantics. This is exactly the kind of flow that can silently break portability if Flutter owns too much.

#### Why this matters

This issue proves whether the new port-and-adapter boundary is real or only cosmetic.

#### Scope

- Build Flutter-facing files/workspace screens.
- Route workspace selection and grant requests through the host.
- Render grant state, revoked state, and error state from host DTOs.
- Support attachment picking and file metadata display using host-provided results.

#### Out of scope

- HarmonyOS file permissions
- replacing Android SAF behavior

#### Suggested implementation areas

- Flutter files feature package
- host `FilesFacade`
- workspace port implementations
- `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`

#### Reference code to study

- OpenCray:
  - `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt`
  - `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Implementation guidance

- Flutter should trigger intents or permission requests indirectly through host commands, not construct platform file semantics itself.
- Treat workspace grant state as host truth.
- Keep error codes structured. Flutter should render state, not guess root causes from message strings.

#### Acceptance criteria

- Flutter can request workspace selection and receive host grant state updates.
- Revoked or invalid grant states are rendered correctly.
- Attachment and file metadata flows do not require Flutter to understand SAF internals.

#### Dependencies

- P0-2
- P0-5
- P1-1

### Issue P1-4: Implement Flutter settings, personalization, and skills management

#### Priority

P1

#### Suggested labels

- `flutter`
- `skills`
- `app-layer`
- `p1`

#### Problem

Settings, soul, personalization, and skills are currently entangled with Android screen logic. If they are migrated casually, the bridge will fill with one-off mutation methods.

#### Why this matters

These domains define long-term product operability. They should be the best-modeled parts of the bridge, not the most improvised.

#### Scope

- Build Flutter screens for:
  - settings
  - model and telemetry preferences
  - personalization and soul editing
  - skills catalog and skill editor
- Route reads and writes through typed facades.
- Ensure prompt- and runtime-relevant changes invalidate or refresh host state correctly.

#### Out of scope

- full runtime skill execution overhaul
- new memory system implementation

#### Suggested implementation areas

- Flutter settings and skills feature packages
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`
- `app/src/main/kotlin/com/opencray/app/AppSkillsStorage.kt`

#### Reference code to study

- OpenClaw:
  - `D:/codes/Opensource/openclaw/src/agents/system-prompt.ts`
- AstrBot:
  - `D:/codes/Opensource/AstrBot/astrbot/core/skills/skill_manager.py`
  - `D:/codes/Opensource/AstrBot/astrbot/core/astr_main_agent.py`

#### Implementation guidance

- Keep a strong distinction between:
  - editable user preference state
  - runtime-consumed prompt/context material
  - installed or available skill inventory
- Follow AstrBot's idea of a skill inventory prompt, but keep execution semantics under OpenCray host control.
- Make soul and personalization edits observable by the runtime manager without Flutter needing to know prompt internals.

#### Acceptance criteria

- Flutter can read and update settings, personalization, and skills through typed host contracts.
- Host state changes are persisted correctly.
- Prompt- or runtime-relevant settings changes refresh future runs without UI-owned prompt logic.

#### Dependencies

- P0-1
- P0-3
- P0-5
- P1-1

### Issue P1-5: Build bridge contract tests and end-to-end host-plus-Flutter smoke tests

#### Priority

P1

#### Suggested labels

- `flutter`
- `bridge`
- `runtime`
- `p1`

#### Problem

Once two UI stacks temporarily coexist, regressions will appear in:

- event ordering
- serialization
- session restoration
- approval state
- error propagation

#### Why this matters

The migration will fail if the bridge becomes opaque and untestable.

#### Scope

- Add contract tests for bridge DTO serialization and versioning.
- Add host-side tests for facade behavior independent of Flutter.
- Add smoke tests proving Flutter can attach, request initial state, send a command, and receive a streamed result.

#### Out of scope

- full visual regression testing
- all-device compatibility matrix

#### Suggested implementation areas

- bridge contract test packages
- app integration test harness
- Flutter integration test harness

#### Reference code to study

- OpenCray existing Android tests under:
  - `app/src/androidTest/kotlin/com/opencray/app/`

#### Implementation guidance

- Test reconnect behavior explicitly.
- Test both snapshot and incremental events.
- Test bridge behavior under app restart or page recreation.
- Keep event envelopes versioned so bridge evolution remains manageable.

#### Acceptance criteria

- DTO contracts have automated serialization coverage.
- A smoke test proves Flutter chat can perform one real host-backed interaction.
- Reattachment after screen recreation or process recreation is covered by tests or documented limitations.

#### Dependencies

- P0-5
- P1-2
- P1-3
- P1-4

## P2 Issues

### Issue P2-1: Remove duplicated native Android presentation logic after Flutter reaches feature parity

#### Priority

P2

#### Suggested labels

- `flutter`
- `android-shell`
- `p2`

#### Problem

During migration, the project will temporarily carry two presentation stacks. Keeping both indefinitely would multiply maintenance cost and regressions.

#### Why this matters

The purpose of the migration is to reduce future UI duplication, not institutionalize it.

#### Scope

- Remove superseded Android-native screen composition once Flutter parity is achieved.
- Keep only the Android host responsibilities that still belong on the native side.
- Update docs and tests to reflect the new ownership model.

#### Out of scope

- Removing all Android code
- HarmonyOS implementation

#### Suggested implementation areas

- Android UI activities and screen classes that become presentation-only duplicates
- related test suites and docs

#### Reference code to study

- OpenCray current Android UI packages under:
  - `app/src/main/kotlin/com/opencray/app/`
  - `flutter_app/lib/features/`

#### Implementation guidance

- Remove only after parity is proven with tests and manual verification.
- Keep thin host entrypoints if they are still needed for embedding, intents, or permissions.

#### Acceptance criteria

- Duplicated native UI logic is removed for migrated features.
- Host responsibilities remain intact.
- Documentation clearly states which layer owns which concern.

#### Dependencies

- P1-2
- P1-3
- P1-4
- P1-5

### Issue P2-2: Prepare Harmony-ready host seam without blocking Android plus Flutter delivery

#### Priority

P2

#### Suggested labels

- `flutter`
- `runtime`
- `p2`

#### Problem

The future HarmonyOS goal can cause over-design if treated as an immediate delivery constraint. At the same time, ignoring it entirely will recreate lock-in.

#### Why this matters

The right balance is to preserve a clean seam now, without demanding a full second host implementation immediately.

#### Scope

- Audit whether the host ports introduced in P0 are sufficient for a second platform.
- Identify any remaining Android-only assumptions in:
  - app services
  - runtime setup
  - workspace abstraction
  - bridge DTOs
- Document the minimum additional work needed for a Harmony host later.

#### Out of scope

- implementing Harmony adapters now
- shipping a Harmony build now

#### Suggested implementation areas

- host ports and application service packages
- `docs/harmony-portability-architecture.md`
- `docs/flutter-ui-migration-architecture.md`

#### Reference code to study

- OpenCray:
  - `docs/harmony-portability-architecture.md`
  - `docs/context-management-design.md`

#### Implementation guidance

- Ask one question repeatedly: "If Android were removed here, what exactly would break?"
- Any answer that includes prompt assembly, context replay, or session ownership indicates the boundary is still wrong.

#### Acceptance criteria

- The project has a documented list of Android-only adapters versus platform-neutral layers.
- Remaining Android coupling points are enumerated explicitly.
- No Flutter feature implementation requires direct Android business logic access outside the defined host ports.

#### Dependencies

- P0-2
- P1-5

## Definition of Done

The Flutter migration can be considered architecturally successful when all of the following are true:

1. Flutter owns presentation, navigation, and UI interaction only.
2. The Android host owns runtime, persistence, workspace permissions, commands, Python, and secrets.
3. Context assembly, prompt layering, memory/soul injection, and skills inventory logic live below the bridge.
4. Current Android UI has already been strangled through the same facades used by Flutter.
5. Flutter chat can reconnect to an existing session and recover host-backed state.
6. Files and workspace flows work through typed host adapters rather than Flutter-side platform logic.
7. Settings, personalization, and skills management use typed bridge contracts rather than ad hoc method calls.
8. The remaining Android-only code is clearly identifiable as host-adapter code, not mixed application logic.

## Common Failure Modes

These are the migration mistakes most likely to waste time:

### Failure mode 1: Building Flutter pages before extracting facades

Result:

- Flutter reaches into `Activity` logic through improvised channels
- host boundary is never stabilized

### Failure mode 2: Letting Flutter own authoritative session state

Result:

- app restart behavior becomes fragile
- reconnect and resume semantics become inconsistent

### Failure mode 3: Pushing Android permission semantics into Flutter business logic

Result:

- portability gets worse, not better
- Harmony work later still requires invasive rewrites

### Failure mode 4: Treating prompt/context logic as a view concern

Result:

- prompt behavior forks between UI implementations
- debugging becomes much harder

### Failure mode 5: Recreating one-off bridge methods for every page action

Result:

- bridge surface explodes
- architecture collapses into RPC clutter instead of stable application services

## Recommended Ownership Split

This is the target working split during implementation:

### Flutter engineers

- screen composition
- navigation
- visual state handling
- bridge client
- UI-side interaction polish

### Android host engineers

- runtime manager
- facades
- bridge handlers
- storage and permission adapters
- session restoration
- platform integrations

### Shared runtime engineers

- prompt and context assembly
- memory and soul wiring
- skills inventory and execution semantics
- tool policy and approval model
- queue, orchestration, and session lifecycle

This division is what keeps the migration efficient instead of turning it into a full rewrite.
