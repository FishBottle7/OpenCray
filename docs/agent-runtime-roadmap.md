# Agent Runtime Roadmap

Last updated: 2026-03-16

## Purpose

This document turns the agent audit and issue backlog into a practical implementation roadmap.

It is meant to answer five planning questions:

1. What should be built first?
2. What should not be built too early?
3. Which modules will change in each phase?
4. What are the main technical risks?
5. What is the minimum sequence that gets OpenCray from "working skeleton" to "complete runtime"?

For completed P0 implementation plans, see:

- `docs/done/design-p0-live-queue-persistence.md`
- `docs/done/design-p0-session-runtime-manager.md`
- `docs/done/design-p0-prompt-layer-architecture.md`
- `docs/memory-design.md`

This roadmap is intentionally pragmatic. It does not try to optimize for every future feature. It optimizes for getting the current architecture into a stable, extensible shape.

## Current Starting Point

OpenCray already has:

- a task contract and queue model
- a minimum multi-turn LLM and tool loop
- a unified tool dispatcher
- local file, command, Python, skills, and MCP capability surfaces
- persistence primitives for session, memory, and soul
- an Android shell that can run the agent for one-shot chat prompts

OpenCray does not yet have:

- durable live runtime sessions
- production memory write and recall
- structured soul runtime behavior
- executable skills
- a unified approval model across all mutating tools
- a mature layered prompt architecture
- hooks, sub-agents, or workspace bootstrap files

## Planning Principles

- Finish host/runtime integration before adding more intelligence layers.
- Do not build skills execution before prompt layering and runtime ownership are stable.
- Do not build hooks before there is a clear lifecycle to hook into.
- Keep Android shell changes thin where possible. Push logic into runtime and shared modules.
- Every major phase should end with testable runtime behavior, not just new data structures.

## Recommended Delivery Order

The recommended order is:

1. Stabilize runtime ownership and persistence.
2. Stabilize prompt architecture and safety enforcement.
3. Add memory and soul as structured runtime systems.
4. Bridge app-managed skills into runtime and implement execution.
5. Add maturity features like hooks, bootstrap files, sub-agents, and richer tracing.

This order matters. If memory, soul, or skills are implemented before session ownership and prompt layering are fixed, the result will likely be duplicated host logic and brittle integrations.

## Milestone Structure

| Milestone | Goal | Duration shape | Main outcome |
| --- | --- | --- | --- |
| M1 | Stateful Runtime Foundation | Short but critical | Durable session ownership and live queue persistence |
| M2 | Prompt and Safety Foundation | Short to medium | Structured prompt assembly and consistent approval model |
| M3 | Memory and Soul Runtime | Medium | Durable intelligence and personalization become runtime features |
| M4 | Skills Runtime | Medium | Skills become executable and visible to the runtime |
| M5 | Advanced Runtime Features | Medium to long | Hooks, bootstrap files, sub-agents, and observability |

## M1: Stateful Runtime Foundation

## Objective

Turn OpenCray from a one-shot chat runner into a session-scoped runtime host.

## Included issues

- P0-1 Persist live queue state for chat-backed runs
- P0-2 Introduce a session runtime manager for long-lived chat sessions
- P0-3 Inject persisted chat history into runtime prompt context

## Why M1 comes first

Every later capability depends on stable runtime ownership.

If OpenCray still creates a fresh loop for every prompt:

- memory has no durable session context
- skills cannot maintain runtime continuity
- cancellation and retry remain awkward
- prompt evolution becomes tied to UI flow instead of runtime lifecycle

## Modules likely to change

- `app/`
  - `AppShellActivity`
  - potentially new `AgentSessionRuntimeManager`
  - chat session integration paths
- `core/`
  - orchestration surfaces if new lifecycle methods are needed
- `persistence/`
  - session-backed queue snapshot behavior

## Technical tasks

- Replace in-memory queue snapshots with persistent queue snapshots in the live app path.
- Introduce one runtime owner per chat session.
- Reconstruct bounded chat history into runtime context.
- Keep live and restored session behavior consistent.

## Main risks

### Risk 1: UI and runtime ownership become tangled

If runtime lifecycle remains inside `AppShellActivity`, later work will keep bloating the Activity.

Mitigation:

- introduce a manager object early
- keep the Activity as a coordinator, not the runtime owner

### Risk 2: Restored queue state diverges from visible chat state

If queue restoration and chat restoration are not sourced from the same session identity model, the app may reopen a session with mismatched UI and runtime state.

Mitigation:

- define one session id mapping strategy
- add restore integration tests

### Risk 3: Prompt context replay becomes unbounded

If full chat history is blindly replayed, prompt size will explode.

Mitigation:

- start with a bounded replay policy
- enforce size budgets immediately

## Exit criteria

M1 is complete when:

- a chat session has a durable runtime owner
- queue state persists and restores
- runtime prompt assembly includes bounded session history
- live app behavior is no longer based on one-off ephemeral loops

## M2: Prompt and Safety Foundation

## Objective

Create a stable prompt architecture and a trustworthy enforcement model for side effects.

## Included issues

- P0-4 Split prompt assembly into explicit named layers
- P0-5 Unify approval gating across all mutating runtime tools

## Why M2 comes before memory and skills

Memory, soul, and skills all need a place to land in prompt assembly and runtime governance.

Without prompt layering:

- memory injection becomes ad hoc
- soul stays a string blob
- skills do not have a clean execution context

Without safety unification:

- runtime behavior will remain inconsistent across tools
- product mode labels will be misleading

## Modules likely to change

- `runtime/`
  - `OpenCrayAgentRuntime`
  - `AgentTooling`
  - prompt package additions
  - safety gating additions
- `app/`
  - shell wiring for prompt sources
  - possible trace rendering changes

## Technical tasks

- Create prompt layer abstractions.
- Move prompt construction out of UI-driven string concatenation.
- Define one gate for all mutating tools.
- Surface approval decisions consistently in traces and results.

## Main risks

### Risk 1: Prompt layers become over-engineered too early

If prompt layering becomes a generic framework before actual needs are wired, it will slow development.

Mitigation:

- keep the first version concrete
- start with named layers only
- avoid premature plugin systems

### Risk 2: Tool safety semantics drift between implementations

If each tool adopts the gate differently, behavior will still be inconsistent.

Mitigation:

- add a common interface or shared helper
- test all mutating tools together

### Risk 3: Product text lags behind enforcement

If mode descriptions in UI are not updated with actual runtime behavior, users will receive conflicting signals.

Mitigation:

- tie UI mode summaries to runtime policy behavior review

## Exit criteria

M2 is complete when:

- runtime prompt assembly is layered and testable
- all mutating tools share one approval and policy model
- app mode semantics match actual enforcement behavior

## M3: Memory and Soul Runtime

## Objective

Turn memory and soul from persistence primitives into active runtime context systems.

## Included issues

- P1-1 Define memory taxonomy and production storage policy
- P1-2 Write durable memory from production agent flows
- P1-3 Recall relevant memory into runtime context
- P1-4 Turn soul into a structured runtime profile

## Why M3 starts here

By this point:

- session ownership exists
- prompt layering exists
- approval behavior is coherent

That gives memory and soul a stable place in runtime.

## Modules likely to change

- `runtime/`
  - new memory package
  - new soul package
  - prompt integration
- `persistence/`
  - possible schema refinements for typed memory fields
  - soul profile mapping updates
- `app/`
  - personalization producer mapping
  - shell integration for memory-sensitive behaviors
- `docs/`
  - memory design and possibly soul design notes

## Technical tasks

- Define which facts become memory.
- Add production memory writes for stable, host-approved cases.
- Add retrieval heuristics and bounded automatic prompt injection.
- Add explicit runtime memory tools for on-demand recall during a run.
- Keep automatic recall and memory tools complementary instead of choosing only one path.
- Replace flat personalization summary with structured soul rendering.
- Prepare memory flush design so durable memory capture can happen before future compaction work.

## Main risks

### Risk 1: Memory becomes noisy and low-value

If too many facts are written, retrieval quality drops and prompt budgets get wasted.

Mitigation:

- start with a narrow write policy
- prioritize stable user preferences and verified workspace facts

### Risk 2: Soul and memory responsibilities overlap

If soul stores ephemeral preferences and memory stores identity directives, the model will receive redundant guidance.

Mitigation:

- keep soul for stable persona and alignment guidance
- keep memory for factual continuity and learned preferences

### Risk 3: Retrieval logic becomes non-deterministic too early

If semantic matching is introduced too early, testing and debugging become harder.

Mitigation:

- start with deterministic rules
- add richer ranking later

## Exit criteria

M3 is complete when:

- production runs can write approved memory records
- runtime can recall bounded relevant memory
- runtime has an explicit plan for on-demand memory recall tools instead of relying only on pre-injected memory
- soul is represented structurally in prompt assembly

## M4: Skills Runtime

## Objective

Turn skills from packages and metadata into executable runtime behavior.

## Included issues

- P1-5 Expose app-managed skills to the runtime
- P1-6 Implement executable skill runtime behavior

## Why M4 follows M3

Skills execution relies on:

- stable prompt architecture
- stable runtime ownership
- stable policy enforcement

Without those, skills would just become a second source of brittle prompt concatenation.

## Modules likely to change

- `skills/`
  - registry and metadata usage surfaces
- `runtime/`
  - skill execution path
  - tool filtering by skill permission
  - skill selection mechanics
- `app/`
  - runtime config now includes installed skills roots
  - UI may need better visibility into runtime-active skills

## Technical tasks

- Pass installed skill roots into runtime.
- Filter disabled skills.
- Add an execution path instead of only `skills_list` and `skill_read`.
- Enforce allowed tools and execution context.

## Main risks

### Risk 1: Skills become prompt snippets with no governance

If skills are implemented as raw prompt injection without permission controls, they can undermine safety and predictability.

Mitigation:

- enforce validated metadata during execution
- filter tools strictly

### Risk 2: UI notion of installed skill differs from runtime-active skill

If enabled and installed states are not resolved consistently, the runtime and UI will disagree.

Mitigation:

- define one runtime-visible skill resolution pipeline

### Risk 3: Skill execution semantics are too ambiguous

If "execute skill" has no precise meaning, debugging will be hard.

Mitigation:

- start with one concrete execution model
- likely inline execution with tool restriction first

## Exit criteria

M4 is complete when:

- runtime sees app-managed installed skills
- a skill can be invoked as a runtime behavior
- skill-specific permissions are enforced

## M5: Advanced Runtime Features

## Objective

Add the architecture that moves OpenCray closer to a mature OpenClaw-class runtime.

## Included issues

- P2-1 Introduce lifecycle hooks
- P2-2 Add file-based workspace bootstrap context
- P2-3 Add bounded sub-agent support
- P2-4 Standardize runtime observability and trace model

## Why M5 is last

These features are important, but they depend on earlier phases being stable.

Hooks are not useful before the lifecycle is well-defined.
Bootstrap files are less valuable before prompt assembly is layered.
Sub-agents are risky before tool governance and skill execution are stable.

## Modules likely to change

- `runtime/`
  - hooks
  - bootstrap loaders
  - sub-agent runtime
  - trace models
- `app/`
  - debug surfaces
  - trace and session rendering
- `docs/`
  - bootstrap specification
  - lifecycle docs

## Technical tasks

- Define hook points and hook failure model.
- Add file-based bootstrap support.
- Implement bounded child runtimes.
- Unify trace data across runtime features.

## Main risks

### Risk 1: Hooks become an uncontrolled extension surface

If hooks are too open too early, core runtime behavior becomes hard to reason about.

Mitigation:

- begin with internal hooks only
- keep the first version narrow and explicit

### Risk 2: Bootstrap files duplicate existing prompt layers

If file-based context is added without rationalizing existing prompt layers, the system may double-inject the same concepts.

Mitigation:

- map each file to one prompt layer
- avoid redundant sources of truth

### Risk 3: Sub-agents create runaway complexity

If parent and child runtimes do not have tight limits, debugging and safety become much harder.

Mitigation:

- require bounded tools, bounded prompt, and bounded depth from the first version

## Exit criteria

M5 is complete when:

- hooks exist and are deterministic
- workspace bootstrap files participate in runtime context
- child runtimes are possible in bounded form
- runtime trace data is coherent across features

## Module Impact Matrix

| Module | M1 | M2 | M3 | M4 | M5 |
| --- | --- | --- | --- | --- | --- |
| `app/` | High | Medium | Medium | Medium | Medium |
| `core/` | Medium | Low | Low | Low | Medium |
| `runtime/` | High | High | High | High | High |
| `persistence/` | Medium | Low | Medium | Low | Low |
| `skills/` | Low | Low | Low | High | Medium |
| `ui/` | Low | Low | Low | Medium | Low |
| `docs/` | Low | Low | Medium | Low | Medium |

## Teaming Recommendation

If multiple engineers work in parallel, use this split:

### Track A: Host and persistence

- M1 runtime ownership
- queue persistence
- session restoration

### Track B: Prompt and policy

- M2 prompt layering
- unified tool safety gate

### Track C: Intelligence layers

- M3 memory and soul

### Track D: Skills runtime

- M4 runtime-visible skills and skill execution

### Track E: Maturity and platform features

- M5 hooks
- bootstrap files
- sub-agents
- observability

This is only safe after M1 and M2 are at least partially complete.

## What Not To Do Too Early

Avoid these moves before M1 and M2 are stable:

- semantic memory search
- autonomous self-editing soul updates
- public plugin or hook systems
- multi-agent task trees
- external marketplace integration
- large prompt file taxonomies without a stable prompt assembly core

These all look attractive, but they increase complexity faster than they increase product value if the foundation is still one-shot and loosely wired.

## Suggested First Two Sprints

## Sprint 1

- P0-1 Persist live queue state
- P0-2 Session runtime manager
- P0-3 Inject bounded chat history

### Sprint 1 outcome

OpenCray stops behaving like a pure one-shot chat runner.

## Sprint 2

- P0-4 Prompt layers
- P0-5 Unified mutating-tool approval

### Sprint 2 outcome

OpenCray gets a stable prompt and safety foundation for memory and skills work.

## Suggested Release Narrative

If the work is presented externally or in release notes, the phases map well to these user-facing narratives:

- M1: "Agent sessions survive interruption and resume with context."
- M2: "Prompt logic and safety behavior are now more reliable and consistent."
- M3: "OpenCray can remember durable preferences and use a structured personalization profile."
- M4: "Installed skills are now active runtime capabilities."
- M5: "OpenCray now supports bootstrap context, advanced tracing, and bounded child runtimes."

## Final Recommendation

The best immediate move is not to start memory or skills execution first.

The best immediate move is:

1. make runtime sessions durable
2. make prompt construction explicit
3. make safety enforcement consistent

After that, memory, soul, and skills will have a stable architecture to plug into instead of becoming more isolated features.
