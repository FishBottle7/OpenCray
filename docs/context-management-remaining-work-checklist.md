# Context Management Remaining Work Checklist

Last updated: 2026-03-13

## Current checkpoint

P0 repair is implemented locally and covered by targeted compile and unit-test verification.

What is done in P0:

- session-owned runtime/queue ownership is wired through `AgentSessionRuntimeManager`
- chat submit/regenerate now enqueue real tasks instead of using a per-submit loop
- runtime transcript reconstruction is separated from session policy injection
- queue snapshot storage uses collision-safe session directory encoding
- prompt assembly reports now distinguish source transcript size from bounded window size
- transcript windowing now prefers recent human turns over tool noise

Phase 1 foundation now in progress:

- `runtime/soul` contains a typed `SoulProfile` model instead of relying only on a flat summary string
- preset resolution, prompt rendering, and compatibility seed mapping from `RuntimeSoulProfile` are covered by focused unit tests
- the soul scaffold now includes a runtime-side prompt composer plus normalized extension handling so future host adapters can feed typed fields without depending on fragile string keys
- app-side personalization storage now generates core typed soul extensions from preset selection and forwards them into `RuntimeSoulProfile.extensions`
- `runtime/memory` now contains typed policy, deterministic candidate extraction, and structured record writing primitives, but they are not yet wired into post-turn ingestion or recall
- `runtime/memory` now also contains bounded recall, ranking, and prompt-layer rendering primitives, and `PromptAssembler` can inject a dedicated `Retrieved Memory` layer with report counts when callers provide recalled records
- the live app path now reads persisted memory records through `AppAgentSessionTaskRuntimeFactory` before prompt assembly, but post-turn deterministic writes are still not wired
- the next safe rollout step is wiring this soul foundation into the existing prompt path without reopening P0 ownership changes

## Remaining work after P0

### Phase 1: context sources become first-class runtime inputs

1. Structured soul profile
   - Replace the current flat soul overlay with typed runtime fields.
   - Introduce resolver and renderer stages so tone, verbosity, escalation style, and tool-use bias can be tested independently.

2. Deterministic memory write pipeline
   - Add a memory candidate extractor after completed turns.
   - Gate writes through explicit policy instead of model-authored free-form dumps.
   - Start with `user_preference`, `project_fact`, `durable_instruction`, and `task_commitment`.

3. Memory recall layer
   - Retrieve bounded memory relevant to the current session/task before the LLM call.
   - Keep recall budgeted and traceable in the context report.
   - Remaining gaps: add workspace identity to the recall request and wire post-turn writes so recalled records stay fresh.

4. Runtime-visible skill inventory
   - Assemble an explicit inventory layer from managed skills roots.
   - Snapshot which skills were visible for a run so behavior can be debugged later.

5. Active skill capsule injection
   - When a skill is selected, inject a dedicated skill capsule instead of relying on raw `skill_read` only.
   - Narrow tool policy where required.

### Phase 2: budget pressure, bootstrap context, and child runtimes

6. Context pruner
   - Add prompt-local pruning rules for large tool outputs, repeated observations, and bulky attachments.
   - Keep pruning separate from durable compaction.

7. Durable compaction summaries
   - Introduce session-level summaries for older turns.
   - Preserve decision history while shrinking the replay window.

8. Bootstrap context files
   - Resolve `AGENTS.md`, `SOUL.md`, `TOOLS.md`, and `PROJECT.md` as bounded context sources.
   - Support `full`, `lightweight`, and `none` modes.

9. Subagent context modes
   - Add `minimal`, `delegated`, and `mirrored` child-context policies.
   - Keep inherited context explicit and budgeted.

10. Full context trace
   - Emit run-level trace data for layer composition, retrieved memories, skill capsules, pruning, and compaction.
   - Make postmortem inspection possible without re-running the session.

## Recommended execution order

1. Structured soul profile
2. Deterministic memory writes
3. Memory recall layer
4. Skill inventory layer
5. Active skill capsule
6. Context pruner
7. Compaction summaries
8. Bootstrap files
9. Subagent context modes
10. Full context trace

## Handoff notes for the next worker

- Do not reopen P0 prompt/queue architecture unless the review subagent finds a concrete defect.
- Build new work on top of:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/*`
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- Preserve the separation between:
  - stable system layers
  - session directives
  - bounded replayed conversation
  - retrieved external context
- Every new context source should ship with:
  - explicit budget rules
  - unit tests
  - report/trace visibility
