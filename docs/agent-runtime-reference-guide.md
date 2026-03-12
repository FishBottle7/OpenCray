# Agent Runtime Reference Guide

Last updated: 2026-03-12

## Purpose

This document adds implementation guidance to the issue backlog in `docs/agent-runtime-issues.md`.

It answers three practical questions:

1. Which external projects are worth studying for each issue group?
2. What specific concept or code area should be studied in those projects?
3. What should be borrowed as a pattern, and what should not be copied directly into OpenCray?

This guide is intentionally selective. It is not a list of every agent project on the internet. It focuses on projects that are close to the gaps already identified in OpenCray.

## How To Use This Guide

- Use external projects for architecture patterns, not for blind copying.
- Prefer borrowing:
  - state ownership patterns
  - prompt layering patterns
  - memory lifecycle patterns
  - tool gating patterns
  - sub-agent constraints
- Avoid borrowing:
  - product-specific wording
  - unrelated abstractions
  - large framework dependencies unless they clearly fit OpenCray
- Before copying any code, verify the current upstream implementation because repository structures change over time.

## Recommended Reference Set

These are the strongest reference projects for the current OpenCray roadmap.

### 1. OpenClaw

#### Why it matters

OpenClaw is the closest reference for:

- file-driven bootstrap context
- system prompt layering
- workspace-first runtime design
- memory as a runtime concept
- hooks
- sub-agent patterns

#### Best use in OpenCray

Use OpenClaw as the main conceptual reference for:

- M2 prompt architecture
- M3 memory and soul layering
- M5 bootstrap files, hooks, and sub-agents

#### What to inspect

- Agent runtime concepts
- System prompt composition
- Agent workspace structure
- Memory layout and memory-facing tools
- Hooks
- Sub-agent documentation

#### What not to copy blindly

- Exact prompt wording
- Every file name or bootstrap convention
- Runtime assumptions that depend on OpenClaw's own host tooling

### 2. LangGraph

#### Why it matters

LangGraph is one of the strongest references for:

- durable agent state
- checkpointing
- graph or session persistence
- separating short-term state from longer-term stores

#### Best use in OpenCray

Use LangGraph for:

- M1 durable runtime sessions
- M3 memory retrieval and persistence policy

#### What to inspect

- Persistence and checkpoint concepts
- Store and memory handling patterns
- Separation between execution state and long-term storage

#### What not to copy blindly

- Graph-oriented abstractions if OpenCray does not need graph orchestration yet
- Framework-shaped APIs that would force unnecessary dependency or complexity

### 3. OpenHands

#### Why it matters

OpenHands is useful as a reference for:

- coding-agent product structure
- tool-heavy local development agents
- skills and repository-aware assistance
- long-running agent interaction surfaces

#### Best use in OpenCray

Use OpenHands for:

- M4 skill execution ideas
- runtime interaction design for coding tasks
- user-facing agent control patterns

#### What to inspect

- Agent runtime concepts
- skill-related documentation
- how the product separates host shell behavior from agent behavior

#### What not to copy blindly

- broad framework structure
- web-first product assumptions
- large abstractions that are not useful in a mobile local-agent architecture

### 4. Aider

#### Why it matters

Aider is a strong reference for:

- repository-aware context selection
- prompt budget discipline
- coding-agent behavior around file context
- repo map and selective context strategies

#### Best use in OpenCray

Use Aider for:

- M1 and M2 context shaping
- future prompt budget management
- deciding how much file and session context to include per turn

#### What to inspect

- repository map concepts
- selective file inclusion strategies
- coding-agent context discipline

#### What not to copy blindly

- terminal-centric UX assumptions
- git-centric flows that do not map directly to OpenCray's Android shell

## Issue Group To Reference Mapping

## Group A: Stateful runtime, queue persistence, and session ownership

### Relevant issues

- P0-1
- P0-2
- P0-3

### Best references

- LangGraph
- OpenHands

### Why these references fit

OpenCray's immediate problem is not "better prompts." It is "runtime ownership and durable execution state."

LangGraph is useful because it treats agent state and checkpoints as first-class runtime artifacts. That matches the OpenCray need to stop recreating a fresh loop on every prompt.

OpenHands is useful because it is a real product-grade coding agent host. It is worth studying how host lifecycle, session behavior, and user-visible runs are separated from core reasoning logic.

### Concepts to borrow

- One durable runtime owner per session
- Restorable run state
- Explicit checkpoint boundaries
- Clear separation between UI shell and runtime controller
- Bounded context restoration

### OpenCray guidance

When implementing P0-1 to P0-3:

- Borrow state ownership and checkpoint concepts from LangGraph.
- Borrow host-runtime separation ideas from OpenHands.
- Do not turn OpenCray into a graph framework.
- Keep the runtime manager concrete and local to the current Android shell architecture.

## Group B: Prompt layering and system prompt architecture

### Relevant issues

- P0-4

### Best references

- OpenClaw
- Aider

### Why these references fit

OpenClaw is the best reference for how a modern agent runtime assembles runtime context from multiple structured inputs instead of relying on one static prompt string.

Aider is useful because it is disciplined about context inclusion and prompt budget control. That matters once OpenCray starts injecting session history, memory, skills, and bootstrap files.

### Concepts to borrow

- Separate identity from operating rules
- Separate workspace context from user profile
- Separate tool protocol from personality
- Keep prompt layers inspectable
- Stay aggressive about prompt budget discipline

### OpenCray guidance

When implementing P0-4:

- Use OpenClaw as the architectural reference.
- Use Aider as the context-discipline reference.
- Avoid building a huge prompt file taxonomy before the layer model is stable.
- Start with a handful of concrete named layers and evolve from there.

## Group C: Unified approval and side-effect policy

### Relevant issues

- P0-5

### Best references

- OpenClaw
- OpenHands

### Why these references fit

OpenCray needs one consistent side-effect policy for commands, file writes, file deletes, file moves, and Python execution.

OpenClaw is useful as a reference for structured tool governance and runtime constraints.

OpenHands is useful as a reference for practical coding-agent tool governance in a real product context.

### Concepts to borrow

- One place where mutating-tool policy is resolved
- Tool-type-based safety semantics
- Traceable approval outcomes
- Consistent user-facing risk surface

### OpenCray guidance

When implementing P0-5:

- Do not leave policy decisions embedded in each tool implementation.
- Introduce one shared gate or one shared gate interface.
- Keep read-only and mutating tools clearly separated.

## Group D: Memory design, writing, and recall

### Relevant issues

- P1-1
- P1-2
- P1-3

### Best references

- OpenClaw
- LangGraph

### Why these references fit

OpenClaw is the stronger reference for what memory should look like from an agent-runtime point of view.

LangGraph is the stronger reference for persistence and retrieval architecture.

OpenCray needs both:

- a runtime concept of memory
- a durable implementation model

### Concepts to borrow

- Clear distinction between short-term session context and longer-term memory
- Intentional memory write policy instead of raw transcript dumping
- Bounded recall
- Stable storage abstraction separate from execution state

### OpenCray guidance

When implementing memory:

- Borrow OpenClaw's idea that memory should be an explicit runtime surface.
- Borrow LangGraph's idea that durable state and execution state are related but not identical.
- Do not start with semantic memory search first.
- Start with deterministic write and retrieval rules that are easy to test.

## Group E: Soul as structured runtime profile

### Relevant issues

- P1-4

### Best references

- OpenClaw

### Why this reference fits

OpenCray's current soul behavior is only a personalization overlay. OpenClaw is the best reference for treating agent identity and runtime guidance as structured context rather than as one UI-generated text block.

### Concepts to borrow

- Stable persona as structured runtime context
- Separation between user profile and agent identity
- File-backed or layer-backed identity inputs

### OpenCray guidance

When implementing soul:

- Keep soul focused on stable identity, voice, and durable guidance.
- Do not mix soul with transient session memory.
- Do not let soul become a generic miscellaneous metadata bucket.

## Group F: Skills visibility and executable skills

### Relevant issues

- P1-5
- P1-6

### Best references

- OpenClaw
- OpenHands

### Why these references fit

OpenCray already has skill discovery and validation. The missing step is execution.

OpenClaw is useful for thinking about skills as runtime capabilities rather than only metadata packages.

OpenHands is useful for studying how a coding agent exposes and constrains actions in a real assistant product.

### Concepts to borrow

- Installed skill roots that become runtime-visible
- Explicit execution context for a skill
- Skill-specific tool restrictions
- Separation between installation and activation

### OpenCray guidance

When implementing executable skills:

- Start with one concrete model: inline execution with restricted tools.
- Delay forked sub-agent skill execution until after the inline model is stable.
- Use validated metadata as policy, not just as documentation.

## Group G: Hooks, bootstrap files, and sub-agents

### Relevant issues

- P2-1
- P2-2
- P2-3
- P2-4

### Best references

- OpenClaw
- LangGraph

### Why these references fit

OpenClaw is the main conceptual reference for:

- hooks
- workspace bootstrap files
- sub-agent concepts

LangGraph is useful for thinking about bounded child execution and persistence-aware orchestration.

### Concepts to borrow

- Deterministic lifecycle hooks
- File-backed bootstrap context
- Bounded child runtimes
- Explicit depth and failure semantics

### OpenCray guidance

When implementing advanced runtime features:

- Add hooks only after lifecycle boundaries are stable.
- Add bootstrap files only after prompt layers are stable.
- Add sub-agents only after tool governance and runtime state ownership are already trustworthy.

## Suggested Study Order

If the team has limited time, study references in this order:

1. OpenClaw system prompt, workspace, memory, and sub-agent docs
2. LangGraph persistence and memory docs
3. OpenHands runtime and skills docs
4. Aider context-selection and repo-awareness concepts

That order matches the current OpenCray needs:

- first architecture
- then state
- then execution patterns
- then context discipline

## Project-Specific Guidance By Milestone

## M1: Stateful Runtime Foundation

### Study first

- LangGraph persistence and checkpoint concepts
- OpenHands host and session behavior

### Implementation advice

- Keep runtime ownership out of `AppShellActivity` as much as possible.
- Make queue snapshot restoration boring and deterministic before adding any richer agent behavior.
- Treat chat session id as the canonical runtime identity until a better runtime id model is introduced.

## M2: Prompt and Safety Foundation

### Study first

- OpenClaw system prompt and workspace context model
- Aider context inclusion discipline

### Implementation advice

- Build prompt layers as a small concrete system, not as a generic plugin framework.
- Keep tool governance centralized.
- Make sure UI mode descriptions match real enforcement semantics.

## M3: Memory and Soul Runtime

### Study first

- OpenClaw memory and identity docs
- LangGraph persistence and store concepts

### Implementation advice

- Start memory with deterministic host-approved writes.
- Keep soul stable and compact.
- Do not let the model freely dump arbitrary long-form memory at first.

## M4: Skills Runtime

### Study first

- OpenClaw skills-related concepts
- OpenHands skill and coding-assistant control patterns

### Implementation advice

- Solve runtime visibility before execution.
- Start with one execution mode.
- Treat skill metadata as executable policy.

## M5: Advanced Runtime Features

### Study first

- OpenClaw hooks, bootstrap, and sub-agent docs
- LangGraph orchestration and durable state concepts

### Implementation advice

- Add hooks sparingly.
- Add child runtimes with hard limits from the first version.
- Ensure observability grows with complexity.

## Anti-Patterns To Avoid

These patterns are likely to slow the project down:

- copying full upstream architecture from another project
- adopting a graph framework shape before OpenCray needs it
- adding semantic search before deterministic memory policy exists
- adding many prompt files before prompt layers are explicit
- treating skills as raw prompt snippets without runtime permission enforcement
- adding sub-agents before parent runtime state is stable

## Verified External References

These references were verified on 2026-03-12.

- OpenClaw Agent Runtime: https://docs.openclaw.ai/concepts/agent
- OpenClaw Agent Workspace: https://docs.openclaw.ai/concepts/agent-workspace
- OpenClaw Memory: https://docs.openclaw.ai/concepts/memory
- OpenClaw System Prompt: https://docs.openclaw.ai/concepts/system-prompt
- OpenClaw Hooks: https://docs.openclaw.ai/automation/hooks
- OpenClaw Agent Bootstrapping: https://docs.openclaw.ai/start/bootstrapping
- OpenClaw Sub-Agents: https://docs.openclaw.ai/tools/subagents
- OpenClaw Tools: https://docs.openclaw.ai/tools
- LangGraph Persistence: https://langchain-ai.github.io/langgraph/concepts/persistence/
- LangGraph Memory and Store Concepts: https://langchain-ai.github.io/langgraph/concepts/memory/
- OpenHands documentation home: https://docs.all-hands.dev/
- OpenHands GitHub repository: https://github.com/All-Hands-AI/OpenHands
- Aider documentation home: https://aider.chat/docs/
- Aider repository map and context guidance: https://aider.chat/docs/repomap.html

## Final Recommendation

If engineering time is limited, the best reference pairing for each near-term phase is:

- M1: LangGraph + OpenHands
- M2: OpenClaw + Aider
- M3: OpenClaw + LangGraph
- M4: OpenClaw + OpenHands
- M5: OpenClaw + LangGraph

That combination should keep OpenCray grounded in:

- product-realistic runtime behavior
- durable state discipline
- structured prompt design
- bounded future complexity
