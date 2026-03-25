# Chat Mid-Loop Supplement Plan

## Goal

This phase adds a conservative mid-loop supplement path for chat runs so the behavior is closer to Claude Code / Codex:

- do not interrupt the active run
- do not cancel and replace the active run
- accept user follow-up input durably while the run is still alive
- inject that input only at a safe checkpoint before the next LLM request
- keep fallback queueing when the runtime is not in a safe state

## Model

Two different inboxes now exist for user input that arrives during an active run:

- follow-up queue:
  used when the current run must finish first and the next input should become a brand-new run
- supplement inbox:
  used only when the current run can safely absorb extra user guidance before its next model turn

The supplement inbox is durable and session-scoped, but every entry is still targeted at one concrete run.

## Safe Checkpoint

Phase 1 mid-loop injection uses only one checkpoint:

1. turn start, immediately before assembling the next LLM prompt

This means:

- no injection while a tool call is executing
- no injection while the run is suspended on approval
- no injection during final answer emission
- no host-side cancellation to force the new input into the current run

## Host Routing Rules

When the user sends a new message and the current session already has an active run:

1. if the session already has deferred follow-up queue items, keep queueing there
2. if the active run is waiting for approval, keep queueing there
3. otherwise, append the message into the durable supplement inbox for the active run

If there is no active run, normal submit behavior stays unchanged.

## Approval Wait Semantics

Approval wait is a separate state and must not be conflated with ordinary running.

During approval wait:

- the current run is still the current run
- the run must not be deleted, replaced, or hidden just because the user rejected the tool call
- new user messages do not inject into the suspended run immediately
- those new user messages are stored as deferred follow-ups unless a later checkpoint explicitly supports approval-state supplements

User-facing language should distinguish this state from ordinary running:

- ordinary running:
  "Recorded. This will be applied to the current run when it reaches the next safe checkpoint."
- approval wait:
  "Recorded. The current run is waiting for approval, so this message will be handled after that decision."

The product reason is simple:

- after rejecting a tool call, the user often wants to say "do not do that; do this instead"
- that instruction is still logically attached to the same work item
- the system should preserve the rejected run as the active context anchor instead of pretending that run disappeared

## Approval Rejection Behavior

Approval rejection must not mean "destroy the run."

After the user rejects a requested tool call:

1. keep the run visible in history and runtime state
2. write the rejection into durable replay
3. mark the run as blocked on user direction, not silently discarded
4. keep the run's context available so the next user instruction can branch from the rejected state

Operationally this means:

- the rejected run remains the last meaningful work item
- the next user message should be understood as "continue from this rejected decision point"
- the host may start a new run for the continuation if needed, but that new run is a continuation of the rejected run's transcript, not a replacement that erases it

UI language should reflect that:

- approval card disappears because that approval request is resolved
- the run card or timeline should show a durable "rejected / awaiting new direction" state
- subsequent user guidance appears as ordinary chat, attached after that rejection point

## Rejection And Supplements

Phase 1 rule:

- once a run enters approval wait, mid-loop supplements are disabled for that run
- any new user message goes to the deferred follow-up path

Why:

- the tool request that triggered approval has already become the stable decision point
- injecting text into that suspended run before the rejection or approval is settled makes replay and user expectation harder to keep deterministic

After rejection:

- the run remains visible
- the approval wait is resolved as rejected
- the next user message should produce a continuation from the existing transcript after the rejection marker

## Safe Checkpoint Roadmap

Safe checkpoints should expand in layers, not all at once.

### Layer 1: Turn Start

Already implemented in this plan.

Definition:

- immediately before the next LLM request

Properties:

- deterministic replay
- no tool interruption
- no approval ambiguity
- easiest mental model for users

### Layer 2: Post-Tool Pre-Model Boundary

Definition:

- after a tool result has been fully recorded into transcript and events
- before the runtime assembles the next model prompt

This is close to Layer 1 in practice, but the runtime can treat it as an explicit checkpoint class instead of an accidental side effect of the loop shape.

Value:

- clearer internal model
- easier future tracing and debugging
- lets UI explain "the run is between tool execution and next reasoning step"

### Layer 3: Post-Resume Boundary

Definition:

- after a suspended run has been resumed by an explicit decision
- before its first resumed LLM request

This matters for:

- approval approved
- retry / repair resume
- managed process restore paths

Guardrail:

- approval rejected should not reopen the old suspended tool request for injection
- instead it should transition into "awaiting new direction" and let the next continuation start from that stable state

### Layer 4: Explicit Internal Wait States

Definition:

- selected non-tool, non-approval internal waiting points that are already durable and replayable

Examples may include:

- durable repair pass boundaries
- replay recovery boundaries
- managed process observation boundaries

This layer should only be added if:

- the wait state is already explicit in durable state
- the transcript and UI can explain what happened
- replay remains deterministic after restore

## Recommended Implementation Order

1. keep approval rejection as a durable resolved state, not a deleted run
2. add user-facing copy that distinguishes ordinary running from approval wait
3. project rejected runs as "awaiting new direction"
4. keep follow-up routing after rejection deterministic and transcript-based
5. only after that, introduce explicit checkpoint classes beyond plain turn start

## Non-Goals For The Next Step

- no mid-approval injection into a still-suspended approval request
- no hidden deletion of rejected runs
- no hard interruption just to apply user follow-up sooner

## Runtime Rules

At the top of every prompt-loop turn:

1. drain supplement entries that target the current run
2. append them to the live transcript as `USER` messages in arrival order
3. emit explicit supplement runtime events
4. continue prompt assembly with the updated transcript

This gives the model the same information the user sees, without mutating the already-finished part of the current turn.

## Provider-Native And Local Continuation Paths

The supplement semantics should stay unified, but the continuation mechanism does not need to.

Core invariant:

- one durable supplement inbox
- one safe-checkpoint gating model
- one transcript / replay truth source
- each provider route may use its own continuation mechanism after supplement has been durably recorded

### OpenAI Responses Route

When the current route is `openai_responses` and provider lineage is still trusted:

- consume supplements at the normal safe checkpoint
- append them into local transcript first
- emit the ordinary supplement runtime event
- continue the next provider request with `previous_response_id`
- send only the newly added supplement and newly produced tool results as delta input when possible

This route should be treated as the native supplement path, not as a local continuation layer that merely happens to optimize the shared path.

What stays shared is only:

- supplement durability
- checkpoint timing
- transcript / replay truth

What should stay provider-native is:

- lineage continuation
- delta input composition
- provider request resume mechanics

### Anthropic Native Route

Anthropic does not provide a general-purpose equivalent of `previous_response_id` for arbitrary mid-loop continuation.

However, Anthropic native tool use does provide one useful native boundary:

- when the run is continuing immediately after a `tool_use`
- and the host is constructing the matching `tool_result` reply
- the runtime may append extra user guidance after the `tool_result` blocks inside that same user turn

This path is only valid at the tool boundary itself.

It must not be generalized into:

- mid-tool interruption
- approval-wait injection
- arbitrary free-form provider lineage reuse

Outside that narrow tool-result boundary, Anthropic should still use the shared supplement inbox plus transcript-first or local runtime-owned continuation.

### Non-Responses Routes

For `openai`, `anthropic`, and other non-Responses transcript-first routes, the next optimization step should not try to fake a provider lineage token.

Instead, the runtime should add a local continuation layer:

- a durable checkpointed continuation envelope
- owned by runtime checkpoint state, not by the UI
- rebuilt only from prompt-visible state, not hidden chain-of-thought

### Checkpointed Continuation Envelope

Goal:

- keep supplement semantics identical across dialects
- reduce the amount of unnecessary full-turn rebuild work
- make safe-checkpoint continuation more explicit and more debuggable

The envelope should capture the stable next-turn continuation boundary, for example:

- checkpoint class such as `turn_start`, `post_tool_pre_model`, or `post_resume`
- current task prompt / active direction anchor
- current prompt-visible transcript frontier
- latest tool frontier needed for the next step
- active skill / approval-resume / subagent-resume state
- consumed supplement cursor for the active run

The envelope must not contain:

- hidden reasoning
- provider-private opaque state that cannot be reconstructed locally
- ad hoc UI-only projection state

At the next safe checkpoint, the runtime should:

1. load the latest trusted continuation envelope for the active run
2. append newly consumed supplements into transcript and replay as usual
3. if the envelope is still valid, build the next provider request from that checkpointed continuation boundary instead of naively treating every turn as a fresh full replay
4. if the envelope is not valid, fall back to the existing transcript-first rebuild path

This keeps the runtime architecture closer to Claude Code style mid-loop continuation without pretending that non-Responses providers expose native lineage continuation.

### Fallback Rule

If any provider-native or local continuation state becomes untrusted:

- preserve the supplement in transcript and replay
- discard the optimization layer
- continue from the normal transcript-first request path

This fallback rule is mandatory because supplement semantics matter more than provider-side efficiency.

## Durable Replay

Each applied supplement must be written into durable transcript/replay state in two forms:

- a real `USER` transcript message so later runs inherit the guidance as ordinary conversation context
- a replayable supplement observation so the host can reconstruct runtime history and UI projection after restore

If the app restores after a crash:

- consumed supplements are rebuilt from replay
- unconsumed supplements remain in the supplement inbox
- if no run is still active, leftover supplements fall back into the normal follow-up queue

## Ordering

Ordering rules are strict:

- supplements never leapfrog existing deferred follow-up queue items
- supplements for the same run are injected oldest-first
- any supplement not consumed by the time its target run ends is demoted into the normal follow-up queue, preserving arrival order

## UI Projection

Until a supplement is consumed, it is projected as an ephemeral outbound bubble.

After it is consumed, the durable replay event takes over so the user still sees that message as part of the run timeline.

## Non-Goals

This phase does not do the following:

- no hard interruption of an in-flight tool
- no “edit current prompt request in place” while the gateway call is already running
- no mid-tool or mid-approval injection
- no speculative merge across multiple active runs

## Next Phase

After this lands and stabilizes, the next step can extend checkpoint coverage beyond turn start, but only if:

- tool and approval boundaries remain explicit
- replay stays deterministic
- UI can distinguish queued follow-ups from applied live supplements

Recommended follow-on order after the current phase:

1. keep the current durable supplement inbox and turn-start checkpoint as the cross-dialect baseline
2. stabilize `openai_responses` continuation with `previous_response_id`
3. add Anthropic-native tool-boundary supplement only at the strict `tool_result` resume boundary
4. add the local checkpointed continuation envelope for non-Responses routes
5. only after those are stable, consider broader checkpoint classes beyond plain turn start
