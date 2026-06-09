# Codex Long-Task Workflow Gap Analysis

Updated: 2026-03-31

This document summarizes the currently verified gap between OpenCray and Codex for long, tool-heavy, self-driven tasks.

Important scope guard:

- do not assume `tool calling` is fully "done" just because native tool calls now work on the main path
- do not assume `Responses-native continuation` is fully "done" just because `openai_responses` and `previous_response_id` now exist in the codebase
- the right question is no longer "can OpenCray run an agent loop at all?"
- the right question is "where is the remaining product, protocol, and orchestration gap once the basic loop exists?"
- worktree-class task isolation is currently treated as out of scope for OpenCray's product direction, because the app is not trying to become a general-purpose coding IDE

This analysis supersedes older docs that now understate the current baseline, especially:

- `docs/chat-runtime-ux-parity-plan.md`
- `docs/openclaw-runtime-parity-roadmap.md`
- `docs/agent-runtime-audit.md`

## 1. Terms

### 1.1 `assistant phase`

`assistant phase` is a host/runtime classification for an assistant emission.

Typical Codex-like phases are:

- `commentary`
- `final`

This is not the same thing as hidden private reasoning.

### 1.2 `assistant commentary`

`assistant commentary` is a short, public, user-visible status update such as:

- "Inspecting the repo before editing."
- "I found the Responses route. Next I am checking continuation state."

Its purpose is:

- reduce perceived latency
- show intent before tool execution
- keep long tasks observable
- avoid exposing private reasoning

### 1.3 `final answer`

`final answer` is the terminal user-facing answer for the current task or turn.

In a Codex-like loop:

- commentary can appear before or between tools
- tool calls and tool results are structured
- final closes the run

## 2. Verified OpenCray Baseline

OpenCray is already much closer to Codex than several older internal docs suggest.

### 2.1 Native function tool calling is real, but still not "finished"

The current codebase already supports provider-native structured tool calling for multiple dialects:

- OpenAI chat-completions style tool calls
- Anthropic messages-style `tool_use`
- OpenAI Responses-style `function_call`

Evidence:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

The gateway model now also explicitly supports:

- host function tools
- provider builtin tools
- `toolChoice`
- `parallelToolCalls`
- `previousResponseId`
- `responseApiPreferred`
- recoverable structured `toolCallErrors`

Evidence:

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`

So the correct statement is:

- OpenCray already has native structured tool calling
- OpenCray still runs a dual-protocol world with legacy JSON fallback and recovery logic
- native tool calling should be treated as a strong baseline, not as a completed end state

### 2.2 `openai_responses` and lineage state now exist

The current codebase now explicitly models `openai_responses` as its own protocol:

- `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`

The provider client now explicitly supports:

- `/v1/responses`
- Responses output parsing
- `function_call` parsing
- commentary/final phase extraction from Responses message items
- builtin web search request/observation metadata

Evidence:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`

The runtime also now keeps per-run Responses continuation state:

- `responsesPreviousResponseId`
- `responsesLineageTrusted`
- `responsesPendingMessages`

and can choose between:

- full rebuild
- local delta continuation
- `responses_native` continuation

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

So the correct statement is:

- OpenCray no longer lacks Responses-native continuation entirely
- but Responses support should still not be treated as "fully finished"

### 2.3 Commentary/progress is stronger than before

The provider client now does more than older docs assumed.

Notable improvements:

- Responses message `phase=commentary` is parsed
- provider structured completion now carries `commentaryText`
- runtime consumes that `commentaryText` as commentary-phase assistant output

Evidence:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

So the old statement "provider-native commentary is not carried through the structured path" is no longer accurate.
After the latest cleanup, commentary is now modeled as one continuous first-class lane across provider parsing, runtime actions, transcript/persistence, and UI replay.

### 2.4 Resume, recovery, compaction, and memory stewardship are real

OpenCray already has substantial long-task infrastructure:

- session-scoped runtime ownership
- durable queue snapshots
- prompt checkpoints for approval and resume boundaries
- recovery planning
- managed process reconnect on restore
- pre-compaction memory flush
- durable compaction summaries

Evidence:

- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- `app/src/main/kotlin/com/opencray/app/PromptCheckpointStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/RunRecoveryPlanner.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushCoordinator.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/compaction/DurableCompaction.kt`

This means OpenCray is not a "single fresh loop per prompt" system anymore.

### 2.5 Subagents exist, but in a narrower form than Codex

OpenCray already has:

- a real delegated child-runtime path
- an explicit subagent control plane:
  - `Task`
  - `spawn_agent`
  - `send_input`
  - `wait_agent`
  - `close_agent`
- subagent lifecycle events
- subagent context modes
- resumable subagent approval states
- a session-scoped durable child handle plane
- host/runtime `subAgents` snapshots backed by durable handle state plus replay/checkpoint state

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentContextBuilder.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentResultCompressor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeSubAgentTest.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceHost.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

But this still does not mean Codex-grade parallel orchestration.

The current shape is:

- `spawn_agent` starts a child handle immediately
- child execution can now continue in the background within the same runtime host/process even after the parent run returns `final`
- `wait_agent` waits for that handle to reach its latest stable state later, or resumes it after approval unlocks a paused child
- a later run can harvest that child through `wait_agent`
- session/runtime keepalive now treats live subagents as active work, so idle release does not tear them down while they are still running
- latest child handle state is now durable across runtime/host rebuild through the session handle store
- but that child is still not a detached actor that survives process death or cold restart

That narrower execution model still matters for the remaining gap.

## 3. What Is Still Not Fully Done In Tool Calling And Responses

This section is intentionally separate from the long-task section.

The point is:

- native tool calling exists
- Responses-native continuation exists
- neither should yet be declared complete

### 3.1 The mainline is still dual-protocol, not cleanly single-protocol

OpenCray still teaches and recovers a legacy JSON action protocol in the main runtime path.

That is valuable for robustness, but it has costs:

- prompt complexity stays higher
- protocol recovery logic stays hot
- provider behavior is less uniform
- the model still learns fallback shapes instead of only one clean native shape

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

Compared with Codex, the remaining gap is not "no native tools"; it is "native tools are still sharing the stage with a legacy fallback contract."

### 3.2 Responses support is present, but coverage and trust boundaries still matter

Current Responses support is substantial, but it still has non-trivial edge handling:

- output items must be normalized
- commentary/final/unphased text must be merged
- malformed tool arguments become `toolCallErrors`
- lineage can be invalidated and dropped back to full rebuild

Evidence:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

This means the codebase is correctly handling the fact that Responses is not a trivial "just switch endpoint" change.

The remaining gap versus Codex is not mere endpoint support.
It is long-run confidence, broader coverage, and cleaner end-to-end semantics.

### 3.3 Responses-native continuation is now durable across checkpoints, but the trust model is still narrower than Codex

This section changed materially.

The runtime now persists and restores Responses continuation state through `OpenCrayPromptResumeState`, including:

- `responsesPreviousResponseId`
- `responsesProviderLineageId`
- `responsesLineageTrusted`
- `responsesPendingMessages`
- `localContinuationEnvelope`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayPromptResumeState.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/PromptCheckpointStoreFactory.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/opencray/app/PromptCheckpointStoreFactoryTest.kt`

This means the older conclusion "Responses-native continuation is only in-memory" is no longer correct.

The remaining gap is subtler:

- runtime continuation decisions still key off `responsesPreviousResponseId`
- `responsesProviderLineageId` is persisted, but not used as an independent decision surface
- the current Responses adapter derives `providerLineageId` from `providerResponseId`, rather than from a distinct provider lineage abstraction

So the remaining Codex gap is no longer absence of durable resume state.
It is that lineage trust and continuation policy are still centered on one provider-specific handle, with limited abstraction above it.

### 3.4 Responses full rebuild now round-trips assistant phase end-to-end much more faithfully

This section also changed materially.

The transcript model now has explicit assistant phase support:

- `RuntimeConversationAssistantPhase`
- `assistantPhase` on `RuntimeConversationMessage`
- replay of assistant messages with preserved phase
- replay of `PROGRESS` messages back into assistant `COMMENTARY` messages during gateway rebuild
- a public `OpenCrayAssistantPhaseEvent` contract
- persisted run journal entries stored as `ASSISTANT_PHASE`
- host replay that restores assistant commentary as `OpenCrayAssistantEvent`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptModels.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/opencray/app/RunEventJournalStoreFactoryTest.kt`

This means the older conclusion "Responses full rebuild cannot round-trip assistant phase semantics" is now wrong.

The remaining gap is narrower and mostly about product enforcement:

- assistant-phase is now the only public runtime event lane for commentary/final emissions
- the deprecated `OpenCrayProgressEvent` compatibility wrapper has been removed
- old persisted run-event payloads are no longer migration targets during development
- the remaining internal `progressText` / `Progress` compatibility naming has been removed in favor of `commentaryText` / commentary-phase naming

So the big gap here is no longer event-model absence or commentary naming drift.
The remaining work has moved on to lineage trust, subagent control, and broader orchestration semantics.

### 3.5 Assistant phase is now unified on the mainline

Today the real public assistant lane is centered on:

- `OpenCrayAssistantPhaseEvent`
- `OpenCrayAssistantEvent`
- `OpenCrayAssistantPhase`

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`

The mainline now uses only:

- `OpenCrayAssistantPhaseEvent`
- `OpenCrayAssistantEvent`
- `OpenCrayAssistantPhase`
- persisted `ASSISTANT_PHASE` run events
- replay payloads with `event_kind=assistant_phase`

The tradeoff is explicit:

- development builds no longer preserve backward compatibility with older progress-event persistence shapes

So this is no longer a migration-cleanup gap.
It is now a deliberate development-stage simplification.

### 3.6 Parallel tool calling is real, route-probed, and still conservatively scoped

This section changed materially as well.

OpenCray now has all of the following:

- prompt instructions that explicitly allow multiple tool calls when `parallelToolCallsEnabled=true`
- gateway requests that set `parallelToolCalls=true`
- runtime logic that groups independent tool calls
- concurrent execution via `dispatchPromptToolCallsInParallel(...)`
- a dedicated validation probe that requests two tool calls under `parallelToolCalls=true`
- direct tests proving batched structured tool calls and concurrent execution

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/opencray/app/facade/llm/LlmConfigFacadeTest.kt`

So the older conclusion "parallel tool calling is still serial-first" is no longer correct.

The remaining gap is scope and product policy:

- `canExecuteInParallel(...)` only whitelists read/search style tools such as `workspace_read_file`, `Read`, `Grep`, `Glob`, `WebSearch`, and `WebFetch`
- mutating tools, process tools, and delegation tools are still excluded from parallel execution

Compared with Codex, the remaining gap is not lack of parallel mechanics.
It is that the parallel contract is still conservative and intentionally narrow.

### 3.7 Host tool vs builtin provider tool arbitration is improved, but still a sensitive area

The runtime now supports builtin tools such as provider-native web search and can hide host `WebSearch` when builtin web search is active.

Evidence:

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

That is the right direction.
But this area still deserves ongoing scrutiny because it is exactly where duplicated capability surfaces can confuse both models and users.

In other words:

- the basic arbitration mechanism exists
- the long-term product contract still needs to be tightened

### 3.8 Capability probing is now richer, but not yet exhaustive

OpenCray now has dedicated Responses-oriented probes for:

- continuation viability
- builtin web search viability
- assistant phase viability

It also derives citation support from the builtin web search probe result metadata.

Evidence:

- `app/src/main/kotlin/com/opencray/app/LlmAgentCapabilitySupport.kt`
- `app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`
- `app/src/test/kotlin/com/opencray/app/facade/llm/LlmConfigFacadeTest.kt`

So the older conclusion "capability probing is still generic, not Responses-specific" is now wrong.

The remaining gap is narrower:

- `parallelToolCallsSupported` still comes from the generic control probe
- there is still no dedicated probe that proves multi-call batching behavior under `parallelToolCalls=true`
- recovery behavior after lineage invalidation is still not part of route validation
- some Responses-specific runtime/provider switches still default to optimistic `true` semantics when route metadata is absent, which is convenient for OpenAI-first routes but riskier for partially compatible custom routes

Compared with Codex, the remaining gap is now in depth and completeness of capability validation, not in total absence of Responses-specific probing.

### 3.9 `providerLineageId` is now populated and persisted, but still mostly observational

`providerLineageId` is no longer an empty placeholder.

The current code does all of the following:

- populates it for `openai_responses`
- carries it through `LiteLlmProviderResult` and `LiteLlmGatewayResult`
- persists it into `OpenCrayPromptResumeState`
- restores it into `PromptTurnCursor`
- emits it into result metadata

Evidence:

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayPromptResumeState.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

But it still appears mostly observational:

- the runtime does not currently choose continuation strategy based on `responsesProviderLineageId`
- the current Responses adapter sets `providerLineageId` to `providerResponseId`

So this gap is no longer "missing field."
It is "persisted lineage metadata exists, but a stronger lineage abstraction and decision model are still missing."

### 3.10 The provider adapter surface is becoming richer, and therefore more fragile

The current provider client now covers:

- OpenAI chat-completions
- OpenAI Responses
- Anthropic messages
- commentary/progress
- reasoning text
- citations
- builtin web search observation
- tool call parse diagnostics

Evidence:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`

That is powerful, but it also means the adapter now carries more branching and more normalization burden.

Compared with Codex, one remaining gap is not feature absence but protocol productization:

- clearer capability matrices
- narrower per-dialect adapters
- stronger conformance testing

### 3.11 Test coverage is materially better, but still not fully Codex-grade

This section also needs to be updated.

There is now direct test coverage for:

- approval resume preserving Responses lineage and using `previous_response_id`
- prompt checkpoint serialization of Responses resume fields
- full rebuild replay of assistant phases
- multiple structured tool calls when parallel tool calling is enabled
- actual concurrent execution of a parallel-safe tool batch
- Responses capability validation probes

Evidence:

- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt`
- `app/src/test/kotlin/com/opencray/app/PromptCheckpointStoreFactoryTest.kt`
- `app/src/test/kotlin/com/opencray/app/facade/llm/LlmConfigFacadeTest.kt`

The remaining high-value gaps appear to be:

- invalid-lineage recovery paths after a previously trusted Responses chain breaks
- route validation that proves actual parallel batching support rather than inferring it
- tests where `providerLineageId` changes runtime behavior rather than merely round-tripping through state

For long-task work, these remaining cases still matter.
But the old conclusion that the core Responses/parallel paths were largely untested is no longer accurate.

## 4. The Main Strategic Gap Vs Codex Has Moved Upward, But Section 3 Still Matters

The biggest strategic difference versus Codex is increasingly in long-task product discipline and orchestration.

But section 3 should not be waved away as "just protocol polish." The unresolved lower-level gaps there still directly affect:

- approval/resume reliability
- multi-turn Responses fidelity
- multi-tool efficiency
- observability of commentary across long runs

So the right framing is:

- OpenCray has moved beyond the "basic loop missing" phase
- OpenCray still has protocol-level holes that materially shape long-task behavior
- above that, the larger remaining gap is orchestration maturity

### 4.1 `TodoWrite` is materially stronger now, but still not a full Codex-grade planning surface

OpenCray already had `TodoWrite`, and that baseline has improved since the earlier audit.

The current implementation now has real invariants and runtime closure checks, including:

- unique todo content
- at most one `in_progress`
- `activeForm` allowed only on the active todo
- final-answer interception when an `in_progress` item is still present
- richer result metadata about plan mutations and state transitions

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTodoStore.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/TodoWritePlanInvariantTest.kt`

So the old conclusion that `TodoWrite` lacks basic invariants is no longer correct.

The remaining Codex gap is higher-level planning ergonomics:

- no durable plan history or delta model
- no explicit planner/worker contract
- no richer task graph or dependency structure
- no automatic plan adoption discipline across more agent profiles

### 4.2 Subagents are now cold-restart resumable, but still not independent child actors

The current exposed subagent surface now includes:

- `Task` as a convenience wrapper
- `spawn_agent`
- `send_input`
- `wait_agent`
- `close_agent`

But the execution semantics are still narrower than Codex:

- `spawn_agent` launches a background child worker immediately and that worker can outlive the parent run within the same runtime host/process
- child runtime checkpoints are now persisted back into the durable handle store, not just approval resume state
- cold restart repair now turns checkpointed `BACKGROUND_RUNNING` children into resumable `BACKGROUND_QUEUED` handles instead of blindly failing them
- `wait_agent` joins or harvests that child later, and resumes it after approval when needed
- session/runtime keepalive now also treats live subagents as active work, so background children are not released as idle session noise
- runtime service bootstrap/recovery now auto-submits internal `wait_agent` recovery tasks for detached queued handles, so cold restart can continue child execution from its last durable checkpoint
- the result is still narrower than Codex because this recovery still reuses the parent session queue and `wait_agent` path instead of a truly independent child actor/scheduler

The built-in subagent profiles now include:

- read-only investigator/reviewer styles
- a `worker` profile that can use `Write`, `Edit`, and `MultiEdit` for bounded workspace edits

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentProfile.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeSubAgentTest.kt`

Compared with Codex, the main missing pieces are:

- removing `Task` is not one of the gaps here; keeping it as sugar over the explicit handle control plane is fine
- broader multi-worker orchestration
- a truly independent child scheduler/session instead of recovery through the parent session queue
- stronger route/close/interrupt semantics around independently running children across resumes and restarts

Codex's public subagent docs are ahead here:

- `https://developers.openai.com/codex/subagents`

### 4.3 There is still no worktree-class isolation surface

Product-scope note:

- this remains a real Codex difference
- but it is currently not planned as an OpenCray target capability
- treat it as a non-goal unless the product direction changes toward IDE-style coding workflows

Codex publicly exposes:

- `Local`
- `Worktree`
- `Cloud`

and uses worktree-based isolation for side-by-side tasks and background automations.

Reference:

- `https://developers.openai.com/codex/app/features`

In the current OpenCray runtime code, there is no equivalent worktree orchestration surface.

This matters because long tasks benefit from:

- isolated write scopes
- less interference between runs
- easier background task continuation
- cleaner task-level provenance

### 4.4 Background scheduling and notifications now exist, but detached execution is still narrower than Codex

This section changed materially too.

OpenCray now has:

- `WorkManagerScheduledWorkScheduler`
- alarm-backed trigger registration
- scheduled task spec and run stores
- runtime notification coordination for approvals and terminal outcomes

Evidence:

- `app/src/main/kotlin/com/opencray/app/ScheduledTaskWorkManager.kt`
- `app/src/main/kotlin/com/opencray/app/ScheduledTaskRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/RuntimeNotificationCoordinator.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceHost.kt`

So the old conclusion "there is not yet a WorkManager or scheduler" is no longer correct.

The remaining Codex gap is product scope and detachment level:

- OpenCray still runs through the app-owned runtime host rather than through a broader local/worktree/cloud execution product
- scheduled/background execution is now real, but still narrower than Codex automations and environment isolation

### 4.5 OpenCray compaction is real, with Responses-native remote compaction on supported routes

OpenCray already does:

- pre-compaction memory flush
- durable compaction summaries
- compacted transcript reinjection
- Responses-native remote compaction when the selected route advertises support

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushCoordinator.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/compaction/DurableCompaction.kt`

This is much better than having no compaction.

The remaining gap is not basic compaction anymore. It is narrower: model-switch pressure safeguards, background-safe maintenance jobs, and broader Codex-style execution isolation are still separate plan items.

But compared with Codex / OpenAI Responses guidance, the gap is that OpenCray still primarily relies on host-rendered summary text rather than provider-native opaque compaction artifacts.

Reference:

- `https://platform.openai.com/docs/guides/compaction`

That difference matters most on very long or deeply branching tasks.

Additional verified details as of 2026-03-27:

- OpenCray currently compacts through `ContextPruner` + `TranscriptWindowBuilder` + `CompactionPolicy` + `MemoryFlushCoordinator` + `DurableCompactionCoordinator`
- the default transcript window is still a local `12`-message tail, not a single model-aware total-token allocator
- durable compaction persists host-authored text summaries and can rewrite the retained transcript through `SessionTranscriptStore.replace(...)`
- the recent observation layer is strongest for discovery and delegation traces and is weaker at restoring recent write / edit / command / process history

This means the current likely failure mode is no longer "the system has no compaction."
The more realistic failure mode is:

- the system remembers the rough direction
- but can lose the exact short-term procedural state needed for clean continuation on long, tool-heavy tasks

For the full verified limits, trigger points, and failure modes, see:

- `docs/context-compaction-verified-findings-2026-03-27.md`

Recommended design direction:

- do not replace OpenCray's layered context model with generic global compression
- add a model-aware global context budget coordinator above the current layer-local reducers
- add a distinct working-state layer so short-term procedural continuity does not depend on transcript replay alone

Design references:

- `docs/codex-claude-balanced-context-management-plan.md`
- `docs/working-state-layer-design.md`

### 4.6 Commentary-first behavior now has an explicit prompt contract, but runtime enforcement is still softer than Codex

This section needs a more precise statement than some older notes.

Codex does not rely on runtime behavior alone for the "say what you are about to do before using tools" pattern.
Its open-source prompt layer explicitly teaches that behavior.

Verified Codex prompt evidence:

- `D:/codes/Opensource/codex/codex-rs/core/gpt_5_1_prompt.md`
  - "keep the user updated"
  - "Before the first tool call, give a quick plan"
- `D:/codes/Opensource/codex/codex-rs/core/prompt.md`
  - "Before making tool calls, send a brief preamble"
- `D:/codes/Opensource/codex/codex-rs/core/models.json`
  - GPT-5.4 `base_instructions` carry the same user-update / preamble contract

OpenCray now mirrors that pattern in its own prompt protocol layer.

Current OpenCray prompt contract:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
  - tells the model to keep the user updated with short public commentary
  - tells the model that before the first tool call it should give a brief public plan
  - tells the model that before making tool calls it should send a brief public preamble
  - teaches native-tool routes to put that preamble in assistant text
  - only teaches legacy JSON commentary/tool_call/final shapes when native tool calling is unavailable
- `runtime/src/test/kotlin/com/opencray/runtime/context/PromptAssemblerTest.kt`
  - asserts that those prompt instructions are present

So one older conclusion is no longer accurate:

- OpenCray no longer lacks a Codex-style prompt contract for "quick plan / preamble before tool calls"

The remaining distinction is product choice rather than a blocking architecture gap:

- the contract is intentionally still prompt-shaped rather than runtime-hard-failed
- not auto-failing a first tool call that omits commentary is currently treated as acceptable behavior, not as a priority defect

Codex-like systems are strongest when this behavior converges across:

- prompting
- runtime
- persistence
- UI

OpenCray is now stronger on the first of those layers and materially better on the middle two.
For current planning, this is not being treated as one of the key remaining architecture gaps.

## 5. Priority Order For Closing The Remaining Gap

If the goal is "make OpenCray feel more like Codex on long tasks", the highest-leverage order is now:

1. Tighten Responses lineage semantics beyond `previous_response_id`, and decide whether `providerLineageId` should become a stronger continuation/trust input.
2. Upgrade subagents from host-local background execution into a cold-restart durable orchestration surface.
3. Promote `TodoWrite` from a validated todo list into a richer planning protocol.
4. Decide how much more of the mutating/process tool surface should become parallel-safe.
5. Add worktree-class task isolation if product direction ever expands toward IDE-style coding workflows.
6. Continue hardening scheduler-backed background execution and notification flows rather than treating them as missing.

If only one item can be prioritized, the best next move is usually:

- tighter Responses lineage and continuation trust

If two items can be prioritized, the best pair is usually:

- stronger subagent contracts
- tighter lineage contracts

## 6. What Should No Longer Be Said

The following older conclusions are now misleading:

- "OpenCray has no native tool calling."
- "OpenCray has no Responses-native continuation."
- "OpenCray has no public progress lane."
- "OpenCray has no durable compaction or memory stewardship."
- "OpenCray has no subagent runtime."
- "OpenCray has not encoded Codex-style quick-plan / preamble guidance in its prompt layer."
- "OpenCray's Responses-native continuation is already Codex-grade."
- "OpenCray's parallel tool calling is already fully productized."

Those statements no longer match the current codebase.

The stronger current conclusion is:

- OpenCray has crossed the line from "basic loop missing" into "advanced orchestration still maturing"

## 7. Evidence Index

### Local code evidence

- gateway request model with builtin tools, `previousResponseId`, and recoverable tool-call diagnostics:
  - `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`
- `openai_responses` protocol registration:
  - `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
- OpenAI / Anthropic / Responses provider parsing:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- Responses continuation state and local/native continuation modes:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- prompt resume state:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayPromptResumeState.kt`
- assistant phase events:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
- route capability snapshot and runtime metadata overrides:
  - `app/src/main/kotlin/com/opencray/app/LlmAgentCapabilitySupport.kt`
- route validation probes:
  - `app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`
- todo tool and store:
  - `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
  - `runtime/src/main/kotlin/com/opencray/runtime/AgentTodoStore.kt`
- session runtime ownership:
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- prompt checkpoints:
  - `app/src/main/kotlin/com/opencray/app/PromptCheckpointStoreFactory.kt`
- recovery planner:
  - `app/src/main/kotlin/com/opencray/app/RunRecoveryPlanner.kt`
- memory flush:
  - `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryFlushCoordinator.kt`
- durable compaction:
  - `runtime/src/main/kotlin/com/opencray/runtime/compaction/DurableCompaction.kt`
- subagent profiles and execution snapshots:
  - `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentProfile.kt`
  - `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentResultCompressor.kt`

### Official external references

- GPT-5.1 prompting guide:
  - `https://cookbook.openai.com/examples/gpt-5/gpt-5.1_prompting_guide/`
- OpenAI local shell iterative loop and continuation guidance:
  - `https://developers.openai.com/api/docs/guides/tools-local-shell`
- OpenAI compaction:
  - `https://platform.openai.com/docs/guides/compaction`
- Codex app features:
  - `https://developers.openai.com/codex/app/features`
- Codex subagents:
  - `https://developers.openai.com/codex/subagents`
