# Chat Streaming Display Plan

Last updated: 2026-04-05

## Status

Design proposal

## Purpose

This document defines how OpenCray should support live streaming assistant text in the in-app chat UI only.

The goal is simple:

- when the agent is generating a long answer, the user should see the answer grow in place
- the user should no longer sit on a static `Thinking` placeholder for the whole generation window
- the system should keep the current durable transcript, run journal, and recovery model intact

This plan intentionally does not turn the whole runtime, persistence, or external API surface into a streaming system.

This document complements:

- `docs/chat-single-live-task-design.md`
- `docs/chat-runtime-ux-parity-plan.md`
- `docs/host-rebuild-and-background-task-plan.md`

## Scope

### In scope

- streaming assistant text inside the app chat UI
- one live assistant bubble that grows in place
- same-process UI detach/reattach support while the runtime service remains alive
- provider-by-provider enablement with safe fallback to the current non-streaming behavior
- preserving the current final-answer persistence path

### Explicitly out of scope

- loopback HTTP SSE or WebSocket for chat
- token-by-token durability
- replaying or restoring partial text after process death
- streaming shell output, tool output, or other non-chat surfaces
- changing external messaging behavior
- rewriting the runtime tool loop from scratch

## Executive Summary

OpenCray already has most of the transport and UI observation plumbing needed for chat streaming. Flutter already subscribes to chat and chat-runtime snapshots, and the Android side already has a service-backed observation path.

The real gap is elsewhere:

- the provider, gateway, and runtime chain still waits for a full model response before emitting user-visible assistant text
- the current durable event and transcript stores are the wrong place to put token-level updates
- the current runtime-message projection shape would create many temporary bubbles if reused as-is

The recommended direction is:

1. add an optional streaming path to the provider and gateway chain
2. turn raw provider chunks into throttled full-so-far text snapshots
3. keep those snapshots in memory only
4. bind them to the existing pending assistant message id
5. render them in the same chat bubble
6. persist only the final completed answer

This is a medium-size targeted change across provider, gateway, runtime, service-host snapshot, and Flutter chat rendering.

It is not a full architecture rewrite.

## User-Visible Product Contract

During a long answer:

1. The chat still inserts the normal pending assistant bubble.
2. As soon as the provider yields visible assistant text, that same bubble starts growing in place.
3. If the run finishes normally, the bubble becomes the final persisted assistant message.
4. If the run fails, is cancelled, or is interrupted, the live draft is cleared and the existing terminal behavior remains in charge.
5. If the app page is detached and later reattached while the same runtime service is still alive, the same live draft should still be visible.
6. If the app process or runtime host is recreated, the partial draft may be lost. The system must not pretend it can resume a half-finished answer word-for-word.

## Why This Is Worth Doing

The product problem is not raw latency alone. It is perceived latency.

For short answers, the current `Thinking` bubble is acceptable.

For long answers, it has two visible weaknesses:

- the user receives no proof that generation is progressing
- the eventual large answer appears all at once, which makes long turns feel slower than they really are

Streaming the visible answer text inside the same bubble solves that product problem without forcing OpenCray to become a fully streaming runtime everywhere else.

## Confirmed Current State

The points below are code-backed findings from the current repository state.

### 1. Provider-side streaming support already exists, but only internally

`OpenAiCompatibleLiteLlmProviderClient` already decides whether to request streaming from the provider. Today it only reconstructs Anthropic or Kimi SSE into a final JSON-like payload. It does not propagate live text upward.

OpenAI-compatible text streaming is not yet normalized into a shared live-update path.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt`

### 2. The gateway surface is still synchronous

`LiteLlmGateway` currently exposes only one main shape:

- `execute(request): LiteLlmGatewayResult`

There is no observer, callback, or streaming result contract at the gateway boundary today.

Relevant file:

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`

### 3. Runtime execution still waits for the full gateway result

`OpenCrayAgentRuntime` still calls `gateway.execute(request)` and only emits commentary or final assistant events after the full result has been parsed.

That means even providers that can deliver streaming bytes still look synchronous from the runtime's point of view.

Relevant file:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

### 4. Chat transport to Flutter already exists

Flutter already receives:

- chat snapshot updates
- chat runtime snapshot updates

The Android host bridge already exposes those over `EventChannel`.

This means app-internal streaming does not require inventing a new Android-to-Flutter transport layer.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`
- `flutter_app/lib/core/bridge/opencray_platform_bridge.dart`
- `flutter_app/lib/core/bridge/opencray_local_runtime_bridge.dart`

### 5. The current runtime-message projection shape would create bubble explosion

The current runtime projection path turns runtime events into temporary chat messages.

That works for coarse events such as approvals, supplements, and progress summaries. It is the wrong shape for streaming text, because assistant-projection ids currently include timestamps. If chunk updates were sent through the same path, the UI would see many temporary bubbles instead of one growing bubble.

Relevant file:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

### 6. The current placeholder-to-final path is a good anchor

Chat submission already creates a pending assistant message and later replaces it with the final assistant output.

That existing `pendingMessageId` flow is the correct anchor for a streaming design. The live draft should update the same pending assistant bubble rather than creating a second representation.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/ServiceOwnedChatSubmissionAccess.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

### 7. The current durable stores are the wrong place for partial text

Two current storage shapes make this very clear:

- runtime event journaling persists runtime events per run and file-backed mode stores one event per JSON file
- chat transcript replacement writes the updated workspace record each time `replaceMessage(...)` is called

If partial text updates were written through either path at chunk frequency, the result would be unnecessary I/O amplification and storage churn.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`

### 8. Background behavior is already good enough for UI-only streaming

The current runtime is not page-bound, but it is still process-bound.

That distinction matters:

- leaving the chat page does not inherently stop the task
- a same-process runtime service can stay alive in the background
- app-process or runtime-host recreation still loses in-memory execution ownership

For chat-only streaming, that is acceptable. A live draft may survive page detach and reattach while the same service stays alive. It does not need to survive process death.

Relevant files:

- `docs/host-rebuild-and-background-task-plan.md`
- `app/src/main/kotlin/com/opencray/app/OpenCrayAgentRuntimeService.kt`
- `app/src/main/kotlin/com/opencray/app/RuntimeForegroundController.kt`

## External Reference Findings

The most relevant external references are `claude code` and `codex`.

### Claude Code

Observed from:

- `D:\codes\Opensource\claude-code-main\claude-code-main\src\cli\transports\ccrClient.ts`

Patterns worth copying:

- stream events are buffered for about 100 ms instead of being forwarded one-by-one
- text deltas are coalesced into a full-so-far snapshot before being emitted
- each flush yields a self-contained text snapshot rather than a tiny fragment
- frontend-visible stream events are marked ephemeral
- internal resume or recovery state is kept separate from frontend-visible stream traffic

Practical lesson:

OpenCray should not forward raw token fragments into the UI. It should batch them briefly and emit the current full text so far.

### Codex

Observed from:

- `D:\codes\Opensource\codex\sdk\typescript\src\events.ts`
- `D:\codes\Opensource\codex\sdk\typescript\src\thread.ts`
- `D:\codes\Opensource\codex\codex-rs\app-server-protocol\schema\typescript\ServerNotification.ts`
- `D:\codes\Opensource\codex\codex-rs\app-server-protocol\schema\typescript\v2\AgentMessageDeltaNotification.ts`

Patterns worth copying:

- the high-level stream uses stable item lifecycle events such as `item.started`, `item.updated`, and `item.completed`
- lower-level text deltas are keyed by a stable message or item id
- the client updates the same logical item instead of adding a new item per update

Practical lesson:

OpenCray should not model streaming as a series of new assistant messages. It should model streaming as repeated updates to one stable pending assistant message.

### What OpenCray Should Not Copy

OpenCray does not need to copy the full protocol surface of either project for this feature.

Phase 1 does not need:

- a fully general event-stream protocol redesign
- durable partial-message history
- external API streaming
- a new public protocol for every delta type

It only needs the two patterns above:

- Claude Code's coalesced full-so-far snapshots
- Codex's stable-item update model

## Options Considered

### Option A. Reuse durable runtime events and emit one event per chunk

Rejected.

Why:

- it would flood the runtime event stream
- it would either blow up the file-backed journal or force special truncation logic
- it would fight the current runtime event projection model, which is not designed for high-frequency text updates

### Option B. Call `replaceMessage(...)` on the transcript for every chunk

Rejected.

Why:

- each update would rewrite the local chat workspace
- the transcript would become the live transport, which is the wrong responsibility
- it would increase I/O load without improving durability in a meaningful way

### Option C. Recommended: add a service-owned in-memory live draft lane and keep final persistence unchanged

Recommended.

Why:

- it matches the user-facing goal exactly
- it reuses the existing pending assistant message id
- it keeps durable data small and stable
- it fits the current runtime-service observation path
- it fails safely when streaming is unavailable

## Recommended Design

### Core Rules

- one pending assistant message owns one live draft
- live draft updates are in-memory only
- only safe user-visible assistant text may drive the draft
- streaming must never create extra assistant bubbles
- unsupported routes must fall back to the current non-streaming behavior
- final transcript persistence stays exactly once per completed answer

### Live Draft Lifecycle

1. Chat submission creates the pending assistant message exactly as it does today.
2. The runtime associates a live draft record with that `pendingMessageId`.
3. The provider and gateway emit throttled full-so-far text snapshots.
4. The runtime or service host updates the live draft record in memory.
5. The chat runtime snapshot exposes that live draft to Flutter.
6. Flutter renders the live draft in the same pending assistant bubble.
7. When the run completes, the existing final message replacement persists the final text once.
8. The live draft is then cleared.

### Stream Eligibility Rules

Phase 1 should stream only when all of the following are true:

- the selected route supports a safe text-stream parser
- the provider yields user-visible assistant text blocks that can be recognized without guessing
- the current path is not the legacy JSON-only fallback path

Phase 1 should not stream:

- raw JSON action text
- reasoning text
- invisible protocol scaffolding

If a tool call is observed in the same turn after draft text has already appeared:

- clear the live draft
- return the bubble to the existing running-state behavior
- do not preserve the partial user-facing draft from that turn

That tradeoff is deliberate. It is better than showing a half-answer that is later invalidated by a tool call.

### Update Frequency

Raw provider chunks should be coalesced into updates roughly every 50 to 100 ms.

Each emitted update should carry:

- the same draft identity
- the full text so far
- an updated timestamp

Do not emit only the newest fragment as the only source of truth. Full-so-far snapshots are easier to reconcile and safer after observer reattach.

### Durability Rule

Partial text must not:

- enter `OpenCrayAgentRunEvent`
- enter the durable run journal
- enter transcript replacement on each chunk
- enter checkpoint or resume state

Only the final completed assistant answer should reach durable chat history.

### Background Rule

If the runtime service stays alive in the same process:

- leaving the page should not stop the live draft
- coming back should show the same growing bubble again

If the app process or runtime host is recreated:

- the partial draft may disappear
- the system must not reconstruct a fake partial answer from storage
- the existing recovery and interruption rules remain in charge

## Data Model Direction

This section describes the intended shape. It is not a final API freeze.

### 1. Provider and Gateway Observer

Add a small streaming observer contract on the provider and gateway path.

Minimum useful callbacks:

- text snapshot updated
- tool call observed
- attempt reset or replacement
- completion or terminal cleanup

The important property is not the exact names. The important property is that the runtime receives a stable full-so-far text snapshot and enough boundary signals to clear it safely.

### 2. Service-Owned Live Draft Record

Add an in-memory record similar to:

- `sessionId`
- `runId`
- `taskId`
- `pendingMessageId`
- `text`
- `updatedAtEpochMs`
- optional provider or route metadata for debugging

This record should live on the service-owned runtime or host side, not in Flutter and not in durable storage.

### 3. Chat Runtime Snapshot Extension

Extend `OpenCrayChatRuntimeSnapshot` with a new collection such as:

- `liveAssistantDrafts`

Each entry should be keyed strongly enough that Flutter can reconcile it to one pending assistant message.

The cleanest product anchor is:

- `pendingMessageId`

because the final message replacement path already uses that id.

### 4. Runtime Snapshot Versioning

`runtimeSnapshotVersion(...)` in Flutter currently only considers:

- latest runtime event time
- latest run update time
- latest subagent update time
- host lifecycle creation time

Once live drafts exist, versioning must also consider the latest live draft update timestamp. Otherwise a fresh live-draft snapshot could be treated as older than an event-only snapshot.

## Detailed Implementation Plan

### Phase 0. Guardrails and Design Freeze

Before code changes begin, lock these decisions:

- phase 1 is chat-UI-only streaming
- partial text is in-memory only
- no new durable runtime event type for token or chunk updates
- no per-chunk transcript writes
- no loopback SSE or WebSocket in phase 1

Success condition for this phase:

- no one later tries to route partial text into the journal or transcript path

### Phase 1. Provider and Gateway Streaming Surface

#### Goals

- keep the current synchronous result contract working
- add an optional observer path for live text snapshots
- normalize at least one provider path end-to-end

#### Recommended file targets

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt`

#### Main changes

1. Extend the gateway and provider boundary with an optional streaming observer.
2. Refactor the Anthropic or Kimi SSE reader so it can do two things at once:
   - still build the final payload used by the current parser
   - emit live text snapshots while the stream is being read
3. Add provider gating so unsupported routes silently remain non-streaming.
4. Batch raw chunk delivery into a small coalescing window.

#### Phase 1 provider recommendation

Start with the route family that is already closest to working:

- Anthropic or Kimi SSE text responses

Why:

- the current code already has SSE handling there
- the stream parser is already partially structured
- it is the shortest path to a real end-to-end result

Do not expand to every provider before one route is working cleanly in the UI.

#### Tests required in this phase

- streamed text snapshots are emitted in order
- multiple raw chunks are coalesced into a full-so-far text snapshot
- final provider result stays identical to the non-streaming parse result
- tool-use stream events do not corrupt the final payload
- unsupported routes ignore the observer and still return normal results

### Phase 2. Runtime Integration and Live Draft Lifecycle

#### Goals

- attach the new observer during gateway execution
- keep partial text off the durable runtime event lane
- clear the live draft on every terminal or invalidating boundary

#### Recommended file targets

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- new runtime-side or app-side draft state helper if needed

#### Main changes

1. When the runtime starts a gateway turn, it optionally passes a streaming observer.
2. The observer does not call `emitAssistantEvent(...)` or `emitCommentaryEvent(...)`.
3. Instead, it updates a separate live-draft sink.
4. The runtime clears or resets the draft when:
   - the turn completes successfully
   - the turn fails
   - the task is cancelled
   - the run is interrupted
   - a tool call invalidates the visible draft
   - a retry or fallback attempt replaces the current attempt

#### Important boundary rule

Do not introduce a token-level `OpenCrayAgentRunEvent` subtype in phase 1.

That would push partial text into the wrong system.

#### Fallback and retry rule

If a route attempt has already produced visible draft text and the gateway then retries or falls back:

- clear the current draft before the new attempt becomes active
- do not merge two route attempts into one visible draft

The user should never see a stitched answer that combines partial text from two different attempts.

### Phase 3. Service-Owned Snapshot State

#### Goals

- expose live draft state through the existing chat runtime snapshot path
- keep the state in memory only
- preserve the service-owned execution model

#### Recommended file targets

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt`

#### Main changes

1. Add a service-owned live draft state map keyed by session and pending assistant identity.
2. Update the chat runtime snapshot builder so it includes the live draft collection.
3. Notify runtime snapshot listeners when live draft state changes.
4. Keep the projection-only fallback behavior explicit.

#### Projection fallback recommendation

Phase 1 should not try to make projection-only fallback carry live drafts durably.

It is acceptable if live drafts are only available on the binder-backed or same-service memory path, because the feature itself is intentionally non-durable.

That said, fallback behavior must be explicit and safe:

- if projection fallback cannot provide a live draft, the UI should show the existing pending state rather than a broken mixed state

### Phase 4. Flutter Model and Rendering

#### Goals

- parse the new live draft shape
- resolve the live draft against the existing pending assistant message
- render one bubble that grows in place

#### Recommended file targets

- `flutter_app/lib/core/models/opencray_chat_snapshot.dart`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/test/chat_feature_screen_test.dart`

#### Main changes

1. Extend `OpenCrayChatRuntimeSnapshot` with the live draft collection.
2. Update `runtimeSnapshotVersion(...)` so live draft updates count as newer snapshots.
3. In the chat feature screen, resolve the effective visible text for a pending assistant bubble as:
   - live draft text if present
   - otherwise the existing placeholder or final message text
4. Ensure the same `messageId` remains the visual owner of the bubble.

#### UI rule

The UI must never render a second assistant bubble for the same pending answer solely because streaming text appeared.

This is the central rendering rule.

#### Recommended Flutter behavior

- if the pending assistant message still says `Thinking` and a live draft exists, show the live draft text
- if the final persisted assistant message arrives, stop using the live draft and show the persisted message
- if the draft disappears because of tool-call invalidation or failure, fall back to the current non-streaming running state

### Phase 5. Hardening and Provider Expansion

#### Goals

- remove edge-case flicker
- support more provider families
- keep degradation behavior predictable

#### Candidates

- OpenAI Responses streaming
- OpenAI chat-completions streaming
- more explicit debug metadata for route attempt replacement

#### Important rule

Provider expansion should happen after the first supported route already works end-to-end in the actual chat UI.

## File-By-File Change Map

This is the recommended first-pass change map.

### Core LLM and provider path

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt`
  - add optional streaming observer support
  - pass route attempt metadata needed for reset or cleanup

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
  - emit throttled full-so-far text snapshots while still building the final payload
  - keep provider parsing compatible with the existing completion parser

- `app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt`
  - add observer and coalescing coverage

### Runtime path

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
  - attach the observer
  - route snapshots to live-draft state instead of durable events
  - clear drafts on all terminal and invalidating boundaries

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
  - ideally unchanged in phase 1
  - do not add a token-level durable event just to make streaming work

### Service-owned chat snapshot path

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
  - hold live draft state in memory
  - surface it through chat runtime snapshot generation

- `app/src/main/kotlin/com/opencray/app/OpenCrayChatRuntimeGateway.kt`
  - extend the snapshot contract

- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt`
  - fan out runtime snapshot changes when drafts update

- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt`
  - define explicit behavior when no live draft memory exists

### Flutter

- `flutter_app/lib/core/models/opencray_chat_snapshot.dart`
  - parse new runtime snapshot fields

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
  - reconcile live draft with pending assistant bubble
  - include live-draft timestamps in runtime snapshot versioning

- `flutter_app/test/chat_feature_screen_test.dart`
  - add UI regression tests for single-bubble growth

## Risk Register

### 1. Bubble explosion

Risk:

- reusing the current runtime-event projection path for streaming would create many temporary bubbles

Mitigation:

- do not model live draft updates as projected runtime messages
- bind updates directly to the pending assistant message id

### 2. Disk and journal amplification

Risk:

- routing partial text into run events or transcript writes would create unnecessary storage churn

Mitigation:

- keep partial text strictly in memory
- persist only the final answer

### 3. Provider mismatch

Risk:

- different providers stream different shapes for text and tool calls

Mitigation:

- provider-by-provider capability gating
- do not enable streaming on unsupported routes

### 4. Tool-call interleaving

Risk:

- a model can emit visible text and then pivot into a tool call

Mitigation:

- if a tool call invalidates the draft, clear it and return to the normal running state
- do not commit the partial text

### 5. Retry and fallback contamination

Risk:

- a fallback attempt could accidentally append onto a draft from an older route attempt

Mitigation:

- clear the draft on route-attempt reset
- never merge draft text across attempts

### 6. Legacy JSON leakage

Risk:

- some paths may still produce JSON action text rather than direct user-visible assistant text

Mitigation:

- disable streaming for those paths until the parser can safely distinguish visible text

### 7. Binder or fallback gaps

Risk:

- a temporary fallback snapshot might not include live draft memory

Mitigation:

- make the absence of a draft safe
- fall back to the current pending bubble behavior instead of guessing

### 8. UI jank from update frequency

Risk:

- too many updates can cause frequent rebuilds and poor scrolling behavior

Mitigation:

- throttle updates to a small fixed window
- use full-so-far snapshots so each rebuild is simple

### 9. Test flakiness

Risk:

- coalescing windows can make tests timing-sensitive

Mitigation:

- use injectable clocks, fake schedulers, or deterministic flush hooks in tests

## Testing Plan

### Provider-level tests

- text chunks are coalesced into full-so-far snapshots
- the final parsed payload remains equivalent to the current non-streaming payload
- tool-use streaming does not leak malformed draft text
- unsupported providers ignore the observer cleanly

### Runtime-level tests

- live draft updates do not create durable runtime events
- live draft is cleared on success
- live draft is cleared on failure
- live draft is cleared on cancellation
- live draft is cleared on retry or fallback attempt replacement
- live draft is cleared when a tool-call boundary invalidates it

### Service and snapshot tests

- chat runtime snapshot includes live drafts
- runtime snapshot listeners are notified when draft text changes
- runtime snapshot version increases on draft updates
- projection-only fallback behaves safely when no live draft exists

### Flutter tests

- one pending assistant bubble grows in place
- no duplicate assistant bubbles appear
- `Thinking` is replaced by live draft text when available
- final persisted answer cleanly replaces the live draft
- older runtime snapshots do not overwrite newer live draft content

### Manual QA

- long plain-text answer with no tool call
- answer that triggers a tool before the final answer
- answer cancelled mid-stream
- same-process background then foreground return
- process kill or app restart during a long answer
- provider timeout or fallback route replacement

## Acceptance Criteria

The phase 1 implementation is good enough when all of the following are true:

- supported routes show visible assistant text before final completion
- the user sees one pending assistant bubble, not many streaming bubbles
- long streamed replies do not create a proportional increase in durable runtime events
- long streamed replies do not trigger transcript replacement for every update
- the last live draft text matches the final persisted assistant message text on successful completion
- after process death, the app does not present stale partial text as if it were final

## Delivery Order Recommendation

Recommended order:

1. Anthropic or Kimi path plus service-owned live draft plus Flutter single-bubble rendering
2. harden tool-call invalidation and retry or fallback reset behavior
3. add more provider families only after phase 1 is stable
4. consider loopback SSE or external API streaming only if product requirements expand later

## Deferred Items

These items are intentionally deferred:

- loopback HTTP chat streaming
- durable partial-message replay
- separate commentary streaming lane
- streaming tool-result cards
- process-death restoration of half-generated assistant text

## Final Recommendation

OpenCray should implement chat streaming as:

- one service-owned in-memory live draft
- updated by throttled full-so-far text snapshots
- rendered in one existing pending assistant bubble
- persisted only once at final completion

That is the smallest design that solves the real user problem without creating a large durability, journaling, or protocol redesign problem.
