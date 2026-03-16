# Context Management Remaining Work Checklist

Last updated: 2026-03-16

## Current checkpoint

P0 repair is implemented locally and covered by targeted compile and unit-test verification.

Boundary clarification for the current runtime shape:

- `ContextManager` is now the prompt-budget and window-allocation layer.
- It owns prompt-local pruning, transcript windowing, prompt-time compaction summaries, and final bounded allocation of already-ranked recalled memory into the prompt.
- It should not become the semantic selector for memory or soul content.
- Memory selection belongs in `runtime/memory`, especially `MemoryRetriever`.
- Soul resolution belongs in `runtime/soul`, especially `MemoryBackedSoulProfileResolver`, `SoulProfileResolver`, and `RuntimeSoulPromptComposer`.
- Current implementation note: `ContextManager` still applies a final `maxInjectedMemoryRecords` cap after memory recall. This is treated as allocation pressure, not source-level memory retrieval policy.

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
- user-input durable memory extraction now prefers a constrained semantic interpreter for `user_preference`, `durable_instruction`, `project_fact`, and structured soul preference intents, with old keyword heuristics retained only as an explicit fallback when no interpreter is available
- prompt-local pruning now runs before transcript windowing, and prompt-time compaction emits an explicit omitted-history summary instead of silently dropping older replay
- the next safe rollout step is finishing the remaining OpenClaw-aligned memory paths without letting `ContextManager` absorb source-selection logic that belongs to memory or soul modules

## Remaining work after P0

### Phase 1: context sources become first-class runtime inputs

1. Structured soul profile
   - Replace the current flat soul overlay with typed runtime fields.
   - Introduce resolver and renderer stages so tone, verbosity, escalation style, and tool-use bias can be tested independently.
   - Current status: typed soul fields, preset resolution, prompt rendering, and memory-backed runtime overlay are implemented.
   - Current status: chat-derived naming and style preferences can now override runtime soul through structured memory records.
   - Remaining gaps: explicit promotion into persisted soul still needs a separate confirmation path, and the manager/allocator boundary should stay strict so soul selection logic does not drift upward into `ContextManager`.

2. Deterministic memory write pipeline
   - Add a memory candidate extractor after completed turns.
   - Gate writes through explicit policy instead of model-authored free-form dumps.
   - Start with `user_preference`, `project_fact`, `durable_instruction`, and `task_commitment`.
   - Current status: post-turn writes, `task_commitment` resolve/expire maintenance, host-visible `memory_write` summaries, and constrained semantic extraction for user-authored durable memories are now implemented.
   - Remaining gaps: assistant-side completion heuristics are still phrase-based, and there is no operator-facing maintenance surface yet.

3. Memory recall layer
   - Retrieve bounded memory relevant to the current session/task before the LLM call.
   - Keep recall budgeted and traceable in the context report.
   - Current status: recall is budgeted, workspace-aware, prompt-visible, and live app runs now refresh memory through post-turn deterministic writes.
   - Current status: runtime context reports now include bounded memory recall trace data for query terms, selected records, budget-omitted records, and filtered counts.
   - Remaining gaps: expose this trace more consistently across operator/debug entry points and decide whether a dedicated debug screen is still needed after the existing snapshot surfaces prove sufficient.
   - Boundary note: ranking, filtering, and recall policy stay in `MemoryRetriever`; `ContextManager` should only enforce final prompt allocation pressure on the already-ranked result.

4. On-demand memory tools
   - Add explicit `memory_search` and `memory_get` runtime tools similar to OpenClaw.
   - Search should run against a projected memory corpus, not raw `memory.json`.
   - Automatic bounded recall and explicit memory tools should coexist rather than replace one another.
   - Current status: not implemented yet.

5. Runtime-visible skill inventory
   - Assemble an explicit inventory layer from managed skills roots.
   - Snapshot which skills were visible for a run so behavior can be debugged later.
   - Current status: not implemented as a prompt-visible runtime layer yet.

6. Active skill capsule injection
   - When a skill is selected, inject a dedicated skill capsule instead of relying on raw `skill_read` only.
   - Narrow tool policy where required.
   - Current status: not implemented yet.

### Phase 2: budget pressure, bootstrap context, and child runtimes

7. Pre-compaction memory flush
   - Add an OpenClaw-style pre-compaction memory flush stage.
   - Trigger it only under transcript/context pressure.
   - Preserve durable notes before durable compaction rewrites older history.
   - Keep flush append-only and traceable, and prevent repeated flushes in the same compaction cycle.
   - Current status: not implemented yet.

8. Context pruner
   - Add prompt-local pruning rules for large tool outputs, repeated observations, and bulky attachments.
   - Keep pruning separate from durable compaction.
   - Current status: prompt-local pruning now rewrites oversized tool payloads, collapses attachment-like blobs, and drops consecutive duplicate background noise before transcript windowing, with summary/report counters carried through prompt assembly and runtime metadata.
   - Remaining gaps: semantic dedupe across non-consecutive failed search loops and richer structured-artifact summarization are still pending.

9. Durable compaction summaries
   - Introduce session-level summaries for older turns.
   - Preserve decision history while shrinking the replay window.
   - Current status: only prompt-time omitted-history summaries are implemented. Durable session-level summaries are not implemented yet.

10. Bootstrap context files
   - Resolve `AGENTS.md`, `SOUL.md`, `TOOLS.md`, and `PROJECT.md` as bounded context sources.
   - Support `full`, `lightweight`, and `none` modes.
   - Current status: not implemented yet.

11. Subagent context modes
   - Add `minimal`, `delegated`, and `mirrored` child-context policies.
   - Keep inherited context explicit and budgeted.
   - Current status: not implemented yet.

12. Full context trace
   - Emit run-level trace data for layer composition, retrieved memories, skill capsules, pruning, and compaction.
   - Make postmortem inspection possible without re-running the session.
   - Current status: memory write activity is now visible at the host runtime layer, and memory recall trace is present in runtime reports.
   - Remaining gaps: deeper cross-layer trace capture is still pending, including memory-tool retrieval trace, memory-flush trace, skill visibility/capsule trace, and durable compaction trace.

## Recommended execution order

1. Preserve the `ContextManager` boundary as allocator/budget owner only
2. Finish structured soul promotion/confirmation work
3. Finish the remaining memory debug/operator surfaces
4. Add OpenClaw-style on-demand memory tools
5. Skill inventory layer
6. Active skill capsule
7. Add pre-compaction memory flush
8. Durable compaction summaries
9. Bootstrap files
10. Subagent context modes
11. Full context trace

## Handoff notes for the next worker

- Do not reopen P0 prompt/queue architecture unless the review subagent finds a concrete defect.
- Build new work on top of:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/*`
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- Keep the boundary explicit:
  - `runtime/memory/*` decides which memory records are recalled and written
  - `runtime/memory/*` should also own memory search/get tooling and pre-compaction memory flush policy
  - `runtime/soul/*` decides the effective runtime soul profile and prompt rendering
  - `runtime/context/ContextManager.kt` budgets and arranges prompt space, but should not absorb source-specific selection logic
- Preserve the separation between:
  - stable system layers
  - session directives
  - bounded replayed conversation
  - retrieved external context
- Every new context source should ship with:
  - explicit budget rules
  - unit tests
  - report/trace visibility
