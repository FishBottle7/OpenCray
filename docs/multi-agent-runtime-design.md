# OpenCray Multi-Agent Runtime Design

Last updated: 2026-03-20

## Status

Approved implementation design

## Purpose

This document defines how OpenCray should evolve from one built-in host agent into multiple user-selectable agents.

Each agent should have its own:

- base soul
- adaptive memory and relationship state
- chat sessions
- workspace
- runtime ownership and queue state

This document is intentionally implementation-oriented. It narrows the design to the current product shape:

- users can create multiple agents
- users can choose which agent they are currently talking to
- users can choose which agent should perform work
- agents do not talk to one another yet
- no cross-agent memory sharing is introduced in this rollout

## Relationship To Existing Context Design

This document supersedes one earlier storage assumption from `docs/context-management-design.md`:

- base `SOUL.md` should no longer live in the agent-visible public workspace root

For the multi-agent product shape:

- public workspace remains tool-visible
- base soul stays in an agent-private area
- runtime still injects typed soul into execution
- ordinary file tools and normal chat flows must not read or rewrite the base soul document

## Approved Product Decisions

### 1. Agent is a first-class isolation boundary

`agentId` must become a real storage and runtime boundary, not only a UI selection value.

Isolation is required for:

- soul
- memory
- session history
- workspace
- queue snapshots
- run records
- compaction state
- transcript supplements

### 2. Base soul stays document-based, but private

OpenCray should keep using `SOUL.md` as the durable base soul source, because the document form is:

- easy to inspect during development
- easy to seed from presets
- easy to diff and debug

But it must move out of the agent-visible public workspace.

`SOUL.md` belongs in an agent-private root and is owned by the host, not by normal runtime file tools.

### 3. Session IDs must be globally unique

`sessionId` must not only be unique within an agent.

It should be globally unique across the whole app instance.

The same rule should also be applied to:

- `agentId`
- `runId`
- `taskId`

Ownership should be represented explicitly by fields and storage relationships, not by relying on local uniqueness assumptions.

### 4. Default isolation is strict

This rollout should not introduce shared memory between agents.

Specifically:

- no shared user-preference store
- no shared relationship state
- no shared task commitments
- no shared project facts

If a later product version wants a shared layer, it should be added explicitly as a second system instead of being implicit in the base storage.

### 5. Agent switching must not rewrite or merge context

Switching the current active agent means switching the bound runtime container and its backing stores.

It must not:

- merge session history
- migrate memory rows between agents
- copy soul state automatically
- change another agent's workspace

## Non-Goals For This Rollout

- no agent-to-agent conversation
- no shared memory graph
- no cross-agent task delegation
- no automatic import of one agent's soul or memory into another
- no compatibility migration for unreleased legacy data

Because the product has not shipped yet, this rollout may prefer a clean new storage layout over compatibility-preserving migration.

## Core Model

## AgentDescriptor

Introduce a durable descriptor for each agent.

Recommended minimum fields:

- `agentId`
- `displayName`
- `createdAtEpochMs`
- `updatedAtEpochMs`
- `presetName`
- `plasticity`
- `activeSessionId`
- `isArchived`

Recommended optional fields:

- `avatarSeed`
- `customLabel`
- `notes`

This descriptor is host-owned metadata. It is not the soul itself and not the memory store.

## AgentRegistryStore

Introduce a registry store that owns:

- the list of agents
- the currently selected `activeAgentId`
- create / rename / archive / delete operations

The registry should be the only source of truth for which agents exist.

## AgentRuntimeContainer

Introduce a per-agent runtime container that owns the entire assembled dependency group for one agent.

Recommended contents:

- `agentId`
- `ChatSessionLocalStore`
- `PersonalizationLocalStore`
- private soul store
- workspace root provider
- transcript / supplement / compaction store factories
- queue snapshot store factory
- run record store factory
- `AgentSessionRuntimeManager`
- host runtime facade bindings for that agent

The container boundary is important because it prevents accidental sharing of stores or runtime state across agents.

## Storage Layout

Recommended layout:

```text
files/
  agents/
    <agentId>/
      private/
        SOUL.md
        agent-config.json
      workspace/
        ...
      chat-local-state/
        ...
      personalization-local-state/
        ...
      queue-snapshots/
        ...
      run-records/
        ...
      transcript-store/
        ...
      transcript-supplements/
        ...
      compaction/
        ...
      voice-metadata-cache/
        ...
  agent-registry/
    agents.json
```

### Public workspace

`agents/<agentId>/workspace/`

This is the normal workspace root visible to runtime tools.

It may contain:

- project files
- user documents
- optional public bootstrap files such as `AGENTS.md`, `TOOLS.md`, `PROJECT.md`

### Private agent root

`agents/<agentId>/private/`

This root is host-only.

It may contain:

- `SOUL.md`
- future agent-private configuration or diagnostics

It must not be exposed through normal runtime file tools.

## Soul Design

### Base soul authority

The durable base soul source remains `SOUL.md`, but the authoritative path becomes:

`agents/<agentId>/private/SOUL.md`

This document is:

- created when an agent is created
- seeded from the selected preset
- updated only by explicit creator/admin flows

### Runtime behavior

At runtime:

1. host loads the private `SOUL.md`
2. host parses it into typed soul form
3. runtime merges it with adaptive memory-backed state
4. runtime injects the final bounded soul contract into prompt assembly

### Public bootstrap change

For the multi-agent rollout, `SOUL.md` should no longer be treated as a public workspace bootstrap file.

That means:

- public bootstrap discovery may still include `AGENTS.md`, `TOOLS.md`, and `PROJECT.md`
- base soul injection comes from the private soul store path, not from public file discovery
- `no_soul` and `no_memory_or_soul` modes still suppress the injected soul contract layer

## Memory Design

Each agent must have its own memory store root:

`agents/<agentId>/personalization-local-state/`

This store owns:

- `user_preference`
- `durable_instruction`
- `project_fact`
- `task_commitment`
- `InteractionPreferenceState`
- `RelationshipState`
- `RelationshipEvent`
- debug-only memory maintenance audit data

This preserves the intended product behavior:

- one agent may know the user's nickname
- another agent may remain formal
- one agent may be warm and cultivated
- another may remain task-first and professional

No memory rows should be implicitly shared across agents in this rollout.

## Session Design

Each agent must have its own chat/session root:

`agents/<agentId>/chat-local-state/`

This store owns:

- session list
- transcript messages
- active session pointer for that agent
- chat-local todo presentation state

Session ownership is explicit:

- every session belongs to exactly one `agentId`
- `activeSessionId` in the registry is scoped per agent

Even though sessions are stored under one agent directory, `sessionId` itself should still be globally unique.

## Runtime And Queue Design

Each agent must own its own runtime lifecycle resources:

- queue snapshots
- run records
- transcript store
- transcript supplements
- compaction store
- managed process ownership

That means the current single host assembly path should be split so that these factories resolve under the active agent root:

- `FileBackedAgentQueueSnapshotStoreFactory`
- `FileBackedAgentRunRecordStoreFactory`
- `FileBackedAgentSessionTranscriptStoreFactory`
- `FileBackedAgentSessionSupplementStoreFactory`
- `FileBackedAgentSessionCompactionStoreFactory`

The `DefaultAgentSessionRuntimeManager` already has an `agentId` concept and should continue to use it, but the created manager must now be owned by a specific `AgentRuntimeContainer`, not by one global host singleton.

## ID Rules

## Agent IDs

`agentId` must be globally unique.

Recommended format:

- `agent-<ulid>`

## Session IDs

`sessionId` must be globally unique across the whole app.

Recommended format:

- `session-<ulid>`

## Run IDs

`runId` must be globally unique across the whole app.

Recommended format:

- `run-<ulid>`

## Task IDs

`taskId` must be globally unique across the whole app.

Recommended format:

- `task-<ulid>`

These IDs should not rely on parent context to stay unique.

Parent-child relationships should be recorded explicitly in fields and storage topology.

## Switching Semantics

Switching the current agent means:

1. update `activeAgentId` in the registry
2. bind UI and host facades to that agent's container
3. show that agent's chat sessions, memory debug views, soul debug views, and workspace

Switching must not:

- cancel another agent's non-idle work automatically
- merge or copy state
- change another agent's active session pointer

If another agent still has running work, its runtime container may stay alive until idle release. The container is still isolated even when it is not the currently visible one.

## Security And Tool Boundary

The public workspace root for tools must be:

`agents/<agentId>/workspace/`

The private root must stay outside all normal tool-approved roots.

Required behavior:

- file tools can read/write only the public workspace root and other separately approved external roots
- file tools must not access `agents/<agentId>/private/`
- normal memory stewardship must not rewrite private `SOUL.md`
- direct-chat soul adaptation remains memory-backed and bounded, not base-soul rewriting

## Required Refactors

### New components

Recommended new types:

- `AgentDescriptor`
- `AgentRegistryStore`
- `AgentPathResolver`
- `AgentRuntimeContainer`
- `AgentRuntimeContainerRegistry`
- `AgentHostRuntimeFactory`
- `AgentSoulProfileStore`

### Existing components that need path/ownership refactor

- `AppAgentWorkspace`
- `WorkspaceSoulProfileStore`
- `PersonalizationLocalStore`
- `ChatSessionLocalStore`
- `OpenCrayHostRuntime`
- `DefaultAgentSessionRuntimeManager` assembly path
- `FileBackedAgentQueueSnapshotStoreFactory`
- `FileBackedAgentRunRecordStoreFactory`
- `FileBackedAgentSessionTranscriptStoreFactory`
- `FileBackedAgentSessionSupplementStoreFactory`
- `FileBackedAgentSessionCompactionStoreFactory`

## Recommended Implementation Order

### Phase 1: Agent registry and path isolation

1. add `AgentDescriptor`
2. add `AgentRegistryStore`
3. add `AgentPathResolver`
4. move all per-agent stores under `files/agents/<agentId>/...`

### Phase 2: Private soul store split

1. replace workspace-root soul loading with private-root soul loading
2. refactor `WorkspaceSoulProfileStore` into an agent-private soul store
3. remove public-workspace `SOUL.md` assumptions from bootstrap resolution

### Phase 3: Runtime containerization

1. introduce `AgentRuntimeContainer`
2. assemble one runtime dependency group per agent
3. bind `AgentSessionRuntimeManager` to that container

### Phase 4: UI selection and switching

1. add agent list / create / select UI
2. bind chat/settings/debug views to current agent
3. expose current `agentId` in debug surfaces

### Phase 5: Cleanup and hardening

1. remove remaining single-agent path assumptions
2. verify no host singleton still points at default global chat/memory directories
3. harden deletion/archive behavior for whole-agent cleanup

## Code Inspection Notes From The Current Repo

The implementation order above is correct, but the current repository shape matters for where the work should start.

### 1. Flutter Agents UI already exists, but it is still prototype-local

Inspected files:

- `flutter_app/lib/features/settings/settings_feature.dart`
- `flutter_app/lib/features/settings/agent_settings_pages.dart`
- `flutter_app/lib/features/settings/bridge_settings_facade.dart`
- `flutter_app/lib/core/bridge/opencray_host_bridge.dart`

Current reality:

- `SettingsFeatureScreen` opens a dedicated `_AgentsSettingsPage`
- `_AgentsSettingsPage` seeds its list from `_buildPrototypeAgents()`
- create / reuse flows only push `_AgentCreatePage` and return `_SavedAgent.fromDraft(...)` locally
- the page does not read from `SettingsFacade`
- the Flutter bridge has no `listAgents`, `createAgent`, `updateAgent`, or `selectAgent` method
- Android `SettingsFacade` returns only a placeholder "Dedicated editor" section for `SettingsRouteId.AGENTS`

Conclusion:

- the Flutter side is visually ready
- the host contract for real agent data does not exist yet
- the first usable landing slice should preserve the current UI layout and replace only the data source and save path

### 2. Host runtime is still assembled as one built-in default agent

Inspected files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentWorkspace.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AgentQueueSnapshotStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionTranscriptStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionSupplementStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionCompactionStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentProcessRegistryFactory.kt`

Current reality:

- the current production `OpenCrayHostRuntime` service-owned assembly still creates one singleton host assembly
- it wires one `PersonalizationLocalStore.fromContext(appContext)`
- it wires one `ChatSessionLocalStore.fromContext(appContext)`
- it uses one fixed `workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(appContext) }`
- it creates one `DefaultAgentSessionRuntimeManager(agentId = "opencray-flutter-host", ...)`
- `AppAgentWorkspace` still points to a single `agent-workspace` directory
- runtime persistence factories still root under one shared `agent-runtime` directory and partition only by `sessionId`

Conclusion:

- current host assembly is single-agent by construction, not by UI omission
- the multi-agent rollout must introduce a real container boundary
- path isolation has to happen before runtime switching is trustworthy

### 3. Soul initialization already exists and should be reused

Inspected files:

- `app/src/main/kotlin/com/opencray/app/facade/personalization/PersonalizationFacade.kt`
- `app/src/main/kotlin/com/opencray/app/WorkspaceSoulProfileStore.kt`
- `app/src/main/kotlin/com/opencray/app/WorkspaceSoulProfile.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationSoulExtensionFactory.kt`

Current reality:

- `LocalPersonalizationFacade.save(...)` already writes a `WorkspaceSoulProfile`
- `WorkspaceSoulProfileStore.saveSoulProfile(...)` already renders a real `SOUL.md`
- `PersonalizationSoulExtensionFactory` already expands preset-managed fields such as tone, verbosity, plasticity, relationship style, risk tolerance, and tool-use bias
- `WorkspaceSoulProfileStore` already merges preset-managed extensions with explicit frontmatter values

Conclusion:

- new-agent creation does not need a fresh soul bootstrap design
- it should reuse the existing soul document rendering path
- but the storage root must move from public workspace `SOUL.md` to private `agents/<agentId>/private/SOUL.md`

### 4. Memory initialization already exists and should move inside the per-agent container

Inspected files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/ChatMemoryIngestionCoordinator.kt`
- `app/src/main/kotlin/com/opencray/app/LiteLlmUserMemoryIntentInterpreter.kt`
- `app/src/main/kotlin/com/opencray/app/LiteLlmTaskCommitmentIntentInterpreter.kt`
- `app/src/main/kotlin/com/opencray/app/LiteLlmMemoryStewardshipInterpreter.kt`
- `app/src/main/kotlin/com/opencray/app/LiteLlmRelationshipEventInterpreter.kt`
- `app/src/main/kotlin/com/opencray/app/LiteLlmSoulTurnSignalInterpreter.kt`

Current reality:

- the current production `OpenCrayHostRuntime` assembly already builds one `ChatMemoryIngestionCoordinator`
- that coordinator already owns the post-turn write path for memory extraction, task commitment updates, stewardship, relationship events, and soul-turn signals
- it is currently bound to one `PersonalizationLocalStore.asMemoryStore()`

Conclusion:

- the memory subsystem itself is not the blocker
- the blocker is that it is assembled only once for one global store
- multi-agent should reuse the same ingestion stack by instantiating it inside each `AgentRuntimeContainer`

### 5. ID generation is still scattered

Inspected files:

- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

Current reality:

- session IDs are created inline in `ChatSessionLocalStore`
- run IDs and task IDs are still composed in runtime/session code and often include session-derived prefixes

Conclusion:

- multi-agent should add one explicit ID factory surface for `agentId`, `sessionId`, `runId`, and `taskId`
- do this before broad switching work, or uniqueness rules will keep leaking through ad hoc helpers

## Field Ownership For The First Host-Backed Agent Create Flow

The Flutter draft already captures more fields than the current host runtime consumes.

The first host-backed implementation should not drop those fields.

Recommended ownership:

| Flutter draft field | Durable owner in v1 | Runtime consumption in the first rollout |
| --- | --- | --- |
| `agentName` | `AgentDescriptor.displayName` and private `SOUL.md display_name` | consumed immediately |
| `soulPreset` | private `SOUL.md preset` | consumed immediately |
| `plasticity` | private `SOUL.md` extension | consumed immediately |
| `callsYou` | private `SOUL.md preferred_naming` extension | consumed immediately |
| `addressStyle` | private `SOUL.md preferred_address_style` extension | consumed immediately |
| `verbosity` | private `SOUL.md verbosity` extension | consumed immediately |
| `relationshipStyle` | private `SOUL.md user_relationship_style` extension | consumed immediately |
| `riskTolerance` | private `SOUL.md risk_tolerance` extension | consumed immediately |
| `toolUseBias` | private `SOUL.md tool_use_bias` extension | consumed immediately |
| `baseDescription` | structured body section in private `SOUL.md`, mirrored in `agent-config.json` | consumed immediately through soul guidance |
| `collaborationGuidance` | structured body section in private `SOUL.md`, mirrored in `agent-config.json` | consumed immediately through soul guidance |
| `escalationRules` | structured body section in private `SOUL.md`, mirrored in `agent-config.json` | consumed immediately through soul guidance |
| `forbiddenBehaviors` | structured body section in private `SOUL.md`, mirrored in `agent-config.json` | consumed immediately through soul guidance |
| `mode` | private `agent-config.json` | consumed when per-agent runtime mode wiring lands |
| `provider`, `protocol`, `baseUrl`, `apiKey`, `model`, `reasoningEffort` | private `agent-config.json` | consumed when per-agent LLM routing lands |
| avatar/media/reference image fields | private `agent-config.json` | UI-only at first; runtime-neutral |
| `voiceSummary` | private `agent-config.json` | optional later promotion into soul extensions |

Practical rule:

- `SOUL.md` should hold what already shapes behavior today
- `agent-config.json` should preserve additional per-agent settings that the UI already edits but runtime has not consumed yet
- do not block agent creation on full runtime support for every advanced field

## Concrete Landing Checklist From This Codebase

The following order is meant to be executable against the current repo, not only architecturally correct in the abstract.

### Slice 1: Freeze contracts and storage ownership

Primary goal:

- define the durable host contract before touching the runtime hot path

Recommended files to add:

- `app/src/main/kotlin/com/opencray/app/agent/AgentDescriptor.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentRegistryStore.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentPathResolver.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentConfig.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentIdFactory.kt`

Recommended tests:

- `app/src/test/kotlin/com/opencray/app/agent/AgentRegistryStoreTest.kt`
- `app/src/test/kotlin/com/opencray/app/agent/AgentPathResolverTest.kt`
- `app/src/test/kotlin/com/opencray/app/agent/AgentIdFactoryTest.kt`

Done when:

- the registry can create, list, archive, and select agents
- `activeAgentId` lives only in the registry
- `agentId` and `sessionId` are globally unique by contract, not by convention

### Slice 2: Add a real agent bootstrap service using the existing soul initializer

Primary goal:

- make "Create agent" write a real isolated agent root without switching the whole app yet

Recommended files to add:

- `app/src/main/kotlin/com/opencray/app/agent/AgentBootstrapService.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentCreateRequest.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentDraftSoulMapper.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentConfigStore.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentSoulProfileStore.kt`

Current implementation note:

- `AgentConfigStore` still writes `agents/<agentId>/private/agent-config.json`, but save/clear now use shared durable text storage and the locked update primitive so foreground bootstrap and service/runtime agent-scope readers do not race through direct truncate writes.

Recommended refactors:

- refactor `WorkspaceSoulProfileStore` into a path-based implementation that can back both:
  - private `agents/<agentId>/private/SOUL.md`
  - any temporary compatibility wrapper that still reads workspace-root `SOUL.md`

Bootstrap behavior:

- create `agents/<agentId>/private/`
- create `agents/<agentId>/workspace/`
- write private `SOUL.md`
- write private `agent-config.json`
- create agent-local chat root
- create agent-local memory root
- create the first globally unique `sessionId`
- store that `activeSessionId` on the descriptor

Recommended tests:

- `app/src/test/kotlin/com/opencray/app/agent/AgentBootstrapServiceTest.kt`

Done when:

- creating two agents yields two complete isolated directory trees
- no public workspace `SOUL.md` is created by bootstrap
- the created agent is fully reconstructable from registry + private files

### Slice 3: Add minimal bridge contracts so the existing Flutter UI can stop using prototype data

Primary goal:

- replace `_buildPrototypeAgents()` and local draft-only save flow with host-backed list/create/select

Recommended files to add or change:

- `flutter_app/lib/core/bridge/opencray_host_bridge.dart`
- `app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`
- `flutter_app/lib/core/models/opencray_agent_snapshot.dart`
- `flutter_app/lib/features/settings/bridge_settings_facade.dart`
- `flutter_app/lib/features/settings/settings_facade.dart`
- `flutter_app/lib/features/settings/agent_settings_pages.dart`

Recommended host methods:

- `listAgents`
- `loadActiveAgent`
- `createAgent`
- `selectAgent`

Recommended tests:

- `flutter_app/test/settings_feature_test.dart`
- Android bridge tests for method mapping if present in this module

Done when:

- the Agents page renders real registry data
- tapping "Create agent" persists a real agent
- selecting an agent updates the host registry even if runtime switching is not wired yet

### Slice 4: Make all file-backed stores agent-aware before routing live runtime traffic

Primary goal:

- move all storage constructors from global app roots to agent-resolved roots

Recommended refactors:

- `app/src/main/kotlin/com/opencray/app/AppAgentWorkspace.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AgentQueueSnapshotStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionTranscriptStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionSupplementStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionCompactionStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentProcessRegistryFactory.kt`

Implementation rule:

- add agent-root or path-resolver-based constructors first
- keep old `fromContext(...)` wrappers only as temporary shims during the cutover

Recommended tests:

- two agents with the same logical session title still write to different roots
- the same `sessionId` string cannot collide across two different agent stores if the path resolver is used correctly
- queue/run/transcript/supplement/process artifacts stay under the owning agent root

### Slice 5: Introduce `AgentRuntimeContainer` and route host work through the active container

Primary goal:

- replace the one global host runtime assembly with one per-agent dependency group

Recommended files to add:

- `app/src/main/kotlin/com/opencray/app/agent/AgentRuntimeContainer.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentRuntimeContainerRegistry.kt`
- `app/src/main/kotlin/com/opencray/app/agent/AgentHostRuntimeFactory.kt`

Primary files to refactor:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

Per-container ownership should include:

- `ChatSessionLocalStore`
- `PersonalizationLocalStore`
- `AgentSoulProfileStore`
- `workspaceRootProvider`
- `ChatMemoryIngestionCoordinator`
- transcript / supplement / compaction / queue / run-record factories
- `DefaultAgentSessionRuntimeManager`

Recommended tests:

- switching the active agent swaps the visible chat state
- switching the active agent swaps the memory store used by ingestion
- switching the active agent swaps the base soul source
- background work for agent A does not rewrite agent B state

### Slice 6: Cut soul loading over from workspace-root `SOUL.md` to private-root `SOUL.md`

Primary goal:

- remove the last public-workspace base-soul assumption

Primary files to refactor:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/facade/personalization/PersonalizationFacade.kt`
- `app/src/main/kotlin/com/opencray/app/facade/settings/SettingsFacade.kt`
- any bootstrap discovery path that still treats public `SOUL.md` as source-of-truth

Done when:

- the effective base soul is resolved only from `agents/<agentId>/private/SOUL.md`
- public workspace tools do not see or manage the base soul file
- memory-backed overlays still apply on top of the private base soul

### Slice 7: Activate per-agent runtime config after isolation is stable

Primary goal:

- make the already-designed Flutter editor fields materially useful without destabilizing the isolation work

Primary areas:

- per-agent LLM route override
- per-agent live-context mode
- per-agent model / reasoning effort
- per-agent avatar/media metadata for the Settings UI

Implementation rule:

- keep global safety settings global unless there is an explicit product decision to isolate them too
- do not overload `SOUL.md` with transport settings like API base URLs or provider secrets

Recommended tests:

- agent A and agent B can point at different model routes without sharing saved config
- `no_soul` / `no_memory_or_soul` stay agent-scoped once per-agent config is active

### Slice 8: Add destructive lifecycle operations only after the isolated core is verified

Primary goal:

- finish the operational surface after the storage and runtime boundaries are proven

Recommended operations:

- rename agent
- edit existing agent
- archive agent
- delete agent and all owned roots
- clone agent from preset or from another descriptor without copying memory by default

Recommended tests:

- deleting one agent does not affect another agent
- archive hides the agent from normal selection without deleting data
- editing one agent does not rewrite another agent's soul or config

## Recommended First Landing Slice

The safest immediate sequence, given the current repo and concurrent work, is:

1. Slice 1: registry + path resolver + ID factory
2. Slice 2: bootstrap service + private soul/config write
3. Slice 3: bridge contracts + Flutter Agents page backed by real host data

Why this first:

- it avoids the hottest runtime chain during the first change set
- it gives the existing Flutter UI real persistence quickly
- it makes later runtime container work additive instead of speculative
- it forces the storage contract to settle before chat/runtime switching starts

What should explicitly wait until after that:

- `OpenCrayHostRuntime` container switching
- private-soul runtime cutover in the live prompt path
- per-agent runtime model/mode overrides

## Verification Requirements

At minimum, implementation should ship with tests for:

- creating two agents yields completely separate workspace/chat/memory roots
- switching agents does not leak sessions across agents
- switching agents does not leak memory or relationship state
- switching agents does not leak soul or preset/plasticity state
- `sessionId` generation is globally unique across agents
- runtime file tools cannot access private `SOUL.md`
- deleting one agent does not affect another agent's stores

## Final Recommendation

Do not implement multi-agent support as one global host runtime plus lightly tagged records.

Implement it as:

- one registry of agents
- one path resolver per `agentId`
- one fully isolated runtime container per agent

That approach matches the current soul and memory architecture best, keeps future behavior predictable, and avoids the most likely cross-agent contamination bugs.
