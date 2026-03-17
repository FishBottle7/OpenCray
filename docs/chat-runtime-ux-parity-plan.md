# Chat Runtime UX Parity Plan

## Goal

Bring OpenCray's in-chat agent run experience closer to OpenClaw and Codex in the specific areas the current product still misses:

1. running state should read like a public timeline, not a vague spinner
2. tool steps should expose concrete action and concrete result
3. approvals should expose requested action, target, and agent reason in a first-class way
4. the system should expose public progress summaries without exposing raw chain-of-thought

This document is intentionally about runtime-to-UI projection, not only Flutter rendering.

## External Reference Findings

### OpenClaw

Observed from local source and product docs:

- `D:\codes\Opensource\openclaw\ui\src\ui\app-tool-stream.ts`
- `D:\codes\Opensource\openclaw\ui\src\ui\views\chat.ts`
- `D:\codes\Opensource\openclaw\ui\src\ui\chat\grouped-render.ts`
- `D:\codes\Opensource\openclaw\ui\src\ui\chat\tool-cards.ts`
- `D:\codes\Opensource\openclaw\ui\src\ui\views\exec-approval.ts`
- `https://docs.openclaw.ai/guides/how-it-works/`
- `https://docs.openclaw.ai/features/tui/`

Key product pattern:

- OpenClaw does not rely on a separate "running card" as the primary source of truth.
- It projects agent activity into a typed timeline: assistant output, tool call, tool result, approvals, completion.
- Tool cards show both the requested action and a preview of the returned output.
- Reasoning is treated as a separate public lane and is user-toggleable; it is not conflated with tool output.
- Approval requests are explicit UI state with command, cwd, security, and ask/reason fields.

### Codex

Observed from official docs and official repo materials:

- `https://developers.openai.com/codex/`
- `https://github.com/openai/codex/blob/main/docs/sandbox.md`
- `https://github.com/openai/codex/blob/main/docs/config.md`

Key product pattern:

- Codex treats sandbox and approval mode as first-class runtime policy, not a UI-only concern.
- The product exposes an action transcript and resumable run state rather than hiding all intermediate execution behind a single pending bubble.
- It uses summarized reasoning/progress surfaces rather than exposing raw internal chain-of-thought.
- Approval and sandbox state are part of the same execution flow as tools and final answers.

## Current OpenCray Gap

Current OpenCray already has:

- durable run/event storage
- tool call/result events
- pending approval cards
- fullscreen run history

But it still misses two important runtime primitives:

1. there is no public intermediate progress event
2. approval and tool detail fields are still mostly flattened into generic text

That is why the current UI skews toward raw tool logs. The Flutter layer is not hiding rich reasoning text; the runtime is simply not emitting a public progress/reasoning-summary event.

## Design Decision

Do not expose raw chain-of-thought.

Instead add a new public runtime event category for short user-visible progress summaries. This should represent statements such as:

- scanning the workspace structure before editing
- comparing two candidate files
- waiting for approval before mutating the workspace
- interpreting a tool result before deciding the next action

These summaries are public artifacts, durable, replayable, and safe to show to the user.

## Event Model Direction

Add a new event kind:

- `progress`

Suggested payload shape:

- `text`
- optional `stage`
- optional `source` such as `model_progress`

Protocol shape exposed to the model:

- `{"type":"progress","text":"Scanning README and Gradle files before editing."}`

Rules:

- public only
- short, factual, non-sensitive
- not a hidden reasoning dump
- can occur between tool calls
- does not terminate the run

## Approval Payload Direction

Pending approvals should keep the existing free-text body for compatibility, but the host snapshot should standardize structured fields for UI rendering:

- `toolName`
- `requestSummary`
- `primaryDetail`
- `pathDetails`
- `workingDirectory`
- `reason`
- `body`
- `isHighRisk`

This avoids forcing Flutter to parse localized prose to discover the real requested action.

## Tool Timeline Direction

Run history entries should distinguish:

1. public progress summary
2. tool call detail
3. tool result detail
4. approval wait state
5. final assistant answer

Compact cards should keep the last few meaningful entries and skip empty placeholder text.

Fullscreen view should stay history-first and show:

- progress summaries
- tool arguments
- tool result previews
- approval wait entries

## Implementation Order

### Phase 1. Public progress events

- extend runtime action protocol with `type=progress`
- add `OpenCrayProgressEvent`
- append progress summaries into runtime transcript as public replayable observations
- project them through host runtime snapshots
- render them in Flutter history and compact cards

### Phase 2. Structured approval snapshot fields

- standardize approval detail fields in `PendingApprovalSnapshot`
- keep compatibility with existing `body`
- let Flutter render tool, request detail, reason, and risk more explicitly

### Phase 3. Richer tool detail/result previews

- keep current tool-specific summaries
- project more structured result metadata where available
- avoid collapsing everything into `contentPreview.take(...)`

### Phase 4. Pruning and repair for the new event type

- classify `progress` as replayable but compactable
- keep recent meaningful progress
- allow older low-value progress noise to compact away
- preserve terminal outcomes and approval/cancel decisions

## Invariants

- approvals remain runtime-owned suspended state
- user-visible progress is public and durable
- no raw chain-of-thought exposure
- replay stays bounded
- Flutter should not invent semantic state that runtime did not emit
