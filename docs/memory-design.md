# OpenCray Memory Design

Last updated: 2026-03-16

## Related designs

- `docs/digital-twin-corpus-import-design.md` describes how imported chats and authored corpus should initialize durable `memory` and `soul` without collapsing those two layers into one prompt blob.
- `docs/memory-soul-image-reference-design.md` defines how durable `memory` should retain image references while staying text-first at runtime.

## Status

Planning document, revised to align more closely with OpenClaw memory patterns.

## Goal

Define an OpenCray memory system that is structurally closer to OpenClaw while still fitting OpenCray's current runtime boundaries.

The target is not "store more notes". The target is a memory subsystem with distinct runtime paths:

1. deterministic durable writes after completed turns
2. bounded automatic recall before a turn
3. explicit on-demand memory retrieval tools during a turn
4. pre-compaction memory flush before durable history compaction

Important boundary:

- durable memory is not the same thing as short-term procedural working state
- the current task or run should have its own `Working State layer`
- durable memory should preserve stable facts, not become a noisy log of recent actions

Related design:

- `docs/working-state-layer-design.md`

## OpenClaw patterns that matter

Based on local source inspection:

- `src/agents/tools/memory-tool.ts`
- `src/auto-reply/reply/agent-runner-memory.ts`
- `src/auto-reply/reply/memory-flush.ts`
- `src/agents/system-prompt.ts`

OpenClaw memory is not one mechanism.

It has three runtime-facing paths:

1. `memory_search` and `memory_get` for on-demand recall during the run
2. durable memory content that lives outside the current transcript
3. pre-compaction memory flush runs that preserve durable notes before history is compacted

The important behavior is:

- long-term memory is not blindly injected every turn
- the agent is told to search memory first when answering prior-work questions
- snippet reads stay small after search
- memory flush is gated by context pressure
- memory flush writes append-only durable notes and should no-op cleanly when there is nothing worth storing

## Boundary rules for OpenCray

- `runtime/memory/*` owns memory semantics
- `runtime/context/ContextManager.kt` owns prompt budgeting and final allocation only
- `runtime/context/ContextManager.kt` must not become the semantic selector for which memories exist or rank highest
- `runtime/soul/*` owns effective runtime soul resolution

This means:

- memory ranking and filtering belong in `MemoryRetriever`
- memory writes belong in `MemoryCandidateExtractor`, `MemoryPolicy`, and `MemoryWriter`
- memory-tool search/read behavior belongs in memory-specific runtime/tooling code
- `ContextManager` may cap already-ranked memory for prompt budget reasons, but that cap is allocation pressure, not memory policy

## Target architecture

### 1. Structured source of truth

OpenCray should keep the structured `MemoryStore` as the authoritative source of truth.

That store should continue to hold:

- typed kind
- scope
- status
- evidence source
- session/workspace linkage
- confirmation timestamps
- optional typed preference extensions

This is a deliberate difference from OpenClaw's file-first storage shape. OpenCray already has a typed persistence layer, so the closer OpenClaw alignment should happen at runtime behavior, not by discarding structured storage.

### 2. Automatic bounded recall

Before each turn, OpenCray should continue to perform automatic bounded recall for high-value durable context.

This path is best for:

- durable instructions
- user preferences
- active task commitments
- project facts that clearly match the current request

This path should stay:

- bounded
- deterministic enough to test
- traceable
- secondary to fresh user instructions

It should not be asked to preserve:

- the exact recent file-edit sequence
- the exact recent command sequence
- active branch-decision state for the current investigation

Those belong in a separate working-state layer.

### 3. On-demand memory tools

OpenCray should add an explicit memory-tool surface similar to OpenClaw:

- `memory_search`
- `memory_get`

These tools are for questions like:

- what did we decide earlier
- what preference did the user set
- what task was left open
- what date, person, path, or prior conclusion matters here

The model should not rely only on automatic prompt injection for these cases. It should be able to search memory explicitly during the run and then fetch only the needed snippet.

#### Planned OpenCray shape

OpenCray should expose a projected memory corpus, not raw `memory.json`.

That projection can be materialized or virtual, but it should behave like a small searchable memory surface:

- `MEMORY.md`
- `memory/YYYY-MM-DD.md`
- optional projected category/session/workspace slices when needed

The critical behavior is:

- search first
- read narrowly second
- keep injected snippet size small
- make sources inspectable in trace/debug views

### 4. Pre-compaction memory flush

Before durable compaction runs, OpenCray should support a dedicated memory flush stage.

This stage should:

- run only when context pressure or transcript-size pressure justifies it
- preserve durable information that should outlive the upcoming compaction
- write append-only durable memory notes
- avoid rewriting bootstrap/reference files
- no-op cleanly when there is nothing worth storing

This is closer to OpenClaw's `memory-flush.ts` and `agent-runner-memory.ts`.

OpenCray does not need to copy the exact file names or prompt wording, but it should copy the lifecycle pattern:

- detect pressure
- run dedicated flush logic before compaction
- mark that the current compaction cycle already flushed
- keep flush traceable

### 5. Deterministic writes remain the foundation

OpenClaw-like memory tools and flush behavior should not replace deterministic writes.

OpenCray should keep deterministic writes as the canonical way that runtime behavior becomes durable memory.

The first write set remains:

- `user_preference`
- `project_fact`
- `durable_instruction`
- `task_commitment`

That write set is still durable memory, not working-state state.
If a piece of information only matters for the active task's short-term continuation, it should usually stay in working state unless memory policy explicitly promotes it.

## Planned runtime paths

### Path A: post-turn deterministic write

```text
completed turn
  -> MemoryCandidateExtractor
  -> MemoryPolicy
  -> MemoryWriter
  -> MemoryStore
```

### Path B: pre-turn automatic bounded recall

```text
user input + session/workspace identity
  -> MemoryRetriever
  -> ranked recalled records
  -> MemoryPromptLayer
  -> ContextManager allocates final prompt space
```

### Path C: in-run on-demand memory recall

```text
model decides memory is needed
  -> memory_search(query)
  -> projected memory corpus search
  -> memory_get(path, from, lines)
  -> narrow snippet injected through tool observation
```

### Path D: pre-compaction memory flush

```text
context pressure detected
  -> MemoryFlushCoordinator
  -> dedicated flush run / flush task
  -> append durable notes to memory projection
  -> record flush metadata for this compaction cycle
  -> durable compaction may continue
```

## Module plan

### Already aligned or partially implemented

- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPolicy.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryCandidateExtractor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryWriter.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryRetriever.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPromptLayer.kt`

### Planned additions to move closer to OpenClaw

- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryCorpusProjector.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemorySearchService.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemorySearchTool.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryGetTool.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushCoordinator.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushPolicy.kt`

The exact class names can change. The important split is:

- structured write/read policy
- projected searchable memory corpus
- runtime tool surface
- pre-compaction flush lifecycle

## Prompt contract

OpenCray should adopt OpenClaw's basic memory prompt rule:

- do not blindly trust automatic injected memory for all prior-work questions
- when the question is about prior work, decisions, dates, people, preferences, or todos, the runtime should make explicit memory retrieval available and nudge the model to use it

In OpenCray terms:

- automatic bounded recall remains for default continuity
- memory tools become the explicit verification path for prior-work questions
- citations/source references should remain optional and product-surface aware

## Recommended rollout

### Step 1

Keep deterministic writes and automatic bounded recall as the stable base.

### Step 2

Add projected memory corpus plus `memory_search` / `memory_get`.

### Step 3

Add prompt guidance telling the model when to use memory tools instead of guessing from partial recall.

### Step 4

Add pre-compaction memory flush with one-flush-per-compaction-cycle safeguards.

### Step 5

Expose one coherent trace covering:

- memory writes
- automatic recall
- tool-based memory retrieval
- pre-compaction memory flush

## Intentional differences from OpenClaw

OpenCray should stay different in these places:

- structured `MemoryStore` remains the source of truth instead of switching to file-first memory as the primary store
- deterministic writes remain the default durable-write path
- `ContextManager` remains an allocator/budget owner, not a memory policy engine

Those differences are deliberate. The goal is OpenClaw-like runtime behavior, not a literal storage-model clone.
