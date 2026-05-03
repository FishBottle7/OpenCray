## Chat Runtime Live Sync Fix Plan

### Problem Summary

There are two remaining runtime UX issues after the startup/journal bloat fix:

1. Managed `process` bubbles can appear to be overwritten by streamed assistant content.
2. The run `inspector` can lag behind and refresh late instead of updating in real time.

These are no longer persistence or recovery problems. They are live synchronization problems across the runtime snapshot, runtime delta, and live draft display paths.

### Confirmed Code Paths

- Flutter runtime snapshot and delta handling:
  - `flutter_app/lib/features/chat/chat_feature_screen.dart`
  - `_handleChatRuntimeSnapshot(...)`
  - `_handleRuntimeEventDelta(...)`
  - `_handleLiveAssistantDraftEvent(...)`
  - `_applyRuntimeActivityPatch(...)`
  - `_mapRuntimeProjection(...)`
  - `_mergeProjectedAssistantPhaseMessages(...)`
  - `shouldReplaceObservedRuntimeSnapshot(...)`
- Android service runtime delta emission:
  - `app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt`
  - `emitServiceOwnedRuntimeEventDeltaFromSnapshot()`
  - `runtimeEventDeltaPayloadFromRuntimePayloads(...)`
- Fallback polling gateways:
  - `ProjectionOnlyOpenCrayChatRuntimeGateway.kt`
  - `RuntimeServiceCommandFallbackTransport.kt`

### Root Cause Hypothesis

The remaining bugs are split across two layers.

#### 1. Split UI update paths in Flutter

`live draft` updates currently have a direct message-patching path, while `process` bubbles and inspector traces depend on runtime projection recomputation.

That creates a race:

- a live draft can update the visible chat immediately
- a process/inspector update must wait for runtime snapshot or delta projection
- if the runtime-side update arrives later, is filtered, or is not emitted upstream, the UI can temporarily show the draft path without the corresponding process/trace update

This is why the process bubble can appear to be visually covered by streaming content even if the run state itself still exists.

#### 2. Runtime delta emission is too event-centric

The service-owned runtime delta path currently derives deltas primarily from changes in `events`.

If runtime-visible state changes in:

- `activeRuns`
- `retainedRuns`
- `managedProcesses`
- `liveAssistantDrafts`
- process output fields such as `stdout`, `stderr`, `stdoutPreview`, `stderrPreview`

without a new visible runtime event being added, the current delta path may fail to emit a timely update.

That makes inspector freshness depend too heavily on whether the backend modeled the change as a new event.

### Fix Strategy

#### Flutter

1. Keep live draft overrides as runtime data, but stop relying on a separate direct message patch as the primary visible update path.
2. Recompute runtime projection from the latest chat snapshot plus effective runtime state when live draft events arrive.
3. Ensure inspector trace refresh is driven by fresh runtime projection objects, not only by full runtime snapshot replacement.
4. Preserve existing ordering guarantees:
   - managed process bubbles stay visible
   - process bubbles remain above later live draft bubbles for the same anchor message
   - terminal process state is not rolled back by thinner snapshots

#### Android service

1. Expand service-owned runtime delta emission so it treats non-event runtime state changes as delta-worthy.
2. Emit a runtime delta when runtime display state changes even if `deltaEvents` is empty.
3. Preserve sequence continuity and avoid forcing a full snapshot resync for normal process-output growth.

### Test-First Plan

#### Flutter tests

Add or extend widget tests in `flutter_app/test/chat_feature_screen_test.dart` to cover:

1. Live draft events must not suppress or visually replace existing managed process bubbles.
2. Inspector must refresh from runtime deltas that change run/process state even when there is no full runtime snapshot reload.
3. Runtime projection must stay coherent when draft events and runtime deltas interleave on the same `pendingMessageId`.

#### Android service tests

Add service-level tests to cover:

1. Runtime delta emission when run/process detail changes without new runtime events.
2. Runtime delta emission when live draft snapshots change without new runtime events.
3. No delta suppression when only runtime display state changed.

### Non-Goals

- No rollback of the checkpoint/journal sanitization fix.
- No increase in synchronous startup snapshot loading.
- No fallback to always forcing full snapshot reloads after every live update.

### Verification

Planned targeted verification commands:

- `flutter test flutter_app/test/chat_feature_screen_test.dart`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.OpenCrayRuntimeServiceHostTest"`

If Flutter hangs in the sandbox, rerun the Flutter test outside the sandbox with approval.
