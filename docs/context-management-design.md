# OpenCray Context Management Design

Last updated: 2026-03-12

## Status

Code-backed architecture design draft

## Purpose

This document defines a production-grade context management design for OpenCray.

It does four things:

1. Describe what OpenCray really does today, based on current code.
2. Explain what is missing for a complete agent-grade context system.
3. Compare OpenCray with two mature reference implementations studied from source code:
   - `D:\codes\Opensource\openclaw`
   - `D:\codes\Opensource\AstrBot`
4. Propose a complete OpenCray-native context architecture, with concrete design patterns, data flow, module boundaries, and implementation guidance.

This document is intentionally deeper than the earlier audit and roadmap documents. It is meant to be the design reference for future runtime work.

## Related documents

- `docs/agent-runtime-audit.md`
- `docs/agent-runtime-task-list.md`
- `docs/agent-runtime-issues.md`
- `docs/agent-runtime-roadmap.md`
- `docs/agent-runtime-reference-guide.md`
- `docs/design-p0-live-queue-persistence.md`
- `docs/design-p0-session-runtime-manager.md`
- `docs/design-p0-prompt-layer-architecture.md`

## Research Method

This version is based on local code inspection, not only public docs.

### OpenCray code inspected

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/AgentLoop.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/MemoryRecord.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/SoulRecord.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillLoader.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillValidator.kt`

### OpenClaw code inspected

Local clone path:

- `D:\codes\Opensource\openclaw`

Primary files examined:

- `src/auto-reply/reply/session.ts`
- `src/auto-reply/reply/agent-runner.ts`
- `src/auto-reply/reply/agent-runner-memory.ts`
- `src/auto-reply/reply/memory-flush.ts`
- `src/config/sessions/types.ts`
- `src/config/sessions/store.ts`
- `src/config/sessions/session-file.ts`
- `src/config/sessions/transcript.ts`
- `src/agents/bootstrap-files.ts`
- `src/agents/system-prompt.ts`
- `src/agents/pi-embedded-runner/system-prompt.ts`
- `src/agents/pi-embedded-runner/run/attempt.ts`
- `src/agents/pi-embedded-runner/compact.ts`
- `src/context-engine/types.ts`
- `src/context-engine/legacy.ts`
- `src/agents/tools/memory-tool.ts`
- `src/agents/skills/workspace.ts`
- `src/agents/pi-extensions/context-pruning/pruner.ts`
- `src/agents/subagent-spawn.ts`
- `src/agents/subagent-registry.ts`
- `src/agents/subagent-announce.ts`
- `src/acp/session.ts`
- `src/acp/translator.ts`

### AstrBot code inspected

Local clone path:

- `D:\codes\Opensource\AstrBot`

Primary files examined:

- `astrbot/core/db/po.py`
- `astrbot/core/conversation_mgr.py`
- `astrbot/core/astr_main_agent.py`
- `astrbot/core/astr_agent_tool_exec.py`
- `astrbot/core/agent/context/manager.py`
- `astrbot/core/agent/context/compressor.py`
- `astrbot/core/agent/context/truncator.py`
- `astrbot/core/agent/runners/tool_loop_agent_runner.py`
- `astrbot/core/skills/skill_manager.py`
- `astrbot/core/astr_main_agent_resources.py`
- `astrbot/core/knowledge_base/kb_mgr.py`
- `astrbot/core/knowledge_base/retrieval/manager.py`
- `astrbot/core/subagent_orchestrator.py`
- `astrbot/core/pipeline/process_stage/method/agent_sub_stages/internal.py`
- `astrbot/core/pipeline/process_stage/method/agent_sub_stages/third_party.py`
- `astrbot/core/agent/runners/dify/dify_agent_runner.py`
- `astrbot/core/agent/runners/coze/coze_agent_runner.py`
- `astrbot/core/agent/runners/dashscope/dashscope_agent_runner.py`
- `astrbot/core/agent/runners/deerflow/deerflow_agent_runner.py`
- `astrbot/builtin_stars/astrbot/long_term_memory.py`

## Terminology

### Context

Everything actually visible to the model during a single run.

Examples:

- system prompt
- current task prompt
- replayed session history
- recalled memory
- injected skill instructions
- tool observations

Context is bounded and ephemeral.

### Memory

Durable information stored outside the current run and later recalled.

Memory is not the same thing as the current transcript.

### Soul

Stable identity and behavioral profile for the agent.

Soul is not a bag of notes. It should shape behavior through structured fields.

### Session

A durable conversational thread with stable identity, transcript history, runtime state, and maintenance lifecycle.

### Skill

A reusable instruction package that may add context, narrow tools, or alter execution strategy.

### Bootstrap files

Agent- or workspace-local files that are injected into startup context or used as structured runtime references.

## Executive Summary

OpenCray currently has a real but minimal request-scoped context loop.

It already has:

- a functioning turn loop in `OpenCrayAgentRuntime`
- a serial session queue abstraction in `SessionQueue`
- local session transcript persistence in `ChatSessionLocalStore`
- local soul and memory persistence primitives
- a skills package loader and validator

It does not yet have a complete context operating model.

What is missing:

- session-scoped runtime ownership
- bounded reconstruction of stored history into runtime context
- explicit named prompt layers
- memory write and recall
- structured soul injection
- runtime-visible executable skill capsules
- context compression and pruning
- workspace bootstrap files
- sub-agent context modes
- context observability and budgeting

### Short conclusion

OpenCray is currently:

- a valid minimal agent loop
- with persistence around the loop
- but without a mature context system connecting those stores back into execution

OpenClaw and AstrBot both solve this with explicit context assembly stages. They differ in style, but they agree on the core pattern:

1. persist durable session history
2. reconstruct a bounded working set before each run
3. inject stable identity layers separately from volatile history
4. treat memory, skills, and subagents as explicit context components
5. apply compression or pruning when the context budget is under pressure

That is the pattern OpenCray still lacks.

## Current OpenCray Context Management

## What OpenCray actually sends today

### System prompt assembly

`AppShellActivity.resolvedAgentSystemPrompt()` builds the runtime system prompt from three pieces:

- base prompt from `LlmSettingsState.systemPrompt` or `OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT`
- session policy text from `ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_ID`
- personalization overlay from the current soul-like summary

Code evidence:

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt:1172-1188`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt:493-496`

This is already a layered prompt in spirit, but it is not modeled as explicit runtime layers. It is still plain string concatenation in the activity.

### Turn prompt assembly

`OpenCrayAgentRuntime.renderPrompt()` builds the user-visible runtime prompt from:

- protocol instructions
- JSON output schema examples
- tool definitions
- task metadata
- current in-memory transcript

Code evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:253-282`

### Runtime transcript scope

`OpenCrayAgentRuntime.executePromptTask()` starts every run with exactly one transcript entry:

- current user input

Then it appends:

- assistant tool call markers
- tool observations

Code evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:66-75`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:134-148`

This means OpenCray has a real thought-action-observation loop, but it is bounded to the current request.

## What OpenCray persists today but does not feed back correctly

### Chat session transcript

`ChatSessionLocalStore` persists sessions and messages, including a seeded system message template.

Code evidence:

- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt:342-370`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt:373-380`

However, the stored conversation is not reconstructed into runtime prompt context before a new run starts.

### Soul and memory

`PersonalizationLocalStore` uses real soul and memory stores, but current runtime usage is limited to flattening soul-like preferences into one summary block. Memory records are not part of runtime behavior.

### Skills

`OpenCrayToolDispatcher` exposes `skills_list` and `skill_read`, but the Android live path does not pass app-managed skill roots into runtime.

Code evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:119-129`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:205-214`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:487-492`

### Queue state

The live Android path still creates a fresh loop per prompt and uses `InMemorySessionQueueSnapshotStore`.

Code evidence:

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt:1033-1037`

That is a major reason OpenCray still behaves like a per-request agent instead of a session runtime.

## Current OpenCray architectural reality

The current context model is:

```text
stored chat history  ----\
stored soul summary   ----+--> Activity builds one systemPrompt string
user settings         ----/

current user input --> OpenCrayAgentRuntime
                     -> per-run transcript starts from current input
                     -> tool calls/results accumulate in memory
                     -> final answer returned

stored memory, stored skills, stored transcript
exist beside runtime but do not actively shape execution
```

This is the central design problem.

## Current OpenCray weaknesses

### 1. Context is request-scoped, not session-scoped

Each send action creates a fresh runtime and fresh loop.

Impact:

- shallow continuity
- no stable per-session runtime owner
- no recoverable in-flight state

### 2. Stored transcript and runtime transcript are separate systems

The app stores chat history for UI and editing, but runtime history begins from the new user turn.

Impact:

- weak conversational continuity
- no bounded replay policy
- no place to add compaction later

### 3. Soul is flattened too early

Soul is rendered as one descriptive paragraph before runtime sees it.

Impact:

- no structured persona boundaries
- no mapping from soul to tool policy, verbosity, escalation, or tone

### 4. Memory is storage-only

Memory persistence exists, but runtime never recalls it and production flows do not write it intentionally.

Impact:

- storage without intelligence

### 5. Skills are metadata, not execution context

OpenCray can validate and read skills, but not attach them as instruction capsules, filtered toolsets, or sub-runtimes.

### 6. No context pressure management

There is no:

- transcript windowing
- compaction summary
- tool-result pruning
- token budgeting by layer

### 7. No context trace

OpenCray cannot answer:

- what exact context was assembled for this run
- how many tokens each layer consumed
- why a message was pruned or omitted

## Mature Patterns Observed in OpenClaw and AstrBot

## OpenClaw: code-backed patterns

OpenClaw is the cleaner reference for a full context pipeline.

### Pattern A: session metadata and transcript are deliberately split

OpenClaw persists session metadata in `sessions.json` and turn-by-turn transcript in per-session `.jsonl` files.

Observed data split:

- `SessionEntry` stores metadata such as:
  - `sessionId`
  - `sessionFile`
  - `updatedAt`
  - `compactionCount`
  - `memoryFlushAt`
  - `skillsSnapshot`
  - `systemPromptReport`
  - subagent lineage and ACP metadata
- actual conversational turns live in `agents/<agentId>/sessions/<sessionId>.jsonl`

Code evidence:

- `D:\codes\Opensource\openclaw\src\config\sessions\types.ts`
- `D:\codes\Opensource\openclaw\src\config\sessions\store.ts`
- `D:\codes\Opensource\openclaw\src\config\sessions\session-file.ts`
- `D:\codes\Opensource\openclaw\src\config\sessions\transcript.ts`

Important lesson for OpenCray:

- transcript history and runtime/session control state should not be forced into one storage shape

### Pattern B: session resolution happens before runtime assembly

`initSessionState()` resolves or creates the session, chooses the transcript path, and handles reset or archival behavior before the runtime even begins assembling the prompt.

Code evidence:

- `D:\codes\Opensource\openclaw\src\auto-reply\reply\session.ts`

Important lesson:

- session identity is not a UI concern
- it is part of runtime context initialization

### Pattern C: bootstrap files are a first-class input source

`resolveBootstrapContextForRun()` loads workspace bootstrap files, filters them by session mode, allows hooks to mutate them, and then converts them into injected context files.

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\bootstrap-files.ts`

Key behavior:

- bootstrap file discovery
- context mode filtering such as `full` vs `lightweight`
- hook override stage before final injection
- per-file and total character budgets

This is not a prompt string hack. It is a pipeline stage.

### Pattern D: system prompt assembly is explicit and sectioned

`buildEmbeddedSystemPrompt()` delegates to `buildAgentSystemPrompt()`, which composes stable sections such as:

- skills guidance
- memory recall guidance
- time and runtime info
- documentation references
- messaging guidance
- context files

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\system-prompt.ts`
- `D:\codes\Opensource\openclaw\src\agents\system-prompt.ts:20-187`

Important implication:

- stable prompt sections are not fused with volatile conversation history
- subagents can request `PromptMode = minimal`
- the system prompt is a structured product, not a blob

### Pattern E: context assembly is its own lifecycle stage

In `run/attempt.ts`, OpenClaw assembles runtime state in ordered phases:

1. resolve skills prompt
2. resolve bootstrap context
3. build embedded system prompt
4. prepare session manager and compaction safeguards
5. let the context engine assemble a bounded message set
6. let the context engine add system prompt additions
7. after the turn, let the context engine ingest and compact

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\run\attempt.ts:1072-1109`
- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\run\attempt.ts:1279-1309`
- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\run\attempt.ts:1377-1404`
- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\run\attempt.ts:1712-1731`
- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\run\attempt.ts:2193-2233`

This is the most important pattern OpenCray should borrow.

### Pattern F: context management is pluggable

OpenClaw exposes a `ContextEngine` interface with lifecycle methods:

- `bootstrap`
- `ingest`
- `ingestBatch`
- `assemble`
- `compact`
- `afterTurn`
- `prepareSubagentSpawn`
- `onSubagentEnded`

Code evidence:

- `D:\codes\Opensource\openclaw\src\context-engine\types.ts`

This is a mature design because:

- context is treated as a subsystem, not buried inside the runner
- compaction is not special-cased string logic
- subagent context behavior has a formal contract

### Pattern G: memory affects runtime in three different ways

OpenClaw memory is not one feature. It has three runtime paths:

1. memory recall tools during the run:
   - `memory_search`
   - `memory_get`
2. plugin-backed durable memory extensions
3. automatic pre-compaction memory flush runs

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\tools\memory-tool.ts`
- `D:\codes\Opensource\openclaw\src\auto-reply\reply\agent-runner-memory.ts`
- `D:\codes\Opensource\openclaw\src\auto-reply\reply\memory-flush.ts`
- `D:\codes\Opensource\openclaw\src\agents\system-prompt.ts:38-63`

Important design choice:

- long-term memory is not blindly injected every turn
- memory is recalled on demand and flushed intentionally before compaction pressure

### Pattern H: skills affect prompt assembly and session metadata

Skills in OpenClaw are resolved into a skill prompt fragment and a `skillsSnapshot` stored on the session entry.

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\skills\workspace.ts`
- `D:\codes\Opensource\openclaw\src\config\sessions\types.ts`

Important lesson:

- the runtime should know which skills were visible for that run

### Pattern I: subagents get their own session lineage and bounded context

OpenClaw subagents receive:

- their own session key
- their own transcript file
- spawn lineage metadata
- reduced bootstrap injection
- subagent-specific prompt mode and control scope

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\subagent-spawn.ts`
- `D:\codes\Opensource\openclaw\src\agents\subagent-registry.ts`
- `D:\codes\Opensource\openclaw\src\agents\subagent-announce.ts`
- `D:\codes\Opensource\openclaw\src\context-engine\types.ts:123-144`

This is the correct model for subagents:

- child runs should not inherit the full parent context by default
- context transfer must be explicit and bounded

### Pattern J: pruning and compaction are separate

OpenClaw distinguishes:

- prompt-time pruning of context payload
- compaction of session history into summaries or reduced form
- store-level pruning and transcript archival for old sessions

Code evidence:

- `D:\codes\Opensource\openclaw\src\agents\pi-extensions\context-pruning\pruner.ts`
- `D:\codes\Opensource\openclaw\src\agents\pi-embedded-runner\compact.ts`
- `D:\codes\Opensource\openclaw\src\config\sessions\store.ts`

That separation matters. Pruning is temporary and prompt-local. Compaction is durable and session-level. Store pruning is retention management.

## AstrBot: code-backed patterns

AstrBot is less unified than OpenClaw, but still shows several mature patterns that OpenCray can reuse.

### Pattern A: local session identity and local conversation history are separate

AstrBot uses:

- `event.unified_msg_origin` as session identity
- `sel_conv_id` in preferences to map session to active conversation
- `ConversationV2.content` in DB as the authoritative local message history

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\db\po.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\conversation_mgr.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\utils\shared_preferences.py`

Important lesson:

- session and transcript are related but not identical concerns

### Pattern B: conversation history is reconstructed before the run

AstrBot retrieves the current conversation and assigns `req.contexts = json.loads(conversation.history)`.

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent.py:1117-1119`
- `D:\codes\Opensource\AstrBot\astrbot\core\conversation_mgr.py`

This is the simplest pattern OpenCray still lacks.

### Pattern C: system prompt assembly happens in multiple feature stages

AstrBot mutates `req.system_prompt` through several explicit steps:

- prompt prefix
- persona instructions
- persona begin dialogs
- skills prompt
- knowledge base hints or knowledge-base tool injection
- safety prompt
- local or sandbox runtime prompts
- tool-call protocol prompt
- live-mode prompt

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent.py:301-355`
- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent.py:630-662`
- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent.py:1138-1205`

AstrBot is still string-based, but it at least has explicit feature stages.

### Pattern D: current user input is appended after historical contexts are loaded

In AstrBot, the final in-memory runner context is:

- stored `req.contexts`
- current user message from `ProviderRequest.assemble_context()`
- one prepended system message from `req.system_prompt`

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\provider\entities.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\tool_loop_agent_runner.py`

Important lesson:

- persisted history and current input are assembled deliberately, not conflated at storage time

### Pattern E: context compression is owned by a dedicated component

AstrBot has a `ContextManager` that applies:

1. max-turn truncation
2. token-based compression
3. fallback halving truncation if compression is still too large

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\agent\context\manager.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\tool_loop_agent_runner.py:126-138`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\tool_loop_agent_runner.py:366-372`

Important implication:

- context budgeting lives below the high-level agent builder
- the runner always processes context before LLM invocation

### Pattern F: compression becomes durable only when history is saved back

AstrBot compresses in-memory runner context first, then persists the mutated result back into conversation history through `_save_to_history()`.

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\pipeline\process_stage\method\agent_sub_stages\internal.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\context\compressor.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\context\truncator.py`

Important lesson:

- compression policy and persistence policy should be separate decisions

### Pattern G: skills are injected as prompt inventory, not hidden implementation detail

AstrBot explicitly builds a skills section that tells the model:

- available skills
- where the corresponding `SKILL.md` lives
- that it must read the file before using the skill
- progressive disclosure rules

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\skills\skill_manager.py:138-210`
- `D:\codes\Opensource\AstrBot\astrbot\core\skills\skill_manager.py:299-370`

This is very close to what OpenCray should do in its first skill-runtime phase.

### Pattern H: personas alter both system prompt and context prefix

AstrBot personas can:

- prepend persona prompt into `system_prompt`
- inject `begin_dialogs` into `req.contexts`
- filter available skills
- filter or widen available tools

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent.py:327-370`

This is the right general lesson for OpenCray soul design:

- stable personality is not only text
- it can shape context and capability selection

### Pattern I: subagents are bounded runtimes with dedicated handoff context

AstrBot handoff execution constructs a reduced child runtime with:

- subagent-specific system prompt
- optional `begin_dialogs`
- dedicated toolset
- dedicated provider override

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_agent_tool_exec.py:266-307`

AstrBot also has a wake-back pattern for background subagent completion, where the parent session is re-entered with a compact summary of the background result.

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_agent_tool_exec.py:388-399`
- `D:\codes\Opensource\AstrBot\astrbot\core\astr_agent_tool_exec.py:490-520`

### Pattern J: knowledge and long-term memory are feature-level injections

AstrBot supports:

- knowledge-base retrieval into `system_prompt`
- knowledge-base query tool injection
- a built-in "long-term memory" feature that is actually plugin-scoped in-memory chat memory

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\astr_main_agent_resources.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\knowledge_base\kb_mgr.py`
- `D:\codes\Opensource\AstrBot\astrbot\builtin_stars\astrbot\long_term_memory.py`

Important lesson:

- recalled knowledge must enter runtime explicitly
- but not every memory-like feature belongs in the durable core transcript model

### Pattern K: there are two context architectures in AstrBot

AstrBot's internal runner path is the real local context architecture:

- `ConversationManager`
- `ConversationV2.content`
- `build_main_agent()`
- `ToolLoopAgentRunner`
- `_save_to_history()`

Third-party runners such as Dify, Coze, DashScope, and DeerFlow mostly delegate continuity to remote thread or conversation ids.

Code evidence:

- `D:\codes\Opensource\AstrBot\astrbot\core\pipeline\process_stage\method\agent_sub_stages\internal.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\pipeline\process_stage\method\agent_sub_stages\third_party.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\dify\dify_agent_runner.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\coze\coze_agent_runner.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\dashscope\dashscope_agent_runner.py`
- `D:\codes\Opensource\AstrBot\astrbot\core\agent\runners\deerflow\deerflow_agent_runner.py`

Important lesson for OpenCray:

- do not let external-provider continuity hide the need for a local context architecture

## OpenCray versus mature references

## What OpenCray already has

- a usable core loop
- a serial queue abstraction
- a local transcript store
- basic soul and memory stores
- skills parsing and validation
- tool dispatcher with workspace safety boundaries

## What OpenCray lacks relative to OpenClaw

| Area | OpenCray today | OpenClaw pattern |
| --- | --- | --- |
| Session runtime ownership | One loop per prompt | One durable session runtime + session files |
| History reconstruction | No bounded replay | Session-backed message reconstruction |
| Prompt assembly | Activity string concatenation | Sectioned prompt builder |
| Bootstrap files | None | Formal bootstrap resolution pipeline |
| Context lifecycle | In runtime class only | Dedicated context engine lifecycle |
| Memory recall | None | Memory tools + prompt guidance + pre-compaction flush |
| Compaction | None | Durable compaction lifecycle |
| Pruning | None | Prompt-time pruning extensions |
| Subagent context | None | Minimal prompt modes + spawn lifecycle hooks |
| Session metadata richness | Minimal | `systemPromptReport`, `skillsSnapshot`, `compactionCount`, lineage |
| Context trace | Minimal | Explicit prompt/context reporting |

## What OpenCray lacks relative to AstrBot

| Area | OpenCray today | AstrBot pattern |
| --- | --- | --- |
| Stored history replay | No | `req.contexts = conversation.history` |
| Persona layering | Flattened summary | Prompt + begin dialogs + tool filtering |
| Skills visibility | Read-only tool path | Skill inventory prompt + runtime selection |
| Context compression | None | `ContextManager` before LLM call |
| Knowledge injection | None | KB injection or KB tool |
| Subagent handoff | None | Dedicated handoff runtimes |
| Local versus remote continuity | Not separated | Internal runner and third-party runner separated explicitly |

## The key architectural gap

The difference is not that OpenCray lacks "more prompt text".

The real difference is that OpenClaw and AstrBot both have a context assembly pipeline, while OpenCray still has scattered context fragments.

## Design goals for OpenCray

OpenCray should adopt the following goals.

### Goal 1: session-first runtime

The unit of context ownership must be the chat session, not the individual prompt.

### Goal 2: explicit context layers

Every contribution to runtime context must have a name, source, budget, and traceability.

### Goal 3: distinct durable stores versus ephemeral working set

OpenCray must separate:

- what is stored durably
- what is selected into the next run

### Goal 4: progressive disclosure for memory and skills

Do not inject everything every turn. Retrieve or activate only what is relevant.

### Goal 5: bounded child context

Subagents should inherit a purpose-built capsule, not the entire parent prompt.

### Goal 6: compaction before crisis

OpenCray must manage budget pressure proactively instead of waiting for context window failures.

### Goal 7: observable assembly

Every run should be inspectable after the fact.

## Proposed OpenCray Target Architecture

## Source-of-truth stores

OpenCray should formalize seven persistent or semi-persistent sources.

1. Session metadata store
2. Session transcript store
3. Session queue state store
4. Soul profile store
5. Memory store
6. Skills registry and installed skill roots
7. Workspace bootstrap source

### Proposed ownership

```text
Session metadata store
  owns:
  - session id
  - active model / mode / config overrides
  - compaction summary pointers
  - runtime status

Session transcript store
  owns:
  - ordered turns
  - tool calls
  - tool observations
  - edit lineage if needed

Session queue store
  owns:
  - in-flight task ids
  - pending / running / cancelled states
  - restart recovery metadata

Soul store
  owns:
  - tone
  - style
  - boundaries
  - escalation rules
  - self-description

Memory store
  owns:
  - durable facts
  - preferences
  - project notes
  - tasks / commitments

Skills registry
  owns:
  - installed skills
  - enable/disable state
  - metadata
  - tool policy

Workspace bootstrap source
  owns:
  - agent-local files like AGENTS.md / SOUL.md / TOOLS.md / PROJECT.md
```

## Runtime context layers

OpenCray should assemble context in explicit layers.

### Layer 1: runtime identity and core policy

Source:

- baked-in OpenCray base prompt
- provider/runtime invariants
- non-negotiable safety invariants

Role:

- define the agent's operating contract

### Layer 2: soul profile

Source:

- structured soul profile from persistence

Role:

- tone
- defaults
- escalation style
- verbosity bias
- collaboration style

### Layer 3: session directives

Source:

- session policy template
- per-session settings
- chat mode
- current model and tool policy

Role:

- describe how this session differs from defaults

### Layer 4: session working context

Source:

- bounded reconstruction of recent transcript
- optional compaction summaries

Role:

- conversational continuity
- local working memory

### Layer 5: retrieved durable context

Source:

- memory recall
- knowledge retrieval
- project bootstrap snippets when required

Role:

- durable continuity
- externalized recall

### Layer 6: skill and execution capsules

Source:

- selected skill inventory
- activated skill instructions
- subagent capsules

Role:

- give the model the correct execution playbook for the current task

### Layer 7: task protocol

Source:

- current user input
- runtime action protocol
- tool schema

Role:

- immediate step decision making

## Full assembly pipeline

```mermaid
flowchart TD
    A[Session Runtime Manager] --> B[Load session metadata]
    A --> C[Load session transcript]
    A --> D[Load soul profile]
    A --> E[Resolve active skills]
    A --> F[Resolve workspace bootstrap files]
    A --> G[Resolve recalled memory and knowledge]

    B --> H[Context Assembler]
    C --> H
    D --> H
    E --> H
    F --> H
    G --> H

    H --> I[Build named layers]
    I --> J[Apply layer budgets]
    J --> K[Assemble final system prompt]
    J --> L[Assemble bounded message window]
    K --> M[LLM request]
    L --> M
    M --> N[Tool loop]
    N --> O[Post-turn ingestion]
    O --> P[Transcript append]
    O --> Q[Memory write candidates]
    O --> R[Compaction / pruning coordinator]
```

## OpenCray target data flow per turn

```text
user sends message
  -> SessionRuntimeManager(sessionId)
  -> load session state
  -> reconstruct bounded working transcript
  -> resolve soul layer
  -> resolve session policy layer
  -> resolve bootstrap snippets
  -> resolve memory recall
  -> resolve skill inventory / active skill capsule
  -> assemble prompt layers + message window
  -> run tool loop
  -> append assistant/tool turns to transcript
  -> evaluate memory writes
  -> evaluate compaction/pruning
  -> persist updated session snapshot
```

## Proposed design patterns

## Pattern 1: Session Runtime Manager

OpenCray needs one runtime owner per active session.

Responsibilities:

- receive user submit/cancel/retry/resume
- own `AgentLoop`
- own queue store binding
- own context assembly requests
- own event fan-out to UI
- own restart recovery

Suggested module:

- `runtime/src/main/kotlin/com/opencray/runtime/session/SessionRuntimeManager.kt`

Without this, context will remain fragmented across activity lifecycle and transient loops.

## Pattern 2: Context Assembler

This should be the equivalent of OpenClaw's prompt plus context-engine assembly stages.

Responsibilities:

- accept a `ContextAssemblyRequest`
- resolve all layers
- produce:
  - `systemPrompt`
  - `messageWindow`
  - `contextReport`

Suggested module:

- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextAssembler.kt`

Suggested outputs:

```kotlin
data class AssembledContext(
  val systemPrompt: String,
  val messages: List<RuntimeMessage>,
  val report: ContextAssemblyReport,
)
```

## Pattern 3: Transcript Window Builder

This component rebuilds the in-run message window from persisted history.

Responsibilities:

- load session transcript
- preserve recent turns
- inject compaction summaries if present
- drop or collapse oversized tool results
- output a bounded working set

Suggested module:

- `runtime/src/main/kotlin/com/opencray/runtime/history/TranscriptWindowBuilder.kt`

This is the minimal feature OpenCray must add first.

## Pattern 4: Structured Soul Resolver

Soul should become typed runtime data.

Suggested fields:

- `tone`
- `verbosity`
- `userRelationshipStyle`
- `riskTolerance`
- `toolUseBias`
- `escalationRules`
- `forbiddenBehaviors`
- `collaborationPreferences`

Suggested modules:

- `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulProfile.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulProfileResolver.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/soul/SoulPromptRenderer.kt`

### Why this matters

If soul remains one summary paragraph, it cannot be governed, diffed, tested, or selectively applied.

## Pattern 5: Memory recall and write loop

OpenCray needs both directions:

- write candidate memories after turns
- recall relevant memories before turns

### Memory write path

```mermaid
flowchart TD
    A[Completed turn] --> B[MemoryCandidateExtractor]
    B --> C[Policy filter]
    C --> D[MemoryWriter]
    D --> E[MemoryStore]
```

### Memory recall path

```mermaid
flowchart TD
    A[Current user input + session state] --> B[MemoryRetriever]
    B --> C[Rank and budget]
    C --> D[Memory context layer]
    D --> E[ContextAssembler]
```

Suggested modules:

- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryCandidateExtractor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryWriter.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryRetriever.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/memory/MemoryPromptLayer.kt`

### Initial memory taxonomy

- `user_preference`
- `project_fact`
- `durable_instruction`
- `task_commitment`
- `environment_fact`

OpenCray should start with deterministic writes only. Do not start with free-form model-authored memory dumps.

## Pattern 6: Skill capsules instead of raw list-only skills

OpenCray should move through three maturity levels.

### Level 1: skill inventory in prompt

Equivalent to AstrBot and OpenClaw.

The model sees:

- skill name
- description
- whether it should be used
- how to read it

### Level 2: activated skill capsule

When one skill is selected:

- its `SKILL.md` or resolved instructions become a dedicated context layer
- tool permissions are narrowed if the skill requires it

### Level 3: executable skill runtime

Skills can request:

- dedicated inline execution context
- filtered tools
- special prompt scaffold
- subagent execution

Suggested modules:

- `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillInventoryLayer.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillCapsuleResolver.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillExecutionCoordinator.kt`

## Pattern 7: bootstrap context files

OpenCray should introduce agent/workspace bootstrap files in a controlled way.

Recommended first bootstrap files:

- `AGENTS.md`
- `SOUL.md`
- `TOOLS.md`
- `PROJECT.md`

Rules:

- each file has a max per-file size
- all files have a total combined size budget
- the assembler can run in:
  - `full`
  - `lightweight`
  - `none`

This is directly inspired by OpenClaw's `resolveBootstrapContextForRun()`.

## Pattern 8: compaction and pruning split

OpenCray must not treat compaction and pruning as the same mechanism.

### Pruning

Prompt-local reduction for the current run.

Examples:

- collapse old tool outputs
- remove bulky repeated command output
- replace old screenshots or attachments with markers

### Compaction

Durable session-level rewrite or summarization.

Examples:

- summarize older turns into a session summary block
- preserve decision history while reducing token footprint

Suggested modules:

- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextPruner.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/compaction/CompactionCoordinator.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/compaction/SessionSummaryStore.kt`

### Practical rule

Start with pruning before compaction.

Why:

- pruning is cheaper
- easier to test
- lower product risk

## Pattern 9: subagent context modes

OpenCray should explicitly support at least three child-context modes.

### `minimal`

Include:

- base runtime rules
- minimal soul
- selected child task
- selected skill capsule if relevant

Do not include:

- full parent transcript
- full bootstrap bundle
- unrelated memory

### `delegated`

Include:

- base runtime rules
- soul essentials
- parent summary
- selected prior findings
- task-specific tool restrictions

### `mirrored`

Only for rare cases. Close to parent context, but still bounded.

### Spawn flow

```mermaid
flowchart TD
    A[Parent runtime] --> B[SubagentContextBuilder]
    B --> C[Select mode: minimal / delegated / mirrored]
    C --> D[Extract allowed layers]
    D --> E[Apply child budgets]
    E --> F[Create child runtime]
    F --> G[Child result summary]
    G --> H[Parent post-child ingestion]
```

This design is closer to OpenClaw's context-engine spawn lifecycle than to ad-hoc prompt copying.

## Pattern 10: context trace and budgeting

Every run should emit a context report.

Suggested report contents:

- which layers were present
- size in chars and estimated tokens
- pruned or omitted records
- retrieved memories
- activated skill
- compaction summary id if used

Suggested modules:

- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextAssemblyReport.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/trace/RuntimeTraceEvent.kt`

This is necessary if OpenCray is going to debug memory, prompt, or skill behavior in production.

## Recommended OpenCray reference architecture

```mermaid
flowchart LR
    subgraph Persistence
      A[SessionStore]
      B[TranscriptStore]
      C[QueueSnapshotStore]
      D[SoulStore]
      E[MemoryStore]
      F[SkillRegistry]
      G[BootstrapResolver]
    end

    subgraph Runtime
      H[SessionRuntimeManager]
      I[ContextAssembler]
      J[TranscriptWindowBuilder]
      K[SoulResolver]
      L[MemoryRetriever]
      M[SkillCapsuleResolver]
      N[ContextPruner]
      O[CompactionCoordinator]
      P[PromptRenderer]
      Q[AgentLoop]
    end

    A --> H
    B --> J
    D --> K
    E --> L
    F --> M
    G --> I
    J --> I
    K --> I
    L --> I
    M --> I
    I --> N
    N --> P
    P --> Q
    Q --> O
    Q --> B
    Q --> E
    H --> Q
    C --> H
```

## Proposed implementation interfaces

The exact package split may change, but the design should converge on something close to this.

### Context request and result

```kotlin
data class ContextAssemblyRequest(
  val sessionId: String,
  val userInput: String,
  val taskMetadata: Map<String, String>,
  val mode: ContextMode,
)

data class ContextAssemblyResult(
  val systemPrompt: String,
  val messages: List<RuntimeMessage>,
  val report: ContextAssemblyReport,
)
```

### Layer contract

```kotlin
interface ContextLayerProducer {
  fun produce(request: ContextAssemblyRequest): ContextLayerResult
}

data class ContextLayerResult(
  val name: String,
  val priority: Int,
  val textBlocks: List<String> = emptyList(),
  val messageBlocks: List<RuntimeMessage> = emptyList(),
  val estimatedTokens: Int = 0,
)
```

### Suggested initial producers

- `BaseIdentityLayerProducer`
- `SoulLayerProducer`
- `SessionPolicyLayerProducer`
- `TranscriptHistoryLayerProducer`
- `MemoryLayerProducer`
- `SkillInventoryLayerProducer`
- `ActiveSkillLayerProducer`
- `BootstrapLayerProducer`
- `TaskProtocolLayerProducer`

## Recommended rollout order

This sequence is intentionally pragmatic.

### Phase 0

1. durable session runtime ownership
2. replace in-memory live queue snapshot store
3. inject stored transcript into runtime
4. split prompt assembly into named layers

### Phase 1

5. structured soul profile
6. memory taxonomy and deterministic writes
7. memory recall layer
8. runtime-visible skills inventory
9. active skill capsule injection

### Phase 2

10. context pruning
11. durable compaction summaries
12. bootstrap files
13. subagent context modes
14. context trace

## Guidance by problem area

This section explains what code to read when implementing each feature.

## If implementing transcript reconstruction

Read:

- OpenClaw:
  - `src/auto-reply/reply/session.ts`
  - `src/config/sessions/types.ts`
  - `src/config/sessions/transcript.ts`
  - `src/agents/pi-embedded-runner/run/attempt.ts`
- AstrBot:
  - `astrbot/core/conversation_mgr.py`
  - `astrbot/core/astr_main_agent.py:1117-1119`
  - `astrbot/core/agent/runners/tool_loop_agent_runner.py`

What to copy conceptually:

- load transcript before runtime execution
- turn it into a bounded message list
- keep this logic out of UI code

## If implementing named prompt layers

Read:

- OpenClaw:
  - `src/agents/system-prompt.ts`
  - `src/agents/pi-embedded-runner/system-prompt.ts`
- AstrBot:
  - `astrbot/core/astr_main_agent.py`

What to copy conceptually:

- separate stable sections from volatile history
- keep prompt assembly pure and testable

## If implementing context compression

Read:

- OpenClaw:
  - `src/agents/pi-extensions/context-pruning/pruner.ts`
  - `src/agents/pi-embedded-runner/compact.ts`
  - `src/context-engine/types.ts`
- AstrBot:
  - `astrbot/core/agent/context/manager.py`
  - `astrbot/core/agent/context/compressor.py`
  - `astrbot/core/agent/context/truncator.py`

What to copy conceptually:

- prune before panic
- compaction is durable, pruning is not
- compression belongs near runner invocation

## If implementing memory recall and write

Read:

- OpenClaw:
  - `src/agents/tools/memory-tool.ts`
  - `src/auto-reply/reply/agent-runner-memory.ts`
  - `src/auto-reply/reply/memory-flush.ts`
- AstrBot:
  - `astrbot/builtin_stars/astrbot/long_term_memory.py`
  - `astrbot/core/astr_main_agent_resources.py`

What to copy conceptually:

- do not inject all memory by default
- use recall policy
- start writes from deterministic events

## If implementing skills runtime integration

Read:

- OpenClaw:
  - `src/agents/skills/workspace.ts`
  - `src/agents/system-prompt.ts`
- AstrBot:
  - `astrbot/core/skills/skill_manager.py`
  - `astrbot/core/astr_main_agent.py:336-355`

What to copy conceptually:

- prompt-visible skill inventory
- progressive disclosure
- active-skill capsule separate from inventory
- snapshot the skill view for traceability

## If implementing subagents

Read:

- OpenClaw:
  - `src/context-engine/types.ts`
  - `src/agents/subagent-spawn.ts`
  - `src/agents/subagent-registry.ts`
  - `src/agents/system-prompt.ts`
- AstrBot:
  - `astrbot/core/subagent_orchestrator.py`
  - `astrbot/core/astr_agent_tool_exec.py`

What to copy conceptually:

- formal spawn lifecycle
- purpose-built child context
- parent wake-up with bounded summary

## Non-goals for the first implementation

OpenCray should not do these first:

- full self-modifying soul updates
- arbitrary LLM-authored memory writes
- fully dynamic plugin context engines
- automatic subagent graphs
- multi-source RAG orchestration

Those are later-stage concerns. The immediate gap is simpler:

- reconstruct stored history
- formalize prompt layers
- add memory and skills as first-class context inputs

## Final design conclusion

The correct target for OpenCray is not "a longer system prompt".

The correct target is:

- a session-owned runtime
- a dedicated context assembly subsystem
- typed sources of truth
- layered prompt and message assembly
- bounded retrieval and pruning
- explicit post-turn ingestion

OpenClaw shows the cleaner end-state architecture:

- session metadata plus transcript split
- bootstrap context
- system prompt sections
- pluggable context engine
- memory tools and memory flush
- pruning and compaction split
- subagent context lifecycle

AstrBot shows a more incremental but still useful design:

- conversation history reconstruction
- staged system prompt enrichment
- context manager before LLM call
- skill inventory prompt
- handoff child runtimes
- explicit separation between local continuity and remote-runner continuity

OpenCray should use OpenClaw as the structural model and AstrBot as the practical implementation reference for simpler intermediate steps.

### The most important design decision

OpenCray context management must become a runtime subsystem with its own contracts.

Until that happens, memory, soul, skills, and transcript persistence will continue to exist as adjacent features instead of forming one coherent agent.
