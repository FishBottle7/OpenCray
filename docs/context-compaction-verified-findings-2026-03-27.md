# Verified Findings: Context Compaction and Short-Term Memory

Date: 2026-03-27

## Scope

This document records what was re-verified in the repository on 2026-03-27 about:

- how OpenCray currently compacts context
- which concrete limits are enforced in code
- where the current implementation is weaker than Codex / OpenAI native compaction
- why the current design can make the agent forget what it just did on long, tool-heavy tasks

This is a verified implementation note, not a target-state design.

## Short Summary

OpenCray already has real context-pressure handling, but the current mechanism is still mostly host-side text summarization.

The current path is:

1. prompt-local pruning
2. transcript windowing
3. prompt-local compaction summary
4. pre-compaction memory flush
5. durable compaction summary persistence
6. durable compaction summary reinjection

Important boundary:

- OpenCray does not currently have one global token-budget controller that allocates all prompt layers against a model-specific context ceiling.
- Instead, it uses multiple local message-count and character-count budgets, then relies on the provider/model boundary for the real hard context limit.

## Verified OpenCray Flow

For prompt tasks, `AppAgentSessionTaskRuntimeFactory` currently prepares context in this order:

1. seed transcript
2. append the current user input
3. run pre-compaction memory flush
4. run durable compaction
5. build the final session context for prompt assembly

Evidence:

- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushCoordinator.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/compaction/DurableCompaction.kt`

The durable compaction path is not only additive metadata. It can rewrite the stored transcript tail through `SessionTranscriptStore.replace(...)`.

## Verified OpenCray Limits

### Transcript and prompt-local pruning

`ContextPrunerConfig` defaults:

- tool payload rewrite threshold: `2400` chars
- tool payload rewrite threshold: `48` lines
- attachment-like detection threshold: `192` chars
- preview limit: `240` chars
- pruning summary limit: `480` chars

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextPruner.kt`

`TranscriptWindowConfig` defaults:

- active transcript window: `12` messages
- per-message default limit: `2400` chars
- assistant tool-call marker limit inside the window: `480` chars
- tool message limit inside the window: `1600` chars

Derived default behavior:

- background messages are capped to roughly one third of the retained window
- with the default `12`-message window, the default background cap is `4`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/TranscriptWindowBuilder.kt`

### Prompt-local compaction summary

`CompactionPolicy` defaults:

- minimum omitted messages before a summary exists: `1`
- summary limit: `480` chars
- preview limit: `120` chars
- top tool-category entries kept in the summary: `3`

The current summary shape keeps:

- compacted message count
- per-role counts
- terminal-outcome counts
- coarse tool-category counts
- most recent omitted user request preview
- most recent omitted assistant reply preview

It does not preserve a detailed replay of omitted tool calls or tool results.

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/CompactionPolicy.kt`

### Durable compaction

`DurableCompactionPolicy` defaults:

- compact only if omitted messages >= `4`
- keep at most `6` stored durable summary entries
- render at most `4` durable summary entries into prompt context
- total rendered durable compaction text limit: `1200` chars

Persistence shape:

- stored in per-session `runtime-compaction.json`
- older transcript tail can be replaced after compaction

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/compaction/DurableCompaction.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/compaction/FileBackedSessionCompactionStore.kt`

### Memory flush and memory recall

`MemoryFlushPolicy` defaults:

- flush if omitted messages >= `4`
- or flush if omitted chars >= `480`
- merged user text cap: `720` chars
- merged assistant text cap: `720` chars
- tool observations cap: `8`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushPolicy.kt`

`MemoryRecallBudget` defaults:

- max recalled records: `6`
- max recalled chars: `900`
- max recalled records per kind: `2`

Then `ContextManager` narrows injected memory again:

- max injected memory records in prompt: `4`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPolicy.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryRetriever.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextManager.kt`

### Recent observations, bootstrap, and skills

`RecentToolObservationConfig` defaults:

- max rendered observations: `4`
- read observation body cap: `2400` chars / `96` lines
- list observation body cap: `1600` chars / `32` lines
- total observation-layer cap: `7200` chars

Important limitation:

- the rendered observation layer currently covers discovery and delegation-style results such as `Read`, `LS`, `Grep`, `Glob`, `Task`, and `Skills*`
- it does not restore detailed `Write`, `Edit`, `Bash`, `python_exec`, or `Process*` traces into the same observation layer

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/RecentToolObservationSupport.kt`

`BootstrapContextResolverConfig` defaults:

- max chars per file: `1600`
- max total bootstrap chars: `3200`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/bootstrap/BootstrapContext.kt`

Skill prompt-layer defaults:

- visible skills injected: `8`
- visible skill description cap: `120` chars
- active skill body cap: `3200` chars
- active skill permission entries cap: `8`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillInventory.kt`

## What OpenCray Currently Preserves Well

The current system is not weak everywhere. It already preserves several useful things:

- the recent transcript tail
- a readable summary of omitted older messages
- a durable summary of older compacted history
- a bounded set of recalled durable memories
- a bounded set of recent discovery/delegation observations
- runtime metadata about compaction and memory flush for debugging and projection

This is much better than a stateless loop or a "drop old messages and hope" strategy.

## What It Tends To Lose

The current implementation is much weaker at preserving precise procedural state.

### 1. Exact tool parameters and exact result slices

Once older messages leave the active tail, the compacted representation is mostly:

- counts
- coarse categories
- short previews

That means the agent can lose:

- which file offset it read
- which grep pattern it used
- which exact lines proved the conclusion
- which command produced which output fragment

### 2. Detailed mutation and execution traces

The current observation layer is strongest for discovery-style results.

Because `Write`, `Edit`, `Bash`, `python_exec`, and `Process*` are not rendered back as first-class recent observations in the same way, the agent has weaker short-term support for remembering:

- which file it just edited
- which command it just ran
- which execution output justified the next step

This is one of the main reasons the agent can appear to forget what it just did.

### 3. Older but semantically important branch decisions

The transcript window is primarily recency-based with a bounded background-message allowance.

This can discard:

- earlier but important branch rationales
- failed approach history
- "we already tried that" evidence

before the runtime has a stronger semantic summary for those decisions.

### 4. Procedural state is not the same as durable memory

Pre-compaction memory flush writes durable facts and commitments.
That is useful, but it is not a replacement for procedural working memory.

It preserves things like:

- user preference
- project fact
- durable instruction
- task commitment

It does not preserve the full short-lived execution trace of the current investigation.

### 5. Total token pressure is only partially observable

`PromptAssembler` reports estimated token counts per layer, but the current runtime does not enforce one model-aware total prompt ceiling across all layers before sampling.

So current context control is best described as:

- bounded and observable
- but not fully token-aware in the Codex sense

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

## Practical Failure Modes

On long, branching, tool-heavy tasks, the current compact path can surface as:

- repeating repository reads that were already done
- rerunning commands because the exact prior output is no longer available
- forgetting which file was edited most recently
- losing the precise reason for a prior branch decision
- weakening continuation quality after multiple tool-heavy turns even though a high-level summary still exists

So the most likely failure is not "the agent forgets the entire task."
The more common failure is:

- the agent remembers the rough direction
- but forgets the exact local work state needed for clean continuation

## Verified Codex Difference

Compared with Codex / OpenAI native compaction, the current OpenCray gap is structural.

Codex-side references:

- `https://github.com/openai/codex`
- `https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/codex.rs`
- `https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/compact.rs`
- `https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/compact_remote.rs`
- `https://raw.githubusercontent.com/openai/codex/main/codex-rs/protocol/src/openai_models.rs`
- `https://developers.openai.com/api/docs/guides/compaction`

Verified difference at a high level:

- Codex uses model-aware token accounting for auto-compact decisions
- Codex can use provider-native compaction paths for OpenAI routes
- OpenAI native compaction yields opaque provider-managed compaction state rather than only host-authored text summaries
- OpenCray currently compacts into readable host-side summary text and then rewrites the retained transcript tail locally

That difference matters most when:

- the task is long
- the task branches
- the task depends on precise procedural continuation
- many tool results matter, not only the final high-level conclusion

## Bottom Line

OpenCray already has meaningful context compaction.

But the current form is still better described as:

- message-window management
- bounded prompt-layer budgeting
- host-side summary compaction

not as:

- full token-aware, model-aware, provider-native auto compaction

As of 2026-03-27, this is the main reason the system can still forget what it just did on long tool-heavy tasks even though it no longer behaves like a stateless fresh-prompt loop.

## Recommended design follow-up

The recommended structural fix for the short-term procedural gap is:

- add a distinct `Working State layer`
- add a model-aware global context budget coordinator above the existing layer-local reducers

See:

- `docs/working-state-layer-design.md`
- `docs/codex-claude-balanced-context-management-plan.md`
