# Agent Runtime Task List

Last updated: 2026-03-12

## Purpose

This document turns the findings in `docs/agent-runtime-audit.md` into an implementation-oriented task list.

The goal is not to list every possible improvement. The goal is to define the shortest path from:

- "a working minimum agent loop"

to:

- "a complete local agent runtime with durable context, structured prompt layers, executable skills, and a consistent safety model"

This plan assumes the current repository state on 2026-03-12.

## Definition of Done

OpenCray can be considered to have crossed from "agent skeleton" into "complete agent runtime" when all of the following are true:

1. The runtime can resume an in-flight or interrupted session using persisted queue state.
2. Stored conversation history is reconstructed into runtime context in a deliberate, bounded way.
3. Memory is both written by production flows and recalled by production flows.
4. Soul is a structured runtime input, not only a UI personalization string.
5. Skills can be discovered, selected, and executed as runtime units.
6. All mutating tools follow one approval and policy model.
7. Prompt architecture is layered and inspectable.
8. The Android shell exposes the real runtime state instead of only one-shot request-response behavior.

## Guiding Principles

- Prefer closing loops over adding new isolated modules.
- Prefer explicit runtime state over implicit UI state.
- Prefer file-backed or structured prompt layers over growing one large string prompt.
- Prefer one consistent safety model for all side-effecting actions.
- Prefer acceptance criteria that can be tested with JVM tests first, then Android integration tests.

## Phase Overview

| Phase | Goal | Result |
| --- | --- | --- |
| P0 | Turn the current one-shot runtime into a stateful agent session | Real session context, persistence, and prompt layering foundation |
| P1 | Add durable intelligence layers | Memory, soul, and executable skills become real runtime systems |
| P2 | Add maturity features | Hooks, sub-agents, richer governance, and observability |

## P0: Stateful Runtime Foundation

P0 is the most important phase. Without P0, later memory and skills work will sit on unstable host wiring.

### Task P0-1: Persist the live agent queue in the Android app

#### Problem

The app currently creates a fresh `AgentLoop` per user prompt and uses `InMemorySessionQueueSnapshotStore`, which means queue state does not survive process death or app restart.

#### Required work

- Replace the app's live path from `InMemorySessionQueueSnapshotStore` to `SessionStoreQueueSnapshotStore`.
- Decide where the session record should live for chat-backed runs.
- Ensure the queue store keying strategy matches the chat session id.
- Rehydrate the queue on app restore when a session is reopened.

#### Suggested files

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/store/SessionStoreQueueSnapshotStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`

#### Acceptance criteria

- If the app is killed during a long-running run, the queue snapshot persists.
- Reopening the same session restores queue state instead of starting from a blank loop.
- Existing queue restart recovery tests still pass.
- A new Android integration test proves that a chat-backed runtime uses the persistent snapshot store.

### Task P0-2: Build a real session runtime manager

#### Problem

The current app path treats each prompt as an isolated one-shot run. There is no host object that owns agent session lifecycle.

#### Required work

- Introduce a session runtime manager that owns:
  - runtime construction
  - queue lookup by session id
  - submit, cancel, retry, resume actions
  - event subscription
- Make the app reuse the same session runtime abstraction for repeated messages in the same chat session.
- Move runtime orchestration out of the UI-heavy path in `AppShellActivity`.

#### Suggested files

- new: `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/AgentLoop.kt`

#### Acceptance criteria

- A chat session id resolves to one runtime owner instead of one ephemeral loop per prompt.
- Cancel and retry actions are available from host code without rebuilding the runtime.
- Unit tests cover session lookup, restoration, and lifecycle transitions.

### Task P0-3: Inject stored chat history into runtime context

#### Problem

The runtime currently begins from the current user input plus some prompt strings. Stored message history is not reconstructed as runtime context.

#### Required work

- Define a transcript-to-runtime-context policy.
- Decide which message roles and system templates are included.
- Add bounded context assembly:
  - last N messages
  - optional summary block
  - system template material
- Avoid blindly replaying the full chat history when it grows too large.

#### Suggested files

- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

#### Acceptance criteria

- Runtime requests include session-derived context beyond the current user turn.
- A test proves that earlier session messages influence the prompt assembly.
- There is a clear size budget or truncation policy.

### Task P0-4: Split prompt assembly into explicit layers

#### Problem

Prompt logic is currently spread across a small number of strings and ad hoc concatenation.

#### Required work

- Introduce explicit prompt layer objects or builders for:
  - identity
  - operating rules
  - session policy
  - personalization
  - tool protocol
- Make prompt assembly inspectable and testable.
- Stop hiding critical agent behavior inside a single concatenated string.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptAssembler.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptLayers.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Acceptance criteria

- The assembled system prompt can be decomposed into named layers.
- Each layer has its own test coverage.
- The runtime no longer depends on ad hoc string concatenation in the app shell.

### Task P0-5: Unify approval handling across all mutating tools

#### Problem

Only `command_exec` consistently goes through `ModeGate`. File mutations and Python execution are not covered by the same approval contract.

#### Required work

- Define a common side-effect gate interface for:
  - command execution
  - file writes
  - file moves
  - file deletes
  - Python execution
- Route all mutating tools through it.
- Make the resulting decision visible in runtime output and UI trace.

#### Suggested files

- `runtime/src/main/kotlin/com/opencray/runtime/ModeGate.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/ToolPolicyGate.kt`

#### Acceptance criteria

- Safe mode blocks all mutating actions unless approved.
- Auto and Developer modes follow the documented policy model consistently.
- Tests cover all mutating tools, not only commands.

## P1: Durable Intelligence Layers

P1 turns OpenCray from a reactive tool-user into a system with continuity.

### Task P1-1: Define memory domains and storage policy

#### Problem

Memory exists as a file format only. There is no runtime meaning attached to it.

#### Required work

- Define memory categories, for example:
  - user preference memory
  - workspace knowledge memory
  - task-progress memory
  - durable identity-adjacent memory
- Define when each category is created or updated.
- Define retention and deletion policy.

#### Suggested files

- new: `docs/memory-design.md`
- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPolicy.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/MemoryRecord.kt`

#### Acceptance criteria

- There is a documented memory taxonomy.
- Each stored memory record has an intended producer and consumer.
- Memory categories are encoded structurally, not only via freeform tags.

### Task P1-2: Write memory from production agent flows

#### Problem

No production path writes memory after a run.

#### Required work

- Add a memory writer stage after selected successful runs.
- Start with simple deterministic cases, for example:
  - explicit user preference statements
  - explicit workspace facts confirmed by tools
  - durable session summaries when a run finishes
- Avoid allowing the model to write arbitrary raw memory without host filtering.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryWriter.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`

#### Acceptance criteria

- Running the app in normal usage creates memory records through production code.
- Memory writes are traceable and bounded.
- Tests prove that only approved memory categories are written.

### Task P1-3: Recall memory into runtime

#### Problem

Even if memory is written, it is useless unless runtime can retrieve it.

#### Required work

- Define retrieval rules:
  - top-k by recency
  - tag or category match
  - workspace or session affinity
- Add a memory injection layer to prompt assembly or add dedicated memory tools.
- Keep a strict token budget for recalled memory.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryRetriever.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPromptLayer.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

#### Acceptance criteria

- Runtime prompts or tools can surface relevant prior memory.
- Retrieval is deterministic enough to test.
- Tests cover recency and category-based recall.

### Task P1-4: Turn soul into a structured runtime profile

#### Problem

Current soul behavior is a flattened personalization summary.

#### Required work

- Expand soul into structured sections such as:
  - display identity
  - voice and tone
  - escalation style
  - user preference alignment
  - safety posture modifiers, if any
- Keep app personalization UI as one producer of soul data, but not the only representation.
- Decide whether soul is persisted only as JSON or also emitted into a workspace-like file representation.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulProfile.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulPromptLayer.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/SoulRecord.kt`

#### Acceptance criteria

- Soul is assembled as structured runtime data before prompt rendering.
- The prompt layer reflects stable fields, not only a freeform summary string.
- Tests cover mapping from persisted soul data into runtime prompt sections.

### Task P1-5: Pass app-managed skills roots into runtime

#### Problem

The app has real managed and catalog skill directories, but runtime does not receive them.

#### Required work

- Feed `filesDir/skills` into runtime `skillsRoots`.
- Decide whether `filesDir/skills-catalog` should also be visible to runtime or remain UI-only.
- Ensure disabled skills are filtered out before runtime exposure.

#### Suggested files

- `ui/src/main/kotlin/com/opencray/ui/skills/SkillEditorViewModel.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`

#### Acceptance criteria

- `skills_list` returns installed runtime-visible skills in the app.
- Disabled skills do not appear as active runtime candidates.
- Android integration tests prove end-to-end runtime visibility.

### Task P1-6: Implement skill execution

#### Problem

`SKILL_CALL` currently only reads skill content. It does not execute anything.

#### Required work

- Define a `skill_execute` path.
- Decide execution model:
  - inline prompt injection
  - tool-filtered execution context
  - forked sub-agent in later phase
- Consume validated metadata:
  - invocation control
  - allowed tools
  - tool permissions
  - context

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/SkillExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillValidator.kt`

#### Acceptance criteria

- A runtime-visible skill can be invoked and alters execution behavior.
- Allowed tool restrictions are enforced during skill execution.
- Tests cover explicit invocation and blocked unauthorized tool usage.

## P2: Runtime Maturity and Advanced Capabilities

P2 should start only after P0 and most of P1 are stable.

### Task P2-1: Introduce lifecycle hooks

#### Problem

There is no standard extension surface around session lifecycle.

#### Required work

- Define hook points such as:
  - before prompt assembly
  - after tool result
  - before memory flush
  - after session completion
- Start with internal hooks first before exposing them broadly.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/hooks/RuntimeHooks.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/hooks/HookContext.kt`

#### Acceptance criteria

- Hook ordering is deterministic.
- Hook failures are isolated and observable.
- At least one production use case is implemented through hooks.

### Task P2-2: Introduce workspace bootstrap files

#### Problem

OpenCray still lacks a file-driven bootstrap model.

#### Required work

- Define supported bootstrap files, for example:
  - `AGENTS.md`
  - `SOUL.md`
  - `USER.md`
  - `TOOLS.md`
  - optional `MEMORY.md`
- Decide search order and precedence.
- Make prompt assembly aware of these files.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/bootstrap/BootstrapFileLoader.kt`
- new: `docs/agent-bootstrap-spec.md`

#### Acceptance criteria

- Runtime can discover and parse supported bootstrap files from the workspace root.
- Prompt assembly reflects the file content through named layers.
- Missing files degrade gracefully.

### Task P2-3: Add sub-agent support

#### Problem

There is no decomposition path for larger tasks.

#### Required work

- Define sub-agent contract:
  - bounded prompt
  - bounded tools
  - depth limits
  - cancellation propagation
- Start with one simple use case, such as file-audit or code-review delegation.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentRuntime.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentTask.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillValidator.kt`

#### Acceptance criteria

- Parent runtime can create a child runtime with reduced context.
- Child result returns as structured observation.
- Limits prevent uncontrolled recursion.

### Task P2-4: Improve runtime observability

#### Problem

Trace data exists, but not as a fully coherent runtime observability system.

#### Required work

- Standardize runtime trace objects for:
  - prompt assembly layers
  - tool decisions
  - approval decisions
  - skill invocation
  - memory recall and write
- Make these visible in the app debug surfaces where appropriate.

#### Suggested files

- new: `runtime/src/main/kotlin/com/opencray/runtime/trace/RuntimeTrace.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Acceptance criteria

- One run can be inspected from prompt input to final result.
- Skill, memory, and approval events appear in one unified trace model.

## Cross-Cutting Task: Testing Strategy

This work should not be implemented as UI-first behavior. Most of it should be driven by unit and integration coverage.

### Required coverage additions

- JVM tests for prompt assembly layers
- JVM tests for memory retrieval and write policy
- JVM tests for skill execution and permission filtering
- JVM tests for unified tool approval gating
- Android integration tests for persistent session restoration
- Android integration tests for app-managed skills becoming runtime-visible

### Suggested new test files

- `runtime/src/test/kotlin/com/opencray/runtime/PromptAssemblerTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/memory/MemoryRetrieverTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/memory/MemoryWriterTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/SkillExecutorTest.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/ToolPolicyGateTest.kt`
- `app/src/androidTest/kotlin/com/opencray/app/AgentSessionRestoreTest.kt`
- `app/src/androidTest/kotlin/com/opencray/app/RuntimeSkillsIntegrationTest.kt`

## Recommended Delivery Order

Use this order unless product priorities force a narrower slice:

1. P0-1 Persist the live queue in the app.
2. P0-2 Build a session runtime manager.
3. P0-3 Inject stored chat history.
4. P0-4 Split prompt assembly into explicit layers.
5. P0-5 Unify approval handling.
6. P1-1 Define memory domains and policy.
7. P1-2 Write memory from production flows.
8. P1-3 Recall memory into runtime.
9. P1-4 Turn soul into a structured runtime profile.
10. P1-5 Pass app-managed skills roots into runtime.
11. P1-6 Implement skill execution.
12. P2-1 Introduce lifecycle hooks.
13. P2-2 Introduce workspace bootstrap files.
14. P2-3 Add sub-agent support.
15. P2-4 Improve runtime observability.

## Suggested Milestone Names

If these tasks are tracked as milestones or epics, the naming below should be clear enough for product and engineering use.

- Milestone A: Stateful Runtime
- Milestone B: Prompt Architecture
- Milestone C: Memory and Soul
- Milestone D: Executable Skills
- Milestone E: Safety Unification
- Milestone F: Bootstrap, Hooks, and Sub-Agents

## Final Note

The project does not need a larger number of features first. It needs tighter integration between the features that already exist.

The critical shift is:

- from "modules exist"

to:

- "modules participate in one runtime lifecycle"

That is the main objective of this task list.
