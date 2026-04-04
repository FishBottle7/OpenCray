# Global Context Budget Coordination Design

Last updated: 2026-04-03

## Status

Planning document.

## Goal

Define how OpenCray should coordinate prompt budgets across all context layers without making Codex-style global compression the primary mechanism for every run.

The target is:

- keep OpenCray's structured, preference-sensitive, layered context assembly
- avoid flattening all context into one generic summary
- still make prompt assembly model-aware at the total-token level
- reserve stronger global compaction for the layers where it actually makes sense

## Core position

OpenCray should not treat "global compression" as the default architecture.

Instead, OpenCray should adopt:

- local layer-specific reduction rules
- plus one global model-aware budget coordinator above them

In other words:

- no, OpenCray does not need to copy Codex's global compression model as its primary design
- yes, OpenCray does need a Codex-grade global budget coordinator

This distinction is important.

## Why not copy Codex-style global compression as the primary mechanism

Codex-style global compaction is strongest when the system relies heavily on:

- provider-native continuation
- provider-managed compacted conversation state
- token-aware remote or opaque history reduction

OpenCray has a different strength:

- layered local context ownership
- stable preference sensitivity
- explicit soul, memory, skill, and bootstrap surfaces
- host-owned session lifecycle and debug traceability

If OpenCray makes generic global compression the first response to pressure, it risks blurring together:

- user preference and identity
- procedural working state
- durable memory
- transcript replay
- archived history

That would reduce one of OpenCray's main product advantages:

- being highly sensitive to stable user and project context without making those signals hostage to generic history compression

## Why a global coordinator is still necessary

Local budgets alone are not enough.

Without one global coordinator, the system can still fail in ways like:

- transcript consumes too much space even after local pruning
- bootstrap or skill layers crowd out working state
- memory injection survives while more important procedural context is dropped
- each layer behaves "correctly" locally, but total prompt size still exceeds the model-safe envelope

So OpenCray needs:

- one place that knows the total available prompt budget for the current model and route
- one place that decides which layers must be preserved, which may shrink, and which may be dropped first

## Architectural principle

OpenCray should distinguish these responsibilities:

### 1. Local reducers

Owned by each layer or context subsystem.

Examples:

- transcript window builder
- pruning rules
- recent observation caps
- memory recall limits
- working-state caps
- skill and bootstrap caps

These answer:

- how can this specific layer shrink safely

### 2. Global budget coordinator

Owned by the runtime context pipeline.

This answers:

- given total pressure, which layers should keep space
- which layers should shrink first
- when to switch from trimming to compaction
- when provider-native continuation or compaction is worth using

## Required terms

### Global budget coordination

The process of fitting all prompt layers into a model-aware total prompt budget.

### Global compression

A stronger fallback where larger parts of replayable context are transformed into reduced or compacted form.

### Layer-local reduction

A layer's own narrowing rules, such as entry caps, line caps, char caps, or structured omission.

### Protected layers

Layers that should not be converted into generic summaries under normal pressure.

### Replayable layers

Layers that can be reconstructed or compacted more aggressively because they represent historical or procedural replay rather than stable identity.

## Design principles

### Principle 1: Not all context is equally compressible

OpenCray must stop thinking of "context" as one blob.

Different layers have different compression safety.

### Principle 2: Identity and preference should not be generic-compressed first

Stable user preference, soul, and session directives should usually stay explicit and structured.

### Principle 3: Working state outranks older replay

If the system must choose, preserving the current procedural state is more valuable than preserving more old transcript.

### Principle 4: Archive should absorb pressure before live working state does

Older transcript and older summaries should degrade before the current work surface does.

### Principle 5: Provider-native compaction is optional acceleration, not the whole design

If a route supports native continuation or compaction, OpenCray should use it where appropriate.
But OpenCray should still keep its own local layer model.

## Proposed layer classes

The runtime should classify layers into budget classes.

### Class A: mandatory live instruction

Examples:

- current user input
- task protocol
- approval or resume boundary instructions

Rule:

- never dropped
- never generic-compressed

### Class B: protected stable identity

Examples:

- base system prompt
- soul and preference contract
- session directives

Rule:

- preserve in explicit form as long as possible
- may be bounded, but should not be collapsed into generic compaction summaries under normal pressure

### Class C: protected procedural continuity

Examples:

- working state
- active blocker state
- latest mutation and execution facts

Rule:

- preserve longer than transcript replay
- compact structurally, not generically

### Class D: recent replay

Examples:

- bounded transcript tail
- recent tool observations

Rule:

- shrink before Class B and Class C
- keep newest and most operationally relevant items first

### Class E: bounded durable recall

Examples:

- memory recall
- active skill capsule
- essential bootstrap snippets

Rule:

- bounded and selectively reduced
- important, but usually below protected procedural continuity

### Class F: optional support context

Examples:

- skill inventory
- non-essential bootstrap snippets
- low-value recent observations

Rule:

- shrink or omit early under pressure

### Class G: archive and compacted history

Examples:

- durable compaction summaries
- archived transcript context
- provider-native compacted history handles

Rule:

- absorb pressure first
- best place for stronger compaction behavior

## Recommended retention order

When budget is tight, the runtime should prefer preserving layers in this order:

1. current user input and task protocol
2. approval/resume boundary instructions
3. base system prompt and stable soul/preference contract
4. working state
5. latest high-value mutation/execution observations
6. bounded recent transcript
7. memory recall
8. active skill capsule
9. essential bootstrap snippets
10. recent discovery observations
11. skill inventory
12. archived or compacted history summaries

This order is the key strategic difference from a naive global compression strategy.

## Reduction order

When the coordinator must reduce prompt size, the runtime should proceed in stages.

### Stage 0: compute total model-aware budget

Inputs:

- model context window
- reserved output tokens
- tool-protocol overhead
- safety margin

### Model window metadata source

OpenCray should currently resolve `context_window_tokens` from a host-owned static model table, not by programmatically querying provider APIs at runtime.

The intended precedence is:

1. explicit/manual override
2. static exact-model table
3. static family fallback rules
4. conservative global default

Current implementation direction:

- keep the static table in app-owned capability resolution code so it can be updated intentionally
- treat the model name or model-family prefix as the primary vendor signal for capability lookup; `baseUrl` or selected provider id are only secondary hints because third-party routes may proxy OpenAI, Anthropic, Gemini, or DeepSeek models
- persist verified or manually overridden `contextWindowTokens` in the capability cache so later runs do not need to rediscover it
- use `128K` as the conservative global fallback when no explicit or static match exists
- do not inject static `max_output_tokens` through this same path yet, because route metadata is also consumed by provider request assembly and a premature output-limit default would change request behavior
- route-specific continuation capabilities

The result should be:

- `hardInputBudget`
- `targetInputBudget`
- `emergencyInputBudget`

### Stage 1: apply local layer caps

Each layer first applies its own safe narrowing rules.

Examples:

- transcript windowing
- tool output pruning
- observation caps
- working-state entry caps
- memory recall caps
- bootstrap caps

### Stage 2: global rebalance without compaction

If total size is still too high, shrink or omit in this order:

1. optional support context
2. archived history
3. low-value recent observations
4. older replay inside transcript
5. memory recall beyond the highest-value subset

At this stage, protected identity and working state should remain intact.

### Stage 3: replay compaction

If the prompt is still too large, compact replayable history further:

- reduce transcript tail more aggressively
- collapse recent replay into stronger structured summaries
- reduce or omit archived summaries
- prefer replacing replay detail before touching working state

### Stage 4: protected-layer emergency trim

Only if the runtime still cannot fit within budget:

- shorten non-essential wording inside protected layers
- trim working-state entry depth while keeping the latest objective, blocker, latest mutation, and next action
- shorten active skill body to a stricter minimum

Even here, the system should avoid flattening protected layers into one generic summary blob.

### Stage 5: provider-native continuation or compaction

If supported by the route and worth the tradeoff:

- use provider-native continuation or compaction for replayable history

But do not outsource these OpenCray-owned layers:

- current user input
- task protocol
- soul and preference contract
- working state

## Proposed budget math

The coordinator should become model-aware.

### Inputs

- `contextWindowTokens`
- `reservedOutputTokens`
- `safetyMarginTokens`
- `effectiveInputPercent`

### Suggested first-pass formula

```text
rawInputBudget =
  contextWindowTokens
  - reservedOutputTokens
  - safetyMarginTokens

targetInputBudget =
  floor(rawInputBudget * effectiveInputPercent)
```

### Recommended use

- use real token counting when available
- otherwise use the current estimated-token fallback only as a degraded approximation

The goal is not perfect token precision.
The goal is preventing a layer-local budgeting system from pretending it understands total pressure when it does not.

## Proposed layer budgets

Each layer should expose:

- `minBudget`
- `targetBudget`
- `maxBudget`
- `priorityClass`
- `reductionOperators`

Example shape:

```text
LayerBudgetSpec
  layerName
  priorityClass
  minTokens
  targetTokens
  maxTokens
  mayDrop
  mayGenericCompress
  reductionOperators[]
```

This lets the coordinator reason about:

- what must survive
- what may shrink
- how each layer may shrink

## Why this is better than one global summary

If everything compresses into one summary, the system loses the distinction between:

- identity
- procedural state
- durable knowledge
- replay history

That makes continuation less reliable and preference sensitivity weaker.

If OpenCray instead uses a global coordinator over typed layers, it can preserve:

- stable preference sensitivity
- explicit procedural continuity
- traceable archival reduction

without needing to summarize everything the same way.

## Relationship to Working State

This design depends on `Working State layer` existing.

Without working state, the coordinator is forced into a bad trade:

- either keep too much transcript
- or summarize away the very information needed for continuation

With working state, the coordinator can do something better:

- preserve a small high-value procedural layer
- shrink replay more aggressively behind it

Related design:

- `docs/working-state-layer-design.md`

## Relationship to durable memory

Durable memory should not absorb budget pressure that belongs to procedural continuity.

This means:

- do not promote recent actions into memory just because transcript pressure exists
- only promote stable facts that pass memory policy
- let working state carry the live process state

Related design:

- `docs/memory-design.md`

## Relationship to transcript compaction

Transcript compaction remains important, but it should become only one tool inside the larger budget strategy.

The correct relationship is:

- transcript compaction is a replay-layer reduction operator
- not the whole answer to prompt pressure

## Relationship to Codex-like native compaction

OpenCray should treat Codex-like native compaction as:

- a powerful optimization for replayable history
- especially useful on supported provider routes

But OpenCray should not let that replace local ownership of:

- stable identity
- working state
- durable memory semantics

This hybrid gives OpenCray the best chance of getting:

- OpenClaw-like preference sensitivity
- plus stronger Codex-like long-task continuity

### Codex reference checkpoint from source

Local Codex source inspection now makes the following behavior clear.

Codex does not primarily switch between several unrelated "compression modes" when the model context window changes.

Instead, it keeps one compact-and-replace history mechanism and changes when that mechanism triggers, plus how much auxiliary payload can fit, based on model-window-derived thresholds.

Observed code-backed rules:

- model-visible effective context window is derived as:
  - `context_window * effective_context_window_percent / 100`
  - current default effective percent is `95`
- automatic compaction uses a separate threshold:
  - default `auto_compact_token_limit = 90% of context_window`
  - if configured manually, Codex still clamps it to at most `90% of context_window`
- compaction can happen in multiple places:
  - before a new turn when accumulated token usage is already over the auto-compact threshold
  - mid-turn when token usage crosses the threshold and the run still needs follow-up
  - when switching from a larger-window model to a smaller-window model and the existing thread now exceeds the new compact threshold
- remote compaction trims Codex-generated trailing function-call history first when the compaction request itself would exceed the effective window
- some side-channel truncation budgets also scale from the effective window rather than using one fixed constant

Important pruning boundary:

- Codex does have deterministic trimming, but it behaves like a narrow guardrail around compaction and side payloads
- it does not use local pruning as a broad "rewrite long replay, collapse duplicate background, and summarize tool output early" policy
- OpenCray should follow that boundary and avoid making prompt-local pruning more aggressive than Codex while it keeps its typed-layer budget architecture

The practical implication is:

- larger context windows in Codex mostly delay compaction pressure and enlarge some bounded inputs
- smaller context windows trigger the same compaction path earlier and force more aggressive trimming around that path

This is similar to the direction OpenCray should take for context-budget presets and source-cap coupling, but it is not the same as replacing OpenCray's typed layer model with one global history summary.

## Required runtime outputs

The budget coordinator should emit a structured report for every run.

Suggested fields:

- total available input budget
- estimated or measured tokens per layer
- target vs actual tokens per layer
- omitted layers
- reduced layers
- compaction operators used
- provider-native continuation or compaction decisions
- emergency-mode activation

This report should make it possible to answer:

- why did the runtime drop this layer
- why did it keep that one
- was replay compacted before working state
- did the model run under normal, tight, or emergency budget pressure

## Suggested module split

Potential ownership:

- `runtime/context/ModelContextBudgetPolicy.kt`
- `runtime/context/GlobalContextBudgetCoordinator.kt`
- `runtime/context/LayerBudgetSpec.kt`
- `runtime/context/ContextBudgetReport.kt`

Layer-local reducers should remain where they belong:

- transcript in transcript/context modules
- memory in memory modules
- working state in working-state modules
- skills and bootstrap in their own modules

## Recommended first implementation

### Phase 1

- add model-aware total prompt budget computation
- add per-layer budget specs
- add global retention and reduction ordering
- keep existing local reducers

### Phase 2

- integrate working-state priority
- add emergency-mode shrinking rules
- improve per-layer reports and debug traces

### Phase 3

- integrate provider-native continuation or compaction where available
- restrict native compaction to replayable layers and archived history

## Bottom line

OpenCray does not need Codex-style global compression as the main architecture.

But it does need a stronger global prompt-budget coordinator.

The right design is:

- layered context
- layer-local reducers
- one model-aware global budget coordinator
- stronger compaction mainly for replayable history

not:

- one giant blob that gets globally summarized whenever pressure rises
