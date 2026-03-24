# Codex Long-Task Workflow Gap Analysis

Updated: 2026-03-23

This document summarizes the current gap between OpenCray and Codex for long, tool-heavy, self-driven tasks.

It also clarifies three terms that are easy to mix together:

- `assistant phase`
- `assistant commentary`
- `final answer`

This analysis supersedes parts of older docs that are now partially stale, especially:

- `docs/chat-runtime-ux-parity-plan.md`
- `docs/openclaw-runtime-parity-roadmap.md`
- `docs/agent-runtime-audit.md`

## 1. Definitions

### 1.1 `assistant phase`

`assistant phase` is best understood as a host/runtime classification for an assistant emission.

It answers: "What kind of assistant output is this right now?"

Typical phases in a Codex-like system are:

- `commentary`: a public progress or intent update
- `final`: the terminal answer for the current task

Important: this is not the same thing as the model's private chain-of-thought. In practice, "phase" is usually a runtime/UI contract, not a universal provider-native field that every API returns in the same format.

### 1.2 `assistant commentary`

`assistant commentary` is a short, public, user-visible progress message. It is the "I am going to inspect the repo first" or "I found the native tool-calling path; next I am checking how progress events are emitted" lane.

Its purpose is:

- reduce perceived latency
- make long tasks feel observable
- show intent before tool execution
- keep the user oriented without exposing private reasoning

Official prompting guidance supports this pattern. The GPT-5.1 prompting guide explicitly recommends that, for long-running executions, the model should explain what it is doing in a commentary message first, before deeper hidden reasoning or tool work begins.

### 1.3 `final answer`

`final answer` is the terminal user-facing answer for the turn or task.

It should not be mixed with a tool call in the same action step. In a Codex-like loop, commentary can appear before or between tool actions, while `final answer` closes the run.

### 1.4 `analysis` is not `commentary`

These four things must stay separate:

- private model reasoning / hidden analysis
- public commentary / progress
- tool calls and tool results
- final answer

The key product discipline in Codex-style systems is not "show the whole reasoning trace". It is "show a safe public progress lane, keep private reasoning private, and keep tools and final answers structurally typed."

## 2. Confirmed OpenCray Baseline

OpenCray is already much closer to Codex than several older internal docs suggest.

### 2.1 Native tool calling already exists on the main path

The runtime already enables native tool calling when visible tool definitions exist:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:229-238`

The provider client already parses OpenAI-style `message.tool_calls`:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:360-377`

The provider client already parses Anthropic-style `tool_use` blocks:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:400-429`

The runtime already consumes structured completion first and only falls back to raw-text parsing when needed:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:552-571`

So the correct statement is:

- OpenCray already has native tool calling
- OpenCray still keeps a legacy JSON fallback layer

The remaining gap is not "native tool calling is missing". The remaining gap is "Codex-grade phase/protocol maturity on top of native tool calling is still incomplete."

### 2.2 Public progress/commentary-like events already exist

OpenCray already parses `progress`, `commentary`, and `status` legacy actions into a progress event:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:658-679`

OpenCray already has a dedicated progress event type:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt:56-63`

OpenCray also has a dedicated assistant event type for normal assistant output:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt:46-54`

There is already a regression test proving the timeline:

- `progress -> tool_call -> tool_result -> progress -> assistant`
- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt:1388-1455`

So the correct statement is:

- OpenCray already has a public progress lane
- but it is not yet promoted into a stronger, end-to-end "assistant commentary phase" contract

### 2.3 Prompting already encourages progress before action

The prompt assembler already teaches the model that:

- native tool calling should be preferred
- a progress action is a short public status update
- one progress action can come before one terminal action

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:223-255`

The runtime reminder also already says:

- prefer native tool calling over legacy JSON fallback
- you may include one short public progress summary before that action

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:2722-2727`

This matters because OpenCray already has the seed of the Codex-like "say what you are about to do, then do it" pattern.

### 2.4 Session, resume, context, subagent, and process foundations already exist

OpenCray already has:

- session-scoped runtime ownership
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceHost.kt`
- restart/recovery-aware queue snapshot support
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt`
- an explicit context manager for prompt preparation
  - `runtime/src/main/kotlin/com/opencray/runtime/context/ContextManager.kt`
- a real subagent runtime and subagent context builder
  - `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentRuntime.kt`
  - `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentContextBuilder.kt`
- managed process tools such as `Bash`, `ProcessStart`, and `ProcessWait`
  - `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:389-424`
  - `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:702-706`

This means OpenCray is no longer in a "toy loop" stage. The important comparison now is product quality and protocol rigor, not whether the basic architecture exists at all.

## 3. What Is Still Behind Codex

The remaining gap is real, but narrower and more specific than "OpenCray cannot do long tasks."

### 3.1 The biggest protocol gap: commentary is not yet a first-class assistant phase end-to-end

OpenCray currently models the public lane in two different ways:

- `OpenCrayProgressEvent`
- `OpenCrayAssistantEvent` with `isFinal`

That works, but it is not the same as a unified assistant-phase contract such as:

- `COMMENTARY`
- `FINAL`

Current symptoms:

- commentary-like output is treated as a separate progress event rather than as an assistant phase
- assistant output still primarily distinguishes `isFinal` instead of a richer phase enum
- this makes UI, persistence, replay, and provider adaptation less uniform than a true phase model

In other words, OpenCray already has the behavior shape, but not yet the clean semantic model.

### 3.2 Provider-native structured completion does not yet fully carry commentary/phase semantics

`LiteLlmStructuredCompletion` already has a `progressText` slot:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:437-454`

The runtime already knows how to turn `progressText` into a progress action:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:574-598`

But the current OpenAI and Anthropic structured parsing paths only populate:

- `toolCalls`
- `finalText`
- `rawText`

They do not currently populate provider-native commentary/progress fields into `progressText`.

Evidence:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:360-377`
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:400-429`

This is one of the most important remaining gaps. Native tool calling is already there, but commentary is still mostly carried by prompt conventions and legacy action parsing, not by a first-class structured provider path.

### 3.3 "Say one sentence before the first tool call" is only a soft instruction today

OpenCray currently says:

- "You may include one short public progress summary before that action."

Evidence:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:2724-2725`

That is permissive, not mandatory.

Codex-like behavior is more stable because the whole stack is aligned around this pattern:

- prompting expects it
- runtime and UI have a dedicated public commentary lane
- the product makes such messages visible and useful

OpenCray today is closer to:

- "the model is allowed to do this"

than to:

- "the system strongly converges on this behavior"

### 3.4 Legacy fallback is still part of the main prompt contract

OpenCray's prompt still explicitly teaches the model the legacy JSON fallback shapes:

- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:223-255`

And the runtime still contains protocol-error recovery for mixed or malformed legacy payloads:

- `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt:1555-1596`

This is useful for robustness, but it also means OpenCray is still operating a dual-protocol world:

- preferred native structured tool calling
- legacy JSON action protocol as fallback

Codex-grade systems are stronger when the mainline protocol is cleaner, with fallback kept narrow and invisible to the model whenever possible.

### 3.5 OpenCray has `TodoWrite`, but not Codex-grade plan-state discipline

OpenCray already exposes `TodoWrite`, but that is not the same as the stronger plan semantics Codex-style prompting uses.

What Codex-style long-task execution usually adds on top:

- explicit milestone items
- stable status transitions
- exactly one `in_progress`
- end-of-turn invariants
- durable plan history or plan deltas
- plan state reflected in user-visible progress updates

By contrast, OpenCray currently has a todo tool, but not yet a first-class planning protocol with strong runtime invariants and replay semantics.

That difference matters a lot on long tasks because it reduces drift, duplicate work, and premature stopping.

### 3.6 Codex is stronger at long-horizon orchestration and productization

Official docs show several product-level behaviors that go beyond just "the loop exists."

Codex's local shell guidance shows an iterative Responses loop using `previous_response_id`:

- `https://developers.openai.com/api/docs/guides/tools-local-shell`

Codex's compaction docs show a first-class server-side and standalone compaction model for long-running interactions:

- `https://developers.openai.com/api/docs/guides/compaction`

Codex app docs show:

- isolated `Local`, `Worktree`, and `Cloud` modes
- worktree-based isolation for side-by-side tasks
- dedicated background worktrees for automations
- background notifications when a task completes or needs approval

References:

- `https://developers.openai.com/codex/app/features`

Codex subagent docs show:

- explicit parallel subagent workflows
- orchestration of spawn, wait, routing, and close
- inherited approval and sandbox policies

References:

- `https://developers.openai.com/codex/subagents`

OpenCray already has partial equivalents:

- subagent runtime
- managed processes
- durable resume
- context manager

But Codex is still ahead in product maturity:

- stronger isolated execution surfaces
- stronger background-task ergonomics
- stronger task observability
- stronger parallel-work orchestration contract

### 3.7 Some existing OpenCray docs are stale and understate the current baseline

Examples of statements that are no longer fully accurate:

- "there is no public intermediate progress event"
- "there is no subagent runtime"
- "tool calling is still mostly non-native"

Those statements made sense earlier, but no longer describe the current codebase accurately.

That matters because bad self-diagnosis leads to wrong priorities. The real priority is not rebuilding the old foundation; it is tightening the protocol and product surfaces that sit on top of the foundation now in place.

## 4. What `assistant phase` and `assistant commentary` mean for OpenCray specifically

If OpenCray wants Codex-like semantics, the clean mapping should be:

- `assistant phase = commentary`
  - public progress / intent update
  - non-terminal
  - often emitted before the first tool call or between major steps

- `assistant phase = final`
  - terminal answer
  - user-facing completion for the task

Under the current OpenCray model, the nearest equivalents are:

- `OpenCrayProgressEvent` ~= commentary lane
- `OpenCrayAssistantEvent(isFinal = true)` ~= final lane

What is missing is a single explicit assistant-phase abstraction that spans:

- provider completion parsing
- runtime action model
- durable event model
- UI rendering

## 5. Can "say one sentence before tool calls" be done by prompt alone?

Yes, partially.

No, not reliably enough if you want Codex-like consistency.

### 5.1 Prompt-only

Prompt-only can get surprisingly far. The GPT-5.1 prompting guide explicitly shows that commentary-first behavior is promptable.

That is already visible in OpenCray's current prompt design:

- progress examples are included
- public progress is defined
- the model is told it may send a short progress summary before acting

If the goal is:

- "usually say one sentence before using tools"

then prompt-only may be enough.

If the goal is:

- "make this a stable product behavior across models, providers, and long tasks"

then prompt-only is not enough.

### 5.2 Prompt plus runtime guard

A stronger implementation is:

1. Prompt the model to emit commentary before the first tool call.
2. Detect whether the first action batch starts with a tool call and has no commentary/progress.
3. Apply a guardrail.

Possible guardrail strategies:

- soft repair: reprompt once and require a public commentary first
- hard protocol rule: reject first-turn bare tool calls when commentary is required
- host-generated fallback: synthesize a host status message such as "Inspecting workspace before action"

Of these, the cleanest long-term option is usually:

- model-generated commentary as the default
- runtime enforcement only when a product flag requires it

### 5.3 Best long-term direction: make commentary a first-class phase

The strongest design is to add a true assistant-phase contract.

For example:

- `AssistantPhase.COMMENTARY`
- `AssistantPhase.FINAL`

Then wire that through:

- provider client structured completion
- runtime action parsing
- event persistence
- chat projection/UI

This would give OpenCray a stable semantic basis for the behavior you want, instead of depending only on soft prompt compliance.

## 6. Recommended Next Steps

### 6.1 Short term

- Keep native tool calling as the preferred path.
- Tighten the prompt wording from "may include one short public progress summary" to "before the first tool call, emit one short public progress summary unless the task is trivial."
- Keep legacy JSON fallback, but make it more clearly secondary.

### 6.2 Medium term

- Add an explicit assistant phase model in runtime and persistence.
- Populate `progressText` from structured provider-native commentary/progress fields whenever the provider exposes them.
- Add a runtime option such as `requireInitialCommentaryBeforeFirstToolCall`.

### 6.3 Long term

- Promote plan state from `TodoWrite` into a first-class long-task control surface.
- Strengthen isolated execution surfaces for parallel or detached work.
- Make background execution, approvals, and resumability feel like one continuous product surface instead of several adjacent mechanisms.

## 7. Evidence Index

### Local code evidence

- Native tool calling request path:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:229-238`
- Structured completion preferred over raw fallback:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:552-571`
- Structured completion progress/final handling:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:574-598`
- Legacy action parsing for `progress/commentary/status`:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:658-679`
- Prompt examples and tool/progress guidance:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:223-255`
- Prompt reminder that allows a short public progress summary:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:2722-2727`
- OpenAI `tool_calls` parsing:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:360-377`
- Anthropic `tool_use` parsing:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:400-429`
- Structured completion shape with `progressText`:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:437-454`
- Assistant and progress event types:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt:46-63`
- Progress -> tool -> result -> progress -> final test:
  - `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt:1388-1455`
- Session runtime ownership:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceHost.kt`
- Recovery-aware queue snapshot store:
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt`
- Context manager:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/ContextManager.kt`
- Subagent runtime:
  - `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentRuntime.kt`
- Managed process tools:
  - `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:389-424`
  - `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt:702-706`

### Official external references

- GPT-5.1 prompting guide, commentary-first guidance:
  - `https://cookbook.openai.com/examples/gpt-5/gpt-5.1_prompting_guide/`
- Local shell iterative loop with `previous_response_id`:
  - `https://developers.openai.com/api/docs/guides/tools-local-shell`
- Long-context compaction:
  - `https://developers.openai.com/api/docs/guides/compaction`
- Codex app features, worktrees, background approvals/notifications:
  - `https://developers.openai.com/codex/app/features`
- Codex subagents:
  - `https://developers.openai.com/codex/subagents`
