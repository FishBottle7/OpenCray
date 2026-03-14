# Agent Runtime Audit

Audit date: 2026-03-12

## Scope

This document summarizes a repository-level audit of OpenCray's current agent implementation. The goals are:

1. Describe what the project already has today.
2. Identify what is still missing before OpenCray can be called a complete agent runtime.
3. Compare the current design to OpenClaw using OpenClaw's official documentation as of 2026-03-12.
4. Audit the current prompt and system-prompt architecture.

This is a static code audit of the repository plus a documentation comparison against OpenClaw official docs. It is not a claim that every path has been executed on-device in production.

## Sources

### Local code inspected

- `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`
- `app/src/main/kotlin/com/opencray/app/PersonalizationLocalStore.kt`
- `core/src/main/kotlin/com/opencray/core/contracts/AgentContracts.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/AgentLoop.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/ModeGate.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillLoader.kt`
- `skills/src/main/kotlin/com/opencray/skills/SkillValidator.kt`
- `app/src/main/kotlin/com/opencray/app/AppSkillsStorage.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/store/SessionStoreQueueSnapshotStore.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/store/file/JsonFileStores.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/MemoryRecord.kt`
- `persistence/src/main/kotlin/com/opencray/persistence/model/SoulRecord.kt`

### External docs verified on 2026-03-12

- OpenClaw Agent Runtime: https://docs.openclaw.ai/concepts/agent
- OpenClaw Agent Workspace: https://docs.openclaw.ai/concepts/agent-workspace
- OpenClaw Memory: https://docs.openclaw.ai/concepts/memory
- OpenClaw System Prompt: https://docs.openclaw.ai/concepts/system-prompt
- OpenClaw Hooks: https://docs.openclaw.ai/automation/hooks
- OpenClaw Agent Bootstrapping: https://docs.openclaw.ai/start/bootstrapping
- OpenClaw Sub-Agents: https://docs.openclaw.ai/tools/subagents
- OpenClaw Tools: https://docs.openclaw.ai/tools

## Executive Summary

OpenCray is no longer just a stub. It already has a real minimum agent core:

- a task contract
- a serial session queue
- a multi-turn LLM and tool loop
- a unified tool dispatcher
- local persistence primitives for session, memory, and soul
- a skills loader and validator

However, it is still not a complete agent runtime in the stronger sense implied by systems like OpenClaw.

The biggest gaps are:

- no production memory write and recall loop
- only partial soul integration
- no full session-context injection from stored chat history
- no true skill execution engine
- no persistent agent session wiring in the Android app
- no complete approval loop across all mutating tools
- no sub-agent orchestration
- no hook or bootstrap architecture comparable to OpenClaw
- a very small prompt architecture compared to mature agent runtimes

Short version:

- OpenCray has a working agent skeleton.
- OpenCray does not yet have a complete agent operating system.

## Current Agent Runtime: What Already Exists

### 1. Core orchestration exists

OpenCray already has the main structural pieces expected from an agent host:

- `AgentTask`, `ExecutionResult`, and task lifecycle contracts in `core/contracts`.
- `AgentLoop` as a thin facade over `SessionQueue`.
- `SessionQueue` implementing serial FIFO execution, retry, cancellation, stop/resume, and snapshot support.
- `SessionTaskRuntime` as the execution abstraction.

This means the project has a real orchestration backbone rather than only UI placeholder state.

### 2. Multi-turn LLM and tool loop exists

`OpenCrayAgentRuntime` already implements a minimal but real thought-action-observation loop:

- start with the user task input
- build a prompt that includes tool definitions
- call the LLM
- parse model output into either `final` or `tool_call`
- dispatch the tool call
- append the tool observation back into transcript
- continue until final answer or limit exhaustion

This is important because the previous repository state only suggested scaffolding. The current code has a real runtime loop.

The loop is still intentionally narrow:

- model output is constrained to JSON objects
- supported actions are only `final` and `tool_call`
- there is no planner/reflection layer
- there is no native `skill_execute` action type

### 3. Unified dispatcher exists

`OpenCrayToolDispatcher` is a real, centralized dispatcher and not just a loose collection of capability modules. It registers and dispatches:

- `workspace_list_files`
- `workspace_read_file`
- `workspace_write_file`
- `workspace_move_file`
- `workspace_delete_file`
- `command_exec`
- `python_exec`
- `skills_list`
- `skill_read`
- `mcp_list_servers`

This is already a meaningful milestone. The repository now has a single runtime surface the model can use.

### 4. App wiring exists, but only for one-shot runs

The Android app does run the agent runtime from chat:

- it builds a runtime in `AppShellActivity`
- creates a single `PROMPT` task
- submits that task into a fresh `AgentLoop`
- immediately drains the queue until idle
- writes the final text back into the chat UI

This means the app does not merely host demo text. It can run a real prompt through a real runtime.

The limitation is architectural:

- every prompt starts a fresh loop
- the queue store is in-memory for the app's live path
- nothing about the agent session persists as an active runtime object after the answer is returned

## What Is Still Missing

### 1. Memory is not a runtime mechanism yet

The repository has `MemoryRecord` and `JsonFileMemoryStore`, but that is only storage infrastructure.

What is missing:

- no production code writes durable memory after a conversation
- no production code reads memory before or during a run
- no retrieval policy exists
- no query, ranking, tag-filter, top-k, recency, or compaction policy exists
- no distinction exists between daily memory and durable memory
- no model-facing memory tool exists

In practical terms, OpenCray has a memory file format, not a memory system.

### 2. Soul is only partially integrated

`SoulRecord` and `PersonalizationLocalStore` are real, and the app does load and save soul-like user preference state.

What is already wired:

- preset, custom label, and custom guidance are persisted
- the current personalization summary is appended to the runtime system prompt

What is missing:

- soul is not a first-class runtime file or profile
- soul is not structured into explicit boundaries, tone, escalation rules, or durable persona directives
- soul is not used to shape tool policy or skill selection
- soul is not evolved or updated by the agent itself

Current soul behavior is best described as "prompt overlay", not "agent personality runtime".

### 3. Chat history is not fully injected as agent context

The app has a local chat session store and persists messages. However, the runtime itself does not reconstruct the full stored conversation as model context.

Today, the runtime mostly starts from:

- the current user input
- the runtime system prompt
- a session policy string
- the personalization overlay

What is missing:

- replay or summarization of prior message history into the runtime transcript
- structured conversation window management
- compaction and resume behavior
- persistent run state across turns in the app host

### 4. Skills are only partially integrated

The skills stack is real in these areas:

- discovery of `SKILL.md`
- front matter parsing
- validation
- registry construction
- Android-side listing, enabling, deleting, and catalog install into app-private directories

The app-side repository currently manages two roots:

- `filesDir/skills`
- `filesDir/skills-catalog`

What is missing:

- the runtime is not given `skillsRoots` by the Android app
- `skills_list` and `skill_read` are read-only tools
- `SKILL_CALL` is only mapped to `skill_read`
- no skill execution engine exists
- skill metadata such as invocation control, tool permissions, context, and subagent hints are not consumed by the runtime as execution policy

Current state:

- OpenCray has a skills package system
- OpenCray does not yet have a skill runtime

### 5. Session persistence exists in infrastructure, not in the live app path

The repository includes restart-safe queue persistence via `SessionStoreQueueSnapshotStore`, and tests exist for restart recovery.

What is missing in the app:

- the actual chat-triggered agent path still uses `InMemorySessionQueueSnapshotStore`
- the live app does not restore an interrupted or paused agent run
- there is no long-lived agent session manager in the Android shell

This is a major gap between "runtime infrastructure exists" and "product behavior is complete."

### 6. Approval and safety are not uniformly enforced across mutating tools

`ModeGate` is real and handles `ALLOW`, `ASK`, and `DENY`.

However, today:

- `command_exec` goes through policy and approval handling
- file mutations and Python execution do not uniformly go through the same approval gate

This creates an inconsistency:

- Safe mode claims to require approval for sensitive operations
- actual enforcement is narrower than that claim

To become a robust local agent, all side-effecting actions need a consistent safety model.

### 7. No sub-agent architecture

There is no evidence of:

- spawning child agents
- passing bounded context to sub-agents
- collecting child results
- managing nested depth or concurrency
- applying distinct prompts for sub-agent roles

This matters because once the core loop works, the next large capability jump is usually multi-agent decomposition.

### 8. No lifecycle hooks or bootstrap system

The repository does not currently expose an OpenClaw-like hooks system.

There is also no file-driven bootstrap ritual comparable to:

- first-run identity shaping
- workspace bootstrap files
- automatic session-memory flush hooks
- boot-time automation

This is one of the biggest architectural differences from OpenClaw.

## Prompt and System Prompt Audit

## Current OpenCray prompt layers

OpenCray currently has only a small prompt stack.

### Layer 1. Default runtime system prompt

`OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT` is the main hardcoded identity prompt for the runtime.

Its role is narrow:

- identify the agent as OpenCray
- instruct it to be workspace-first
- encourage tools over guessing

This is a useful seed prompt, but it is short and generic.

### Layer 2. User-editable system prompt

`LlmSettingsStore` persists a user-provided `systemPrompt`.

This means the app already supports runtime prompt overrides, but this is still one text field, not a full prompt architecture.

### Layer 3. Chat default system template

`ChatSessionLocalStore` seeds a default system template with `system.default.v1`.

Its current value is mainly about preserving transcript completeness and user-visible context.

This is closer to a session policy or host behavior note than a full agent operating prompt.

### Layer 4. Personalization overlay

`AppShellActivity.resolvedAgentSystemPrompt()` appends a personalization summary derived from soul-like settings.

This gives the model some personality guidance, but only in flattened natural language form.

### Layer 5. Runtime action-format prompt

`OpenCrayAgentRuntime.renderPrompt()` adds a second layer of behavior control on every turn:

- decide next step
- return exactly one JSON object
- use one of the allowed shapes
- see the tool list
- see task metadata
- see the running transcript

This is operationally important because it is doing most of the real control work. In practice, it is the strongest prompt in the current system.

## Prompt maturity assessment

OpenCray currently has prompt support, but not a mature prompt architecture.

What exists:

- one default runtime identity prompt
- one chat session template
- one user-editable prompt override
- one personalization overlay
- one action-format scaffold

What does not exist yet:

- file-based prompt decomposition
- dedicated tool-usage policy prompt blocks
- dedicated memory behavior prompt blocks
- dedicated skill invocation prompt blocks
- separate sub-agent prompts
- compaction prompts
- reflection prompts
- bootstrap prompts tied to workspace files

## Important conclusion about prompts

Building a capable agent usually does require more than one system prompt. The important point is not raw quantity, but layering.

A stronger prompt stack typically separates:

- identity
- tone and boundaries
- user profile
- operating rules
- tool policy
- memory policy
- skill policy
- safety and escalation rules
- host or surface-specific instructions

OpenCray is still at the stage where most of this is compressed into a very small amount of prompt text.

## OpenCray vs OpenClaw

The comparison below is based on OpenClaw official docs verified on 2026-03-12.

### 1. Workspace model

OpenClaw treats the workspace as the agent's primary home directory and context source. The docs describe a workspace-centered runtime with injected bootstrap files and optional sandbox variants.

OpenCray today has:

- an approved workspace root for runtime tools
- a bounded file system model
- app-private persistence stores

Gap:

- OpenCray has a workspace boundary
- OpenCray does not yet have a workspace-centric agent identity architecture

### 2. Bootstrap files and project-context injection

OpenClaw injects workspace files such as:

- `AGENTS.md`
- `SOUL.md`
- `TOOLS.md`
- `IDENTITY.md`
- `USER.md`
- optional `HEARTBEAT.md`
- one-time `BOOTSTRAP.md`
- optional `MEMORY.md`

OpenCray today does not have an equivalent file-driven prompt architecture.

Gap:

- OpenClaw is file-first
- OpenCray is string-first

### 3. Memory model

OpenClaw documents a two-layer memory design:

- daily markdown logs under `memory/YYYY-MM-DD.md`
- optional curated long-term memory in `MEMORY.md`

It also exposes memory-facing tools and documents when the agent should write memory.

OpenCray today has:

- `MemoryRecord`
- `JsonFileMemoryStore`

Gap:

- OpenClaw has a runtime memory loop
- OpenCray has a persistence primitive only

### 4. System prompt assembly

OpenClaw documents a system prompt that is rebuilt each run and includes:

- tool list
- skills metadata
- workspace location
- time and runtime metadata
- injected workspace bootstrap files

OpenCray currently assembles:

- base system prompt
- session policy text
- personalization overlay
- runtime action formatting text

Gap:

- OpenCray's prompt is much smaller and much less structured

### 5. Skills

OpenClaw documents multi-location skill loading with workspace override precedence.

OpenCray already has multi-root skill loading capability in `SkillLoader`, and the Android shell now has app-managed and catalog directories for skills.

Gap:

- OpenClaw's skills are runtime-facing execution components
- OpenCray's skills are still largely package metadata and management artifacts

### 6. Hooks

OpenClaw exposes a hook system for automation and lifecycle extension.

OpenCray does not currently expose a hook system.

Gap:

- no session lifecycle hook points
- no memory flush hook
- no bootstrap-extra-files hook
- no command logging hook model beyond direct runtime logic

### 7. Sub-agents

OpenClaw documents sub-agent support and explicit constraints around depth, context, and stop propagation.

OpenCray currently has no comparable runtime capability.

### 8. Tool groups and policy surface

OpenClaw documents grouped tool policy surfaces such as file-system, memory, session, web, and runtime groups.

OpenCray today has a local dispatcher and `ModeGate`, but not a similarly rich policy language or grouped tool governance model.

## Summary Table: OpenCray vs OpenClaw

| Capability area | OpenCray today | OpenClaw docs on 2026-03-12 | Gap level |
| --- | --- | --- | --- |
| Core task loop | Present | Present | Low |
| Multi-turn tool loop | Present, minimal | Present, mature | Medium |
| Central dispatcher | Present | Present | Low |
| Workspace-first architecture | Partial | Strong | Medium |
| File-based bootstrap prompt stack | Absent | Strong | High |
| Memory write and recall loop | Absent | Strong | High |
| Skill execution runtime | Absent | Present | High |
| Hooks | Absent | Present | High |
| Sub-agents | Absent | Present | High |
| Persistent live session runtime in app | Absent | Present | High |
| Safety coverage consistency | Partial | More mature policy model | Medium |

## What OpenCray Needs Next

The next milestone should not be "add more UI." The next milestone should be "turn the current skeleton into an actual agent operating model."

### Priority 0: Complete the runtime loop around state

- pass real session history into runtime
- make the app use persistent queue snapshots for live runs
- unify cancellation, retry, and resume with user-facing controls

### Priority 0: Build a real memory system

- define short-term versus durable memory
- decide when memory should be written
- add retrieval and ranking
- inject recalled memory into runtime context or expose it as tools

### Priority 0: Expand the prompt architecture

- separate identity, soul, user, tools, and memory policy
- stop compressing everything into one or two strings
- make prompt layers inspectable and testable

### Priority 1: Turn skills into executable runtime units

- pass `skillsRoots` into the runtime from the app
- define `skill_execute`
- enforce skill-specific tool permissions
- honor invocation-control and execution context

### Priority 1: Unify safety across all side-effecting tools

- file mutation tools should go through the same policy logic as commands
- Python execution should also use unified approval semantics
- app mode labels should match actual enforcement

### Priority 1: Introduce a workspace bootstrap model

- add file-backed identity and soul documents
- support optional user profile and tool-convention docs
- define what the runtime injects every run versus on demand

### Priority 2: Add hooks and sub-agent orchestration

- session lifecycle hooks
- memory flush before compaction
- sub-agent spawn and bounded result return
- explicit prompt reduction for child agents

## Final Assessment

OpenCray is now beyond the "pure prototype" stage, but it is still before the "complete agent runtime" stage.

The most accurate description of the current repository is:

"OpenCray has a working minimum agent loop, a real dispatcher, persistence primitives, and a skill package system. It does not yet have the memory, soul, session, skills, hooks, and prompt architecture needed for a full OpenClaw-class agent runtime."

That distinction matters because the next phase should focus less on adding isolated modules and more on closing the loops between the modules that already exist.
