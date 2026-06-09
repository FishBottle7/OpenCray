# Codex / Claude Balanced Context Management Plan

Last updated: 2026-06-09

## Status

Scoped implementation complete for the current branch slice.

Model-switch pressure safeguards are explicitly deferred by product decision and are not part of the current completion gate.

This document replaces the active planning role of:

- `docs/done/context-cache-hit-maximization-design.md`
- `docs/done/global-context-budget-coordination-design.md`
- `docs/done/context-management-remaining-work-checklist.md`

Those archived files remain useful as history and detailed rationale, but this is the current execution plan for the next context-management slice.

## Goal

Move OpenCray closer to Codex and Claude Code style context management without flattening OpenCray's memory, soul, skill, and working-state model into one generic summary.

The target shape is:

- Codex-like where provider-native continuation and remote compaction are available.
- Claude-Code-like where stable project/user instructions stay cache-friendly and easy to reason about.
- OpenCray-specific where durable memory, sticky memory, soul, skills, bootstrap, and working state remain typed layers with explicit ownership.

The point is not to copy one product wholesale. The point is to get the same operational properties:

- long tool-heavy tasks keep continuity
- stable prefixes hit prompt caches
- dynamic state does not constantly invalidate provider continuation
- old replay can be compacted without losing user-visible transcript fidelity
- durable memory stays semantically governed instead of becoming a transcript trash can

## External Reference Boundary

Reference links checked on 2026-06-08:

- OpenAI Responses conversation state: https://platform.openai.com/docs/guides/conversation-state?api-mode=responses
- OpenAI Responses API reference: https://platform.openai.com/docs/api-reference/responses
- OpenAI Responses compact API reference: https://platform.openai.com/docs/api-reference/responses/compact?api-mode=responses
- OpenAI prompt caching: https://platform.openai.com/docs/guides/prompt-caching
- Claude Code memory: https://docs.anthropic.com/zh-CN/docs/claude-code/memory
- Anthropic prompt caching: https://docs.claude.com/en/docs/build-with-claude/prompt-caching

OpenAI's current Responses documentation supports three design facts that matter here:

- Responses can preserve conversation state through `previous_response_id` or a Conversation object.
- `previous_response_id` does not carry prior `instructions` forward automatically, so OpenCray must keep sending current top-level instructions intentionally.
- `/v1/responses/compact` is a Responses-only advanced compaction path. It is stateless, takes a full current window, keeps prior user messages verbatim, and replaces prior assistant/tool/encrypted reasoning history with an opaque compaction item.

OpenAI prompt caching also shapes the plan:

- prompt caching is automatic on supported models
- exact prefix matches matter
- static content should appear before dynamic content
- `cached_tokens` should be logged as the real feedback signal

Claude Code's public memory docs shape the other side:

- project and user memory are explicit files such as `CLAUDE.md`
- memory is loaded according to scope and path
- memory content is meant to be concrete project/user guidance, not a generic hidden summary

Anthropic prompt caching reinforces the same stable-prefix rule:

- cache writes and cache hits are explicit cost/performance concepts
- cache lifetime choices such as short-lived and longer-lived cache entries matter
- cache-friendly prompt layout is a protocol concern, not just an implementation detail

## Current OpenCray Baseline

Already implemented:

- model-aware context budget presets and raw override settings
- source-cap coupling for transcript, recall, bootstrap, skills, active skills, and recent observations
- prompt-layer final-state reporting
- provider prompt-cache request metadata and cache-usage telemetry
- non-Responses local continuation with durable versus dynamic front-context hashes
- OpenAI Responses native continuation using `previous_response_id` when lineage and request shape are trusted
- OpenAI Responses native remote compaction through `/v1/responses/compact`
- pre-run and mid-turn memory flush
- pre-run and mid-turn durable compaction
- sticky memory capsule in durable context
- pinned active skill capsule in durable context
- replay-owned handling for ordinary workspace discovery instead of front-loading it into working state
- run snapshot/debug trace projection for budget, memory, skills, bootstrap, flush, compaction, and remote compaction

The main remaining design issue is not "add compaction". It is:

- stop treating high-volatility context as a rewritten front block, especially on Responses-native routes

## Target Context Model

OpenCray should keep five model-visible classes.

### Zone A: Stable Operating Contract

Purpose:

- stable system/developer instruction
- runtime protocol
- safety and tool-use contract

Rules:

- belongs at the cache-critical prefix
- changes should emit explicit cache-break reasons
- must be sent intentionally on Responses calls because prior `instructions` are not carried over by `previous_response_id`

### Zone B: Durable Sticky Context

Purpose:

- durable compaction summaries
- stable bootstrap snippets
- pinned active skills
- sticky memory capsules
- stable soul or policy context that is explicitly safe for repeated injection

Rules:

- may enter cache-sensitive front context
- must be reducible under budget pressure but not silently omitted
- must have provenance and debug trace
- should change much less often than ordinary recall

### Zone C: Dynamic Operational Updates

Purpose:

- current working-state changes
- active-skill activation/switch/clear events
- one-turn live policy changes
- explicit context updates that matter for future turns

Rules:

- should not be a long rewritten front block on Responses-native routes
- should become append-only provider-safe context update items where possible
- should remain separately rendered for non-Responses local continuation and full rebuild fallback
- should be bounded and traceable

### Zone D: Replay And Tool History

Purpose:

- conversation messages
- tool calls and tool results
- workspace discovery
- ordinary inspection results
- recent assistant reasoning visible in history

Rules:

- canonical transcript stays intact
- model-visible replay may use deterministic projections for large tool results
- remote or local compaction acts primarily on this class
- user-visible transcript fidelity must not depend on the compacted representation

### Zone E: Durable Memory Corpus

Purpose:

- long-term user preferences
- project facts
- durable instructions
- task commitments
- relationship and interaction-preference state

Rules:

- not automatically all injected
- source selection stays in `runtime/memory`
- only selected recall enters the current prompt
- opportunistic recall is dynamic by default
- only explicitly promoted sticky memories enter Zone B
- memory search/get remains available for on-demand retrieval

## Protocol Strategy

### OpenAI Responses

Preferred path:

1. keep Zone A and stable Zone B as the provider-native baseline
2. use `previous_response_id` when lineage, tool pool, tool schema, request settings, and stable baseline match
3. append provider-safe Zone C context update items instead of rewriting the dynamic front block
4. keep ordinary tool/history replay as replay or pending delta
5. call `/v1/responses/compact` only under pressure or reset boundaries
6. fall back to full rebuild when lineage or baseline trust fails

Important boundary:

- remote compaction is Responses-native only
- opaque/encrypted compaction output is not a readable local summary
- if no readable summary exists, OpenCray may preserve provider compaction metadata but must use local durable summaries for local prompt-visible continuity

### OpenAI Chat Completions

Preferred path:

1. use stable prefix ordering for prompt cache hits
2. use existing local continuation and cache-shape metadata
3. use deterministic replay projection before semantic compaction
4. use local durable compaction when replay pressure crosses threshold

Important boundary:

- no native `previous_response_id`
- no native `/responses/compact`
- do not advertise provider-native remote compaction

### Anthropic

Preferred path:

1. preserve stable prompt/cache-control regions
2. keep Claude-style memory/instruction surfaces explicit and scoped
3. avoid unnecessary churn in system/tool/document blocks
4. use local durable compaction and deterministic replay projection for replay pressure

Important boundary:

- Anthropic prompt caching is a provider feature, but Responses remote compaction is not
- memory-like guidance should stay explicit and scoped, closer to CLAUDE.md style, not hidden in generic compaction

### On-Device And Custom Routes

Preferred path:

1. rely on local budget coordination
2. rely on deterministic replay projection
3. rely on local durable compaction
4. expose unsupported native features as unavailable metadata, not silent no-ops

## Memory And Soul Balance Rules

OpenCray should not treat memory as just another compressible replay segment.

Memory has three different runtime roles:

1. corpus: durable records outside the prompt
2. recall: bounded dynamic context selected for the current turn
3. capsule: explicitly promoted sticky memory safe for repeated injection

Only role 3 belongs in durable cache-sensitive context by default.

Soul follows a similar split:

1. base soul: stable workspace/profile authority
2. projected interaction state: typed derived state from memory
3. turn policy: current-turn response-shape guidance

Only stable and bounded pieces belong near the prefix. Turn policy stays dynamic and should not poison native continuation unless it is intentionally sent as a provider-safe update.

## Reduction And Pressure Order

Normal turn order:

1. Assemble typed sources by owner: memory, soul, skills, bootstrap, working state, transcript.
2. Apply layer-local reducers.
3. Apply global budget coordination.
4. Apply deterministic replay projection.
5. Choose protocol continuation mode.
6. Run provider call.

Pressure order:

1. reduce optional support context
2. reduce skill inventory and bootstrap detail
3. reduce dynamic recall
4. project or trim large replay/tool outputs
5. compact older replay into durable compaction
6. use Responses remote compaction when available and useful
7. emergency-minimize protected layers only with explicit trace

Things that should not happen first:

- generic-compress soul
- generic-compress sticky memory
- rewrite working state into durable memory
- compact every turn just to keep context tidy

## Implementation Plan

### Phase 1: Active Plan Cleanup And Trace Alignment

Status: done by this doc cleanup.

Tasks:

- archive superseded context plans under `docs/done`
- keep one active successor plan
- keep old files as source history, not active execution targets

### Phase 2: Responses Baseline And Context Updates

Status: done.

Goal:

- stop making Responses-native continuation depend on full Zone C byte equality

Work:

- add `ResponsesContextBaselineSnapshot`
- add `ResponsesContextReferenceState`
- add `ResponsesPendingContextUpdate`
- classify which dynamic changes can become append-only provider-safe update items
- keep opportunistic recall out of the Responses baseline by default
- let active skill activation and working-state changes emit bounded update items
- add reset rules when the update chain gets too long

Verification:

- Responses native continuation survives safe working-state update items
- ordinary memory recall does not break baseline unless promoted sticky
- tool pool/schema changes still force rebuild
- lineage loss still forces rebuild
- run snapshots expose pending Responses context update count and hash

### Phase 3: Deterministic Replay Projection Store

Status: done in prior branch work.

Goal:

- keep canonical transcript untouched while making model-visible replay stable and compact

Work:

- persist replay projection records per session
- freeze projection bytes once chosen for a historical tool result
- version projection policy
- emit projection trace in run snapshots
- make compaction operate after projection has had a chance to reduce pressure

Verification:

- a large historical tool result is projected deterministically on repeated turns
- projection does not change canonical transcript
- cache hashes stay stable across repeated replay assembly
- projection metadata is visible in host and projection-only run snapshots

### Phase 4: Provider-Specific Cache Layout

Status: done for the currently supported provider families.

Goal:

- make cache-sensitive layout explicit across Responses, Chat, Anthropic, and local routes

Work:

- formalize cache-shape metadata for all protocol families
- ensure OpenAI static prefix and tool schemas remain early and stable
- ensure Anthropic cache-control layout does not churn due to dynamic recall
- make setting-driven cache breaks explicit in snapshots
- expose per-route cache performance summary from provider usage metadata

Verification:

- OpenAI cached token telemetry round-trips into run trace
- Anthropic cache read/write telemetry round-trips into run trace
- dynamic memory recall change is visible as dynamic drift, not stable-prefix drift
- Responses remote compaction is advertised only on Responses-native routes; Chat, Anthropic, on-device, and custom routes keep local projection/compaction contracts and honest unsupported-native-feature metadata

### Phase 5: Model-Switch Safeguard

Status: deferred.

Reason:

- explicitly excluded from the current scope by user direction
- should remain a separate trigger-stage implementation so it does not blur normal pre-run compaction semantics

Goal:

- protect continuation/compaction when switching to a smaller context window

Work:

- compare previous route context window with next route context window
- force pressure evaluation before model switch when next window is smaller
- choose local compaction or full rebuild before sending an oversized request
- mark this as a dedicated trigger stage, not normal pre-run compaction

Verification:

- switching from a large-window route to a smaller route compacts or rebuilds before provider call
- trace reports previous window, next window, estimate, and chosen action

### Phase 6: Background Maintenance Worker

Status: done as inline-first trace contract; real background worker deferred until latency data justifies it.

Decision:

- pre-run and mid-turn flush/compaction currently feed correctness-sensitive context for the next model call
- moving those writes to a background worker without a stronger job protocol risks stale prompt state and double-compaction
- the completed scope records `executionMode=inline` for memory flush and durable compaction so future `background` jobs can share the same trace surface

Goal:

- move expensive flush/compaction maintenance out of the inline path only where latency data justifies it

Work:

- define background-safe compaction jobs
- ensure no job mutates the active transcript under an in-flight model turn
- add idempotent job signatures
- preserve synchronous fallback when the provider call would otherwise fail

Verification:

- inline path still works
- run trace distinguishes maintenance execution mode
- background worker remains deferred until there is latency evidence and an idempotent job protocol

### Phase 7: Cross-Layer Trace Replay

Status: done for the current debug snapshot surface.

Goal:

- make context debugging answer "why did this layer appear, shrink, move, or vanish?"

Work:

- add a read-only trace replay surface over run snapshots
- include budget decisions, cache-zone placement, memory source, sticky promotion, projection records, and compaction records
- keep this debug-only

Verification:

- an operator can inspect one run and see exact context layer final states
- no need to reconstruct behavior from raw metadata keys
- budget, cache, memory recall, sticky memory, skills, bootstrap, flush, local compaction, remote compaction, replay projection, and Responses context-update traces are projected through host and projection-only snapshots

## Explicit Non-Goals

- Do not replace structured memory with global summaries.
- Do not make all recalled memory sticky.
- Do not make prompt compaction run every turn.
- Do not claim native remote compaction outside OpenAI Responses.
- Do not hide user-visible transcript loss behind provider-native compaction.
- Do not treat Claude Code memory files and OpenCray durable memory as identical; they solve related but different layers.
- Do not complete model-switch pressure safeguards in this branch slice; they remain a deferred standalone plan item.

## Completion Criteria

This scoped plan is complete when:

- OpenAI Responses native continuation is baseline-plus-update oriented rather than full dynamic-front-byte oriented.
- Chat/Anthropic/on-device routes have stable local cache-shape contracts and honest native-feature metadata.
- Opportunistic memory recall no longer breaks provider-native continuation by default.
- Sticky memory and pinned skills can deliberately enter durable context with trace.
- Replay projection is deterministic and persisted.
- Local/remote compaction are late, threshold-driven, and traceable.
- Debug surfaces show the reason for cache breaks, compaction, projection, and layer reduction.

Deferred completion criterion:

- Model-switch pressure is handled before sending oversized requests.
