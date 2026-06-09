# Context Cache Hit Maximization Design

Last updated: 2026-04-13

## Status

Direction confirmed on 2026-04-10.

This document is intentionally narrower than `docs/context-management-design.md`.

It focuses on one concrete question:

- how OpenCray should maximize context reuse and prompt-cache hit rate
- without introducing a high steady-state token tax
- and without collapsing user-visible transcript fidelity

## Why this document exists

OpenCray already has:

- layered prompt assembly
- local continuation for non-Responses routes
- Responses-native continuation for supported OpenAI routes
- context budgeting and layer-local reduction
- provider-side prompt cache hints and cache telemetry

But those pieces are not yet organized around one first-class goal:

- keep the model-visible prefix as stable as possible

Today, OpenCray still pays unnecessary cache misses because several stable and unstable inputs are mixed too early in the same prompt surface.

The largest current structural issue is:

- `assembledPrompt.contextPrompt` is a single merged block that currently contains many layers with very different volatility

When one small dynamic field changes inside that block, the whole front-loaded context message changes with it.

## Inputs researched for this design

### OpenCray code audited

- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextManager.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/GlobalContextBudgetCoordinator.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
- `docs/context-management-design.md`
- `docs/llm-prompt-caching-implementation-plan.md`
- `docs/openai-responses-web-search-implementation-plan.md`

### Codex source inspected

Local path inspected on 2026-04-10:

- `D:\codes\Opensource\codex`

Primary files:

- `codex-rs/codex-api/README.md`
- `codex-rs/protocol/src/openai_models.rs`
- `codex-rs/core/src/codex.rs`
- `codex-rs/core/src/compact_remote.rs`
- `codex-rs/core/src/truncate.rs`
- `codex-rs/app-server-protocol/src/protocol/thread_history.rs`
- `codex-rs/app-server/README.md`
- `codex-rs/core/tests/suite/compact.rs`

### Claude Code source inspected

Local path inspected on 2026-04-10:

- `D:\codes\Opensource\claude-code-main\claude-code-main`

Primary files:

- `src/bootstrap/state.ts`
- `src/constants/prompts.ts`
- `src/constants/common.ts`
- `src/utils/forkedAgent.ts`
- `src/utils/toolPool.ts`
- `src/utils/toolResultStorage.ts`
- `src/services/compact/autoCompact.ts`
- `src/commands/compact/compact.ts`
- `src/commands/context/context-noninteractive.ts`
- `src/utils/permissions/yoloClassifier.ts`

## Executive conclusion

OpenCray should not try to solve cache reuse with one trick.

It needs a three-layer strategy, in this order:

1. Prefer provider-native continuation when the route truly supports it.
2. Make the replayed prefix byte-stable across turns for non-native paths and fallback rebuilds.
3. Trigger compaction only near real pressure, not as a normal per-turn maintenance loop.

The implementation should borrow different strengths from Codex and Claude Code:

- from Codex:
  - native continuation first
  - compaction as a threshold-triggered event, not a constant background tax
  - compaction as a durable history replacement event with explicit markers
- from Claude Code:
  - treat prompt-cache stability as a first-class architectural invariant
  - freeze replay-shape decisions once chosen
  - preserve byte-identical replay decisions for large tool results
  - separate user-visible history from model-visible replay projection

## What OpenCray does today

At a high level, current prompt construction is:

1. `ContextManager.prepare(...)` resolves memory, working state, skill state, tool observations, transcript window, and related traces.
2. `PromptAssembler.assemble(...)` builds ordered layers.
3. `GlobalContextBudgetCoordinator.rebalance(...)` may reduce or omit some layers.
4. `OpenCrayAgentRuntime` sends:
   - `systemPrompt`
   - `contextPrompt`
   - `messages`

Current effective transport shape on message-based routes is:

1. top-level `systemPrompt`
2. a first synthetic `user` message containing the whole `contextPrompt`
3. replayed transcript messages

The problem is not that OpenCray lacks layering.

The problem is that the transport-facing split is still too coarse.

## Current bottlenecks that reduce cache hits

### 1. Stable and unstable context are merged too early

`contextPrompt` currently includes almost every non-system layer except `Conversation`.

That means one front-loaded message can simultaneously contain:

- durable memory
- durable compaction
- skill inventory
- active skill
- working state
- tool protocol

These do not change at the same rate.

Mixing them together creates unnecessary prefix churn.

### 2. Per-turn operational state sits too close to the prefix

The following are naturally high-churn:

- working state
- explicit replay-independent operational capsules, if a future source is promoted into them
- turn budget reminders
- supplements

They should not sit in the same cache-critical front segment as:

- identity
- session policy
- stable soul contract
- bootstrap files
- durable memory recall

### 3. Model-visible replay and user-visible transcript are still too coupled

OpenCray already keeps structured transcript data, but it does not yet fully enforce this rule:

- what the user sees can remain full-fidelity
- what the model replays can be a deterministic projection

Without that separation, any attempt to stabilize replay becomes riskier because it feels like mutating the transcript itself.

### 4. Cache-sensitive setting changes are not yet modeled explicitly

OpenCray does not yet define a clear product rule for cache-sensitive prompt-shape settings.

That creates two problems:

- the runtime cannot tell whether a cache break happened because the user intentionally changed a setting
- the runtime cannot distinguish acceptable one-time shape changes from accidental prefix churn

### 5. Compaction and cache reuse are not yet coordinated

If compaction fires too early, OpenCray wastes tokens and LLM calls.

If compaction fires too late, the provider may reject the request or the rebuilt prompt may already have lost its stable prefix.

The threshold needs to be explicit and route-aware.

## Reference findings

## Codex findings

### A. Codex is continuation-first

From the local source inspected on 2026-04-10:

- Codex has a dedicated Responses transport layer
- the API client models explicit Responses options
- compaction is its own endpoint and lifecycle

This matters because Codex does not treat every turn as a naive full replay if a native continuation path exists.

### B. Codex compacts near pressure, not continuously

From `codex-rs/protocol/src/openai_models.rs`, `ModelInfo::auto_compact_token_limit()` clamps the configured limit against roughly `90%` of the model context window.

This is the important takeaway:

- Codex does not run semantic compaction as a normal every-turn tax
- it waits until accumulated usage approaches a high watermark

One Codex test comment references behavior "after exceeding 95%". The code path inspected on 2026-04-10 still uses the `90% of context window` clamp as the main automatic compaction ceiling. The practical experience may look closer to the edge because output reserve, turn shape, and mid-turn continuation all affect when pressure becomes visible.

### C. Codex uses compaction as an explicit history-replacement event

Codex exposes compaction as a real thread event:

- there is a compaction item
- thread history preserves a compaction marker
- replacement history becomes the new model-visible basis

This is stronger than treating compaction as an invisible prompt-local summary blob.

### D. Codex still keeps deterministic truncation utilities

Codex also has deterministic truncation helpers for oversized tool output.

That is important because:

- "do not pay an extra LLM call unless needed" is part of its design
- not every large payload deserves semantic summarization

## Claude Code findings

### A. Claude Code treats prompt-cache stability as a hard invariant

The code is full of comments explaining why a change would bust the server-side prompt cache.

Examples from the inspected source:

- cache-sensitive headers are latched for the session
- date is memoized at session start to avoid a midnight prefix bust
- tool order is partition-sorted for cache stability
- dynamic system prompt sections are moved behind a dynamic boundary

This is not incidental polish.

It is a first-class architectural rule.

### B. Claude Code reuses exact "cache-safe params" for forks

The inspected `CacheSafeParams` includes:

- system prompt
- user context
- system context
- tool context
- parent context messages

The point is not abstraction elegance.

The point is byte identity.

### C. Claude Code freezes tool-result replacement decisions

The inspected `toolResultStorage.ts` keeps stable state so that once a large tool result is replaced with a preview, later turns re-emit the exact same preview bytes.

This is a major idea OpenCray should borrow.

It means:

- the model-visible replay can become cheaper
- without rewriting the user-visible transcript
- and without introducing prompt-shape drift across turns

### D. Claude Code does not rely on per-turn semantic compression as the default

It still has `/compact` and autocompact flows, but the main performance win comes from:

- preserving the warmed prefix
- not busting it accidentally

This is the part OpenCray most needs right now.

## Design goals

1. Maximize cache reuse on routes that support server-side prompt caching.
2. Maximize continuation reuse on routes that support provider-native continuation.
3. Avoid paying steady-state LLM compaction cost on healthy turns.
4. Preserve full user-visible history and debugging fidelity.
5. Keep current layered context architecture rather than collapsing into one opaque prompt blob.
6. Make cache misses diagnosable by reason, not just observable by token count.

## Non-goals

1. Do not rewrite the chat UI to display compacted history as if it were the original history.
2. Do not run a semantic summarizer every turn.
3. Do not invent fake provider-native continuation for routes that do not have it.
4. Do not overfit OpenCray to Anthropic-only or OpenAI-only transport assumptions.

## Proposed target architecture

## 1. Split the model-visible request into cache zones

OpenCray should stop thinking in only three transport blobs:

- `systemPrompt`
- one giant `contextPrompt`
- `messages`

It should instead define four cache-relevant zones.

### Zone A: Session-static prefix

This is the most cache-critical zone.

It should change only when the session's identity-level contract really changes.

Recommended contents:

- identity
- runtime rules
- session policy
- stable soul contract
- bootstrap files
- stable tool protocol
- stable tool definitions ordering

Transport recommendation:

- keep in top-level `systemPrompt`

### Zone B: Session-semistable durable context

This zone is still front-loaded, but it is allowed to evolve occasionally across turns.

Recommended contents:

- sticky durable memory capsules only
- durable compaction summary
- stable skill inventory
- explicitly pinned active skill capsule only, as a future capability

Transport recommendation:

- separate front `user` context message or a small fixed group of context messages
- do not merge with Zone C

### Zone B boundary rules

Zone B membership must be decided by volatility, not only by source type.

That means:

- not every automatically recalled memory belongs in Zone B
- not every active skill belongs in Zone B

#### Memory rule

OpenCray currently has two distinct memory behaviors:

- automatic bounded recall before the model call
- explicit `memory_search` / `memory_get` tool access

Automatic recall is still "on demand" in the sense that it is derived from the current turn input.
Because of that, it should not be treated as session-semistable by default.

The durable front zone should carry only memory that is expected to stay sticky across a turn family, such as:

- durable identity reminders
- persistent user preferences
- stable project facts
- other recalled memory that remains materially unchanged across adjacent turns

By contrast, opportunistic turn-local recall that is triggered by the current wording of the user's request should stay in Zone C.

Otherwise a small recall change will churn the front-loaded prefix and break cache reuse for the entire replay tail.

The practical design rule is:

- stable or latched memory capsules may live in Zone B
- fresh per-turn recall should default to Zone C unless explicitly promoted into a sticky capsule

This keeps automatic memory useful without letting every recall pass rewrite the cache-critical prefix.

#### Active skill rule

OpenCray should not try to infer that an active skill is "probably stable for a few turns".

That inference is too soft to use as a cache-boundary decision.

For the current product shape, the rule should be:

- active skill defaults to Zone C
- only a future explicit pinned-skill mechanism may place an active skill capsule into Zone B

In other words:

- temporary activation stays dynamic
- implicitly reused activation still stays dynamic
- only an explicit session-level pin should be allowed to affect the front durable zone

This avoids letting skill switching churn the same prefix region that should stay warm for soul, bootstrap, durable memory, and the rest of the replay baseline.

### Zone C: Turn-dynamic operational context

This zone changes frequently and should be considered cache-fragile.

Recommended contents:

- fresh per-turn memory recall
- turn-local or non-latched active skill capsules
- working state, but only when actual procedural state exists
- turn budget reminders
- transient supplements
- any future replay-independent operational capsule that is explicitly promoted out of replay

Transport recommendation:

- separate dynamic context message after Zone B
- never merged back into Zone A

Ordinary tool traces do not belong here by default.

Codex-style rule:

- `Read` / `LS` / `Grep` / `Glob` stay in replay
- delegation summaries stay in replay
- skills and scheduling inspection stay in replay
- `Task Metadata` is not a standing front-loaded layer
- `Working State` is not allowed to appear just because the current task text exists

### Zone D: Replay transcript

This is the replayed conversation and tool loop history.

Recommended contents:

- user messages
- assistant commentary
- assistant tool calls
- tool results
- assistant answers
- runtime replay-visible observations

Transport recommendation:

- standard replay messages

## 2. Preserve two truths instead of one

OpenCray should explicitly distinguish:

- canonical transcript truth
- model-visible replay truth

### Canonical transcript truth

Used for:

- UI
- audit
- export
- trace/debug
- recovery

This remains full-fidelity.

### Model-visible replay truth

Used for:

- actual provider requests
- local continuation envelopes
- cache-stable subagent forks where applicable

This is allowed to be:

- deterministically reduced
- preview-replaced
- compacted

But it must be reproducible from durable state.

## 3. Add deterministic replay projection for large tool results

OpenCray should introduce a session-scoped, deterministic replay-projection layer for oversized tool results and attachment-like payloads.

### Key rule

Once a tool result has been projected one way for model replay in a session, that projection decision must be frozen for subsequent replay.

### Stabilize early, then stop changing shape

If a historical tool result is likely to need replay reduction, OpenCray should choose that replay form early and keep it stable.

Recommended rule:

- if a result crosses the replay-preview threshold, create its replay preview before transport request assembly writes that shape into the provider-visible prompt
- persist the exact replay bytes and projection metadata
- do not bounce the same result between full, compact, and preview forms across later turns
- do not re-expand a reduced replay item just because a later turn temporarily has more budget

### Projection timing rule

Replay projection should happen before the provider sees the request body that may later be reused through prompt caching.

In other words:

- do not first send a larger historical payload, let that larger prefix warm the cache, and then shrink it on a later turn
- decide the replay shape at request-build time for model-visible history
- once chosen, keep that projected replay shape stable across later turns unless the canonical source item itself changes

### Conservative projection rule

Early replay downgrade should be conservative.

OpenCray should only pre-project content that is genuinely low-value for later semantic continuity, such as:

- attachment-like blob payloads
- large machine-oriented exhaust with little future semantic value
- repetitive, enumerative, or purely operational tool output where a stable preview preserves the only information likely to matter later

OpenCray should avoid early downgrade for content that still carries likely future meaning, such as:

- user messages
- assistant decisions, commitments, or answers
- recent tool results that are likely to be directly referenced, edited, or inspected again
- rich code, prose, or evidence-bearing output just because it is long

When classification is uncertain, prefer keeping the fuller replay form and rely on the later budget/compaction path instead of aggressive early reduction.

### Example

If a `Read` or `Bash` result is too large and OpenCray projects it as:

- a persisted preview header
- a short deterministic excerpt
- a pointer to where the full output is available in debug/UI

then later turns must replay the same projected bytes for that same result id.

### Why this matters

Without frozen replay decisions:

- the same earlier event can look different on turn 5 than it did on turn 3
- the provider prefix changes
- prompt cache reuse collapses

### Design notes

- projection state must be keyed by durable tool-result identity
- preview text must be persisted exactly as emitted
- the UI must keep the full raw tool output path separate from replay projection
- the projected form should be decided at the model-visible request boundary, before provider cache write opportunity
- the projection policy should bias toward "compress only obviously low-value exhaust"

This is a direct lesson from Claude Code's replacement-state design.

### Current conservative implementation slice

The implemented Phase 2 slice is intentionally narrower than the full design target.

OpenCray now freezes exact replay bytes for tool-result messages that are obviously low-value for later semantic continuity:

- attachment-like content such as `data:` payloads or long base64-like lines
- very large `stdout`
- very large `stderr`
- very large `structured_content`

OpenCray does not currently replay-project a tool result only because the main `content` field is long.

That means long code, prose, evidence excerpts, and ordinary `Read` output stay full unless they also match the attachment-like rule above.

This is deliberate.

It keeps OpenCray on the conservative side of the Codex / Claude Code reference line instead of turning replay projection into a broad semantic shrinker.

Implementation details of the current slice:

- the replay boundary stores a session-scoped frozen projection record keyed by a deterministic tool-result digest
- the frozen record persists the exact projected `LiteLlmGatewayToolResult` surface, not just a regenerated summary string
- later replay reuses the stored projected bytes verbatim across local continuation, resume, and full rebuild paths
- canonical transcript and checkpoint transcript remain full-fidelity and still retain the raw tool result
- the projected content uses a stable head/tail preview wrapper and retains small sidecar fields when that does not defeat the reduction

Current default thresholds are intentionally high:

- attachment-like content must be at least `1024` chars
- `stdout` / `stderr` / `structured_content` projection starts at `6000` chars

Those defaults are expected to be revisited only if real mobile traces show frequent oversized operational exhaust.

## 4. Apply cache-sensitive settings immediately, but make cache breaks explicit

User-facing cache-sensitive settings should take effect immediately.

OpenCray should not defer those changes to the next session just to protect prompt-cache hit rate.

The product rule is:

- if the user changes a prompt-shape-affecting setting, the current session adopts it immediately
- the resulting cache cost is acceptable and expected
- the runtime must record that the warmed prefix changed because of an intentional setting change

### Why this matters

This keeps product behavior honest:

- user intent is applied immediately
- cache stability remains important, but it does not override explicit user control
- cache misses caused by settings become diagnosable instead of mysterious

### Operational rule

OpenCray should still minimize accidental prompt-shape churn:

- user-visible settings may change immediately
- deterministic replay projection should not oscillate
- internal transport defaults should be derived deterministically from the current config, not from hidden ad hoc toggles

## 5. Prefer native continuation first, prompt cache second

The optimization priority should be:

1. native continuation, when trusted
2. local continuation envelope, when native continuation is unavailable
3. stable full rebuild with prefix reuse

### OpenAI Responses routes

If lineage is trusted:

- prefer `previous_response_id`
- avoid full replay
- retain local transcript as durable truth anyway

Important implementation constraint confirmed against the official OpenAI Responses docs on 2026-04-13:

- `instructions` are not carried forward automatically when using `previous_response_id`
- OpenCray may therefore vary the top-level `systemPrompt` per turn
- but provider lineage still retains the previously submitted conversation items
- so Zone B and Zone C user-message context cannot be silently changed during native continuation

Operational rule:

- only use native continuation when the pending delta is provider-safe
- and the stored front-context shape still matches the next turn
- otherwise rebuild the full message stack from the current zones

If lineage is untrusted:

- fall back to full rebuild
- but rebuild from the new cache-zone split so the stable prefix still has a chance to hit provider cache

### Non-Responses routes

Use:

- stable prefix zones
- deterministic replay projection
- local continuation envelope where valid

Do not pretend those routes have provider-native continuation.

### Responses-native redesign target

For Responses-native specifically, OpenCray should move closer to Codex's "stable baseline plus append-only context updates" model instead of treating a high-churn Zone C block as part of the provider-side continuation shape.

Codex-aligned reading from the local source inspected on 2026-04-13:

- Codex keeps a durable `reference_context_item` baseline
- when the baseline exists, it appends small context update items instead of reinjecting the whole initial context
- those updates are appended into model-visible history as normal `developer` or contextual `user` items
- full reinjection is reserved for baseline loss, compaction reset, or other real rebuild boundaries

That pattern matters because OpenCray's current Responses-native shape still treats both Zone B and Zone C front-context bytes as a strict continuation contract:

- if Zone B changes, full rebuild is correct
- if Zone C changes, OpenCray currently also falls back to full rebuild

This is still more conservative than Codex, because Codex does not model most dynamic runtime state as a rewritten front block on every turn.

#### Target transport shape for Responses-native

Responses-native should distinguish three different classes of model-visible context:

1. Responses baseline context
   - stable system instructions
   - stable durable front context
   - only content that is acceptable as the long-lived continuation baseline
   - rebuilt only when lineage is unavailable or a real shape break happened

2. Responses context update items
   - append-only developer or contextual-user messages
   - represent changes relative to the last persisted baseline snapshot
   - eligible for native continuation when provider-safe
   - should be small, explicit, and diff-like rather than full-state restatements

3. Replay and tool history
   - ordinary conversation
   - ordinary tool results
   - deterministic replay projections
   - compaction summaries or replacement history when compaction has already happened

In other words, for Responses-native the desired future state is not:

- stable front block plus another front block that keeps changing

It is:

- stable baseline
- then append explicit context updates when state changes
- then keep the rest in replay/history

#### What this means for Zone C

For Responses-native, high-volatility Zone C should stop being a provider-side front-prefix dependency.

The concept may still remain useful inside OpenCray as an internal classification for:

- fallback full rebuild assembly
- debug and trace reporting
- source-level budgeting

But provider-native continuation should no longer depend on "the entire current Zone C rendered bytes still match".

Instead:

- stable slices may be promoted into the baseline only when they are intentionally sticky
- dynamic slices should become append-only context updates
- opportunistic one-turn state should stay in replay/history or explicit tools

#### Concrete reclassification rules

Responses-native should follow these rules.

Stable baseline only:

- tool protocol and durable runtime operating contract
- durable compaction summaries
- stable bootstrap and stable soul or policy instructions
- only memory or skill state that has been explicitly promoted into a sticky capsule

Append-only context updates:

- working-state changes that matter to future turns
- active-skill activation, switch, or clear events
- live-mode or runtime-policy changes that are not part of the immutable baseline
- future sticky-memory promotion or demotion events

Replay/history instead of front context:

- ordinary workspace discovery such as `Read`, `LS`, `Grep`, and `Glob`
- ordinary inspection summaries
- one-turn opportunistic memory recall hits
- transient observations that are already visible through tool replay

#### Automatic memory recall on Responses-native

This is the most important change from the current design.

Automatic opportunistic recall that is triggered only by the current user wording should not remain a front-loaded Responses-native dependency.

Recommended rule:

- automatic opportunistic recall is allowed for non-Responses and full-rebuild fallback assembly
- but on Responses-native it should not mutate the continuation baseline by default

Instead, Responses-native should prefer this order:

1. explicit memory tools
2. sticky promoted memory capsules
3. replay-visible prior memory activity
4. full rebuild only when a true baseline-affecting memory change happened

This keeps "the user's wording changed a little" from constantly breaking `previous_response_id`.

#### Working state on Responses-native

Working state is allowed to influence future turns, but it should not be represented as a whole rewritten front block on every change.

Recommended direction:

- persist a Responses-specific working-state reference snapshot
- derive a bounded context update item from the diff between previous and current state
- append that update item through native continuation
- periodically collapse or reset through full rebuild or compaction only when the update stream itself becomes too large

This is closer to Codex's `reference_context_item` plus settings-update approach than the current "render current dynamic context bytes and compare them as a front prefix" strategy.

#### Active skill on Responses-native

The current default of placing `Active Skill` in a dynamic front zone is acceptable for non-Responses and full rebuilds, but it is too volatile for native continuation.

Recommended direction:

- initial `skill_read` activation should emit one append-only skill activation update item
- the skill remains effective because that activation is now part of native history
- only explicitly pinned or sticky skill capsules may later enter the stable baseline

#### New persisted state required

To support this redesign cleanly, Responses-native should add a persisted baseline-and-diff contract instead of relying only on rendered front-context strings.

Recommended new state:

- `ResponsesContextBaselineSnapshot`
  - the last durable baseline that native continuation assumes
- `ResponsesContextReferenceState`
  - structured fields used to compute update diffs
- `ResponsesPendingContextUpdates`
  - provider-safe appended update items waiting to be sent with the next `previous_response_id` call

This is intentionally closer to Codex's `reference_context_item` model than to the current Zone-B-plus-Zone-C byte equality check.

#### Allowed native continuation after this redesign

After the redesign, Responses-native should remain allowed when:

- lineage is trusted
- tool pool, tool schema, and request-shape fingerprints still match
- baseline context still matches
- pending deltas contain only provider-safe tool results and provider-safe context update items

Responses-native should rebuild only when:

- stable baseline changed
- lineage is unavailable or untrusted
- provider-safe update generation failed
- pending context updates exceeded a bounded chain length
- compaction or replay reset intentionally cleared the baseline

#### Explicitly not recommended

This redesign should not do the following:

- do not try to "patch" provider-hidden prefix bytes the way local continuation can
- do not keep opportunistic recalled memory as a standing Responses front block
- do not rewrite whole working-state or active-skill blobs into the native front shape every turn
- do not use keyword-style heuristics to guess whether a dynamic slice is sticky enough for baseline promotion

#### Rollout recommendation

Recommended execution order for Responses-native only:

1. shrink the Responses baseline so it contains only truly stable slices
2. introduce structured Responses context reference state and diff builders
3. allow provider-safe context update items alongside pending tool-result deltas
4. remove opportunistic automatic recall from the Responses-native front dependency path
5. add bounded reset rules for overgrown update chains

This keeps the change scoped to Responses-native while moving OpenCray materially closer to the Codex continuation shape.

## 6. Change when compaction runs

OpenCray should not run semantic compaction as a routine maintenance step during normal healthy turns.

### Proposed compaction policy

Compaction should run only when one of these becomes true:

1. the projected next request is nearing the configured compaction threshold
2. the provider returns an overflow such as `context_length_exceeded`
3. a route-specific continuation path is lost and the fallback full rebuild is now too large
4. the user explicitly requests compaction

### Threshold strategy

Recommendation:

- default auto-compaction trigger should be a late trigger
- it should not be more aggressive than Codex by default

The design target is:

- use a model-aware usable input budget
- reserve output headroom
- reserve a small emergency margin
- only compact when the projected request is genuinely near that ceiling

This is intentionally different from:

- per-turn summarization
- eager "compress older history a little bit every turn"

### Compaction form

Preferred order:

1. provider-native compaction if the route truly supports it
2. host-managed replacement-history compaction otherwise

Prompt-local pruning should remain available for pathological payloads, but it should not become a hidden always-on semantic compressor.

## 7. Do not compact the user-visible transcript

This design explicitly separates:

- compaction for model-visible replay
- transcript fidelity for the user

OpenCray should keep:

- full tool history for UI and debugging
- full trace of what happened

Even when the model sees:

- compacted replacement history
- replay previews instead of raw oversized outputs

This matches the product direction already established in prior context discussions:

- compression is for the model path
- not for rewriting what the user sees

## 8. Make cache busts explainable

OpenCray should emit structured diagnostics for cache-break reasons.

Recommended categories:

- `system_prefix_changed`
- `durable_context_changed`
- `tool_pool_changed`
- `tool_schema_changed`
- `user_setting_changed`
- `dynamic_context_changed`
- `replay_projection_changed`
- `continuation_lineage_untrusted`
- `compaction_boundary_changed`
- `provider_cache_ttl_expired`

### Current attribution implementation slice

The currently landed attribution slice is narrower than the full diagnostic target, but it already covers the most actionable local-continuation cache-shape breaks.

For non-Responses local continuation, OpenCray now persists three prompt-cache shape fingerprints inside the continuation envelope:

- tool pool fingerprint
- tool schema fingerprint
- request settings fingerprint

It also persists the transport-facing front-context split explicitly instead of relying only on an ordered prompt list:

- `durableContextPrompt`
- `dynamicContextPrompt`

On the next turn, if envelope reuse is otherwise eligible but any of those fingerprints no longer match the current request shape, OpenCray forces a full rebuild and emits one of:

- `tool_pool_changed`
- `tool_schema_changed`
- `user_setting_changed`

If the non-system front context itself changes, OpenCray now distinguishes which zone moved:

- `durable_context_changed`
- `dynamic_context_changed`

`durable_context_changed` still forces a full rebuild.

`dynamic_context_changed` is now narrower on non-Responses local continuation: when the stored envelope prefix still matches the stored Zone B and Zone C message shape, OpenCray locally replaces only the dynamic front-context message block and then appends transcript delta. If that prefix can no longer be verified, it falls back to the same explicit full rebuild path.

This keeps the fallback explicit instead of collapsing those cases into a generic `anchor_changed` or opaque full-rebuild path, while still avoiding unnecessary whole-request rebuilds for pure Zone C churn.

These now show up in:

- run diagnostics metadata
- host-backed debug surfaces, including the Settings trace view
- local runtime server JSON through the same snapshot metadata path

The non-Responses path now also externalizes a stable cache-shape contract for downstream consumers instead of leaving the shape implicit inside checkpoint-only state. Each main-path request/result can emit:

- `contextCacheContractVersion = non_responses_front_zone_v1`
- `contextCacheStableAnchorHash`
- `contextCacheDurableContextHash`
- `contextCacheDynamicContextHash`
- `contextCacheFrontContextZoneMask`
- `contextCacheFrontContextMessageCount`

That contract is intentionally narrow:

- it does not expose raw prompt bytes
- it is scoped to the non-Responses split-front assembly path
- it is stable enough for downstream local-model cache consumers to detect "same durable prefix, different Zone C" without reverse-engineering provider-visible messages

This is necessary because "cache miss happened" is not enough to tune the system.

## 9. Proposed request assembly after this redesign

For message-based routes, the target assembly order should become:

1. top-level `systemPrompt`
   - Zone A only
2. first front-loaded context message
   - Zone B only
3. second front-loaded context message
   - Zone C only
4. replay messages
   - Zone D

Important clarification:

- the Zone B versus Zone C split is not source-based
- it is stability-based

So even if two items both come from `memory` or both come from `skills`, one may belong in Zone B and the other in Zone C depending on whether it is sticky across turns or only relevant to the current turn.

For Responses-native continuation:

1. keep Zone A stable
2. keep Zone B and Zone C deterministic when full rebuild is needed
3. otherwise rely on lineage continuation and only append the required delta

This gives OpenCray two independent wins:

- a smaller blast radius when dynamic context changes
- better fallback behavior when lineage continuation is lost

## 10. Specific changes recommended for OpenCray

### A. Prompt model changes

Add explicit assembly groups instead of only rendered strings.

Recommended additions:

- `stableSystemPrompt`
- `durableContextPrompt`
- `dynamicContextPrompt`
- `replayTranscriptPrompt`

`taskPrompt` can remain as a debug/report surface, but provider assembly should stop depending on one merged `contextPrompt` blob.

### B. Prompt assembler changes

`PromptAssembler` should:

- keep current logical order
- but emit group membership metadata for each layer
- and render group outputs separately
- and stop treating `Retrieved Memory` or `Active Skill` as single fixed-zone sources
- instead classify them into durable versus dynamic transport groups based on sticky-versus-ephemeral runtime state

### C. Runtime request builder changes

`OpenCrayAgentRuntime` should:

- stop injecting all non-conversation context as one synthetic user message
- inject Zone B and Zone C separately
- keep replay transcript as replay transcript

### D. Replay projection store

Introduce a session-scoped replay-projection state store for:

- frozen tool-result preview replacements
- persisted projection traces
- replay-projection versioning

### E. Immediate setting-change semantics

Define an explicit rule set for prompt-shape-affecting settings:

- user-facing settings apply immediately
- each such change emits a structured cache-break reason
- replay-projection and downgrade decisions remain stable once chosen

### F. Compaction coordinator changes

Compaction trigger logic should consult:

- model context window metadata
- reserved output tokens
- continuation mode
- whether deterministic replay projection already brought the request back under budget

### G. Telemetry changes

Emit:

- provider cache read tokens
- provider cache write tokens
- continuation mode
- cache-break reason
- compaction trigger reason
- post-compaction first-call marker

## 11. Performance stance

This design is explicitly biased toward low steady-state overhead.

### Normal turn cost should remain mostly deterministic

On healthy turns, OpenCray should pay only for:

- ordinary prompt assembly
- deterministic replay projection lookup
- model-aware budget estimation

It should not pay for:

- a semantic summarizer call every turn
- a compaction fork every turn
- a memory-rewrite pass every turn just to keep context tidy

### LLM compaction should be exceptional, not ambient

LLM-driven compaction is allowed, but only when:

- the route can really benefit from it
- or the request is close to failure

That is the only way to maximize cache hit rate without turning context management itself into a token sink.

## 12. Rollout plan

### Phase 1: cache-zone refactor

- split assembly output into Zone A/B/C/D groups
- change runtime request builder to send Zone B and Zone C separately
- keep existing logic otherwise
- refine source routing so automatic memory recall and active-skill capsules are placed by volatility rather than by source family alone

### Phase 2: deterministic replay projection

- add frozen replay projection for large tool results
- persist exact preview bytes
- keep canonical transcript untouched
- make projection happen before provider-visible request assembly, not after cache warm-up
- keep the projection classifier conservative and biased toward non-semantic exhaust

### Phase 3: cache-break diagnostics and stable downgrade rules

- implement explicit cache-break attribution for immediate setting changes
- freeze replay downgrade shape once a historical item is projected
- avoid repeated full/compact/minimal oscillation for the same replay item

### Phase 4: threshold-triggered compaction revision

- rework auto-compaction trigger to be explicitly late and model-aware
- make trigger reasons debuggable

### Phase 5: route-specific tightening

- tighten Responses lineage trust
- improve local continuation fallback rebuild to preserve cache zones
  - Current status: non-Responses local continuation now persists durable versus dynamic front-context zones explicitly, uses zone-aware change reasons, and no longer drops the continuation envelope from no-tool checkpoint boundaries just because the turn had no visible tool pool.
  - Current status: pure Zone C drift on non-Responses local continuation no longer always forces a whole-request rebuild. When the stored envelope front prefix is still structurally aligned, OpenCray now does a local front patch by replacing only the dynamic front-context block and then appending transcript delta; full rebuild remains the guardrail when durable context changed or the stored prefix can no longer be trusted.
  - Current status: Responses-native continuation now uses the same explicit shape contract. The runtime persists a serialized Responses continuation shape carrying stable anchor, Zone B and Zone C front-context bytes, tool-pool fingerprint, tool-schema fingerprint, and request-settings fingerprint; `previous_response_id` is only reused when lineage is available, that stored shape still matches, and the pending delta contains only safe tool-result messages.
  - Current status: legacy JSON fallback is no longer forced on every Responses turn. Native continuation is now truly reachable for shape-stable tool turns, while raw JSON tool-call fallback, supplement user deltas, attachment-artifact tool results, lineage loss, tool-pool drift, and Zone C drift all force explicit full rebuild with structured reasons.
  - Current status: ordinary workspace discovery such as `Read`, `LS`, `Grep`, and `Glob` is now replay-owned instead of front-loaded into `Recent Working Observations` or observation-derived `Working State`. This is closer to Codex's `ResponseItem` history handling: the model sees those tool results through replay or pending tool-result delta, not through a duplicated control-plane summary layer.
  - Current status: replay-owned scope has now been tightened further across the total assembly path. Delegation summaries, skills-discovery and scheduling-inspection results, workspace package/document inspection, and `Task Metadata` no longer get front-loaded into Zone C on the main path; `Working State` now appears only when real operational state exists instead of restating the task input by itself.
  - Remaining gap: provider-native continuation is still intentionally conservative for true operational churn. Real procedural state changes such as `TodoWrite`, schedule create/update/delete mutations, resume checkpoints, blockers, or other live working-state updates still change Zone C and therefore still break provider-native lineage until later sticky-capsule or replay-shape work narrows that boundary without hiding real state changes. The new local front patch currently applies only to non-Responses local continuation.

## 13. Risks

### Risk 1: immediate setting changes can bust a warmed cache

Mitigation:

- accept that this is valid product behavior
- emit structured diagnostics showing that the cache break was caused by an intentional setting change
- keep other replay-shape decisions stable so one user change does not trigger repeated secondary churn

### Risk 2: deterministic replay projection can drift from canonical history

Mitigation:

- never overwrite canonical transcript
- keep explicit projection metadata and versioned replacement records

### Risk 3: overzealous compaction can still destroy short-term continuity

Mitigation:

- compaction stays late-triggered
- working state remains separate from generic history compaction

## 14. Confirmed product decisions

These directions were confirmed on 2026-04-10.

### Decision A: cache-sensitive user settings are immediate

Adopted direction:

- user-facing prompt-shape settings take effect immediately in the current session
- OpenCray does not defer them to the next session to preserve cache warmth
- the runtime should emit an explicit cache-break reason when such a change alters the warmed prefix

Trade-off:

- lower cache reuse after a user setting change
- but correct and immediate product behavior

### Decision B: deterministic model-visible replay projection is allowed

Adopted direction:

- OpenCray may replace oversized historical tool results with persisted preview forms in model replay
- canonical transcript, UI, export, and debug keep the full raw result
- if a replay item needs trimming or downgrade, do it early and then keep that replay shape stable

Trade-off:

- much better replay stability and lower replay cost
- more projection state and replay bookkeeping

### Decision C: semantic compaction remains late-triggered by default

Adopted direction:

- semantic compaction should trigger late, close to real pressure
- OpenCray should not be more aggressive than Codex by default
- deterministic pruning and replay projection may happen earlier, but semantic compaction itself remains threshold-driven

Trade-off:

- lower steady-state cost and stronger warmed-prefix reuse
- higher need for precise pressure estimation and overflow fallback handling

## 15. Adopted decision set

OpenCray should therefore:

1. apply cache-sensitive user settings immediately and diagnose the resulting cache break explicitly
2. use deterministic replay projection for oversized historical tool results while preserving full UI/debug transcript
3. keep semantic compaction late-triggered, with stable replay downgrade decisions chosen earlier when needed
4. classify automatic memory recall and active-skill capsules by stability, not merely by source, so only sticky slices enter the front durable cache zone

That combination is the closest fit to your stated goal:

- maximize cache hit rate
- do not pay unnecessary steady-state performance cost
- do not hide or rewrite what the user sees
