# OpenCray Soul / Memory Gap Analysis With Hermes Reference

Last updated: 2026-04-21

## Purpose

This note records:

- what the current OpenCray `soul` and `memory` plans are aiming for
- what is already implemented versus still missing in the live app path
- what Hermes is actually doing in local source, and which parts are useful reference for OpenCray

This is not a feature-alignment plan with Hermes.
Hermes is only a reference point for a few runtime boundaries and operating patterns.
The target is to finish and strengthen OpenCray's own richer `memory` and `soul` system.

## Internal docs reviewed

- `docs/memory-design.md`
- `docs/multi-agent-runtime-design.md`
- `docs/memory-soul-image-reference-design.md`
- `docs/working-state-layer-design.md`
- `docs/context-management-remaining-work-checklist.md`
- `docs/subagent-runtime-plan.md`

## Hermes local source reviewed

Primary comparison source:

- `D:\codes\Opensource\hermes-agent-main`

Code paths reviewed:

- `D:\codes\Opensource\hermes-agent-main\tools\memory_tool.py`
- `D:\codes\Opensource\hermes-agent-main\agent\memory_provider.py`
- `D:\codes\Opensource\hermes-agent-main\agent\memory_manager.py`
- `D:\codes\Opensource\hermes-agent-main\agent\prompt_builder.py`
- `D:\codes\Opensource\hermes-agent-main\hermes_state.py`
- `D:\codes\Opensource\hermes-agent-main\tools\session_search_tool.py`
- `D:\codes\Opensource\hermes-agent-main\hermes_constants.py`
- `D:\codes\Opensource\hermes-agent-main\hermes_cli\profiles.py`

Local docs reviewed as supporting context:

- `D:\codes\Opensource\hermes-agent-main\website\docs\user-guide\features\memory.md`
- `D:\codes\Opensource\hermes-agent-main\website\docs\user-guide\features\honcho.md`
- `D:\codes\Opensource\hermes-agent-main\website\docs\developer-guide\architecture.md`

The Hermes comparison below is now based on local source inspection first. The local docs are only supporting material.

## Current OpenCray baseline

The important clarification is that OpenCray is not missing a memory system in the abstract. It is already ahead of Hermes in memory semantics, but it is still behind Hermes in runtime boundary clarity on the live path.

### What is already real in OpenCray

1. Durable memory is structured, typed, and already on the live path.
   - `PersonalizationLocalStore` persists `memory.json`.
   - `ChatMemoryIngestionCoordinator` already performs post-turn extraction, stewardship, task-commitment maintenance, interaction-preference projection, relationship-event writes, and pre-compaction memory flush.
   - `MemoryRetriever`, `MemoryPromptLayer`, `MemoryCorpusProjector`, `MemorySearchService`, `memory_search`, and `memory_get` are already implemented.

2. Runtime soul is already more than a flat prompt block.
   - `WorkspaceSoulProfileStore` persists and parses a real `SOUL.md`.
   - `MemoryBackedSoulProfileResolver` already overlays durable memory-backed preferences and relationship state onto base soul.
   - personalization save/load/reset and debug projection already use typed soul fields.

3. Multi-agent storage groundwork already exists below the live runtime boundary.
   - `AgentRegistryStore`, `AgentPathResolver`, `AgentBootstrapService`, `AgentConfigStore`, and `AgentSoulProfileStore` already exist.
   - agent bootstrap can already create `agents/<agentId>/private/SOUL.md`, `workspace/`, `chat-local-state/`, and `personalization-local-state/`.

4. Working-state persistence is partly implemented already.
   - `ChatSessionLocalStore` can already persist `WorkingState`.
   - `ChatSessionBackedWorkingStateStore` already exists.

This means the current gap is not "invent memory and soul from zero". The real gap is that the live app/runtime still assembles and runs these capabilities as one global agent.

## Current gaps in the live path

### 1. Live runtime is still effectively single-agent

The approved multi-agent design says `agentId` must be a real storage and runtime isolation boundary.

Current live assembly still does this instead:

- `OpenCrayRuntimeContextDependencies.kt` creates one `PersonalizationLocalStore.fromContext(appContext)`
- `OpenCrayRuntimeContextDependencies.kt` creates one `ChatSessionLocalStore.fromContext(appContext)`
- the live path still uses one fixed `workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(appContext) }`
- `InProcessOpenCrayRuntimeOwner.kt` still threads those global stores into the runtime factory

This means:

- created agents exist in storage and bridge APIs
- but the active runtime still does not switch its memory store, soul store, workspace root, or session store by `activeAgentId`
- the current agent system is bootstrap-ready, not runtime-complete

### 2. Base soul authority on the live path is still public workspace `SOUL.md`

The approved multi-agent design explicitly says base soul should move to:

`agents/<agentId>/private/SOUL.md`

Current live app path still loads and writes soul through `WorkspaceSoulProfileStore` against the public workspace root.

This leaves several plan-level gaps open:

- base soul is still tool-visible in the normal workspace
- runtime is still anchored to workspace-root `SOUL.md`
- private base-soul isolation is not yet the live source of truth

### 3. Durable memory is still global app-local personalization state

The approved direction is per-agent memory under:

`agents/<agentId>/personalization-local-state/`

The live app still uses a single app-level `personalization-local-state/memory.json`.

This means:

- long-term preference and relationship state are still global in the live runtime
- agent switching cannot yet produce true soul/memory isolation
- the existing agent bootstrap tree is not yet the live durable memory root

### 4. Agent registry and bootstrap are not yet connected to runtime containerization

OpenCray already has:

- `AgentRegistryStore`
- `AgentBootstrapService`
- `AgentPathResolver`
- `AgentSoulProfileStore`

But it does not yet have the runtime side that the approved design calls for:

- `AgentRuntimeContainer`
- `AgentRuntimeContainerRegistry`
- `AgentHostRuntimeFactory`

Without that container layer, `activeAgentId` remains mostly a host/UI selection value instead of the boundary that owns:

- memory
- soul
- session history
- queue snapshots
- run records
- transcript/supplement/compaction stores

### 5. Working-state persistence is not fully wired on the main live path

The working-state design is meant to preserve short-term procedural continuity that should not be dumped into durable memory.

Important current state:

- `ChatSessionLocalStore` can already persist `WorkingState`
- `ChatSessionBackedWorkingStateStore` already exists
- but `AppAgentSessionTaskRuntimeFactory` still defaults to `InMemoryWorkingStateStore`
- and the main in-process runtime owner does not wire the chat-backed working-state store into the live runtime factory

So the live path still falls short of the intended long-task continuity model:

- working state exists as a design and partial implementation
- but restart-safe procedural continuity is not fully enabled on the main path

### 6. Searchable session memory is still weaker than it should be

OpenCray already persists chat sessions, transcripts, and session-side working data, but it still lacks a first-class session-memory surface that is clearly separate from durable memory.

That leaves an architectural gap relative to the approved direction:

- searchable session recall is not a first-class runtime contract
- durable memory still has to carry more continuity burden than it should
- the system does not yet have a clean "search past sessions, summarize, and inject only what matters" layer

### 7. Image-reference runtime access is only partially landed

The image-reference plan is broader than the currently shipped path.

What exists:

- durable promotion services for memory evidence images
- durable promotion services for soul visual identity assets
- encoding and decoding helpers for memory and soul image references

What is still missing from the approved design:

- explicit runtime tools like `view_memory_image_reference(memory_id, ref_id)`
- explicit runtime tools like `view_soul_reference_image(ref_id)`
- full private-agent-root routing for soul assets on the live path

### 8. Memory flush and compaction are still inline maintenance, not an isolated worker model

OpenCray already has:

- pre-compaction memory flush
- durable compaction summaries
- trace projection for both

But the remaining-work checklist still correctly calls out missing pieces:

- no separate durable compaction worker
- no dedicated flush task
- no mid-turn pressure path
- no model-switch safeguard

This is less of a soul/memory data-model gap and more of a lifecycle/orchestration gap, but it still affects real long-run memory quality.

## Hermes memory/session/profile system: verified from local source

### 1. Hermes keeps built-in durable memory intentionally small and bounded

`tools/memory_tool.py` confirms that the built-in durable memory is file-backed, profile-scoped, and intentionally small:

- stored under `get_hermes_home() / "memories"`
- split into `MEMORY.md` and `USER.md`
- bounded to `2200` chars for memory and `1375` chars for user profile
- maintained through one `memory` tool with `add`, `replace`, and `remove` actions
- `replace` and `remove` identify entries by `old_text` substring matching

The same file also scans candidate writes for prompt-injection and exfiltration patterns before acceptance, because these memories are injected into the system prompt.

### 2. Hermes freezes built-in memory at session start

`tools/memory_tool.py` also shows that the built-in store loads from disk through `load_from_disk()` and captures a `_system_prompt_snapshot` at load time.

That means:

- system-prompt memory is frozen for the duration of the session
- mid-session memory writes persist to disk immediately
- those writes do not change the injected prompt block until the next session starts

This is not an incidental limitation. It is a deliberate operational choice that keeps the prompt prefix stable and bounded.

### 3. Hermes separates long-term memory from session continuity

`hermes_state.py` confirms that Hermes stores persistent session state in:

`get_hermes_home() / "state.db"`

That store is SQLite-backed and uses FTS5 for message search.

`tools/session_search_tool.py` then adds a separate `session_search` surface that:

- searches past session messages through FTS5
- summarizes matching sessions instead of dumping raw transcripts
- excludes the current session lineage so the agent does not re-query context it already has

So Hermes does not try to make `MEMORY.md` and `USER.md` carry session continuity. Long-term memory and session recall are explicitly different systems.

### 4. Hermes has an explicit additive memory-provider seam

`agent/memory_provider.py` defines a real provider interface with lifecycle methods such as:

- `initialize`
- `system_prompt_block`
- `prefetch`
- `sync_turn`
- `get_tool_schemas`
- `handle_tool_call`
- `shutdown`

It also defines optional hooks such as:

- `on_session_end`
- `on_pre_compress`
- `on_memory_write`
- `on_delegation`

`agent/memory_manager.py` then makes the boundary concrete:

- the built-in provider is always active
- only one external provider is allowed in addition
- prefetch recall is merged across providers
- recall is wrapped into `<memory-context>` blocks for prompt injection
- provider failures are treated as non-fatal

This is a clean backend seam even though the built-in memory model itself is simple.

### 5. Hermes treats profile isolation as a live root boundary

`agent/prompt_builder.py` loads `SOUL.md` from:

`get_hermes_home() / "SOUL.md"`

not from the current project workspace.

`hermes_constants.py` resolves the main storage paths from `HERMES_HOME`, and `hermes_cli/profiles.py` confirms that named profiles get isolated directories with their own:

- config
- `SOUL.md`
- memory
- sessions
- gateway/process state
- optional per-profile subprocess `HOME`

In other words, Hermes profile isolation is not just a bootstrap convention. It is the live runtime contract.

## What Hermes does better right now

For the specific soul/memory problem, Hermes is ahead of OpenCray in these practical ways:

1. `HERMES_HOME` is already the live root boundary for soul, memory, sessions, config, and process state.
2. Session history search is explicitly separated from durable memory.
3. Long-term memory is operationally bounded and prompt-cache friendly.
4. Memory backend seams are explicit and pluggable.

## What OpenCray can selectively borrow from Hermes

The main thing worth borrowing is Hermes's boundary clarity, not its tiny built-in memory format.
OpenCray should stay opinionated about its own typed memory model and soul layer.

### 1. Turn `agentId` into a real live root boundary

OpenCray should make `activeAgentId` the owner of:

- private soul
- durable memory
- session store
- working state
- queue/run records
- workspace root

This is the single most important lesson from Hermes.

Status note:

- this slice already has a branch in progress
- the remaining design task is to finish the live runtime cutover, not to redesign the idea from zero

### 2. Split soul, durable memory, working state, and session search into distinct layers

OpenCray should keep these concerns separate:

- `soul` for stable persona and identity policy
- typed durable memory for cross-session facts and relationship state
- working state for short-term procedural continuity
- searchable session history for "what happened before" recall

Hermes is useful because `MEMORY.md` and `USER.md` do not try to carry session continuity. That continuity lives in `state.db` plus `session_search`.

### 3. Make prompt-injected long-term memory stable and operationally bounded

Hermes's frozen built-in memory snapshot is a good operational pattern even if OpenCray should not copy the exact file model.

The reusable principle is:

- writes can land immediately
- but the prompt-injected long-term memory block should stay small, stable, and predictable
- per-turn recall can still exist, but it should not cause large prompt-prefix drift without clear policy

### 4. Add a clean memory-provider seam

OpenCray should keep its stronger typed memory semantics, but separate:

- memory policy
- memory backend
- recall or sync hooks

Hermes's provider lifecycle is a useful reference for this because it makes built-in and external memory implementations pluggable without changing the rest of the agent loop.

### 5. Add explicit safety review for anything that flows back into the system prompt

Hermes screens built-in memory writes because those entries are injected into the system prompt.

OpenCray should apply the same principle anywhere content can be promoted back into high-trust prompt slots, especially:

- soul edits
- durable memory summaries
- compaction summaries
- session summaries

## What OpenCray already does better than Hermes

OpenCray should not copy Hermes blindly, because OpenCray is already ahead in some important ways:

1. OpenCray durable memory is structured and typed instead of just file notes.
   - kinds, scopes, status, timestamps, typed extensions, relationship state, and interaction-preference state already exist.

2. OpenCray already has deterministic post-turn write policy and stewardship.
   - candidate extraction, resolution, reaffirmation, expiry, and flush are already explicit.

3. OpenCray already has bounded automatic recall plus explicit memory tools.
   - `memory_search` and `memory_get` are already closer to a runtime retrieval contract than Hermes's tiny built-in memory files.

4. OpenCray already has a richer soul layer.
   - base soul, memory-backed overlay, relationship gates, and adaptive preference projection are all richer than Hermes's built-in memory model.

So the right lesson is not "switch OpenCray to Hermes memory".
The right lesson is "finish OpenCray's own memory and soul architecture, while borrowing only the specific runtime boundaries that Hermes already operationalizes."

## What OpenCray should not copy from Hermes

Two things should stay explicit in the design:

1. OpenCray should not regress to `MEMORY.md` plus `USER.md` as its primary durable memory model.
2. OpenCray should not force all long-term memory to behave like frozen flat file notes just because Hermes uses that pattern for prefix stability.

OpenCray's typed durable memory, stewardship, and soul overlay model are stronger than Hermes's built-in memory. The goal is to import the boundary discipline, not to flatten the model.

## Sharpest gap summary

The clearest direct comparison is:

- Hermes live soul root: `HERMES_HOME/SOUL.md`
- OpenCray live soul root: workspace-root `SOUL.md`

- Hermes live durable memory: `HERMES_HOME/memories/MEMORY.md` and `USER.md`
- OpenCray live durable memory: one app-global `personalization-local-state/memory.json`

- Hermes session recall: `state.db` plus FTS5 plus `session_search`
- OpenCray session recall: session persistence exists, but there is no first-class searchable session-memory layer yet

- Hermes backend seam: built-in provider plus one additive external provider
- OpenCray backend seam: richer memory semantics exist, but there is still no equally clean provider boundary

So the main OpenCray gap is not memory richness. It is the missing live cutover from "one app-global runtime with agent scaffolding around it" to "per-agent runtime roots with separate durable memory, private soul, and session recall."

## Recommended next slices

### 1. Finish the live cutover to agent-aware roots

Priority path:

- `activeAgentId` must drive path resolution
- runtime assembly must stop using one global `PersonalizationLocalStore`
- runtime assembly must stop using one global `ChatSessionLocalStore`
- live workspace root must become `agents/<agentId>/workspace/`

This cutover needs to happen in the live runtime assembly path, not only in bootstrap helpers.

Status:

- there is already a branch in progress for turning `agentId` into a real root boundary
- the design focus should now be on finishing the live-path wiring across all dependent stores and runtime assembly points

### 2. Move live base soul loading to private agent soul

Priority path:

- stop treating workspace-root `SOUL.md` as the live base-soul authority
- use `AgentSoulProfileStore` as the live base-soul entry point
- keep public workspace tools away from the base soul document

### 3. Wire persistent working state into the live runtime

Priority path:

- replace the default in-memory working-state store on the live path
- use `ChatSessionBackedWorkingStateStore`
- keep working state separate from durable memory

### 4. Add a separate searchable session-memory surface

Hermes's strongest reusable idea is not its tiny long-term memory files.
It is the separation between:

- durable memory
- searchable session history

OpenCray should add a first-class session-search surface on top of transcript/session stores instead of expecting durable memory to carry procedural continuity.

### 5. Formalize a memory-provider seam

OpenCray already has strong runtime memory semantics.
What it does not yet have is a clean backend/provider seam comparable to Hermes's built-in versus external-provider split.

That would allow:

- current structured local memory as the default provider
- later experimental semantic or external memory providers
- clearer separation between memory policy and memory backend

### 6. Add safety checks for prompt-bound memory and summary surfaces

Priority path:

- define which memory and summary artifacts are allowed to flow into system-prompt slots
- add validation or scanning on writes to those surfaces
- keep low-trust session content from being promoted into high-trust identity or memory blocks without review

### 7. Keep OpenCray's richer semantics, but do not copy Hermes's tiny-memory model

The best reuse direction is:

- copy Hermes's boundary clarity
- keep OpenCray's typed durable memory model
- keep OpenCray's richer soul overlay model
- finish the live runtime cutover that makes those richer semantics real per agent

## Bottom line

The main OpenCray gap is no longer missing `memory` or missing `soul` as concepts.

The real gap is:

- runtime isolation still lags behind the approved multi-agent storage design
- live soul authority still lags behind the approved private-soul design
- working-state continuity still lags behind the intended long-task model
- searchable session memory is still weaker than it should be

Hermes is a useful reference, not a target state.
It matters here only because the local source already shows three boundaries working clearly:

- long-term memory versus session history
- profile isolation as a real root boundary
- pluggable memory backends

OpenCray should keep its richer typed memory and soul semantics, finish the storage/runtime cutover, and strengthen the searchable session layer without flattening the model toward Hermes.

## Retrieval note

Primary Hermes evidence for this comparison was verified from the local source tree at:

- `D:\codes\Opensource\hermes-agent-main`

The earlier GitHub-web-only comparison is superseded by this local-source-based revision.
