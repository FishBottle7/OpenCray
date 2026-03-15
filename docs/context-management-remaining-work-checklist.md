# Context Management Remaining Work Checklist

Last updated: 2026-03-15

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
- `runtime/memory` now contains typed policy, deterministic candidate extraction, and structured record writing primitives
- `runtime/memory` now also contains bounded recall, ranking, and prompt-layer rendering primitives, and `PromptAssembler` can inject a dedicated `Retrieved Memory` layer with report counts when callers provide recalled records
- the live app path now reads persisted memory records through `AppAgentSessionTaskRuntimeFactory` before prompt assembly, and completed turns now flow through host-side deterministic memory ingestion
- chat-derived identity and style preferences now stay in memory as structured overrides and are resolved into an effective runtime soul profile instead of directly overwriting persisted soul state
- workspace identity is now derived from the app workspace root set and used consistently for workspace-scoped memory recall and writes
- completed turns now also maintain session-scoped `task_commitment` memory deterministically, resolving completed commitments and expiring stale ones before new writes
- host runtime activity now exposes `memory_write` events with written, resolved, and expired memory ids so memory maintenance stays debuggable from the live session surface
- memory recall trace now projects through host run snapshots and the local runtime server, so existing debug surfaces can inspect it without adding a dedicated memory UI first
- the next safe rollout step is wiring this soul foundation into the existing prompt path without reopening P0 ownership changes

## Remaining work after P0

### Phase 1: context sources become first-class runtime inputs

1. Structured soul profile
   - Replace the current flat soul overlay with typed runtime fields.
   - Introduce resolver and renderer stages so tone, verbosity, escalation style, and tool-use bias can be tested independently.
   - Current status: chat-derived naming and style preferences can now override runtime soul through structured memory records, but explicit promotion into persisted soul still needs a separate confirmation path.

2. Deterministic memory write pipeline
   - Add a memory candidate extractor after completed turns.
   - Gate writes through explicit policy instead of model-authored free-form dumps.
   - Start with `user_preference`, `project_fact`, `durable_instruction`, and `task_commitment`.
   - Current status: post-turn writes, `task_commitment` resolve/expire maintenance, and host-visible `memory_write` summaries are now implemented.
   - Remaining gaps: completion heuristics are still phrase-based and there is no operator-facing maintenance surface yet.

3. Memory recall layer
   - Retrieve bounded memory relevant to the current session/task before the LLM call.
   - Keep recall budgeted and traceable in the context report.
   - Current status: recall is budgeted, workspace-aware, prompt-visible, and live app runs now refresh memory through post-turn deterministic writes.
   - Current status: runtime context reports now include bounded memory recall trace data for query terms, selected records, budget-omitted records, and filtered counts.
   - Remaining gaps: expose this trace more consistently across operator/debug entry points and decide whether a dedicated debug screen is still needed after the existing snapshot surfaces prove sufficient.

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
   - Current status: prompt-local pruning now rewrites oversized tool payloads, collapses attachment-like blobs, and drops consecutive duplicate background noise before transcript windowing, with summary/report counters carried through prompt assembly and runtime metadata.
   - Remaining gaps: semantic dedupe across non-consecutive failed search loops and richer structured-artifact summarization are still pending.

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
    - Memory write activity is now visible at the host runtime layer; deeper cross-layer trace capture is still pending.

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
