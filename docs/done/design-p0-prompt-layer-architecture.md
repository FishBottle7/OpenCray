# Design: P0-4 Prompt Layer Architecture

Last updated: 2026-03-12

## Status

Completed

This design has been implemented through the runtime prompt assembly path and verified by tests.

## Related backlog items

- `docs/agent-runtime-issues.md` Issue P0-4
- `docs/agent-runtime-roadmap.md` M2 Prompt and Safety Foundation

## Goal

Refactor OpenCray's prompt construction into explicit named layers so that runtime instructions become inspectable, testable, and extensible.

The first target is not a huge prompt framework. The first target is to move from:

- ad hoc string concatenation

to:

- explicit prompt structure

## Problem Statement

Today, OpenCray's runtime prompt behavior is spread across:

- default runtime system prompt
- optional user-edited system prompt
- default chat system template
- personalization overlay
- runtime action-formatting instructions inside `renderPrompt()`

This has several drawbacks:

- prompt logic is difficult to reason about
- adding memory and skills will make the prompt harder to control
- host code currently participates too directly in prompt construction
- testing prompt behavior is too coarse

The core issue is not only prompt wording. It is prompt structure.

## Design Principles

- Keep the first version concrete and small.
- Prefer named layers over generic plugin systems.
- Separate identity from operating rules.
- Separate user/session context from tool protocol.
- Keep prompt layers inspectable in tests and traces.
- Do not block later file-based bootstrap work.

## Current Prompt Inputs

The current effective runtime prompt is built from five sources:

1. `DEFAULT_OPENCRAY_SYSTEM_PROMPT`
2. user-provided `systemPrompt`
3. default chat system template from `ChatSessionLocalStore`
4. personalization summary from soul-like settings
5. runtime protocol text in `OpenCrayAgentRuntime.renderPrompt()`

These sources are meaningful, but not yet cleanly separated.

## Proposed Prompt Layer Model

## Layer groups

OpenCray should use the following initial prompt layers.

### 1. Identity layer

Purpose:

- define who OpenCray is
- define the baseline assistant role

Source candidates:

- default runtime system prompt
- user-edited system prompt override

### 2. Runtime rules layer

Purpose:

- state durable operating rules
- set general priorities such as workspace-first behavior

In P0, this may remain close to the identity layer, but it should still be modeled separately so later safety and bootstrap work can evolve cleanly.

### 3. Session policy layer

Purpose:

- inject session-specific policy or template material
- capture host-defined chat-level instructions

Source candidates:

- `system.default.v1`
- future chat session policy templates

### 4. Personalization layer

Purpose:

- inject structured user-preference or soul-derived guidance

In P0, this can still be rendered from current personalization state, but it should be isolated as its own layer.

### 5. Tool protocol layer

Purpose:

- tell the model how to interact with the runtime
- define output format rules
- define tool-call shape expectations

This currently lives inside `renderPrompt()` and is operationally the most important behavior layer.

### 6. Task context layer

Purpose:

- include task id, task type, metadata, and bounded conversation history

This should remain separate from system-level instructions.

## Prompt Assembly Output Model

The runtime should assemble a prompt from explicit prompt sections, not from anonymous strings.

Suggested internal model:

```kotlin
data class PromptLayer(
  val name: String,
  val kind: PromptLayerKind,
  val content: String,
)

enum class PromptLayerKind {
  SYSTEM,
  PROTOCOL,
  CONTEXT,
}
```

Suggested final assembly container:

```kotlin
data class AssembledPrompt(
  val systemPrompt: String,
  val taskPrompt: String,
  val layers: List<PromptLayer>,
)
```

This makes prompt construction inspectable and traceable without introducing a complex framework.

## Proposed Assembly Responsibilities

## `PromptAssembler`

Responsibility:

- produce `AssembledPrompt` from current runtime inputs

Inputs:

- runtime config
- task
- bounded conversation context
- session policy text
- personalization or soul profile
- tool definitions

Outputs:

- assembled system prompt
- assembled task prompt
- named layer list for inspection

## `PromptLayers`

Responsibility:

- house concrete layer builders
- render each layer from structured inputs

This keeps prompt assembly logic out of `AppShellActivity` and out of the main runtime loop body.

## Proposed Assembly Split

### System prompt

The system prompt should be composed from stable instruction layers:

- identity layer
- runtime rules layer
- session policy layer
- personalization layer

### Task prompt

The task prompt should be composed from operational and dynamic layers:

- tool protocol layer
- task context layer

This split is important because:

- stable instructions belong in `LiteLlmGatewayRequest.systemPrompt`
- dynamic current-turn protocol and context belong in the per-turn task prompt

## Example Shape

### System prompt

```text
[Identity]
You are OpenCray...

[Runtime Rules]
Prefer tools over guessing...

[Session Policy]
...

[Personalization]
...
```

### Task prompt

```text
[Tool Protocol]
Return exactly one JSON object...

[Available Tools]
...

[Task Context]
task_id=...
task_type=...
conversation=...
```

The exact formatting can remain plain text in P0. The important part is the conceptual separation and internal structure.

## Proposed Code Placement

Suggested files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptAssembler.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptLayers.kt`
- possibly new: `runtime/src/main/kotlin/com/opencray/runtime/prompt/PromptModels.kt`

Expected integration changes:

- `OpenCrayAgentRuntime` stops hand-building prompt text directly
- `AppShellActivity` stops concatenating core runtime prompt strings

## Layer Input Model

The prompt assembler should accept structured inputs rather than preformatted text blobs where possible.

Suggested sketch:

```kotlin
data class PromptAssemblyInput(
  val task: AgentTask,
  val baseSystemPrompt: String,
  val sessionPolicyText: String?,
  val personalizationText: String?,
  val toolDefinitions: List<AgentToolDefinition>,
  val transcript: List<ConversationEntry>,
)
```

Later, this can evolve to structured soul and memory inputs without changing the whole architecture.

## P0 Scope Boundaries

This design intentionally stops short of:

- file-backed bootstrap prompt files
- memory prompt layers
- skill prompt layers
- sub-agent prompt profiles
- prompt-time semantic context ranking

Those are future layers, not P0 requirements.

## Testing Strategy

## Unit tests

Suggested file:

- `runtime/src/test/kotlin/com/opencray/runtime/prompt/PromptAssemblerTest.kt`

Required coverage:

- identity layer assembly
- session policy inclusion and omission
- personalization inclusion and omission
- tool protocol rendering
- final assembly order

## Snapshot-style tests

Where practical, add assertion coverage that checks stable rendered prompt sections.

The goal is not to lock down every character forever. The goal is to catch accidental structural regressions.

## Trace Integration

Prompt layers should be compatible with later runtime tracing.

Even if full trace work is deferred, the assembler should produce a data structure that could later be rendered into a debug trace.

This is one reason to keep named layer objects rather than raw strings only.

## Migration Plan

### Step 1

Extract current prompt logic into prompt layer builders without changing behavior.

### Step 2

Update `OpenCrayAgentRuntime` to consume `AssembledPrompt`.

### Step 3

Move host-side prompt concatenation out of `AppShellActivity`.

### Step 4

Add tests that lock down layer ordering and inclusion.

## Risks

### Risk: too much abstraction too early

If prompt layers are implemented as a fully generic plugin framework, the change will slow development and obscure simple behavior.

Mitigation:

- keep the first version concrete
- use explicit layer types

### Risk: old and new prompt assembly coexist too long

If prompt building remains partially in the runtime and partially in the app shell, the refactor will be incomplete.

Mitigation:

- move assembly ownership decisively into the runtime package

### Risk: confusing boundary between system prompt and task prompt

If stable instructions and dynamic context are mixed arbitrarily, the new model will still be hard to reason about.

Mitigation:

- use the stable-vs-dynamic split as a design rule

## Definition of Done

P0-4 is complete when:

- prompt assembly is represented by named layers
- `OpenCrayAgentRuntime` consumes structured assembled prompts
- app shell no longer owns core runtime prompt concatenation
- tests cover layer inclusion, omission, and ordering
