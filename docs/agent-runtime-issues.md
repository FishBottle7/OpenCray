# Agent Runtime Issue Backlog

Last updated: 2026-03-16

## Purpose

This document translates `docs/agent-runtime-task-list.md` into issue-ready backlog items.

Each issue is written in a format that can be copied into GitHub Issues, Linear, Jira, or another tracker with minimal editing.

For implementation references and external projects worth studying before solving these issues, see `docs/agent-runtime-reference-guide.md`.

## Suggested Labels

Use these labels consistently where they fit:

- `agent-runtime`
- `android-shell`
- `runtime`
- `persistence`
- `prompting`
- `memory`
- `soul`
- `skills`
- `safety`
- `p0`
- `p1`
- `p2`

## P0 Issues

### Issue P0-1: Persist live agent queue state for chat-backed runs

#### Priority

P0

#### Suggested labels

- `agent-runtime`
- `android-shell`
- `persistence`
- `p0`

#### Problem

The Android app currently creates a fresh `AgentLoop` for each prompt and uses `InMemorySessionQueueSnapshotStore` in the live path. This prevents interrupted runs from being restored after app restart or process death.

#### Why this matters

Without durable queue state, OpenCray behaves like a one-shot request runner instead of a persistent local agent session.

#### Scope

- Replace the live queue store in the app from `InMemorySessionQueueSnapshotStore` to `SessionStoreQueueSnapshotStore`.
- Define how queue snapshots map to chat session ids.
- Ensure persisted queue records are restored when reopening a session.
- Keep queue restart behavior deterministic.

#### Out of scope

- Memory retrieval
- Skill execution
- Prompt architecture redesign

#### Suggested implementation areas

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/store/SessionStoreQueueSnapshotStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`

#### Subtasks

- Audit how chat session ids are created and persisted.
- Define whether queue state should live alongside chat session persistence or in a separate runtime store.
- Replace in-memory snapshot store usage in the live app path.
- Add restore logic when a chat session is reopened.
- Add or update integration tests for restart recovery in the app path.

#### Acceptance criteria

- Queue state survives app restart for a chat-backed run.
- Reopening the same chat session restores the queue snapshot.
- Existing queue restart recovery tests still pass.
- A new Android integration test proves the app no longer uses in-memory-only queue state for live runs.

#### Dependencies

None

### Issue P0-2: Introduce a session runtime manager for long-lived chat sessions

#### Priority

P0

#### Suggested labels

- `agent-runtime`
- `android-shell`
- `runtime`
- `p0`

#### Problem

Runtime ownership is embedded directly inside `AppShellActivity`, and each prompt creates a new loop. There is no session-scoped runtime manager that owns submit, cancel, retry, resume, and event subscription.

#### Why this matters

Without a session runtime manager, OpenCray cannot behave like a durable agent session. It also makes cancellation, retry, restoration, and future multi-step flows harder to implement.

#### Scope

- Introduce a dedicated runtime manager keyed by chat session id.
- Move runtime creation and queue lookup out of UI-heavy code.
- Provide a host-facing API for:
  - submit
  - cancel
  - retry
  - resume
  - observe runtime events

#### Out of scope

- Memory
- Skills execution
- Hooks

#### Suggested implementation areas

- new: `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/AgentLoop.kt`

#### Subtasks

- Define a runtime manager interface and lifecycle.
- Decide how runtime instances are cached and released.
- Move runtime construction out of `AppShellActivity`.
- Add host methods for cancel and retry.
- Wire event sink subscription through the manager.

#### Acceptance criteria

- A single chat session resolves to one runtime owner instead of one runtime per prompt.
- Host code can cancel and retry without rebuilding the runtime manually.
- Runtime lifecycle is unit-tested.

#### Dependencies

- P0-1

### Issue P0-3: Inject persisted chat history into runtime prompt context

#### Priority

P0

#### Suggested labels

- `agent-runtime`
- `prompting`
- `android-shell`
- `p0`

#### Problem

The runtime currently begins mostly from the latest user input plus prompt scaffolding. Persisted session history is not reconstructed into bounded runtime context.

#### Why this matters

Without conversation context, OpenCray has poor continuity and behaves like stateless request execution.

#### Scope

- Define a transcript-to-runtime-context policy.
- Inject recent session history into runtime requests.
- Include relevant system template material.
- Apply strict size limits or truncation rules.

#### Out of scope

- Long-term memory retrieval
- Summarization of old history beyond a minimal bounded policy

#### Suggested implementation areas

- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

#### Subtasks

- Define which message roles are eligible for replay.
- Decide whether assistant placeholders should be included.
- Add bounded transcript assembly before runtime execution.
- Add tests for context inclusion and truncation.

#### Acceptance criteria

- Runtime requests contain session-derived context beyond the current user turn.
- Tests prove earlier messages influence prompt assembly.
- The implementation has a clear budget or truncation policy.

#### Dependencies

- P0-2

### Issue P0-4: Split prompt assembly into explicit named layers

#### Priority

P0

#### Suggested labels

- `agent-runtime`
- `runtime`
- `prompting`
- `p0`

#### Problem

Prompt construction is currently built from a small number of concatenated strings. This makes behavior hard to inspect, evolve, and test.

#### Why this matters

Prompt logic is runtime logic. If it remains implicit string concatenation, later work on memory, soul, skills, and bootstrap files will become brittle.

#### Scope

- Introduce a prompt assembly model with named layers.
- Split out:
  - identity
  - operating rules
  - session policy
  - personalization
  - tool protocol
- Make prompt assembly testable without Android UI involvement.

#### Out of scope

- File-based bootstrap files
- Hook system

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptAssembler.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptLayers.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Subtasks

- Define prompt layer model.
- Move base prompt construction out of `AppShellActivity`.
- Move runtime action scaffold into explicit prompt protocol layer.
- Add tests per layer and for final assembly.

#### Acceptance criteria

- The runtime prompt can be decomposed into named layers.
- Each layer is individually testable.
- App code no longer performs ad hoc concatenation for core runtime instructions.

#### Dependencies

- P0-3

### Issue P0-5: Unify approval gating across all mutating runtime tools

#### Priority

P0

#### Suggested labels

- `agent-runtime`
- `runtime`
- `safety`
- `p0`

#### Problem

Only `command_exec` consistently flows through `ModeGate`. File mutation and Python execution do not yet share one policy and approval model.

#### Why this matters

The product claims mode-based safety behavior, but the runtime does not enforce that model consistently across all side-effecting tools.

#### Scope

- Define a common approval gate for all mutating tools.
- Route file writes, moves, deletes, and Python execution through it.
- Make gate results visible in runtime outputs and app trace.

#### Out of scope

- Read-only tools
- Memory recall

#### Suggested implementation areas

- `runtime/src/main/kotlin/com/opencray/runtime/ModeGate.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/ToolPolicyGate.kt`

#### Subtasks

- Define which tools count as mutating.
- Add shared pre-execution gate API.
- Apply policy handling to all mutating tools.
- Add tests for Safe, Auto, and Developer mode behavior.

#### Acceptance criteria

- Safe mode blocks all mutating tools unless approved.
- Auto and Developer modes match documented behavior.
- Tests cover file and Python tool approval behavior, not only commands.

#### Dependencies

- P0-2

## P1 Issues

### Issue P1-1: Define memory taxonomy and production storage policy

#### Priority

P1

#### Suggested labels

- `agent-runtime`
- `memory`
- `runtime`
- `p1`

#### Problem

Memory storage exists, but there is no runtime meaning attached to memory records.

#### Why this matters

Without a memory taxonomy, later write and retrieval code will become inconsistent and difficult to govern.

#### Scope

- Define memory categories.
- Define intended producers and consumers for each category.
- Define retention and deletion policy.
- Document the design.

#### Out of scope

- Actual memory write path
- Actual memory retrieval path

#### Suggested implementation areas

- new: `docs/memory-design.md`
- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPolicy.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/MemoryRecord.kt`

#### Subtasks

- Decide category model.
- Decide whether tags remain freeform or become structured.
- Add schema support for typed memory domains if needed.
- Write design document.

#### Acceptance criteria

- Memory categories are documented.
- Each category has a clear producer and consumer.
- The storage schema can represent the taxonomy.

#### Dependencies

- P0-4

### Issue P1-2: Write durable memory from production agent flows

#### Priority

P1

#### Suggested labels

- `agent-runtime`
- `memory`
- `runtime`
- `p1`

#### Problem

Production code does not currently create memory records from agent behavior.

#### Why this matters

OpenCray cannot improve continuity without writing durable memory.

#### Scope

- Add memory write hooks or post-run handlers for selected deterministic events.
- Start with low-risk memory types:
  - user preferences explicitly stated by the user
  - stable workspace facts confirmed by tools
  - end-of-run session summaries

#### Out of scope

- Arbitrary model-authored freeform memory

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryWriter.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/store/file/JsonFileStores.kt`

#### Subtasks

- Add post-run memory write stage.
- Define filtering policy for which facts are promotable to memory.
- Add trace visibility for memory writes.
- Add tests for approved and rejected writes.

#### Acceptance criteria

- Production runs create memory records in controlled cases.
- Memory writes are bounded and testable.
- Tests prove only approved categories are written.

#### Dependencies

- P1-1

### Issue P1-3: Recall relevant memory into runtime context

#### Priority

P1

#### Suggested labels

- `agent-runtime`
- `memory`
- `prompting`
- `p1`

#### Problem

Stored memory is not recalled into runtime execution.

#### Why this matters

If memory cannot be recalled, persistence adds storage but not intelligence.

#### Scope

- Add retrieval rules for relevant memory.
- Split runtime memory recall into two complementary paths:
  - bounded automatic recall for default continuity
  - explicit runtime memory tools for on-demand retrieval during the run
- Inject recalled memory into prompt layers without blindly injecting the full durable store.
- Enforce both automatic-recall and tool-snippet budgets.

#### Out of scope

- Rich plugin-backed memory backends beyond the first projected memory corpus
- Pre-compaction memory flush lifecycle

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryRetriever.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPromptLayer.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryCorpusProjector.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

#### Subtasks

- Define retrieval heuristics.
- Keep automatic prompt injection and runtime memory tools as separate, complementary paths.
- Add `memory_search` and `memory_get` or equivalent OpenClaw-style memory tools.
- Define the projected memory corpus those tools will search/read.
- Add ranking and truncation.
- Add tests for recency, category-based recall, and tool-visible memory projection.

#### Acceptance criteria

- Relevant memory is visible to the runtime in production execution through bounded automatic recall.
- The runtime has an explicit on-demand memory retrieval tool path for prior-work questions.
- Retrieval is deterministic enough to test.
- Prompt assembly reflects memory only within a bounded budget.

#### Dependencies

- P1-1
- P1-2

### Issue P1-4: Turn soul into a structured runtime profile

#### Priority

P1

#### Suggested labels

- `agent-runtime`
- `soul`
- `prompting`
- `p1`

#### Problem

Current soul integration is mostly a flattened personalization summary.

#### Why this matters

Soul should influence runtime behavior as structured context, not just as a single descriptive paragraph.

#### Scope

- Define structured soul fields.
- Map persisted personalization data into a runtime soul profile.
- Render soul as a prompt layer rather than only as a summary string.

#### Out of scope

- Automatic self-modifying soul updates by the agent

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulProfile.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulPromptLayer.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/SoulRecord.kt`

#### Subtasks

- Define soul profile structure.
- Map preset and custom guidance into structured fields.
- Replace direct summary injection with soul prompt rendering.
- Add tests for soul-to-prompt mapping.

#### Acceptance criteria

- Soul is assembled into runtime as structured data.
- Prompt rendering uses structured soul fields.
- Tests cover mapping and rendering.

#### Dependencies

- P0-4

### Issue P1-5: Expose app-managed skills to the runtime

#### Priority

P1

#### Suggested labels

- `agent-runtime`
- `skills`
- `android-shell`
- `p1`

#### Problem

The app has real managed skill directories, but runtime does not receive them through `skillsRoots`.

#### Why this matters

Without runtime visibility, the Android skills UI and the runtime exist as parallel systems instead of one integrated system.

#### Scope

- Pass `filesDir/skills` into runtime.
- Decide whether catalog skills should stay UI-only or be runtime-visible.
- Filter disabled skills from runtime exposure.

#### Out of scope

- Skill execution

#### Suggested implementation areas

- `app/src/main/kotlin/com/opencray/app/facade/skills/SkillsFacade.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`

#### Subtasks

- Add runtime root resolution for installed skills.
- Add enabled-skill filtering.
- Update runtime config construction in the app.
- Add integration tests for `skills_list`.

#### Acceptance criteria

- `skills_list` returns installed runtime-visible skills in the app.
- Disabled skills are not exposed as active runtime candidates.
- Android integration test proves end-to-end visibility.

#### Dependencies

- P0-2

### Issue P1-6: Implement executable skill runtime behavior

#### Priority

P1

#### Suggested labels

- `agent-runtime`
- `skills`
- `runtime`
- `p1`

#### Problem

`SKILL_CALL` currently degrades to `skill_read`, which means skills are not executable runtime units.

#### Why this matters

Without execution semantics, skills remain metadata packages and cannot shape agent behavior in practice.

#### Scope

- Introduce `skill_execute`.
- Define how a skill modifies runtime execution.
- Enforce allowed tools and related permissions.
- Honor invocation control and execution context.

#### Out of scope

- Full sub-agent support if it requires new runtime architecture beyond inline skill execution

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/SkillExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillValidator.kt`

#### Subtasks

- Define skill execution contract.
- Implement runtime path for `skill_execute`.
- Filter available tools per skill policy.
- Add tests for allowed and denied tool paths.

#### Acceptance criteria

- A runtime-visible skill can be invoked and alter execution behavior.
- Skill-specific tool restrictions are enforced.
- Tests cover explicit invocation and blocked unauthorized actions.

#### Dependencies

- P1-5

## P2 Issues

### Issue P2-1: Introduce lifecycle hooks for runtime extension points

#### Priority

P2

#### Suggested labels

- `agent-runtime`
- `runtime`
- `p2`

#### Problem

There is no structured extension surface around runtime lifecycle events.

#### Why this matters

Hooks allow future memory flushing, telemetry, audit, bootstrap augmentation, and policy extensions without embedding all behavior in core runtime classes.

#### Scope

- Define internal runtime hook points.
- Add deterministic hook ordering.
- Add one or more production uses through hooks.

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/hooks/RuntimeHooks.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/hooks/HookContext.kt`

#### Subtasks

- Define hook lifecycle.
- Decide failure behavior.
- Implement first internal hook use case.
- Add tests for ordering and fault isolation.

#### Acceptance criteria

- Hooks run in deterministic order.
- Hook failures are contained and visible.
- At least one production feature uses hooks.

#### Dependencies

- P0-4
- P1-2

### Issue P2-2: Add file-based workspace bootstrap context

#### Priority

P2

#### Suggested labels

- `agent-runtime`
- `prompting`
- `runtime`
- `p2`

#### Problem

OpenCray still relies on string-built runtime prompts instead of a file-backed bootstrap model.

#### Why this matters

File-based bootstrap context is one of the key structural differences between OpenCray and systems like OpenClaw.

#### Scope

- Define supported bootstrap files.
- Define precedence and load order.
- Integrate them into prompt assembly.

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/bootstrap/BootstrapFileLoader.kt`
- new: `docs/agent-bootstrap-spec.md`
- `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptAssembler.kt`

#### Subtasks

- Define bootstrap file set.
- Define fallback behavior.
- Add workspace bootstrap loader.
- Add prompt layer integration.

#### Acceptance criteria

- Runtime can load supported bootstrap files from workspace roots.
- Prompt assembly includes bootstrap content through named layers.
- Missing files degrade gracefully.

#### Dependencies

- P0-4

### Issue P2-3: Add bounded sub-agent support

#### Priority

P2

#### Suggested labels

- `agent-runtime`
- `runtime`
- `skills`
- `p2`

#### Problem

There is no sub-agent capability for decomposition of larger tasks.

#### Why this matters

Sub-agents are the next major step after single-loop tool usage for handling wider or parallelizable tasks.

#### Scope

- Define sub-agent task contract.
- Support parent-to-child bounded context.
- Support structured child result return.
- Enforce recursion and depth limits.

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentRuntime.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentTask.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillValidator.kt`

#### Subtasks

- Define child runtime prompt contract.
- Define execution and cancellation behavior.
- Add simple initial use case.
- Add depth-limit enforcement.

#### Acceptance criteria

- Parent runtime can create a child runtime with reduced context.
- Child result returns as structured observation.
- Limits prevent unbounded recursion.

#### Dependencies

- P1-6

### Issue P2-4: Standardize runtime observability and trace model

#### Priority

P2

#### Suggested labels

- `agent-runtime`
- `runtime`
- `android-shell`
- `p2`

#### Problem

Trace output exists, but not as one consistent runtime observability model.

#### Why this matters

As memory, skills, approvals, and sub-agents are added, debugging will become difficult without one trace model.

#### Scope

- Define trace structures for:
  - prompt assembly
  - tool usage
  - approval outcomes
  - memory recall and write
  - skill execution
  - sub-agent calls
- Surface trace information in app debug views where appropriate.

#### Suggested implementation areas

- new: `runtime/src/main/kotlin/com/opencray/runtime/trace/RuntimeTrace.kt`
- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`

#### Subtasks

- Define normalized trace schema.
- Add emitters from runtime layers.
- Add basic app-side rendering for debug use.
- Add tests for trace payload completeness.

#### Acceptance criteria

- One run can be inspected from prompt input to final result.
- Memory, skills, and approval events appear in one coherent trace stream.

#### Dependencies

- P0-4
- P1-2
- P1-6

## Suggested Epic Structure

If these issues are grouped into epics, this structure should work:

- Epic A: Stateful Runtime
  - P0-1
  - P0-2
  - P0-3
- Epic B: Prompt and Safety Foundations
  - P0-4
  - P0-5
- Epic C: Memory and Soul
  - P1-1
  - P1-2
  - P1-3
  - P1-4
- Epic D: Skills Runtime
  - P1-5
  - P1-6
- Epic E: Advanced Runtime Capabilities
  - P2-1
  - P2-2
  - P2-3
  - P2-4

## Suggested First Sprint

If only one sprint can be funded immediately, start with:

1. P0-1 Persist live queue state
2. P0-2 Session runtime manager
3. P0-3 Inject chat history
4. P0-4 Split prompt layers
5. P0-5 Unify approval handling

This sprint does not deliver a full agent, but it does create the host architecture required for every later phase.
