# 2026-04-21 Agent Run Pipeline Review

## Scope

Review target:
- user sends instruction
- context assembly
- model invocation
- streaming or non-streaming result handling
- bubble rendering rules
- run inspector visibility
- persistence

Required product expectations from user:
- tool calls must not use chat bubbles
- `process` and `final answer` information should use bubble output, streamed or non-streamed as appropriate
- all information must appear in Run Inspector
- all information must be durably persisted

## Method

I am tracing the actual code path instead of trusting docs or tests:
1. locate entrypoints and runtime orchestration
2. inspect event model and transcript persistence
3. inspect UI projection for bubbles and inspector
4. compare behavior against required product semantics
5. record concrete findings with file references and rationale

## Initial map

Likely code areas:
- Android host/runtime bridge in `app/src/main/kotlin/com/opencray/app/`
- runtime orchestration in `runtime/src/main/kotlin/com/opencray/runtime/`
- transcript and queue persistence in `runtime/.../session/` and `persistence/...`
- Flutter bubble / inspector projection in `flutter_app/lib/features/chat/` and bridge models

## Working notes

- `draft/` already exists in the repository. I am reusing it rather than creating a duplicate folder.
- Need to verify whether the active chat UI is Flutter-only, Android-native-only, or mixed through a gateway.

## Confirmed findings in progress

### F1. First prompt in a session can enter the model context twice, and the duplicate copy is not structurally identical

Severity: high

Evidence chain:
- `ChatSubmissionCoordinator.submitPromptRun()` first writes the user turn and a pending assistant placeholder into `ChatSessionLocalStore.appendSubmittedTurn(...)`.
- `ServiceOwnedChatSubmissionAccess.submitPromptRun(...)` calls `handle.ensureProcessing()` only after `appendSubmittedTurn(...)` succeeds, so the runtime does not need a race to observe the just-written chat turn.
- `AppAgentSessionTaskRuntimeFactory.prepareSessionContext(...)` then calls `sessionContextFactory.create(...)` with `visibleThroughMessageId = pendingMessageId` and `excludedMessageIds = setOf(pendingMessageId)`.
- That means the base chat conversation already includes the current user message, but excludes only the assistant placeholder.
- Immediately after seeding, `prepareSessionContext(...)` appends `task.input` again into the runtime transcript store when `promptResumeState == null`.

Why this is bad:
- On the first prompt of a session, `SessionTranscriptStore` is empty, so `seedIfEmpty(baseContext.conversation)` stores the current user message once.
- The later `appendTaskInputToTranscript` stores the same logical user turn a second time.
- The second copy may be worse than a plain duplicate because `task.input` is formatted by `ChatRuntimeTextFormatter.format(...)`, which can inline attachment descriptors into the text body, while the chat-session message path stores attachments structurally.
- Result: the first turn can present the model with duplicated user intent and mixed attachment representations.

Files involved:
- `app/src/main/kotlin/com/opencray/app/ServiceOwnedChatSubmissionAccess.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/ChatRuntimeSessionContextFactory.kt`
- `app/src/main/kotlin/com/opencray/app/ChatRuntimeTextFormatter.kt`

Preliminary conclusion:
- This is a real context-assembly bug, not a hypothetical style issue.

### F2. Streaming assistant draft text is bubble-visible, but it is not durably persisted and does not enter Run Inspector history

Severity: high

Evidence chain:
- `OpenCrayAgentRuntime.assistantDraftObserver(...)` emits `onAssistantDraftUpdated` / `onAssistantDraftCleared` callbacks from the model stream observer.
- `OpenCrayHostRuntime` stores those drafts only in the in-memory map `liveAssistantDraftsBySession`.
- `runtimeActivitySnapshotMap(...)` exposes drafts under `liveAssistantDrafts`, but not as runtime `events`.
- Flutter consumes live draft events in `_handleLiveAssistantDraftEvent(...)` and patches chat message bubbles directly.
- Run trace / inspector history is built from `runtimeSnapshot.events`, durable subagent snapshots, and managed process snapshots. There is no draft event kind in that history path.

Why this is bad:
- Mid-stream visible text can appear in the chat bubble but disappear on restart or process death because it is not journaled.
- The same mid-stream text does not show up in Run Inspector history even though the user explicitly requires all information to appear there.
- This produces a split-brain UI: bubble sees data that inspector and durable storage do not.

Files involved:
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Preliminary conclusion:
- The current implementation only supports ephemeral stream rendering, not durable stream observability.

### F3. Managed process output is rendered in run-trace history, not in chat bubbles

Severity: high relative to requested product semantics

Evidence chain:
- `OpenCrayHostRuntime.projectedRuntimeMessageText(...)` only projects `assistant_phase` and selected `supplement` events into chat bubbles.
- Tool calls, tool results, approvals, cancellations, and anything else return `null` there.
- Managed processes are not runtime events in that bubble projection path.
- Flutter adds managed process information through `_buildRunManagedProcessHistory(...)`, which feeds run-trace history entries, not chat message bubbles.

Why this is bad:
- The user requirement for this review is explicit: tool calls should not use bubbles, but `process` and `final answer` should use bubble output.
- Current code satisfies the “tool calls not bubbles” part, but it does not satisfy the “process via bubbles” part.
- Process information is effectively inspector/history-only.

Files involved:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Preliminary conclusion:
- This is a direct product-semantics mismatch.

### F4. Tool result summaries can still leak into the final assistant bubble

Severity: high relative to requested product semantics

Evidence chain:
- `OpenCrayHostRuntime.finalTextForLocked(...)` computes `toolSummaryFallback`.
- When the run succeeds with blank/internal stdout, or fails in certain cases, the final displayed assistant text falls back to `successfulToolSummaryFallbackTextLocked(...)`.
- That fallback is produced from the latest successful `OpenCrayToolResultEvent` via `chatToolResultText(...)`.

Why this is bad:
- Even though `projectedRuntimeMessageText(...)` correctly suppresses tool call/result bubbles, the final assistant message path can still surface tool-result-derived text as a normal assistant bubble.
- That violates the requested separation: tool activity should stay out of bubble output.

Files involved:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

Preliminary conclusion:
- Bubble suppression for tool activity is not end-to-end; it is bypassed by the final-text fallback path.

### F5. Failed tool interactions are not replayed into the durable runtime transcript with the same fidelity as successful ones

Severity: medium to high

Evidence chain:
- `AppAgentSessionTaskRuntimeFactory.transcriptAwareEventSink(...)` only records tool replay messages when `OpenCrayToolResultEvent.result.status == SUCCESS`.
- During the live turn, `OpenCrayAgentRuntime.applyPromptToolResult(...)` does append failed tool results into the in-memory prompt cursor transcript.
- After the run finishes, durable replay repair falls back to generic terminal observations such as `run_interrupted` / `retry_abandoned`, not the original structured failed tool interaction.

Why this is bad:
- Within the active turn, the model has the failed tool result in context.
- Across restart or on later turns, the durable transcript can lose the structured details of that failed tool invocation.
- That weakens future context assembly precisely in the cases where prior failure details matter for retries and debugging.

Files involved:
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`

Preliminary conclusion:
- Successful tools and failed tools do not have parity in durable replay.

### F6. Final assistant output can diverge between chat UI and durable transcript, and attachment-only final answers can disappear from future model context

Severity: high

Evidence chain:
- `OpenCrayAgentRuntime` emits a final assistant event when the model returns `AgentModelAction.Final`.
- `AppAgentSessionTaskRuntimeFactory.transcriptAwareEventSink(...)` drops final assistant replay events because `recordAssistantReplayEvent(...)` returns early when `event.isFinal` is true.
- The only durable transcript write for a successful final answer is `recordSuccessfulAssistantTurn(...)`, which appends `result.stdout` only when that text is non-blank.
- Host-side final chat rendering is computed later in `OpenCrayHostRuntime.onTaskFinished(...)` by taking `finalTextForLocked(...)`, then applying attachment markdown compatibility rewriting, resolving final attachments, and finally writing the resulting assistant message into chat storage.
- If the final answer is attachment-only, `finalizedAssistantText(...)` intentionally keeps the stored assistant text blank while preserving attachments for the visible chat message.
- Future prompt preparation does not use the visible chat transcript as the authoritative source once the transcript store is populated; `prepareSessionContext(...)` ultimately passes `transcriptStore.snapshot()` into the runtime session context.

Why this is bad:
- A user can see an assistant completion in chat, including attachments, while the durable transcript used for later model turns contains no corresponding final assistant turn.
- Even when there is text, the durable transcript can preserve the raw runtime stdout while the chat bubble shows a host-rewritten version after attachment compatibility or sanitization.
- Result: future context assembly can diverge from what the user actually saw, which is a direct integrity bug in the input -> persistence -> later-context loop.

Files involved:
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

Preliminary conclusion:
- The final-answer persistence path is split between runtime transcript storage and host chat storage, and the two do not preserve the same artifact.

### F7. Final-answer attachments are visible in chat bubbles but are absent from Run Inspector data

Severity: high

Evidence chain:
- Final attachment requests travel through `ExecutionResult.metadata[FINAL_ATTACHMENTS_JSON]` and are resolved by `OpenCrayHostRuntime.finalAttachmentsForResultLocked(...)`.
- Those resolved attachments are written into the assistant chat message in `onTaskFinished(...)`.
- Runtime event projection for assistant phases only serializes scalar fields like `text`, `phase`, `responseFormat`, and `isFinal`; it does not serialize final attachments.
- Run snapshots also do not expose any final-assistant attachment payload separate from chat messages.
- Flutter Run Inspector history is assembled from runtime events, durable subagent events, run context sections, and managed process snapshots, not from the chat bubble attachment payload.

Why this is bad:
- A final answer with files, images, or voice artifacts can be fully visible in the chat bubble while Run Inspector has no way to show the same result payload.
- This directly violates the requirement that all information must appear in Run Inspector.
- The issue is worst for attachment-only final answers, where the inspector may degrade to an empty or generic final entry while the actual user-visible output lives only in the chat transcript.

Files involved:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Preliminary conclusion:
- Final-answer attachment data currently has a bubble path, but no inspector path.

## Testing gaps noted so far

- I found tests for transient live-draft rendering, suppression of tool payload bubbles, and managed-process visibility in runtime snapshots or run trace UI.
- I did not find coverage for restart/durability parity of live assistant drafts, first-turn duplicate user input in runtime transcript seeding, failed-tool durable replay parity, or final-answer attachment parity between chat bubbles, durable transcript, and Run Inspector.
- Existing tests appear stronger on immediate UI projection than on end-to-end persistence/replay invariants.

## Update After Test-Guided Repair

### Added regression coverage

Kotlin regressions added earlier in this review cycle:
- `AppAgentSessionTaskRuntimeFactoryToolCallTest.firstPromptRunUsesStructuredChatTurnOnceWhenChatStoreAlreadyContainsSubmittedTurn`
- `AppAgentSessionTaskRuntimeFactoryTodoStoreTest.transcriptAwareEventSinkPersistsFailedToolResultReplayMessages`
- `AppAgentSessionTaskRuntimeFactoryTodoStoreTest.recordSuccessfulAssistantTurnPersistsAttachmentOnlyFinalAnswerIntoTranscript`
- `OpenCrayHostRuntimeTest.taskFailureDoesNotSurfaceSuccessfulToolSummaryInFinalAssistantBubbleForPromptRuns`
- `OpenCrayHostRuntimeTest.chatSnapshotProjectsManagedProcessMessagesBeforePendingAssistantReply`
- `OpenCrayHostRuntimeTest.recreatedHostsExposeFinalAttachmentsOnRetainedRunInspectorPayload`
- `OpenCrayHostRuntimeTest.recreatedHostsRestorePersistedLiveAssistantDraftsIntoRuntimeSnapshotAndInspectorHistory`

Flutter regressions added in this repair pass:
- `opencray_chat_snapshot_test.dart`: `chat run snapshot parses final attachments`
- `chat_feature_screen_test.dart`: `retained terminal runs show final attachments in fullscreen inspector`

### Important refinement on F1

My first static read overstated the exact symptom.

What the request-capture test showed after instrumentation:
- the extra `USER` message in the provider request was the front-context tool protocol prompt, not a second copy of the user instruction
- the real bug on the first structured prompt path was subtler: when the chat store already held the submitted turn, the seeded transcript kept the lower-fidelity attachment payload (`filePath = null`) instead of upgrading it with the richer runtime attachment metadata from task metadata
- that meant the model request could preserve only the degraded attachment record unless the runtime appended a second prompt turn

Repair:
- `AppAgentSessionTaskRuntimeFactory.prepareSessionContext(...)` now merges the richer prompt attachment payload into the seeded transcript entry instead of blindly skipping or duplicating the current prompt
- `OpenCrayAgentRuntime.seededConversation(...)` now treats attachment records with equivalent identity but different field completeness as the same logical prompt turn, preventing re-append loops
- the test now scopes its count to the actual user prompt content instead of all `USER` role transport messages

### Issues closed by code and tests

- F1 closed by seeded-transcript prompt merge plus request-capture verification.
- F2 closed by persisting live assistant drafts into runtime events and restoring them into `liveAssistantDrafts` and inspector history after host recreation.
- F3 closed by projecting managed process status/output into chat bubbles before the pending assistant reply, with retained inspector history still present.
- F4 closed by suppressing successful tool-summary fallback text for prompt runs, so tool output does not leak into the final assistant bubble.
- F5 closed by persisting failed tool call/result replay messages into the durable transcript with structured parity.
- F6 closed by persisting attachment-only final answers into the durable transcript instead of dropping them when `stdout` is blank.
- F7 closed end-to-end by exposing `finalAttachments` on retained run snapshots and rendering them in the Flutter fullscreen Run Inspector.

### Verification performed on 2026-04-21

- Kotlin targeted unit tests:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryToolCallTest.firstPromptRunUsesStructuredChatTurnOnceWhenChatStoreAlreadyContainsSubmittedTurn" --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest.transcriptAwareEventSinkPersistsFailedToolResultReplayMessages" --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest.recordSuccessfulAssistantTurnPersistsAttachmentOnlyFinalAnswerIntoTranscript" --tests "com.opencray.app.OpenCrayHostRuntimeTest.taskFailureDoesNotSurfaceSuccessfulToolSummaryInFinalAssistantBubbleForPromptRuns" --tests "com.opencray.app.OpenCrayHostRuntimeTest.chatSnapshotProjectsManagedProcessMessagesBeforePendingAssistantReply" --tests "com.opencray.app.OpenCrayHostRuntimeTest.recreatedHostsExposeFinalAttachmentsOnRetainedRunInspectorPayload" --tests "com.opencray.app.OpenCrayHostRuntimeTest.recreatedHostsRestorePersistedLiveAssistantDraftsIntoRuntimeSnapshotAndInspectorHistory"`
- Dart static analysis:
  - `dart analyze flutter_app`
- Flutter targeted tests:
  - `flutter test test/opencray_chat_snapshot_test.dart test/chat_feature_screen_test.dart`

### Residual risk

- I validated the repaired seams with targeted Kotlin and Flutter tests, not the entire repository test suite.
- The current repair is focused on the user-requested chain: prompt submission, context assembly, model call, bubble projection, Run Inspector visibility, and persistence parity.

## Second-round review after repair

### Re-check status

- The first-round host/runtime repairs look materially present in the current codebase: seeded prompt merge, managed-process bubble projection on the main host path, prompt-run tool-summary suppression, failed-tool transcript replay, retained-run `finalAttachments`, and persisted `Draft` assistant-phase restoration.
- This second pass focused on what the earlier repair set still did not close: projection-only fallback behavior, service-owned/service-backed read paths, restart windows, and visible-chat vs durable-context parity.

### F8. Projection-only and fallback read paths still lose streamed bubble semantics after the main-path repair

Severity: high

Evidence chain:
- `ProjectionOnlyOpenCrayChatRuntimeGateway.runtimeProjectionForSession(...)` still serializes only `activeRuns`, `retainedRuns`, `subAgents`, and `events` in the runtime payload; it does not emit `liveAssistantDrafts`.
- `ProjectionOnlyOpenCrayChatRuntimeGateway` also does not override `observeLiveAssistantDraftEvents(...)`, so the interface default remains a no-op.
- `ServiceBackedOpenCrayChatRuntimeGateway.observeChatRuntime(...)` and `observeLiveAssistantDraftEvents(...)` start from `fallbackGateway`, and `currentReadGateway()` falls back to that projection gateway whenever the service-backed gateway is unavailable.
- `ServiceOwnedChatRuntimeGateway.decorateChatRuntimePayload(...)` can only overlay live drafts that arrive after startup into its in-memory map; it does not rebuild them from durable state, and `loadChatRunSnapshot(...)` bypasses even that limited decoration.
- On the projection chat path, `loadProjectionChatSnapshot(...)` only re-renders persisted chat messages and terminal placeholder replacement; there is no projection path for live draft bubbles or managed-process bubbles.

Why this is bad:
- The main host runtime now restores persisted streamed drafts, but the fallback/service-owned read path can still drop them after a service restart or during binder fallback because it never reconstructs `liveAssistantDrafts` from the journal.
- In those modes, the user can still see draft history in Run Inspector events, but not the streamed bubble state that the product requires for visible process/final-answer progress.
- The repaired product behavior is therefore not end-to-end; it only holds on the primary host runtime path.

Files involved:
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt`

Key references:
- `ProjectionOnlyOpenCrayChatRuntimeGateway.runtimeProjectionForSession(...)`
- `ProjectionOnlyOpenCrayChatRuntimeGateway.loadProjectionChatSnapshot(...)`
- `ProjectionOnlyOpenCrayChatRuntimeGateway.renderedProjectionMessages(...)`
- `OpenCrayChatRuntimeGateway.observeLiveAssistantDraftEvents(...)`
- `ServiceBackedOpenCrayChatRuntimeGateway.currentReadGateway()`
- `ServiceOwnedChatRuntimeGateway.decorateChatRuntimePayload(...)`
- `ServiceOwnedChatRuntimeGateway.loadChatRunSnapshot(...)`

### F9. Projection/service fallback run snapshots still diverge from the host Run Inspector contract

Severity: high

Evidence chain:
- The host runtime run snapshot now exposes `finalAttachments`, `llmDiagnostics`, `liveContext`, `contextBudget`, `memoryTrace`, `memoryFlush`, `bootstrap`, `durableCompaction`, `skillInventory`, and `activeSkill`.
- `ProjectionOnlyOpenCrayChatRuntimeGateway.runSnapshotToMap(...)` still omits all of those fields and only exports the basic lifecycle/process/recovery subset.
- Both `ServiceBackedOpenCrayChatRuntimeGateway.loadChatRunSnapshot(...)` and `ServiceOwnedChatRuntimeGateway.loadChatRunSnapshot(...)` read through the current/fallback gateway payload instead of normalizing to the richer host contract.
- The current projection tests assert terminal text, journal replay, and managed-process snapshots, but I did not find assertions covering projection parity for `finalAttachments` or the newer run-inspector context sections.

Why this is bad:
- The same run can expose a richer inspector payload on the main host path and a reduced payload on the fallback/service-backed path.
- That creates a real observer split: the user-visible Run Inspector becomes mode-dependent for final attachments and several context/debug sections that were explicitly added to satisfy the first review.
- This is not just a missing convenience field. It breaks the user's requirement that all information be visible in Run Inspector regardless of transport/runtime path.

Files involved:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt`
- `app/src/test/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGatewayTest.kt`

Key references:
- `OpenCrayHostRuntime.runSnapshotToMap(...)`
- `ProjectionOnlyOpenCrayChatRuntimeGateway.runSnapshotToMap(...)`
- `ServiceBackedOpenCrayChatRuntimeGateway.loadChatRunSnapshot(...)`
- `ServiceOwnedChatRuntimeGateway.loadChatRunSnapshot(...)`

### F10. Persisted draft restore still has a stale-draft resurrection window because draft clear is not journaled

Severity: medium to high

Evidence chain:
- `OpenCrayHostRuntime.onAssistantDraftUpdated(...)` now records a persisted `assistant_phase` runtime event with stage `Draft`.
- `OpenCrayHostRuntime.onAssistantDraftCleared(...)` clears only the in-memory draft map and emits a live draft event payload; it does not record a corresponding durable clear marker.
- `OpenCrayHostRuntime.persistedAssistantDraftForRun(...)` reconstructs a draft from the latest persisted `Draft` event unless it can see a newer non-draft visible event.
- `OpenCrayAgentRuntime.clearAssistantDraft(task)` is invoked before several later recovery or continuation events are emitted.

Why this is bad:
- If the app/service crashes after `clearAssistantDraft(task)` but before the next visible runtime event is durably recorded, restart will replay the last persisted `Draft` assistant-phase event and resurrect a draft the user already saw disappear.
- This is a real durability edge case, not a cosmetic timing issue, because the restore logic explicitly trusts the latest persisted `Draft` event in the absence of a newer visible event.
- The earlier repair solved “draft updates are persisted”, but not “draft clears are durably ordered”.

Files involved:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

Key references:
- `OpenCrayHostRuntime.onAssistantDraftUpdated(...)`
- `OpenCrayHostRuntime.onAssistantDraftCleared(...)`
- `OpenCrayHostRuntime.persistedAssistantDraftForRun(...)`
- `OpenCrayAgentRuntime.clearAssistantDraft(...)`

### F11. Markdown-compatibility final attachments still do not have durable context parity with what the user sees

Severity: high

Evidence chain:
- On the visible chat path, `OpenCrayHostRuntime.onTaskFinished(...)` computes `attachmentMarkdownCompatibilityLocked(...)`, merges those compatibility attachments into `finalAttachmentsForResultLocked(...)`, and writes the result into chat storage.
- On the durable transcript path, `AppAgentSessionTaskRuntimeFactory.recordSuccessfulAssistantTurn(...)` still persists assistant attachments only through `finalTranscriptAttachments(result)`, which reads `ExecutionResult.metadata[FINAL_ATTACHMENTS_JSON]`.
- `finalTranscriptAttachments(...)` does not look at the host-side markdown-compatibility extraction path at all.
- Future context assembly does not keep using the chat-session transcript once the runtime transcript store is populated; `prepareSessionContext(...)` seeds from chat messages only when the transcript store is empty, and afterwards uses `transcriptStore.snapshot()`.
- `ChatRuntimeSessionContextFactory.create(...)` proves that the richer visible chat attachments do exist in chat storage, but that richer view is not the long-term source of truth for later model context.

Why this is bad:
- A final answer can show attachment references correctly in chat and in host-side final attachment handling, while later model turns only remember the narrower explicit attachment set from `FINAL_ATTACHMENTS_JSON`.
- In practice this means the user-visible assistant answer and the durable model context can still diverge after the first repair, especially for markdown-derived compatibility attachments.
- This is the same class of integrity problem as the first review, just narrowed to the compatibility-attachment branch that the earlier repair did not close.

Files involved:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/ChatRuntimeSessionContextFactory.kt`

Key references:
- `OpenCrayHostRuntime.onTaskFinished(...)`
- `OpenCrayHostRuntime.attachmentMarkdownCompatibilityLocked(...)`
- `OpenCrayHostRuntime.finalAttachmentsForResultLocked(...)`
- `AppAgentSessionTaskRuntimeFactory.recordSuccessfulAssistantTurn(...)`
- `AppAgentSessionTaskRuntimeFactory.finalTranscriptAttachments(...)`
- `AppAgentSessionTaskRuntimeFactory.prepareSessionContext(...)`
- `ChatRuntimeSessionContextFactory.create(...)`

## Second-round test notes

Targeted verification I ran on 2026-04-21:
- `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.ProjectionOnlyOpenCrayChatRuntimeGatewayTest" --tests "com.opencray.app.ServiceOwnedChatRuntimeGatewayTest" --tests "com.opencray.app.OpenCrayHostRuntimeTest.recreatedHostsRestorePersistedLiveAssistantDraftsIntoRuntimeSnapshotAndInspectorHistory"`

Result:
- The targeted Kotlin tests passed.
- The passing tests increase confidence that the first-round main-path repairs are real.
- They do not close the new findings above because I still did not find assertions for:
  - projection/runtime fallback parity for `finalAttachments` and the richer run-inspector metadata sections
  - durable replay behavior when a draft is cleared and the host/service crashes before the next visible event
  - transcript/context parity for markdown-compatibility attachments that exist only on the host finalization path

## Second-round repair notes

Date: 2026-04-21

I implemented the second-round fixes for F8-F11 after adding the missing regression tests first.

### Repair summary

1. Projection/service fallback now restores the same live draft + process/commentary bubble semantics as the host path.
   - `ProjectionOnlyOpenCrayChatRuntimeGateway` now projects:
     - `liveAssistantDrafts`
     - `updatedAtEpochMs`
     - ephemeral commentary/process chat bubbles anchored before the assistant placeholder
   - It also exposes `observeLiveAssistantDraftEvents(...)` via polling diff instead of silently dropping the stream on fallback paths.
   - I removed the interface-level silent empty implementation and wired the loopback HTTP fallback gateway too, so draft observation no longer degrades to a no-op simply because the runtime path changed.

2. Projection/fallback run snapshots now carry the same inspector payload sections as the host path.
   - `ProjectionOnlyOpenCrayChatRuntimeGateway.runSnapshotToMap(...)` now emits:
     - `finalAttachments`
     - `llmDiagnostics`
     - `liveContext`
     - `contextBudget`
     - `memoryTrace`
     - `memoryFlush`
     - `bootstrap`
     - `durableCompaction`
     - `skillInventory`
     - `activeSkill`
   - I reused the shared parsing helpers in `RunSnapshotInspectorSupport.kt` so the projection path stops drifting from the host contract.

3. Draft clear is now durably journaled.
   - `OpenCrayHostRuntime.onAssistantDraftCleared(...)` now records a persisted `assistant_phase/Draft` event with empty text in addition to clearing in-memory state and emitting the live clear event.
   - This closes the crash window where a draft could disappear in UI, crash before the next visible event, and then resurrect after restart.
   - I deliberately made the clear journal write independent from whether an in-memory draft entry still exists, because after recreation the visible draft may be coming only from durable replay.

4. Markdown compatibility attachments now persist into transcript/context, not only visible chat.
   - `AppAgentSessionTaskRuntimeFactory.recordSuccessfulAssistantTurn(...)` now computes a final transcript turn that:
     - resolves markdown attachment references from assistant stdout
     - looks up both current-run artifacts and prior chat-store attachments
     - rewrites text when the markdown token is only an attachment reference
     - persists the compatibility attachments into the transcript store
   - I added a small read helper in `ChatRuntimeSessionContextFactory` so transcript persistence can enumerate prior chat attachments from the richer chat store, which is the exact missing parity the review found.
   - I also broadened the markdown attachment parser to accept both `attachment:` links and plain relative paths like `attachments/final/diagram.png`; otherwise the new transcript regression test would still fail.

### Verification

Targeted verification I ran after the repairs:

- `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.ProjectionOnlyOpenCrayChatRuntimeGatewayTest" --tests "com.opencray.app.OpenCrayHostRuntimeTest.recreatedHostsDoNotRestoreAssistantDraftAfterPersistedClearMarker" --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest.recordSuccessfulAssistantTurnPersistsMarkdownCompatibilityAttachmentsIntoTranscript"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.ServiceOwnedChatRuntimeGatewayTest" --tests "com.opencray.app.OpenCrayRuntimeServiceHostTest"`

Result:
- All of the above passed on 2026-04-21.

### Residual risk

- I did not run the full `:app:testDebugUnitTest` suite in this round; I ran the newly added reproductions plus the service/fallback regression suites most likely to be affected by these changes.
- The host-side visible final-message markdown compatibility path still has separate logic from the transcript-side repair. The behavior is now materially consistent for the repaired scenarios, but the duplication is still a maintenance risk if future attachment parsing rules change again in only one place.

## Third-round review

Date: 2026-04-21

This pass focused on the exact chain the user asked for:
- user instruction ingestion
- context assembly / transcript seeding
- model call / streaming
- commentary vs final bubble projection
- tool call visibility in Run Inspector
- fallback / restart / persistence parity

Method:
- local source re-validation
- 3 `gpt-5.4 xhigh` subagent review passes
- I accepted only the subagent conclusions that I could tie back to current source

### What now looks genuinely fixed

These older findings from the earlier draft are no longer the main problems in current code:

1. Projection/fallback run snapshot parity for the richer inspector payload is materially repaired.
   - `ProjectionOnlyOpenCrayChatRuntimeGateway.runSnapshotToMap(...)` now carries the newer sections such as `finalAttachments`, `llmDiagnostics`, `liveContext`, `contextBudget`, `memoryTrace`, `memoryFlush`, `bootstrap`, `durableCompaction`, `skillInventory`, and `activeSkill`.
   - This means the old F9 section above is stale as written.

2. Draft clear durability is materially repaired.
   - `OpenCrayHostRuntime.onAssistantDraftCleared(...)` now leaves a durable clear marker path instead of relying only on in-memory state.
   - The old F10 section above is stale as written.

3. Markdown-compatibility attachment persistence into transcript/context is materially repaired.
   - `recordSuccessfulAssistantTurn(...)` now persists the compatibility-resolved final transcript turn instead of persisting only the narrower explicit attachment list.
   - The old F11 section above is stale as written.

4. Prompt seeding / merge parity is materially repaired.
   - `prepareSessionContext(...)` + `mergePromptMessageIntoSeededTranscript(...)` no longer duplicate or drop the current prompt in the seed path.

5. Service-owned delegate runtime snapshots now preserve delegate-side live drafts correctly.
   - The earlier suspicion that delegate-backed snapshots always overwrite richer draft state should not be treated as an active bug without a narrower reproducer.

### Confirmed residual findings

#### R1. `actions`-array structured streaming still loses live bubble semantics

Severity: high

Evidence:
- `PromptAssembler` explicitly teaches the model to emit `{"actions":[{"type":"commentary",...}, ...]}` and `{"actions":[{"type":"commentary",...},{"type":"final",...}]}`:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:457`
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:459`
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:497`
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:499`
- The streaming draft normalizers still classify any raw JSON containing `"actions"` as structured protocol and then try to extract only a top-level `type` / `decision`:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5133`
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5155`
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5165`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1591`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1611`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1621`
- In the `{"actions":[...]}` case, the first visible `type` is normally `"commentary"`, so `extractStructuredAssistantDraftText(...)` returns `null`, after which the caller suppresses the entire payload as hidden structured protocol.
- Final parsing does flatten nested actions later via `parseActionObject(...)`, so this is not “final answer disappears entirely”; it is specifically “streaming commentary/final draft bubble does not surface while the model is still generating”.

Why this matters:
- The prompt contract says commentary/final information should flow through the public bubble protocol.
- On the raw-JSON streaming path, the model can follow the documented protocol exactly and still produce no live bubble updates.
- This is a contract break between prompt, runtime, and UI.

#### R2. Managed process stdout/stderr is still not fully available in Run Inspector

Severity: high

Evidence:
- The durable process snapshot stores full `stdout` / `stderr`:
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:132`
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:141`
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:142`
- Host and projection payload serializers only export preview fields:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:5227`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:5249`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:5250`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1746`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1767`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1768`
- Flutter only models and renders `stdoutPreview` / `stderrPreview`:
  - `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1370`
  - `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1410`
  - `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1411`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:4593`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:4599`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:4604`

Why this matters:
- The system already persists the full process output, but the Run Inspector contract still exposes only a tail preview.
- That fails the user requirement that all process information should be visible in Run Inspector and durably inspectable.

#### R3. Final attachment artifact resolution still depends on a 24-event in-memory host buffer

Severity: high

Evidence:
- Runtime events are appended into `chatRuntimeEventState` with `MAX_RUNTIME_EVENT_HISTORY = 24`:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2577`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2582`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2585`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:8032`
- Finalization resolves attachment artifacts only from that in-memory event buffer:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6420`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6424`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6739`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6753`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6832`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6837`
- `onTaskFinished(...)` uses that path before archiving the final assistant attachments into chat storage:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:467`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:472`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:475`

Why this matters:
- If an artifact-producing tool runs early and the run emits enough later lifecycle/tool/commentary events, artifact lookup can fall out of the 24-event ring before final answer archiving.
- That means final attachment persistence can still fail even though the artifact metadata was already journaled elsewhere.
- This is a persistence-integrity hole, not just an inspector omission.

#### R4. Binder-loss / fallback reads can still drop live assistant drafts

Severity: high

Evidence:
- Service-backed reads immediately fall back to the projection gateway when no binder-backed gateway is available:
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:207`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:234`
- The service-owned path maintains live drafts in memory and overlays them onto runtime payloads when it is the active reader:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:447`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:449`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:625`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:635`
- Host runtime itself also keeps the live draft map in memory:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:631`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2728`
- Projection fallback reconstructs drafts only from durable journal-visible events:
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:737`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:750`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:809`
- The service-backed draft observer also switches dynamically to whatever gateway is current:
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:25`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:30`

Why this matters:
- During binder-loss / release windows, the read path can move from an in-memory-enriched source to a journal-only source.
- If the active draft has not yet crossed the durable boundary that projection can reconstruct, the UI can observe a false clear even though the run is still alive.
- This is a real transport-path parity gap in the bubble/progress chain.

#### R5. Non-success terminal assistant bubbles still do not become durable transcript truth

Severity: medium-high

Evidence:
- The user-visible final assistant bubble is written for all terminal outcomes via `onTaskFinished(...)` and `finalTextForLocked(...)`:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:450`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:462`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:510`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6189`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6210`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6214`
- Transcript persistence of the final assistant turn still happens only for `ExecutionStatus.SUCCESS`:
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1935`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1943`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1953`
- The transcript-side assistant replay path explicitly ignores final assistant events:
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1900`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:2075`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:2079`
- Later prompt preparation uses the transcript store snapshot as the working conversation source after seeding:
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1740`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1873`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:1883`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:2812`

Why this matters:
- If the assistant visibly ends with cancellation / denial / failure text, that terminal bubble can still be absent from the later model-visible transcript.
- The user and the model can therefore disagree about what the last assistant message actually was.

#### R6. Workspace alias tool names still degrade the Flutter Run Inspector

Severity: medium-high

Evidence:
- Host/runtime events keep the raw tool name:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:5995`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6008`
- Host summaries explicitly recognize alias names such as `workspace_read_file`, `workspace_list_files`, and `workspace_write_file`:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3999`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:4017`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:4055`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:4132`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:4161`
- Flutter inspector switches still recognize only canonical names for these cards/details/fallbacks:
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:5742`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:6255`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:7265`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:7426`
- The only alias-like exceptions there are `command_exec` and `python_exec`:
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:6824`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:6831`
- Producer-side tests already emit `workspace_write_file`:
  - `app/src/test/kotlin/com/opencray/app/OpenCrayHostRuntimeTest.kt:2185`
  - `app/src/test/kotlin/com/opencray/app/OpenCrayHostRuntimeTest.kt:2617`

Why this matters:
- Real backend events can degrade into generic/raw tool cards in the Run Inspector even though the host already knows how to summarize them.
- This is an end-to-end contract mismatch between producer and UI consumer.

#### R7. Managed process durability is still capped, so older run/process inspector detail can disappear

Severity: medium

Evidence:
- Registry retention is capped at 16 tracked processes:
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:226`
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:227`
- Older snapshots are evicted once the cap is exceeded:
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:563`
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:580`
  - `runtime/src/main/kotlin/com/opencray/runtime/process/AgentProcessRegistry.kt:584`
- Projection/runtime managers rebuild associated process snapshots only from the currently retained registry set:
  - `app/src/main/kotlin/com/opencray/app/RunStateProjectionSupport.kt:10`
  - `app/src/main/kotlin/com/opencray/app/RunStateProjectionSupport.kt:21`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:878`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:888`
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1714`
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1722`

Why this matters:
- A run record can keep `managedProcessIds`, but once the registry evicts the corresponding snapshots, the inspector no longer has the detail object to show.
- This breaks the “all information should be durable and inspectable later” goal for long-lived sessions.

#### R8. Restore planner and rewrite path still disagree on interrupted runs without checkpoints

Severity: high

Evidence:
- Recovery planning first projects interrupted `RUNNING` / `RETRY_PENDING` / `CANCEL_REQUESTED` entries into a synthetic failed form:
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:99`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:432`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:440`
- The planner then emits `INTERRUPT_RECOVERY_REQUIRED` with reason `no_recoverable_checkpoint_after_restore`:
  - `app/src/main/kotlin/com/opencray/app/RunRecoveryPlanner.kt:193`
  - `app/src/main/kotlin/com/opencray/app/RunRecoveryPlanner.kt:207`
  - `app/src/main/kotlin/com/opencray/app/RunRecoveryPlanner.kt:208`
- But the actual rewrite gate still evaluates the original queue entry and only allows interruption for `QUEUED` or `uncertain_inflight_mutation` cases:
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:358`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:359`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:423`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:426`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:428`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:429`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:640`

Why this matters:
- The planner says these restored interrupted runs must stay interrupted until the user explicitly decides what to do.
- The rewrite path can still leave the original live-state entry in place for the exact no-checkpoint case the planner just classified as unsafe.
- This is a restart/persistence integrity bug, not a cosmetic state-label mismatch.

### Older draft claims that should be treated as stale or narrowed

1. The old F9/F10/F11 sections above should not be used as current bug tickets without rereading current code.
   - They were valid at the time.
   - They are no longer the highest-confidence current defects.

2. The earlier concern about `ServiceOwnedChatRuntimeGateway.decorateChatRuntimePayload(...)` broadly “dropping restored drafts” is too broad as written.
   - Delegate-backed preservation now looks intentionally tested.
   - The real live-draft problem I can still defend is the binder-loss / fallback transport split described in R4.

3. “Process/inspector parity is fixed” should also be treated as too broad.
   - Process attachment linkage and several inspector sections improved.
   - Full stdout/stderr inspector visibility and long-horizon process durability are still not closed.

### Current confidence / gaps

- High confidence:
  - R1
  - R2
  - R3
  - R4
  - R5
  - R8

- Moderate confidence:
  - R6
  - R7

- I did not run a new full Gradle sweep in this third pass.
- The strongest evidence in this round is static-source-path consistency, not fresh end-to-end runtime reproduction.

## Fourth-round review

Date: 2026-04-22

This pass re-reviewed the chain after the reported repair round, with two goals:
- verify which third-round findings are now materially fixed
- keep digging into previously under-covered protocol and recovery edges

Scope emphasis:
- user instruction -> prompt/context assembly -> model output parsing
- live draft extraction vs final persistence
- run inspector payload/export/Flutter consumption
- service-backed fallback and recovery tail logic

### What now looks materially fixed

1. Structured `actions[]` draft extraction is no longer completely blind.
   - Both `OpenAiCompatibleLiteLlmProviderClient` and `OpenCrayAgentRuntime` now parse visible draft text out of `actions[]` payloads instead of suppressing the entire array shape.
   - Regression coverage was added for surfaced `actions[]` commentary/final drafts and for hiding nested `actions[]` inside tool arguments.
   - Relevant refs:
     - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5139`
     - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5184`
     - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1627`
     - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1672`
     - `app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt:3557`
     - `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt:1003`

2. Managed process inspector parity is materially repaired.
   - Host and projection now export full `stdout` / `stderr`, not only previews.
   - Flutter now parses and renders full output, while still keeping preview/truncation compatibility.
   - Relevant refs:
     - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:5265`
     - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1770`
     - `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1412`
     - `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1440`
     - `flutter_app/lib/features/chat/chat_feature_screen.dart:4653`

3. Final attachment artifact lookup is no longer purely tied to the 24-event in-memory ring.
   - `attachmentArtifactsForRunLocked(...)` now reads durable run-journal events first and overlays live events on top.
   - Relevant refs:
     - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6850`
     - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6855`
     - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6860`

4. Managed process recovery by persisted id is materially improved.
   - Projection/runtime association now falls back to `processRegistry.read(processId)` instead of relying only on the currently listed registry set.
   - Relevant refs:
     - `app/src/main/kotlin/com/opencray/app/RunStateProjectionSupport.kt:22`
     - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:892`
     - `app/src/test/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGatewayTest.kt:429`

5. Restore rewriting for interrupted runs without checkpoints is materially fixed.
   - `INTERRUPT_RECOVERY_REQUIRED` no longer depends on the narrower old gate; interrupted restore states are now rewritten to explicit retry failure as the planner intended.
   - Relevant refs:
     - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:358`
     - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:424`
     - `app/src/test/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStoreTest.kt:545`

6. Service-backed live-draft observer no longer emits the false fallback clear on binder disconnect.
   - The observer path now uses sticky gateway selection instead of immediate fallback switching.
   - Relevant refs:
     - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:22`
     - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:213`
     - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:227`
     - `app/src/test/kotlin/com/opencray/app/OpenCrayRuntimeServiceHostTest.kt:2957`

### Confirmed residual findings

#### R9. Mixed `actions[]` batches can still stream a final answer that runtime later discards

Severity: high

Evidence:
- Prompt guidance allows `actions[]`, but explicitly says that inside one array you should emit commentary first and then either tool calls or exactly one final action:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:497`
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:768`
- Draft extraction for `actions[]` currently surfaces the last commentary/final text it can find:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5184`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1672`
- But execution later suppresses `AgentModelAction.Final` whenever the parsed batch contains any tool action at all:
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:3879`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:4099`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:8922`

Why this matters:
- If the model emits an invalid-but-plausible mixed batch such as `commentary + tool_call + final`, the user can see a streamed final-answer preview before the runtime executes the tool.
- That preview will not become the persisted final answer because the runtime ignores `Final` in any batch that also contains a tool action.
- This is a visible stream/persist mismatch in the public bubble protocol.

#### R10. Standalone JSON commentary/progress/status preambles are still suppressed from live drafts

Severity: medium

Evidence:
- PromptAssembler explicitly tells the model to use a commentary action as the public preamble in the JSON protocol:
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:442`
  - `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt:448`
- But standalone structured draft extraction still only returns text for top-level `final` / `answer`; it does not surface top-level `commentary` / `progress` / `status` objects:
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5126`
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5160`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1614`
  - `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1648`
- The host runtime can still persist that commentary later once the parsed event is emitted:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2723`

Why this matters:
- A model that follows the documented “standalone commentary action first” guidance still produces no live draft bubble for that preamble.
- The user only sees the commentary after the turn is parsed and replayed, not during streaming.
- This is smaller than the old `actions[]` blind spot, but it still breaks the intended public-progress semantics.

#### R11. Non-workspace preserved aliases still bypass structured Flutter run-trace formatting

Severity: medium

Evidence:
- Flutter canonicalizes only `workspace_*` aliases:
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:5655`
- The main formatter paths still switch on canonical names like `Read`, `Grep`, `Bash`, and `WebFetch`:
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:6836`
  - `flutter_app/lib/features/chat/chat_feature_screen.dart:7388`
- Host and projection payloads still export raw event `toolName`:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6019`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:6032`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1895`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1908`
- Runtime normalization preserves alias invocation metadata and explicitly supports aliases such as `read`, `grep`, `bash`, and `webfetch`:
  - `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolCallNormalizer.kt:63`
  - `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolCallNormalizer.kt:243`
  - `runtime/src/test/kotlin/com/opencray/runtime/AgentToolAliasDispatchTest.kt:285`
  - `runtime/src/test/kotlin/com/opencray/runtime/AgentToolAliasDispatchTest.kt:311`
  - `runtime/src/test/kotlin/com/opencray/runtime/AgentToolAliasDispatchTest.kt:339`
  - `runtime/src/test/kotlin/com/opencray/runtime/AgentToolAliasDispatchTest.kt:437`

Why this matters:
- `workspace_*` aliases are now covered, but other preserved aliases still degrade into generic/raw cards instead of the richer structured summaries.
- The end-to-end producer/consumer contract is still only partially normalized.

#### R12. Durable journal tail ordering still uses timestamps before append order

Severity: low

Evidence:
- Persisted journal entries have a durable append sequence `seq`:
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:97`
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:176`
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:305`
- But list/listForRun ordering still sorts by `emittedAtEpochMs`, then `persistedAtEpochMs`, then `runId`, then `seq`:
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:148`
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:250`
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:362`
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:363`
  - `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt:366`
- Recovery/tail readers still treat `asReversed().firstOrNull()` as “latest”:
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:67`
  - `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt:68`
  - `app/src/main/kotlin/com/opencray/app/RunRecoveryProjectionSupport.kt:28`
  - `app/src/main/kotlin/com/opencray/app/RunRecoveryProjectionSupport.kt:29`

Why this matters:
- A later-persisted but backdated event can lose tail precedence to an earlier-persisted event with a larger emitted timestamp.
- That makes recovery classification and “latest event” reasoning depend on wall-clock timestamps instead of durable append order.
- I did not find a regression test that exercises out-of-order `emittedAtEpochMs` vs `seq`.

### New testing gaps worth noting

1. The UI draft-clear/reconciliation path is still lightly covered.
   - Current widget tests cover initial draft render and direct live update, but I did not find a Flutter test that asserts `cleared=true` plus later authoritative snapshot reconciliation.

2. I attempted targeted Gradle test execution for the repaired areas, but the current sandbox blocked Android/Gradle user-directory initialization before test execution.
   - This was an environment failure, not a red test result.

### 2026-04-21 follow-up after one-to-one subagent reviews

I ran a second review wave with four scoped subagents over the already-landed fixes, then patched the confirmed regressions they found.

#### Review follow-up F1: transcript final-turn merge was still wrong under replay + markdown attachment normalization

Confirmed by reviewer:
- `AppAgentSessionTaskRuntimeFactory.kt` still duplicated the trailing `FINAL_ANSWER` when a replayed final event stored raw markdown like `![diagram](...)`, then final result persistence rewrote that content to `""` plus attachments.
- `FAILED + LLM_RETRY_EXHAUSTED_AWAITING_RESUME` was still being treated as a durable final assistant turn even though that path is resumable, not terminal.

Fixes applied:
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
  - `recordFinalAssistantTurn(...)` now skips `ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME`.
  - replayed final events now use `appendTrailingFinalAssistantReplayTurn(...)` so they do not overwrite a canonical final turn that already exists.
  - canonical final result persistence now always replaces the trailing final assistant turn content and merges attachments, instead of requiring byte-identical content.
- `app/src/test/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactoryTodoStoreTest.kt`
  - added durable reload regression for replayed markdown-only final event + final result normalization.
  - added paused-retry regression asserting no durable final assistant turn is written before resume.

#### Review follow-up F2: actions-array streaming fix had two new provider regressions

Confirmed by reviewer:
- non-empty `response.completed.response.output` / `response.incomplete.response.output` was being treated additively, so stream-only provisional tool calls could survive into final completion.
- completed payload text could not overwrite partial streamed text because the merge path only filled missing fields.
- the new `actions` draft extractor scanned any `"actions"` key, including nested tool arguments.

Fixes applied:
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
  - non-empty completed/incomplete `response.output` now replaces stored output items authoritatively instead of merging.
  - removed the additive merge path for completed response output items.
  - `actions` array extraction now only matches a top-level JSON object field.
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
  - mirrored the same top-level-only `actions` detection for runtime draft extraction.
- tests added:
  - `executePrefersCompletedResponsesOutputOverPartialStreamText`
  - `executeDropsStreamOnlyFunctionCallWhenCompletedResponsesOutputHasOnlyFinalAnswer`
  - `executeSuppressesNestedActionsInsideStructuredToolDraftsFromOpenAiResponses`
  - `runPromptTaskSuppressesNestedActionsInsideStructuredToolDraftPayloads`

#### Review follow-up F3: binder-loss observer path still fell back to projection and could fake-clear live drafts

Confirmed by reviewer:
- synchronous loads were hardened, but observer paths still switched to fallback on binder loss.
- `waitForChatRun(timeoutMs)` also consumed the fixed binder-await budget before applying the caller timeout.
- queued tasks could still synthesize approval checkpoints that the rewrite gate refused to honor.

Fixes applied:
- `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt`
  - `observeChat(...)`, `observeChatRuntime(...)`, and `observeLiveAssistantDraftEvents(...)` now use a sticky binder-backed gateway selector that retains the last live service gateway across binder churn instead of immediately rebroadcasting projection fallback.
  - `waitForChatRun(...)` now spends binder await time out of the caller timeout budget instead of adding it on top.
- `app/src/main/kotlin/com/opencray/app/RecoveryAwareQueueSnapshotStore.kt`
  - `canRestoreWaitingApproval(...)` now accepts `QUEUED` entries when a synthetic waiting-approval checkpoint exists.
- tests added:
  - observer disconnect regression for `observeChatRuntime(...)`
  - observer disconnect regression for `observeLiveAssistantDraftEvents(...)`
  - timeout-budget regression for `waitForChatRun(...)`
  - queued waiting-approval recovery rewrite regression

#### Review follow-up F4: attachment artifact recovery still dropped same-turn same-tool artifacts

Confirmed by reviewer:
- `attachmentArtifactsForRunLocked(...)` deduped full runtime events before extracting artifact metadata.
- `runtimeEventDedupKey(...)` for `OpenCrayToolResultEvent` does not include call identity, content, or artifact metadata, so multiple successful results from the same tool/turn collapsed together.

Fix applied:
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
  - `attachmentArtifactsForRunLocked(...)` no longer pre-dedupes events before artifact extraction; it now scans the combined durable + live event stream in reverse order and dedupes at the artifact-id level only.
- `app/src/test/kotlin/com/opencray/app/OpenCrayHostRuntimeTest.kt`
  - added regression for two successful same-tool results in the same turn with different artifact ids, asserting the requested later artifact still resolves.

#### Separate worker result landed in parallel

A separate subagent completed the process/inspector work in parallel:
- full `stdout` / `stderr` now flow into inspector snapshots in host + projection paths.
- Flutter model/rendering now prefers full output and consumes `workspace_*` tool aliases.
- process retention default was raised from `16` to `64`.

I have not yet run an additional independent review pass on that process/inspector patch set in this follow-up note.

#### Verification status after follow-up patches

I attempted targeted Gradle verification for the touched transcript/actions/runtime paths.

What happened:
- sandboxed Gradle failed first because Android plugin setup could not write `C:\\Users\\CodexSandboxOffline\\.android`.
- rerunning outside the sandbox got past that environment issue, but the build is still blocked by pre-existing unrelated compile failures:
  - `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayToolDispatcherMediaToolTest.kt`
  - `app/src/main/kotlin/com/opencray/app/ServiceOwnedChatSubmissionAccess.kt`
  - `app/src/main/kotlin/com/opencray/app/facade/media/MediaSpeechSettingsFacade.kt`
- one new compile error introduced in `AppAgentSessionTaskRuntimeFactory.kt` during this follow-up was caught and fixed immediately.

So the current state is:
- the new review findings have corresponding code changes and regression tests in place;
- repository-wide targeted Gradle verification is still partially blocked by unrelated existing compile failures outside the touched paths.

### 2026-04-22 addendum after final subagent return

I collected the last delayed subagent result after the fourth-round write-up and locally re-checked the three strongest claims.

#### Addendum A1: sticky binder observer selection introduced a new fallback-switch regression

Status: confirmed

Evidence:
- `observeChat(...)` and `observeChatRuntime(...)` now both route through sticky gateway selection:
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:13`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:46`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:213`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:227`
- The selector keeps returning `lastBinderGateway` after disconnect instead of falling back:
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:229`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:235`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:237`
- `observeWithDynamicGateway(...)` only resubscribes when the gateway instance actually changes:
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedGatewaySupport.kt:55`
  - `app/src/main/kotlin/com/opencray/app/ServiceBackedGatewaySupport.kt:66`
- That is now inconsistent with the existing observer-switch test contract:
  - `app/src/test/kotlin/com/opencray/app/OpenCrayRuntimeServiceHostTest.kt:2657`
- There is also a newer disconnect test that intentionally avoids a fallback clear while a draft is live:
  - `app/src/test/kotlin/com/opencray/app/OpenCrayRuntimeServiceHostTest.kt:2893`

Why this matters:
- The sticky change fixed one real problem: disconnect no longer immediately rebroadcasts an empty fallback draft state.
- But it created another: general chat/runtime observers can remain pinned to a dead binder gateway and stop switching back to fallback, while direct load calls still do fall back.
- The observer contract and load contract are now inconsistent.

#### Addendum A2: archived managed-process recovery is still better on projection than on the host path

Status: confirmed

Evidence:
- The runtime factory still exposes only `listManagedProcesses(sessionId)`:
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:267`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:225`
- Host-side run assembly still computes `managedProcesses` only from the listed process set:
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1342`
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1350`
  - `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1436`
- Projection-side recovery now has a read-by-id fallback:
  - `app/src/main/kotlin/com/opencray/app/RunStateProjectionSupport.kt:22`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:888`
  - `app/src/test/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGatewayTest.kt:430`
- Host runtime still serializes whatever the runtime manager populated into `run.managedProcesses`:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:1636`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:5238`

Why this matters:
- Projection and service-backed read paths can now recover archived process snapshots by persisted id.
- The in-process host path still cannot, so the same run can expose richer process detail on projection than on the host.

#### Addendum A3: runtime-event bubbles and managed-process bubbles are still only partially ordered

Status: confirmed

Evidence:
- Projection sorts runtime events first, then managed processes, then concatenates the two lists:
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:476`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:507`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:535`
  - `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:410`
- Host does the same:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3484`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3517`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3545`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3426`
- The service-owned read path still comes from the projection gateway:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:76`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:126`
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:679`

Why this matters:
- Bubble ordering is only chronological within the runtime-event subset and within the managed-process subset.
- A process bubble that should appear between two runtime events will still be rendered after all runtime-event bubbles for the same anchor.

### 2026-04-22 Mainline Fix Pass

Status: partially verified, source-level high confidence

What I changed after taking over the critical path:
- `ServiceBackedOpenCrayChatRuntimeGateway.kt`
  - Re-split observer semantics.
  - `observeChat()` and `observeChatRuntime()` now use the non-sticky dynamic selector again, so binder loss can actually rebind to projection fallback and then rebind back to a new binder gateway.
  - `observeLiveAssistantDraftEvents()` keeps the sticky selector so disconnect does not immediately synthesize a fallback clear event for the live draft stream.
- `OpenAiCompatibleLiteLlmProviderClient.kt`
  - Top-level structured `commentary` / `progress` / `status` now produce visible streaming draft text.
  - `actions[]` batches now suppress `final` draft exposure whenever the same batch also contains tool-like / hidden execution actions.
  - This aligns stream-time visibility with the runtime’s actual execution and durable-final semantics.
- `OpenCrayAgentRuntime.kt`
  - Mirrored the same structured-draft extraction rules as the provider path so host-side stream rendering and runtime-side draft bubbling do not diverge.
- `AppAgentSessionTaskRuntimeFactory.kt`
  - Wired `readManagedProcess(sessionId, processId)` through to `processRegistry.read(processId)` instead of inheriting the list-only fallback.
- `AgentSessionRuntimeManager.kt`
  - Host run assembly now uses `readManagedProcess(...)` when a persisted `managedProcessId` is missing from the live `listManagedProcesses()` set.
  - Archived/durable process snapshots can therefore be reattached on the host path the same way projection already could.
  - Restored interrupted-run repair also now reads by id instead of only from the live list.
- `RunEventJournalStoreFactory.kt`
  - Tightened the base durable entry comparator so persisted append order wins before emitted timestamp order.
  - `latestRuntimeEventOrNull()` had already been switched to durable-tail semantics; the comparator now stops contradicting that policy.

Targeted verification attempted:
- `git diff --check` passed for the files touched in this pass.
- Sandboxed Gradle test invocation failed before test execution because Android/Gradle initialization still tried to write under `C:\\Users\\CodexSandboxOffline\\.android` / `.gradle`.
- An escalated targeted Gradle test command was attempted next, but it timed out without producing a reliable completion signal, so I am not counting it as a pass.

Risk notes:
- The observer split deliberately accepts that `observeChatRuntime()` may still emit a projection fallback snapshot on disconnect; whether the UI visually clears depends on the Flutter snapshot replacement / live-draft override rules that are already in the tree.
- Bubble global ordering and Flutter preserved-alias handling were already fixed in the current worktree before this pass; I re-checked the source paths and did not re-edit them here.

### 2026-04-22 test追补与顺手修复

Status: targeted fixes landed, targeted regressions verified

#### T1. 测试启动阻塞的真实根因

先说明一个容易误判的点：我上一轮把 Gradle/JVM 测试卡死归因为 Android 目录初始化问题，但这次拿到完整栈后确认，最初那条命令里同时设置了：
- `ANDROID_SDK_HOME`
- `ANDROID_USER_HOME`

AGP 8.12 会把这两个变量都当作 Android Preferences 目录注入方式，并直接拒绝双注入。报错不是项目源码造成的，而是测试命令环境本身冲突。

修正后仅保留：
- `GRADLE_USER_HOME=D:\codes\MobileProjects\OpenCray\.gradle-user`
- `ANDROID_USER_HOME=D:\codes\MobileProjects\OpenCray\tmp-android-home`

验证：
- `.\gradlew.bat :app:help` 已成功通过。

#### T2. 这轮测试真正打出来的两个问题

1. `runtime` 真实行为回归：结构化 `actions[]` 草稿重复发射

证据：
- `runtime/build/test-results/testDebugUnitTest/TEST-com.opencray.runtime.OpenCrayAgentRuntimeTest.xml`
- 初始失败用例：`runPromptTaskSuppressesStructuredFinalDraftWhenActionsBatchContainsToolCall`
- 失败内容：期望只收到一次 `Checking the transcript first.`，实际收到两次。

根因：
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1566`
- 流式 observer 会对每次可见文本快照都直接发 `onAssistantDraftUpdated(...)`。
- 当模型先流出半截 `actions[]`，再流出完整 `actions[]` 时，两次都能解析成同一条 commentary，于是重复发射。
- 这会导致 UI draft bubble / run inspector draft 记录出现重复增量，而不是“内容未变时保持稳定”。

修复：
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1566`
- 为 `assistantDraftObserver` 增加 `lastVisibleDraftText`。
- 若本次归一化后的可见文本与上次相同，则不再重复发 `onAssistantDraftUpdated(...)`。
- `onVisibleTextReset()` 时同步清空该缓存，避免后续真正重新流式时被误抑制。

2. `app` 新暴露的编译回归：`return@mapIndexedNotNull` 控制流在当前编译状态下直接炸成 unresolved label

证据：
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3492`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:484`
- 清理 `app` 构建产物后重新编译，Kotlin 编译器在这些 `return@mapIndexedNotNull` 上报 `Unresolved label`，之前属于被增量产物掩盖的编译断点。

根因判断：
- 这两段代码都在 `mapIndexedNotNull { ... }` 里用多处 `return@mapIndexedNotNull null` 进行筛选。
- 在当前这份工作树和编译器状态下，这种写法并不稳定；同类逻辑在 host/projection 两条路径上同时炸，说明不是单点业务逻辑，而是控制流形态本身存在脆弱性。

修复：
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3492`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:484`
- 两处都改成显式 `buildList { for (...) { continue } }` 形式，完全移除 label-return。
- 语义未变：仍然是“找不到 run / anchor / text 时跳过该 event”，只是改成编译器更稳的控制流。

#### T3. 2026-04-22 定向验证结果

1. `runtime` 回归测试

命令：
```powershell
.\gradlew.bat :runtime:testDebugUnitTest --no-daemon --console=plain `
  --tests "com.opencray.runtime.OpenCrayAgentRuntimeTest.runPromptTaskStreamsTopLevelStructuredCommentaryProgressAndStatusDraftText" `
  --tests "com.opencray.runtime.OpenCrayAgentRuntimeTest.runPromptTaskSuppressesStructuredFinalDraftWhenActionsBatchContainsToolCall"
```

结果：
- 通过
- XML: `runtime/build/test-results/testDebugUnitTest/TEST-com.opencray.runtime.OpenCrayAgentRuntimeTest.xml`
- `tests="2" failures="0" errors="0"`

2. `app` provider 结构化 draft 流式测试

命令：
```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain `
  --tests "com.opencray.app.OpenAiCompatibleLiteLlmProviderClientTest.executeStreamsTopLevelStructuredProgressDraftTextFromOpenAiChatCompletions" `
  --tests "com.opencray.app.OpenAiCompatibleLiteLlmProviderClientTest.executeSuppressesStructuredFinalDraftWhenActionsBatchContainsToolCallFromOpenAiChatCompletions"
```

结果：
- 通过
- XML: `app/build/test-results/testDebugUnitTest/TEST-com.opencray.app.OpenAiCompatibleLiteLlmProviderClientTest.xml`
- `tests="2" failures="0" errors="0"`

3. `app` host/projection/recovery 链路测试

命令：
```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain `
  --tests "com.opencray.app.RecoveryAwareQueueSnapshotStoreTest.loadRewritesInterruptedRestoreWithoutCheckpointToExplicitRetryFailure" `
  --tests "com.opencray.app.ProjectionOnlyOpenCrayChatRuntimeGatewayTest.projectionOnlyChatRuntimeGatewayReadsManagedProcessSnapshotsByPersistedIdWhenListIsTrimmed" `
  --tests "com.opencray.app.OpenCrayRuntimeServiceHostTest.serviceBackedGatewayObserversSwitchBetweenFallbackAndBinderGateways" `
  --tests "com.opencray.app.OpenCrayRuntimeServiceHostTest.serviceBackedChatRuntimeGatewayObserveChatRuntimeFallsBackAfterBinderDisconnectAndRebinds" `
  --tests "com.opencray.app.OpenCrayRuntimeServiceHostTest.serviceBackedChatRuntimeGatewayObserveLiveDraftEventsStaysStickyAcrossDisconnectButRebindsOnReconnect"
```

结果：
- `OpenCrayRuntimeServiceHostTest.xml`: `tests="3" failures="0" errors="0"`
- `RecoveryAwareQueueSnapshotStoreTest.xml`: `tests="1" failures="0" errors="0"`
- `ProjectionOnlyOpenCrayChatRuntimeGatewayTest.xml`: `tests="1" failures="0" errors="0"`

说明：
- 我也尝试过把这些 app 用例和别的目标测试合并成一条长命令跑，但工具调用本身会在长时间无返回时超时；因此最后采用了拆分、`--no-daemon`、逐组落 XML 的方式取证。
- 从当前 XML 结果看，这次顺手修掉的两个问题都已经被目标回归压住。

### 2026-04-22 复审补盲：inspector / snapshot replacement / service overlay

Status: source-level confirmed, not yet patched in this pass

#### F1. service-owned live draft shrink snapshot 仍然可能被 Flutter 直接丢弃，导致旧 runtime 状态滞留

证据链：
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:625`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:645`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:163`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:175`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:178`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:187`

根因：
- service-owned chat gateway 在 `decorateChatRuntimePayload(...)` 里只覆写 `liveAssistantDrafts` 列表，不推进 `updatedAtEpochMs`，也不为“列表变薄”的情况写任何版本信号。
- Flutter 侧 `shouldReplaceObservedRuntimeSnapshot(...)` 明确把“同版本但更薄”的 runtime snapshot 视为回滚：
  - `events` 变少 -> 拒绝
  - `liveAssistantDrafts` 变少 -> 拒绝
  - `subAgents` 变少 -> 拒绝
  - 可见 run 变少 -> 拒绝
- 这意味着 service-owned/projection 路径只要发出一个“版本不变，但 live draft 被清空/活跃 run 减少”的 payload，Flutter 就会继续保留旧 `_latestChatRuntimeSnapshot`。

影响：
- live draft 清空后，消息气泡虽然可能被单独的 draft event 临时修正，但 `_latestChatRuntimeSnapshot` 仍可能保留旧 draft / 旧 run / 旧 subagent 状态。
- 后续一旦 `_applyHostState()` 重新基于旧 runtime snapshot 映射 UI，旧状态有机会再次回流到 run trace / inspector。
- 这个问题是链路级的契约错位：native/service 允许“同版本 shrink”，Flutter comparator 明确拒绝。

我为什么把它算作已确认问题：
- 这不是“可能时间戳没更新”的纯猜测，而是两个已存在的生产逻辑正面冲突。
- service-owned gateway 当前确实只改 `liveAssistantDrafts`，不改版本字段；Flutter 当前也确实只接受同版本“更厚”的 runtime snapshot。

#### F2. run inspector 仍然不是按真实执行时序展示，tool result / subagent / process 会被错位重排

证据链：
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4326`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4364`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4379`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4386`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4486`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4750`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4758`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:5759`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:3526`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:516`

根因拆分：

1. tool result 会被提前挂回 tool call 所在位置
- `_findNextToolResultIndex(...)` 会一路向后扫到下一个 `tool_result`，中间只在遇到新的 `tool_call` 时才停止。
- `_mapRunTraceHistoryEntry(...)` 在处理 `tool_call` 时直接吃掉那个更晚的 `tool_result`，并用 `consumedIndexes.add(...)` 把 result 本体抹掉。
- 如果 `tool_call` 和 `tool_result` 中间真实插了 `subagent` / `approval_*` / `assistant_phase`，inspector 仍然会把 result 提前显示在 call 位置。

2. durable subagent / managed process / final attachments 统一在事件循环之后尾插
- `_buildRunTraceHistory(...)` 先按 `runEvents` 建历史，再统一 `history.add(...)` durable subagent，再 `history.addAll(processHistory)`，最后 `history.addAll(finalAttachmentHistory)`。
- 这不是按时间合并，而是按“类别分段追加”。

3. process 在 inspector 里的排序规则还和 bubble 投影规则不一致
- inspector 的 `_buildRunManagedProcessHistory(...)` 用 `updatedAtEpochMs` 排序。
- host/projection 的气泡投影 `projectedManagedProcesses` 则用 `startedAtEpochMs -> updatedAtEpochMs -> processId` 排序并参与全局时间线合并。

影响：
- 用户在 bubble 里看到的过程顺序，和 run inspector 里看到的顺序，仍然可能不一致。
- inspector 会把“工具结果先于中间事件出现”“进程统一被塞到尾部”“子代理 durable 状态统一落尾”的时序假象展示给用户。
- 对“从用户发送指令到上下文拼装到调用模型到工具到最终输出”的审计场景，这类错位会直接污染排障判断。

#### F3. Flutter 合并 live draft override 时把 runtime snapshot 的 `updatedAtEpochMs` 丢掉了

证据链：
- `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1554`
- `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1562`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:8408`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:8447`

根因：
- `_resolveRuntimeSnapshot(...)` 在有 override draft 时会重新构造一个新的 `OpenCrayChatRuntimeSnapshot(...)`。
- 新对象复制了 `sessionId / activeRuns / retainedRuns / subAgents / events / liveAssistantDrafts / hostLifecycle`，但没有复制 `updatedAtEpochMs`。
- `OpenCrayChatRuntimeSnapshot` 构造器对 `updatedAtEpochMs` 的默认值是 `0`。

影响：
- override 生效时，Flutter 后续再基于这个 merged runtime 做任何“整体更新时间”判断，都会失去原始 payload 的顶层时钟。
- 当前 `runtimeSnapshotVersion(...)` 还能靠 events/runs/drafts 兜一部分，但这已经把“顶层 snapshot 时钟”和“逐字段最大值”的语义混在一起了，后续很容易继续养出排序/替换边界问题。

备注：
- 这一条我定级低于 F1/F2，因为它目前更像“已存在的版本语义缺口”，不一定每次都马上炸成用户可见错误。
- 但它确实是当前代码里的硬伤，而且刚好落在 live draft override 这条本轮重点链路上。

#### F4. service-owned warmup/chat-decoration 也和 Flutter chat snapshot comparator 存在同类契约错位

证据链：
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:181`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:928`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:1041`
- `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt:1058`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:126`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:153`

根因：
- service-owned chat gateway 会在 `decorateChatPayload(...)` 里根据 warmup 状态改写：
  - `isInputEnabled`
  - `composerPlaceholder`
- warmup 变化会通过 `onWarmupStateChanged = chatGateway::notifyChatSnapshotsChanged` 主动推送新 chat snapshot。
- 但这个 decorator 同样不推进 `updatedAtEpochMs`，也不写入任何额外版本字段。
- Flutter 侧 `shouldReplaceObservedChatSnapshot(...)` 只看：
  - `chatSnapshotVersion`
  - `runtimeVersion`
  - `messages.length`
  - `pendingApprovals.length`
  - `todos.length`
- comparator 完全不看 `isInputEnabled` 和 `composerPlaceholder`。

影响：
- 只要 warmup 状态变化没有同时带来 message/runtime/todo/approval 的版本提升，Flutter 就可能把新的 chat snapshot 当成“等价旧状态”直接丢掉。
- 结果是输入框禁用态、准备中占位文案、warmup 失败提示文案都可能在 native 侧已经变化、Flutter 侧却继续显示旧值。

这条和 F1 的关系：
- F1 是 runtime snapshot 的 shrink/overlay 契约错位。
- F4 是 chat snapshot 的 warmup/UI 字段契约错位。
- 两者共同说明：当前 native -> Flutter 的 snapshot 替换规则，并没有建立“所有 decorator 改写都必须带版本信号”这个基本约束。

### 2026-04-23 复审补盲：state application / embedded-vs-streamed runtime resolution

Status: source-level confirmed, not yet patched in this pass

#### F5. Flutter 已经接受了更“厚”的 streamed runtime snapshot，但在真正映射 UI 之前又可能被更“薄”的 embedded runtime 覆盖回去

证据链：
- `flutter_app/lib/features/chat/chat_feature_screen.dart:30`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:43`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:163`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:181`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:2238`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:2537`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3447`
- `flutter_app/test/chat_feature_screen_test.dart:27`
- `flutter_app/test/chat_feature_screen_test.dart:574`

根因：
- `shouldReplaceObservedRuntimeSnapshot(...)` 在同版本时明确接受“更厚”的 runtime snapshot：
  - `events` 更多 -> 接受
  - `liveAssistantDrafts` 更多 -> 接受
  - `subAgents` 更多 -> 接受
  - 可见 run 更多 -> 接受
- `_handleChatRuntimeSnapshot(...)` 只要这个 gate 通过，就会把 incoming 写进 `_latestChatRuntimeSnapshot`。
- 但 `_applyHostState()` / `_mapSnapshot()` 在真正生成 `ChatFeatureState` 之前，又会调用 `resolveChatRuntimeSnapshot(snapshot.runtimeActivity, runtimeSnapshot)`。
- `resolveChatRuntimeSnapshot(...)` 在版本相等且 `visibleRuns` 数量不等时，返回的是 run 更少的那个 snapshot：
  - `embedded < streamed` -> 返回 `embedded`
  - `embedded > streamed` -> 返回 `streamed`
- 这和 runtime replace gate 的“同版本优先更厚 snapshot”是正面冲突。

影响：
- 只要出现“独立 runtime 流已经收到更厚 snapshot，但 chat snapshot 内嵌 runtime 仍是同版本更薄副本”的场景，Flutter 会先接受新的 `_latestChatRuntimeSnapshot`，随后又在 `_mapSnapshot()` 阶段把它解析回更薄的 embedded runtime。
- 结果不是单纯的“多收了一次无效更新”，而是 UI 最终消费到的 `runTraces / previewCard / sessionCard / assistant phase` 仍可能按旧 runtime 构建。
- 如果构建出来的 `resolvedNextState` 和旧 `_state` 恰好等价，`chatFeatureStatesEquivalent(...)` 还会直接短路这次刷新，于是新增 run / run trace 根本不会进入界面。

我为什么把它单列成新问题：
- 这不是 F1/F4 那种“版本信号不单调”问题，而是 Flutter 自己内部两段逻辑的契约冲突：
  - 入口 gate 认定 incoming runtime 值得替换
  - 真正映射 UI 时的 resolver 又把它撤销
- 对应测试也已经把这组相互冲突的偏好写死了：
  - `resolveChatRuntimeSnapshot prefers the settled snapshot when versions tie`
  - `shouldReplaceObservedRuntimeSnapshot ignores thinner snapshots at the same version`

#### F6. projection-only / fallback chat snapshot 没有顶层 `updatedAtEpochMs`，导致一整类 drawer / summary / 标题更新会被 Flutter 静默丢弃

证据链：
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:315`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:337`
- `app/src/main/kotlin/com/opencray/app/ProjectionOnlyOpenCrayChatRuntimeGateway.kt:356`
- `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:208`
- `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:214`
- `app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCrayChatRuntimeGateway.kt:280`
- `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1821`
- `flutter_app/lib/core/models/opencray_chat_snapshot.dart:1879`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:106`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:126`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:244`
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3494`

根因：
- projection-only chat snapshot 在顶层 map 里写了：
  - `screenTitle`
  - `modeLabel`
  - `sessionButtonLabel`
  - `summary`
  - `drawer`
  - `runtimeActivity`
- 但没有写顶层 `updatedAtEpochMs`。
- Flutter `OpenCrayChatSnapshot.fromMap(...)` 对缺失的 `updatedAtEpochMs` 会直接回落到 `0`。
- `chatSnapshotVersion(...)` 只看：
  - `snapshot.updatedAtEpochMs`
  - 最新 message 时间
  - `runtimeVersion`
- `shouldReplaceObservedChatSnapshot(...)` 在版本相等后，只再比较：
  - `runtimeVersion`
  - `messages.length`
  - `pendingApprovals.length`
  - `todos.length`
- 也就是说，只要 projection/fallback 路径上发生的是“drawer / summary / 标题 / 会话 unread/title/preview”等 chat-only 变化，而没有同步改变 message/runtime/todo/approval 数量，Flutter 会把 incoming chat snapshot 直接拒掉。

影响：
- 这不是 service-owned warmup 那种单一字段问题，而是 projection-only chat snapshot 上整类 UI 字段都没有可靠版本信号。
- service-backed gateway 在 binder 未连上、切回 fallback、或观察流尚未切到 binder 时，都会走这个 projection-only fallback。
- 结果是 session drawer 的 unread/title/preview、summary 标题、session button 文案、mode label 这类信息，可能 native/projection 端已经变了，Flutter 侧仍然继续显示旧值。

补充：
- `chatFeatureStatesEquivalent(...)` 实际上已经把这些 UI 字段都纳入比较；问题发生在更早的 snapshot replace gate。
- Host path 也不是完全安全：
  - `ChatSessionLocalStore` 会维护 workspace 级 `updatedAtEpochMs`，session 列表也按各自 `updatedAtEpochMs` 排序。
  - 但 `OpenCrayHostRuntime` 生成 chat snapshot 顶层时间戳时，只取 `activeSession.updatedAtEpochMs` 和 `runtimeActivityUpdatedAtEpochMs`。
  - 这意味着 host path 对“非当前 session 的 drawer 变化 / workspace 级变化”同样缺少直接版本覆盖。
  - 证据：`app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt:72`、`app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt:928`、`app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt:1373`、`app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:1751`、`app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:1775`

## 2026-04-23 修复记录

Status: patched and regression-verified

### 本轮关闭的问题

1. F1 service-owned live draft overlay / clear 现在会发出稳定的顶层版本信号。
   - `ServiceOwnedChatRuntimeGateway` 不再只覆盖 `liveAssistantDrafts`。
   - 当 draft 集合变化时会推进 `updatedAtEpochMs`；当重复读取同一装饰态时会保留上一次已经推进过的顶层时间戳，避免回退到底层 payload 的旧时间戳。
   - 这同时修掉了“更新后下一次 load 又掉回旧时间戳”的二次回滚窗口。

2. F4 service-owned warmup chat decoration 现在也有版本信号。
   - `decorateChatPayload(...)` 改写 `isInputEnabled` / `composerPlaceholder` 后，会同步推进或继承顶层 `updatedAtEpochMs`。
   - Flutter chat snapshot comparator 现在能稳定接住 warmup 输入禁用态、准备中占位文案和失败占位文案变化。

3. F5 Flutter runtime resolver 和 replace gate 已经对齐。
   - `resolveChatRuntimeSnapshot(...)` 不再在同版本 tie 时偏向更薄的 embedded runtime。
   - 它现在按和 `shouldReplaceObservedRuntimeSnapshot(...)` 一致的偏好选择更厚的 runtime：events / live drafts / subagents / visible runs。
   - 这样 `_handleChatRuntimeSnapshot(...)` 接受下来的 streamed runtime，不会在 `_mapSnapshot()` 里又被 embedded runtime 覆盖回去。

4. F3 Flutter live draft override 合并现在保留顶层 `updatedAtEpochMs`。
   - `_resolveRuntimeSnapshot(...)` 重建 runtime snapshot 时已复制 `updatedAtEpochMs`，不再把 override 后的 runtime 顶层时间戳归零。

5. F2 Run Inspector 主线时序已修正。
   - tool call 只有在没有插入有意义中间事件时才会和 tool result 分组。
   - managed process history 改为按 `startedAtEpochMs -> updatedAtEpochMs -> processId` 排序。
   - durable subagent / process / final attachments 不再按类别尾插，而是按时间合并进主历史线。
   - 这修掉了 tool result 被提前挂回 tool call、process 被统一落尾、final attachments 固定尾插带来的审计错位。

6. F6 projection-only / fallback chat snapshot 现在带顶层 `updatedAtEpochMs`。
   - `ProjectionOnlyOpenCrayChatRuntimeGateway.loadProjectionChatSnapshot()` 现在把 active session、runtime projection、drawer session 更新时间、rendered message 时间合并成顶层时间戳。
   - 我顺手把 host path 也扩成了包含 drawer session 更新时间，补掉文档里提到的 host 非当前 session drawer 变化盲区。

### 这轮新增回归覆盖

- Flutter
  - `chat_feature_screen_test.dart`
    - `resolveChatRuntimeSnapshot prefers the thicker snapshot when versions tie`
    - `same-version streamed runtime overrides a thinner embedded runtime when mapping UI`
    - `fullscreen inspector preserves chronology across tool, process, and final attachment entries`

- Kotlin
  - `ServiceOwnedChatRuntimeGatewayTest`
    - `serviceOwnedChatRuntimeGatewayAdvancesChatSnapshotUpdatedAtWhenWarmupDecorationChanges`
    - 扩展 `serviceOwnedChatRuntimeGatewayAugmentsProjectionRuntimeSnapshotsWithLiveDraftsWhenDelegateMissing`，断言 draft update / clear 都会推进 runtime top-level `updatedAtEpochMs`
  - `ProjectionOnlyOpenCrayChatRuntimeGatewayTest`
    - `projectionOnlyChatRuntimeGatewayIncludesTopLevelUpdatedAtForDrawerOnlySessionChanges`

### 2026-04-23 定向验证

已通过：
- `./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.opencray.app.ServiceOwnedChatRuntimeGatewayTest" --tests "com.opencray.app.ProjectionOnlyOpenCrayChatRuntimeGatewayTest"`
- `flutter test test/chat_feature_screen_test.dart --plain-name "resolveChatRuntimeSnapshot prefers the thicker snapshot when versions tie"`
- `flutter test test/chat_feature_screen_test.dart --plain-name "same-version streamed runtime overrides a thinner embedded runtime when mapping UI"`
- `flutter test test/chat_feature_screen_test.dart --plain-name "fullscreen inspector preserves chronology across tool, process, and final attachment entries"`

辅助检查：
- `git diff --check`

### 备注

- 这轮 app 定向单测第一次重跑时，新增的 service-owned 时间戳修复自己打出了一个真实回归：装饰态虽然在变化时推进了时间戳，但重复 `loadChatRuntimeSnapshot()` 会掉回底层 payload 的旧时间戳。我随后把“相同装饰态读取时继承上一次顶层时间戳”的逻辑补上，第二次重跑后目标测试类通过。
- chronology 用例最开始把 subagent 也塞进了主 actor 断言，但 fullscreen inspector 对 subagent 使用独立 actor tab；我把断言收敛到默认主视图中真正同屏可见的主线条目，避免用错误的 UI 选择器制造假红。

## 2026-04-24 修复记录

Status: patched and targeted regression-verified

### 本轮关闭的问题

1. seeded runtime transcript 在已有 chat history 的首轮 prompt 上会静默丢当前 user turn。
   - 触发条件：
     - `transcriptStore` 为空；
     - `baseContext.conversation` 非空；
     - 当前 prompt 不与 seeded history 中任一现有 user message 完全匹配。
   - 旧逻辑会只尝试 `mergePromptMessageIntoSeededTranscript(...)`，找不到匹配时直接返回，不做 append。
   - 结果：
     - 当前 prompt 不进入 runtime transcript；
     - memory flush / durable compaction 的 omitted 计数被少算 1；
     - prompt assembly 看到的 live conversation 落后一轮；
     - prompt 自带 attachment 也会跟着丢。
   - 修复：
     - `mergePromptMessageIntoSeededTranscript(...)` 现在返回是否真正完成 merge；
     - 当 merge 未命中时，`prepareSessionContext(...)` 回退到 `transcriptStore.appendIfDistinct(promptMessage)`。

2. P4A bridge wait loop 对小 timeout 的轮询过粗，会把剩余 deadline 整段睡过去。
   - `waitForBridgeResult(...)` 现在先计算单次 `pollIntervalMs`，每轮只 sleep 到 `min(pollIntervalMs, remainingMs)`，不再越过当前 deadline。
   - 同时把最小轮询间隔从 `25ms` 下调到 `10ms`，避免显式小 timeout 下只轮询到 1-2 次。

3. `P4aPythonRuntimeTest.execDoesNotSpendStartupBudgetDuringLauncherPreparation` 之前是在用 Windows 线程调度粒度证明 runtime 语义，测试前提本身不稳。
   - 当前环境里失败 metadata 直接表明：超时点是 `startup`，且 `serviceReadyExists=false`、`serviceStateExists=false`，说明后台线程压根没在贴边预算内落盘 marker。
   - 这个测试现在改成：
     - 保留 `launcherDispatchDurationMs >= 100ms`；
     - 直接断言 `launcherDispatchCompletedAtEpochMs == startupTimerStartedAtEpochMs`；
     - 同时把 timeout 拉到不会把线程调度噪音误报成产品 bug 的范围。
   - 这样测试验证的是“startup 计时锚点”，而不是“后台线程一定在 100ms 内被系统调度”。

4. `OpenCrayAgentRuntimeTest` 中关于 streamed draft 的一个 runtime 断言已经过时。
   - 现有 runtime 行为是：
     - 压掉 structured tool payload；
     - 压掉 internal signal payload；
     - 保留 public commentary 作为对用户可见的流式 draft。
   - 旧测试还在断言 `assistantDrafts.isEmpty()`，与当前 commentary 可见链路不一致。
   - 已把断言改成仅保留 `Inspecting files` 这一条 public commentary。

### 这轮新增回归覆盖

- `AppAgentSessionTaskRuntimeFactoryTodoStoreTest`
  - `prepareSessionContextAppendsPromptToSeededTranscriptWhenHistoryAlreadyExists`

### 2026-04-24 定向验证

已通过：
- `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.P4aPythonRuntimeTest.execDoesNotSpendStartupBudgetDuringLauncherPreparation" --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest.prepareSessionContextFlushesOmittedHistoryAndReloadsFreshMemoryRecords" --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest.prepareSessionContextCompactsOlderTranscriptIntoDurableSummaries" --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest.prepareSessionContextAppendsPromptToSeededTranscriptWhenHistoryAlreadyExists" --no-daemon --console=plain`
- `./gradlew.bat :runtime:testDebugUnitTest --tests "com.opencray.runtime.OpenCrayAgentRuntimeTest.runPromptTaskSuppressesStructuredToolAndInternalDraftPayloadsButKeepsPublicCommentary" --no-daemon --console=plain`

补充说明：
- 我把 `:runtime:testDebugUnitTest` 顺手带起来时，还额外撞出了上面的 runtime commentary 旧断言红点；它不是本轮 app 修复引入的回归，而是此前行为已经切成“commentary 可见、tool/internal JSON 不可见”，测试还停在旧预期。

### 2026-04-24 全仓验证补记

1. JVM 全仓 `./gradlew.bat test` 在沙箱内第一次失败，不是业务红点，而是 Kotlin 编译的 `Metaspace` OOM。
   - 沙箱内报错集中在多个 `compile*UnitTestKotlin` task，根因是编译期内存不足，不是测试断言失败。
   - 提权后用更高 JVM 参数重跑：
     - `ORG_GRADLE_JVMARGS='-Xmx4g -XX:MaxMetaspaceSize=1024m'`
     - `./gradlew.bat test --no-daemon --console=plain`
   - 结果：`BUILD SUCCESSFUL`

2. Python 全仓 `python -m pytest` 第一次失败在 collection，不是业务用例失败。
   - 根因：
     - `python_tests/` 里有残留的 `pytest-cache-files-*`、`pytest-temp-run` 等目录；
     - `pytest.ini` 没有限制递归目录，pytest 会把这些权限异常目录当成测试路径继续扫。
   - 修复：
     - 在 `pytest.ini` 增加 `norecursedirs`，显式跳过这些临时目录；
     - 同时禁用 `cacheprovider`，避免 pytest 在仓库根目录做原子 cache 写入时再次撞到历史残留目录权限问题。
   - 结果：`41 passed`

3. Flutter 模块全量测试已通过。
   - 执行：
     - `flutter test`
   - 结果：全量通过。
