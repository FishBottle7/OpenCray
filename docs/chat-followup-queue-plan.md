# Chat Follow-up Queue Plan

## Scope

This document defines the staged behavior for user messages that arrive while a chat run is already active.

The immediate goal is to match the safer first-phase behavior used by tools like OpenClaw and current Codex-style products:

- do not interrupt the active run
- do not cancel and replace the active run
- queue follow-up user input durably
- let the current run finish naturally
- drain queued follow-ups in order

Mid-loop user-input injection is explicitly deferred to a later phase.

## Phase 1

Phase 1 uses a durable per-session follow-up queue outside the transcript.

Behavior:

- If the current session already has a non-terminal run, a new user message is queued instead of starting a second run.
- Queued user messages are projected into the chat UI as ephemeral outbound bubbles so the user can see they were accepted.
- When the active run reaches a terminal state, the host automatically starts the next queued follow-up.
- Follow-ups drain sequentially, one queued user input per new run.
- If the app restores an idle session that still has queued follow-ups, the host starts draining from the oldest queued input.

Non-goals for Phase 1:

- no mid-loop transcript mutation
- no prompt merging into an already running turn
- no cancellation or superseding of the active run just because the user typed again

## Phase 2

Phase 2 can add explicit mid-loop injection, but only after Phase 1 remains stable.

Recommended order:

1. define exactly which runtime states may accept injected user input
2. persist injected-input markers in durable replay/transcript state
3. ensure approval waits and tool execution boundaries do not accept unsafe injection
4. add UI language that distinguishes queued follow-ups from injected live guidance

## Guardrails

- Keep queued follow-up storage durable and session-scoped.
- Keep transcript writes atomic when promoting queued input into a real submitted turn.
- Prefer visible user-facing fidelity over hidden synthetic prompt merging.
- Do not reintroduce host-only supersede logic unless product direction explicitly changes.
