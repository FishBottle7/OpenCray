# Context Management Remaining Work Checklist

Last updated: 2026-03-19

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
- user-input durable memory extraction now runs through constrained semantic interpreters for `user_preference`, `durable_instruction`, `project_fact`, and structured soul preference intents, and the direct-chat path fails closed when user/soul interpreters are unavailable instead of falling back to keyword heuristics
- prompt-local pruning now runs before transcript windowing, and prompt-time compaction emits an explicit omitted-history summary instead of silently dropping older replay
- projected memory corpus tools now expose `memory_search` and `memory_get`, and explicit memory retrieval emits `memory_retrieval` runtime events that project through host/local run snapshots
- managed skills roots now resolve into a bounded `Skill Inventory` prompt layer, and run metadata/snapshots now preserve which skills were visible versus injected for later debugging
- explicit `skill_read` can now promote one active skill into a dedicated `Active Skill` capsule layer for later turns, with host-visible trace and allowlist-style tool narrowing kept outside `ContextManager`
- Settings debug tooling now exposes snapshot-backed `Memory Inspector` and `Soul Inspector` state views so persisted memory records, stored/base/effective soul, overlays, field sources, and deterministic linked activity can be inspected without reconstructing them from live events
- `Memory Inspector` now also supports direct projected-memory `search/get` drill-down, so operators can query the projected corpus and inspect bounded snippets in-place without leaving the debug surface
- chat fullscreen run trace and Settings `Context & Memory Trace` now render structured run-level context traces for bootstrap, recalled memory, memory flush, durable compaction, skill inventory, and active skill directly from host snapshots
- the next safe rollout step is finishing bounded automatic memory stewardship plus child-context policy work, now that Level 2 skill activation, pre-compaction memory flush, durable compaction, and bootstrap trace are in place without moving source-selection logic into `ContextManager`

## Remaining work after P0

### Phase 1: context sources become first-class runtime inputs

1. Structured soul profile
   - Replace the current flat soul overlay with typed runtime fields.
   - Introduce resolver and renderer stages so tone, verbosity, escalation style, and tool-use bias can be tested independently.
   - Current status: typed soul fields, preset resolution, prompt rendering, and memory-backed runtime overlay are implemented.
   - Current status: chat-derived naming and style preferences can now override runtime soul through structured memory records.
   - Current status: direct-chat soul writes are now semantically gated and fail closed. `agent_display_name` may remain durable, chat-derived style/verbosity requests are clamped to session scope, durable adaptive drift must flow through `interaction_preference_signal`, protected soul fields such as risk tolerance and tool-use bias are filtered out of direct-chat memory paths, and runtime soul overlay ignores raw or legacy direct-chat records that try to durably rewrite protected fields.
   - Current status: the first relationship/persona-evolution slice is now live. Durable long-term adaptive style requests now land directly as typed `interaction_preference_signal` records instead of direct soul rewrites, and presets carry a typed `plasticity` axis into runtime soul extensions so later projection stays bounded and testable.
   - Current status: the next pure-runtime scaffold is now in place under `runtime/soul/*`. Serializable `InteractionPreferenceState`, `RelationshipState`, and `RelationshipEvent` models exist, along with deterministic JVM-tested updaters for plasticity-aware preference drift, address-style/naming preference accumulation, event-driven relationship-state deltas, and trust/safety/intimacy gating with negative-event inertia.
   - Current status: live runtime soul overlay no longer consumes raw durable adaptive preference records directly. Raw `interaction_preference_signal` writes only become effective after they are projected into persisted `InteractionPreferenceState`, which keeps runtime behavior anchored to typed state snapshots instead of ad-hoc preference rows.
   - Current status: a persistence bridge now exists for the new soul state objects. `InteractionPreferenceState`, `RelationshipState`, and `RelationshipEvent` can be serialized into `MemoryRecord.extensions` and round-tripped back through tolerant parsers, so later extraction/writer work has a stable durable payload contract instead of inventing ad-hoc JSON blobs in multiple places.
   - Current status: the first real relationship-event write path is now live in the app ingestion layer. `ChatMemoryIngestionCoordinator` can ask a semantic `RelationshipEventInterpreter` for structured relationship events, plan durable event/state writes, and persist both `RELATIONSHIP_EVENT` plus updated `RELATIONSHIP_STATE` records through the normal memory writer. A LiteLLM-backed interpreter is wired into the default app runtime so this path no longer stops at `NoOp`.
   - Current status: `InteractionPreferenceState` now also has a first real durable write/read path. Post-turn memory ingestion can derive an internal interaction-preference snapshot from newly written durable `interaction_preference_signal`, `user_preferred_name`, and `user_address_style` records, persist it as an `INTERACTION_PREFERENCE_STATE` soul object, and live runtime soul overlay now reads that persisted state as the sole preference-evolution input.
   - Current status: typed interaction-preference planning is now structurally thinner than before. During post-turn ingestion, `InteractionPreferenceState` snapshots are planned directly from the turn's extracted preference candidates, so typed state generation no longer depends on re-reading freshly written preference rows through an intermediate compatibility layer.
   - Current status: durable adaptive preference writes now carry typed interaction-preference signal extensions directly, and `InteractionPreferenceMemoryWritePlanner` reads only those typed fields when projecting the next snapshot instead of inferring drift from coarse `warm` / `serious` labels.
   - Current status: the LiteLLM user/soul intent interpreters and runtime intent models now also expose a dedicated `preference_extensions` channel for adaptive relationship writes, so upstream extraction can emit typed warmth/formality/initiative hints explicitly instead of relying on coarse style labels.
   - Current status: write-side extraction now normalizes durable adaptive style requests onto the explicit `interaction_preference_signal` preference key. Runtime canonicalizes its durable value from `preference_extensions` and projects it into `InteractionPreferenceState`.
   - Current status: typed soul prompt composition now carries user-addressing preferences end-to-end. Persisted or session-scoped interaction preference state can surface `preferred_naming` plus `preferred_address_style` into the effective runtime soul, so cross-session naming and addressing preferences no longer depend on raw compatibility records alone.
   - Current status: relationship provenance is now exposed on a strict debug-only path. `loadSoulDebugSnapshot` projects persisted `InteractionPreferenceState` plus `RelationshipState` into structured debug payloads, Flutter `Soul Inspector` shows the underlying preference state, relationship scores, gate checks, recent-negative guard, and derived address/high-intimacy/playful-affection decisions, and soul `fieldSources` now distinguish stored soul, direct memory overlay, projected interaction-preference state, and projected relationship-state layers instead of collapsing everything into a generic memory overlay label. None of those “why” details are surfaced in the normal chat UI or run trace surface.
   - Current status: the adaptive interaction-preference skeleton is now broader and runtime-visible. Persisted preference state now carries `warmth`, `formality`, `initiative`, `playfulness`, and `reassurance` offsets, the soul overlay exposes those offsets into typed prompt fields, and direct-chat LLM extraction can emit the richer typed `preference_extensions` set instead of only the original three-axis subset.
   - Current status: deeper behavior gating is now graduated instead of stopping at a couple of coarse allow/deny bits. Relationship resolution now derives prompt-visible permissions for `supportive_reassurance`, `proactive_relational_check_in`, `light_playfulness`, and `playful_teasing`, while still keeping higher-intimacy / playful-affection behavior bounded by relationship state and recent-negative guards. Preference offsets can suppress those behaviors even when relationship state alone would have allowed them. The runtime debug projection also carries those gate results for later inspector plumbing.
   - Current status: runtime soul prompting now consumes the expanded adaptive slice as an explicit behavior contract instead of only raw scalar fields. `SoulPromptRenderer` emits deterministic `behavior_guidance` lines derived from preference offsets, relationship bands, and behavior gates, so downstream model behavior has a concrete prompt-visible instruction layer for warmth/formality/initiative/playfulness/reassurance and the newer reassurance/check-in/teasing boundaries.
   - Current status: the design now explicitly separates turn-time control into semantic interpretation, deterministic response policy, and main-agent generation. A constrained turn-signal interpreter now runs on the live app path, runtime consumes that structured signal through a dedicated turn-response-policy composer, and prompt assembly injects a bounded `Turn Response Policy` system layer so live runs can decide task-first vs support-first shape, clarification budget, reassurance mode, relational check-in mode, and playfulness mode without falling back to keyword heuristics.
   - Current status: soul inspector attribution now also covers the expanded adaptive slice end to end. Host/runtime `fieldSources` include the five preference offsets plus the newer reassurance/playfulness/check-in gate fields, and the Flutter inspector has a structured fallback that still attributes those fields to projected interaction-preference or relationship-state layers when older snapshots omit explicit `fieldSources`.
   - Current status: upstream semantic extraction coverage is now broader for the newer adaptive and relationship-sensitive axes. The live prompts explicitly document directness-vs-reassurance boundaries (`不用安慰我，直接说`), light-but-not-flirty playfulness (`轻松点但别油`), task initiative vs relational check-ins (`主动提醒截止时间` / `别没事问候`), live support-seeking vs warm appreciation, mixed task-plus-affect turns (`我有点慌，但先告诉我怎么回滚`), mixed scope splitting inside one message (`这次直接一点，以后还是温柔一点`), durable-vs-one-turn separation (`平时不用哄我，但今天先陪我一下`), indirect boundary wording (`你不用照顾我情绪，抓重点就行`), short follow-ups resolved from prior context (`那就这么做` / `就按你说的来`), and English equivalents such as `keep it light, not cheesy` / `don't comfort me, just tell me what's wrong`, while still separating warmth requests from actual reciprocal relationship growth. The adaptive preference guidance for user-memory and soul-memory interpreters is now also shared from one prompt helper so the two extraction paths do not silently drift apart.
   - Current status: semantic coverage has now been extended one step further for paragraph-style mixed intent. Shared adaptive-preference prompts now document three-plus-scope messages that mix durable naming, workspace-scoped directness, durable initiative boundaries, and one-turn emotional support asks in the same paragraph; the turn-signal prompt now also documents antecedent resolution for follow-ups like `那第二个吧` / `go with option two`, longer task-plus-emotion turns, and support-first-vs-task-first mixed clauses; and the relationship-event prompt now carries stronger negative examples for gratitude-plus-execution, warmth-request-plus-approval, and boundary-reset turns so those paths stay out of fake relationship growth.
   - Current status: the live app base-soul path is now tightened around workspace `SOUL.md`. Personalization save/reset, settings summaries, runtime base soul loading, soul debug snapshots, and plasticity reads now all use a shared `WorkspaceSoulProfileStore`, so the live app no longer treats `PersonalizationLocalStore`'s soul record as a second durable base-persona authority.
   - Current status: the old app-local soul persistence shim has now been retired from `PersonalizationLocalStore`; that store is back to memory/history responsibility only, while base-soul persistence stays in workspace `SOUL.md`.
   - Current status: the app-side durable soul DTO is now also decoupled from `PersonalizationLocalStore`. Workspace soul reads/writes and base-runtime adapters flow through a standalone `WorkspaceSoulProfile`, so the memory/history store no longer owns the type shape for persisted base soul either.
   - Remaining gaps: typed soul still needs to stay disciplined as a runtime-normalized representation rather than drift into a second editing surface, semantic extraction still needs more depth for broader non-Chinese/English phrasing and cases where interpretation depends on richer prior-turn context than the current bounded short-window prompt provides, linked activity for internal soul snapshots/events is still thinner than for classic user-preference records, and any future true soul editing goes through a separate creator/admin flow. The manager/allocator boundary should also stay strict so soul selection logic does not drift upward into `ContextManager`.

2. Deterministic memory write pipeline
  - Add a memory candidate extractor after completed turns.
  - Gate writes through explicit policy instead of model-authored free-form dumps.
  - Start with `user_preference`, `project_fact`, `durable_instruction`, and `task_commitment`.
  - Current status: post-turn writes, `task_commitment` resolve/expire/reaffirm maintenance, host-visible `memory_write` summaries, constrained semantic extraction for user-authored durable memories, constrained semantic interpretation for `task_commitment` completion/renewal, and existing Flutter/runtime trace surfaces that retain written/resolved/reaffirmed/expired memory ids are now implemented.
  - Current status: bounded `task_commitment` maintenance is now broader than simple completion/renewal. The runtime can accept constrained LLM decisions to abandon an obsolete commitment, supersede an older open commitment with one newly proposed commitment from the same turn, or drop a redundant proposed commitment before it is written, while still failing closed when the interpreter is unavailable.
  - Current status: operator-facing read-only inspection now exists through snapshot-backed `Memory Inspector` and `Soul Inspector` debug surfaces, including deterministic linked activity for source, recall, explicit retrieval, and maintenance relationships.
  - Current status: the first bounded automatic stewardship slice is now wired into post-turn ingestion. A dedicated memory-stewardship interpreter can review proposed `user_preference`, `durable_instruction`, and `project_fact` writes against related active records and emit only constrained actions such as `refresh_record_with_candidate`, `drop_candidate`, `reaffirm_record`, `resolve_record`, and `supersede_record_with_candidate`; runtime then enforces per-turn caps and scope/preference-key compatibility before writing anything.
  - Current status: non-task stewardship is no longer limited to “existing record versus new candidate”. When one turn proposes multiple stewardable candidates, the review layer can now prune duplicate/conflicting candidates even if there is no prior active record yet, which closes an important mixed-intent / mixed-scope extraction gap.
  - Current status: the live app path now configures non-task stewardship to fail closed when a stewardship review is required but the interpreter is unavailable, so related `user_preference`, `durable_instruction`, and `project_fact` candidates are withheld rather than silently written through an unavailable manager.
  - Current status: live app stewardship now also routes single `user_preference`, `project_fact`, and `durable_instruction` candidates through bounded semantic review instead of only reviewing them when an old record or a same-turn conflict already exists. This gives the manager a chance to reject speculative facts, one-turn formatting/tone asks, and task-local instructions before they become durable memory.
  - Current status: stewardship review inputs are now slightly more structured than plain content strings. Active records and proposed candidates carry source metadata into the constrained interpreter prompt, so the LLM can distinguish user-authored durable requests from tool-observed facts or other evidence while still returning only bounded maintenance actions.
  - Current status: stewardship review inputs now also carry bounded recency metadata for active records plus source-task metadata for candidates, so the constrained interpreter can weigh “fresh explicit correction” against “older stored belief” without needing to infer chronology only from prose.
  - Current status: non-task stewardship now also supports a bounded `refresh_record_with_candidate` action. This lets the constrained interpreter consume a paraphrased or newly re-confirmed candidate as fresh evidence for an existing record, bump the stored record’s confirmation time, and drop the redundant candidate instead of forcing every refresh to become either a duplicate write or a full supersession.
  - Current status: runtime-side guardrails for `refresh_record_with_candidate` and `supersede_record_with_candidate` are now stricter than kind/scope matching alone. For `project_fact` and `durable_instruction`, runtime also requires deterministic same-topic compatibility, so a model cannot refresh or supersede an unrelated memory row just because it was shortlisted in the same review set.
  - Current status: `refresh_record_with_candidate` for `project_fact` / `durable_instruction` is now runtime-bounded to pure reconfirmation only. If the proposed candidate introduces new durable detail or changes a key value on the same topic, runtime rejects refresh and leaves the candidate in place for normal write/supersede handling instead of silently mutating the old row.
  - Current status: non-task stewardship is no longer strictly candidate-driven. When a turn has no stewardable replacement candidate but does explicitly refer to a small set of already-stored `user_preference`, `project_fact`, or `durable_instruction` rows, runtime can now shortlist those scope-compatible active records for bounded record-only review, allowing the constrained interpreter to resolve or reaffirm them without opening arbitrary corpus-wide maintenance.
  - Current status: candidate-driven and record-only stewardship can now coexist in one turn. A turn may accept a new durable candidate while also resolving or reaffirming a different explicitly mentioned old memory, as long as both stayed inside the bounded shortlist and action set.
  - Remaining gaps: `task_commitment` now covers resolve/reaffirm/abandon/supersede/drop-proposed, and non-task stewardship now covers related-record review, same-turn candidate pruning, and bounded refresh, but richer bounded operations such as semantic merge or more nuanced invalidation across non-commitment records are still pending. Commitment maintenance also does not yet model richer multi-step plan graphs or reopen flows.
  - Target direction: let a constrained LLM stewardship layer interpret whether memory should be refreshed, superseded, invalidated, merged, resolved, reopened, or otherwise maintained, while runtime executes only a bounded operation set instead of arbitrary free-form edits.
  - Guardrails: stewardship should fail closed when the interpreter is unavailable, must not directly rewrite internal soul objects or `SOUL.md`, should prefer append/supersede/resolve style transitions over raw record mutation, and must avoid large destructive memory rewrites from one ambiguous turn.

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
   - Current status: projected memory corpus now renders human-readable adaptive summaries for typed `interaction_preference_signal` records (and legacy compatibility records that carry the same typed extensions), so memory search/get can expose `warmth higher`, `initiative lower`, and similar drift signals without forcing operators to decode canonical storage tokens manually.
   - Current status: `Memory Inspector` now exposes safe read-side projected-memory `search/get` drill-down backed by the same host/runtime snapshot path, so operators can issue direct projected-corpus queries and inspect narrow snippets from the debug UI.
   - Remaining gaps: explicit memory tools remain intentionally read-only for the current product shape; richer replay/filter workflows beyond the current safe read-side drill-down are still pending, and any write-side correction should come from bounded automatic stewardship rather than a user-facing memory editor.

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
   - Current status: the broader live-context mode set is now also wired through the live app settings/runtime path. Operators can choose `full`, `lightweight`, `none`, `no_soul`, and `no_memory_or_soul`, and run metadata/debug surfaces now preserve the effective live-context mode plus whether soul and automatic memory recall were enabled for that run.
   - Current status: runtime context injection now has an explicit fail-closed guard in addition to the app-side live-mode mapping. `ContextInjectionPolicy` can suppress soul contract layers and automatic memory prompt layers even if an upstream caller accidentally populated those fields, which keeps `no_soul` / `no_memory_or_soul` semantics source-based rather than relying only on null-or-empty data by convention.
   - Current status: explicit memory tools are now separated from live-context mode semantics. Automatic memory injection still follows `no_memory_or_soul`, while `memory_search` / `memory_get` are governed by a distinct operator setting so teams can choose between "no automatic memory" and "no memory access at all".
   - Remaining gaps: deeper bootstrap-file drill-down remains limited to the current read-only trace views, and child/subagent runs still do not inherit or override these live-context policies through an explicit child-context model yet.

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
3. Finish bounded automatic memory stewardship, starting with allowed maintenance operations and then expanding `user_preference`, `task_commitment`, `project_fact`, and `durable_instruction`
4. Wire live bootstrap mode selection beyond the current `full`/`none` app path
   - Include the planned `no_soul` and `no_memory_or_soul` live profiles when this selection surface is implemented.
5. Subagent context modes
6. Broader full-context trace and debug drill-down

## Explicitly deferred for the current product shape

These items are intentionally not part of the current rollout, even though they may be reasonable in a more human-relationship-oriented system.

- Do not add passive relationship decay driven only by elapsed time, long gaps between sessions, or simple absence.
- Do not add automatic relationship downgrade rules for "only shows up when needing help", "long-term one-sided asking", or similar slow-burn social interpretations unless they are backed by a later product decision and a narrowly testable evidence model.
- Do not make cross-session warmth, trust, or intimacy feel fragile enough that a work-oriented agent quickly turns cold again just because the user was away for a while.
- Do not productize a user-facing or operator-facing manual memory editing surface for normal use. Keep memory stewardship primarily automatic; retain existing debug-only maintenance hooks only as development/diagnostic escape hatches.

Rationale for the defer:

- The current product target is still a work-capable agent with some cultivation potential, not a fully human-like relationship simulator.
- For this product shape, continuity and earned warmth matter more than realistic social decay.
- Time-only or absence-only decay would create avoidable surprise and punish normal work usage patterns.
- If this area is revisited later, it should be framed as an explicit relationship-simulation mode with separate policy, evidence requirements, and UX expectations rather than silently added to the default memory/soul path.

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
