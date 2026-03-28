# Files Native Refresh Plan

## Context

The current Files screen now refreshes in Flutter when one of these events happens:

- the Files tab becomes active again
- the app returns to the foreground
- the user switches directories
- the Files tab stays visible long enough for the lightweight polling loop to fire

That closes the immediate usability gap, but it is still a fallback strategy. External file writes are only reflected on the next poll tick while the Files tab is visible.

## Goal

Move from visible-only polling to native filesystem change delivery so the Files screen can refresh closer to real time, with less repeated snapshot loading when nothing changed.

## Recommended Direction

Implement native workspace change observation on Android, debounce those changes on the host side, and expose them to Flutter as a dedicated event stream. Flutter should keep the existing tab-activation and lifecycle refresh triggers as a safety net, but polling can then be reduced heavily or removed.

## Proposed Architecture

### 1. Host-side watcher

Add a workspace watcher in Android code near the local host gateway layer. The watcher should:

- observe the active workspace root recursively
- emit coarse change signals instead of per-file UI payloads
- debounce bursts of writes into a single refresh event
- restart cleanly if the workspace root changes

Android options:

- `FileObserver` is the most direct Android-native fit
- a Java NIO `WatchService` wrapper is possible, but `FileObserver` is likely simpler for app storage and Android lifecycle handling

Recommended event payload:

```kotlin
mapOf(
  "workspaceRoot" to "/abs/path",
  "reason" to "content_changed",
  "changedPaths" to listOf("docs/report.md"),
  "timestampEpochMs" to 0L,
)
```

`changedPaths` can stay bounded and optional. Flutter only needs to know that the cached tree is stale.

### 2. Bridge surface

Expose a new event channel from `OpenCrayFlutterHostBridge` to Flutter, separate from `watchShellSnapshot()`.

Suggested naming:

- Android channel: `opencray/files_snapshot_events`
- Dart API: `Stream<OpenCrayFilesRefreshSignal> watchFilesRefreshSignals()`

Keep `loadFilesSnapshot()` as the source of truth. The new stream should only tell Flutter when to reload, not replace snapshot loading itself.

## Flutter-side behavior

When the Files screen is active and receives a refresh signal:

- coalesce duplicate signals while a snapshot load is already in flight
- run a silent snapshot refresh
- preserve the current directory, selection cleanup, and pending transfer sanitation logic

When the Files screen is inactive:

- either ignore the signal and refresh on next activation
- or mark the snapshot as stale and refresh once when the tab becomes active

The second option is better for battery and avoids unnecessary background work.

## Debounce Rules

Native observation must debounce aggressively enough to survive editor save bursts and recursive copy operations.

Recommended starting point:

- collect events for `250ms` to `500ms`
- collapse them into one `"content_changed"` signal
- if a refresh is already queued, do not enqueue another one

## Failure and Fallback Behavior

Keep the current Flutter fallback triggers even after native observation lands:

- refresh on Files tab activation
- refresh on app resume
- manual refresh button

This protects the UI if the native watcher misses an event, the workspace root changes mid-session, or a platform-specific storage edge case appears.

## Suggested Implementation Order

1. Add a native workspace watcher abstraction and tests around debounce and root switching.
2. Expose the watcher through a new event channel in `OpenCrayFlutterHostBridge`.
3. Add a Dart bridge stream and wire it into `FilesFeatureScreen`.
4. Keep the current polling path behind a fallback gate.
5. After validation on device, reduce the polling frequency or disable polling when native observation is available.

## Risks

- recursive watching can leak observers if root changes are not cleaned up correctly
- editor save flows may emit multiple low-level events for one user action
- some storage locations may behave differently from app-private files
- refreshing too eagerly can still cause unnecessary full-tree snapshot rebuilds

## Validation

Device verification should cover:

- editing a file outside the Files screen and returning to Files
- creating, deleting, renaming, and moving files from another screen or process
- burst writes in the same directory
- workspace root changes while the app stays alive
- app background and foreground transitions during active file writes
