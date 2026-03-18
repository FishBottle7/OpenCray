# Context Management Remaining Work Checklist

Last updated: 2026-03-17

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
- host runtime activity now exposes `memory_write` events with written, resolved, reaffirmed, and expired memory ids so memory maintenance stays debuggable from the live session surface
- memory recall trace now projects through host run snapshots and the local runtime server, so existing debug surfaces can inspect it without adding a dedicated memory UI first
- user-input durable memory extraction now prefers a constrained semantic interpreter for `user_preference`, `durable_instruction`, `project_fact`, and structured soul preference intents, with old keyword heuristics retained only as an explicit fallback when no interpreter is available
- prompt-local pruning now runs before transcript windowing, and prompt-time compaction emits an explicit omitted-history summary instead of silently dropping older replay
- projected memory corpus tools now expose `memory_search` and `memory_get`, and explicit memory retrieval emits `memory_retrieval` runtime events that project through host/local run snapshots
- managed skills roots now resolve into a bounded `Skill Inventory` prompt layer, and run metadata/snapshots now preserve which skills were visible versus injected for later debugging
- explicit `skill_read` can now promote one active skill into a dedicated `Active Skill` capsule layer for later turns, with host-visible trace and allowlist-style tool narrowing kept outside `ContextManager`
- Settings debug tooling now exposes snapshot-backed `Memory Inspector` and `Soul Inspector` state views so persisted memory records, stored/base/effective soul, overlays, field sources, and deterministic linked activity can be inspected without reconstructing them from live events
- chat fullscreen run trace and Settings `Context & Memory Trace` now render structured run-level context traces for bootstrap, recalled memory, memory flush, durable compaction, skill inventory, and active skill directly from host snapshots
- the next safe rollout step is finishing operator drill-down/curation surfaces plus child-context policy work, now that Level 2 skill activation, pre-compaction memory flush, durable compaction, and bootstrap trace are in place without moving source-selection logic into `ContextManager`

## Remaining work after P0

### Phase 1: context sources become first-class runtime inputs

1. Structured soul profile
   - Replace the current flat soul overlay with typed runtime fields.
   - Introduce resolver and renderer stages so tone, verbosity, escalation style, and tool-use bias can be tested independently.
   - Current status: typed soul fields, preset resolution, prompt rendering, and memory-backed runtime overlay are implemented.
   - Current status: chat-derived naming and style preferences can now override runtime soul through structured memory records.
   - Current status: direct-chat soul writes are now partially hardened. `agent_display_name` may remain durable, but chat-derived style/verbosity requests are clamped to session scope, protected soul fields such as risk tolerance and tool-use bias are filtered out of direct-chat memory paths, and runtime soul overlay ignores legacy direct-chat records that try to durably rewrite those protected fields.
   - Current status: the first relationship/persona-evolution slice is now live. Durable long-term style requests are still captured as `relationship_style_profile` compatibility records instead of direct soul rewrites, presets carry a typed `plasticity` axis into runtime soul extensions, and those legacy records are now expected to be phased forward into persisted soul-state objects rather than consumed directly by live soul resolution.
   - Current status: the next pure-runtime scaffold is now in place under `runtime/soul/*`. Serializable `InteractionPreferenceState`, `RelationshipState`, and `RelationshipEvent` models exist, along with deterministic JVM-tested updaters for plasticity-aware preference drift, address-style/naming preference accumulation, event-driven relationship-state deltas, and trust/safety/intimacy gating with negative-event inertia.
   - Current status: live runtime soul overlay no longer consumes raw durable `relationship_style_profile` records directly. That old warm/serious compatibility signal is only meaningful once it has been projected into persisted `InteractionPreferenceState`, which removes the last direct runtime fallback from the legacy relationship-style path.
   - Current status: a persistence bridge now exists for the new soul state objects. `InteractionPreferenceState`, `RelationshipState`, and `RelationshipEvent` can be serialized into `MemoryRecord.extensions` and round-tripped back through tolerant parsers, so later extraction/writer work has a stable durable payload contract instead of inventing ad-hoc JSON blobs in multiple places.
   - Current status: the first real relationship-event write path is now live in the app ingestion layer. `ChatMemoryIngestionCoordinator` can ask a semantic `RelationshipEventInterpreter` for structured relationship events, plan durable event/state writes, and persist both `RELATIONSHIP_EVENT` plus updated `RELATIONSHIP_STATE` records through the normal memory writer. A LiteLLM-backed interpreter is wired into the default app runtime so this path no longer stops at `NoOp`.
   - Current status: `InteractionPreferenceState` now also has a first real durable write/read path. Post-turn memory ingestion can derive an internal interaction-preference snapshot from newly written durable `relationship_style_profile`, `user_preferred_name`, and `user_address_style` records, persist it as an `INTERACTION_PREFERENCE_STATE` soul object, and live runtime soul overlay now reads that persisted state as the sole preference-evolution input instead of falling back to raw legacy relationship-style records.
   - Current status: typed soul prompt composition now carries user-addressing preferences end-to-end. Persisted or session-scoped interaction preference state can surface `preferred_naming` plus `preferred_address_style` into the effective runtime soul, so cross-session naming and addressing preferences no longer depend on raw compatibility records alone.
   - Current status: relationship provenance is now exposed on a strict debug-only path. `loadSoulDebugSnapshot` projects persisted `InteractionPreferenceState` plus `RelationshipState` into structured debug payloads, Flutter `Soul Inspector` shows the underlying preference state, relationship scores, gate checks, recent-negative guard, and derived address/high-intimacy/playful-affection decisions, and soul `fieldSources` now distinguish stored soul, direct memory overlay, projected interaction-preference state, and projected relationship-state layers instead of collapsing everything into a generic memory overlay label. None of those “why” details are surfaced in the normal chat UI or run trace surface.
   - Current status: the live app base-soul path is now tightened around workspace `SOUL.md`. Personalization save/reset, settings summaries, runtime base soul loading, soul debug snapshots, and plasticity reads now all use a shared `WorkspaceSoulProfileStore`, so the live app no longer treats `PersonalizationLocalStore`'s soul record as a second durable base-persona authority.
   - Remaining gaps: typed soul still needs to stay disciplined as a runtime-normalized representation rather than drift into a second editing surface, `relationship_style_profile` is still retained as a compatibility input on the write side while legacy extraction contracts are phased down, adaptive axes still need to expand beyond the initial warmth/formality/initiative/address-style skeleton, deeper behavior-level gates above the new allow/deny bits are still pending, linked activity for internal soul snapshots/events is still thinner than for classic user-preference records, and any future true soul editing goes through a separate creator/admin flow. The manager/allocator boundary should also stay strict so soul selection logic does not drift upward into `ContextManager`.

2. Deterministic memory write pipeline
   - Add a memory candidate extractor after completed turns.
   - Gate writes through explicit policy instead of model-authored free-form dumps.
   - Start with `user_preference`, `project_fact`, `durable_instruction`, and `task_commitment`.
  - Current status: post-turn writes, `task_commitment` resolve/expire/reaffirm maintenance, host-visible `memory_write` summaries, constrained semantic extraction for user-authored durable memories, constrained semantic interpretation for `task_commitment` completion/renewal, and existing Flutter/runtime trace surfaces that retain written/resolved/reaffirmed/expired memory ids are now implemented.
  - Current status: operator-facing read-only inspection now exists through snapshot-backed `Memory Inspector` and `Soul Inspector` debug surfaces, including deterministic linked activity for source, recall, explicit retrieval, and maintenance relationships.
  - Remaining gaps: there is still no operator editing/curation flow for memory state, and the current `task_commitment` semantic pass is intentionally narrow to resolve/reaffirm decisions rather than broader free-form memory edits.

3. Memory recall layer
   - Retrieve bounded memory relevant to the current session/task before the LLM call.
   - Keep recall budgeted and traceable in the context report.
   - Current status: recall is budgeted, workspace-aware, prompt-visible, and live app runs now refresh memory through post-turn deterministic writes.
   - Current status: runtime context reports now include bounded memory recall trace data for query terms, selected records, budget-omitted records, and filtered counts.
   - Remaining gaps: retrieval trace is now correlated into inspector-linked activity, but there is still no richer trace workflow for filtering, replay, or per-run drill-down from the debug surface itself.
   - Boundary note: ranking, filtering, and recall policy stay in `MemoryRetriever`; `ContextManager` should only enforce final prompt allocation pressure on the already-ranked result.

4. On-demand memory tools
   - Add explicit `memory_search` and `memory_get` runtime tools similar to OpenClaw.
   - Search should run against a projected memory corpus, not raw `memory.json`.
   - Automatic bounded recall and explicit memory tools should coexist rather than replace one another.
   - Current status: implemented with projected-corpus search/get tooling and `memory_retrieval` runtime events visible through existing host/local snapshot surfaces.
   - Remaining gaps: explicit memory tools are implemented and their deterministic record links now surface in the inspectors, but there is still no operator action surface for replaying/searching them directly from debug UI; current debug pages remain read-only inspection surfaces.

5. Runtime-visible skill inventory
   - Assemble an explicit inventory layer from managed skills roots.
   - Snapshot which skills were visible for a run so behavior can be debugged later.
   - Current status: implemented as a bounded prompt-visible `Skill Inventory` layer with run metadata and host/local snapshot projection for visible, injected, omitted, implicit, and invalid skill counts.
   - Remaining gaps: inventory visibility is traceable and now surfaces in chat/debug UI, but there is still no richer operator drill-down or replay surface beyond read-only run trace and inspector views.

6. Active skill capsule injection
   - When a skill is selected, inject a dedicated skill capsule instead of relying on raw `skill_read` only.
   - Narrow tool policy where required.
   - Current status: implemented as a run-local progressive disclosure path: successful `skill_read` activates a dedicated `Active Skill` context layer for later turns, trace projects through runtime metadata plus host/local run snapshots, and simple allowlist-style tool narrowing is enforced outside `ContextManager`.
   - Remaining gaps: there is still no automatic implicit skill selection, no executable `skill_execute` runtime, and no fork/subagent execution path for `context: fork` skills.

### Phase 2: budget pressure, bootstrap context, and child runtimes

7. Pre-compaction memory flush
   - Add an OpenClaw-style pre-compaction memory flush stage.
   - Trigger it only under transcript/context pressure.
   - Preserve durable notes before durable compaction rewrites older history.
   - Keep flush append-only and traceable, and prevent repeated flushes in the same compaction cycle.
   - Current status: implemented as a pre-run memory flush path before session-context assembly. The flush only triggers when prompt-local pruning plus transcript windowing would omit enough older history, writes durable candidates through the existing memory extractor/writer path, reloads fresh memory records so the same run can immediately recall them, projects structured `memoryFlush` trace through runtime metadata plus host/local run snapshots, and dedupes repeated flushes with omitted-window signatures plus stable candidate ids so simple window growth does not keep rewriting the same memory.
   - Remaining gaps: there is still no separate durable compaction worker or dedicated flush task. Flush currently stays as a pre-run preservation stage wired through session preparation.

8. Context pruner
   - Add prompt-local pruning rules for large tool outputs, repeated observations, and bulky attachments.
   - Keep pruning separate from durable compaction.
   - Current status: prompt-local pruning now rewrites oversized tool payloads, collapses attachment-like blobs, and drops consecutive duplicate background noise before transcript windowing, with summary/report counters carried through prompt assembly and runtime metadata.
   - Remaining gaps: semantic dedupe across non-consecutive failed search loops and richer structured-artifact summarization are still pending.

9. Durable compaction summaries
   - Introduce session-level summaries for older turns.
   - Preserve decision history while shrinking the replay window.
   - Current status: implemented as a pre-run durable compaction stage before prompt assembly. Prompt tasks now compact older omitted transcript slices into a separate per-session durable summary store, physically trim the runtime transcript tail through `SessionTranscriptStore.replace(...)`, inject the rendered durable summaries as a dedicated `Durable Compaction` prompt layer, and project structured durable-compaction trace through runtime metadata plus host/local run snapshots.
   - Remaining gaps: compaction still runs inline during session preparation rather than as a background worker, and the current trace exposes counts/timestamps rather than a richer per-entry audit surface.

10. Bootstrap context files
   - Resolve `AGENTS.md`, `SOUL.md`, `TOOLS.md`, and `PROJECT.md` as bounded context sources.
   - Support `full`, `lightweight`, and `none` bootstrap modes.
   - Extend the live mode plan with two additional context-suppression profiles:
     - `no_soul`: keep normal bootstrap and memory behavior, but suppress `SOUL.md` injection plus runtime soul overlay for the run.
     - `no_memory_or_soul`: suppress `SOUL.md` injection, runtime soul overlay, and automatic memory recall/injection for the run.
   - Boundary note: these two extra profiles are broader live-context modes, not just bootstrap-file filters. Explicit memory tools may remain a separate policy surface unless they are disabled independently.
   - Current status: implemented as a bounded runtime-side bootstrap layer. `BootstrapContext` flows through `ContextManager`, `PromptAssembler` injects bootstrap files into prompt assembly, runtime metadata preserves structured bootstrap-file trace, and host/local run snapshots plus Flutter chat/debug surfaces now render bootstrap visibility/injection state.
   - Remaining gaps: `lightweight` mode exists in the resolver/tests, but the live app path still selects `full` for prompt turns and `none` otherwise. The broader live-mode set still needs wiring so operators can choose `no_soul` and `no_memory_or_soul` explicitly, and there is not yet a broader operator surface for deeper bootstrap-file drill-down beyond existing trace views.

11. Subagent context modes
   - Add `minimal`, `delegated`, and `mirrored` child-context policies.
   - Keep inherited context explicit and budgeted.
   - Current status: not implemented yet.

12. Full context trace
   - Emit run-level trace data for layer composition, retrieved memories, skill capsules, pruning, and compaction.
   - Make postmortem inspection possible without re-running the session.
    - Current status: memory write activity, bounded memory recall trace, explicit memory-tool retrieval trace, deterministic memory/soul linked activity, bootstrap-file trace, skill visibility trace, active skill capsule trace, pre-compaction memory-flush trace, and durable-compaction trace now project through runtime metadata plus host/local snapshot surfaces. Chat run-trace UI and Settings `Context & Memory Trace` consume these run-level traces without a separate debug protocol.
   - Remaining gaps: deeper cross-layer trace capture is still pending, especially per-layer budgeting/provenance summaries and richer compaction/bootstrap replay or drill-down workflows beyond the current read-only trace surfaces.

## Recommended execution order

1. Preserve the `ContextManager` boundary as allocator/budget owner only
2. Finish structured soul promotion/confirmation work
3. Finish the remaining memory debug/operator surfaces, especially editing/curation flows
4. Wire live bootstrap mode selection beyond the current `full`/`none` app path
   - Include the planned `no_soul` and `no_memory_or_soul` live profiles when this selection surface is implemented.
5. Subagent context modes
6. Broader full-context trace and debug drill-down

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
