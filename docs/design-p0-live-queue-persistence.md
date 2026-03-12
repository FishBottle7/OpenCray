# Design: P0-1 Live Queue Persistence

Last updated: 2026-03-12

## Status

Draft

## Related backlog items

- `docs/agent-runtime-issues.md` Issue P0-1
- `docs/agent-runtime-roadmap.md` M1 Stateful Runtime Foundation

## Goal

Replace the Android app's current in-memory live queue storage with a persistent queue snapshot store so that chat-backed agent runs can survive process death and app restart.

The design target is not "distributed queueing" or "background job orchestration." The target is much narrower and more practical:

- one chat session owns one persistent agent queue state
- runtime queue state can be restored when the same chat session is reopened
- queue lifecycle stays deterministic and local-first

## Problem Statement

Today, `AppShellActivity.runAgentPrompt()` creates a fresh `AgentLoop` and wires it with `InMemorySessionQueueSnapshotStore`.

That means:

- queue state is lost on process death
- cancellation and retry state are not durable in the real app path
- queue restoration logic exists in the repository, but is not used in production host wiring

This creates a mismatch between:

- what the runtime infrastructure can support
- what the actual app currently does

## Current State

### What exists already

- `SessionQueue` supports snapshot persistence.
- `SessionStoreQueueSnapshotStore` already persists queue snapshots into `SessionStore`.
- restart-safe queue recovery tests already exist at the infrastructure level.
- `ChatSessionLocalStore` already provides a persistent identity model for chat sessions.

### What is missing

- the live chat agent path does not use the persistent snapshot store
- there is no explicit mapping between chat session identity and agent queue persistence
- there is no restoration flow on app reopen

## Design Principles

- Reuse existing persistence infrastructure before adding new stores.
- Keep the queue snapshot colocated with the chat-backed runtime session identity.
- Make restoration deterministic and boring.
- Avoid introducing a separate job scheduler abstraction in P0.
- Keep UI and persistence concerns separated.

## Proposed Design

## High-level decision

Use `SessionStoreQueueSnapshotStore` as the live queue persistence mechanism for chat-backed agent runs.

The queue snapshot should be keyed by the active chat session id and restored when that same session is reopened.

## Runtime identity model

### Canonical runtime identity

For P0, the canonical runtime identity should be:

- `chatSessionId`

This is sufficient because:

- the app already has stable chat session ids
- queue state is currently single-session and single-agent in practice
- it avoids adding a second identity model before a runtime manager exists

### Agent id

Keep agent id stable and explicit, for example:

- `opencray-app`

This matches current runtime construction and avoids introducing an agent identity migration in P0.

## Storage model

### Store type

Persist queue snapshots using:

- `SessionStoreQueueSnapshotStore`

### Backing record

Back snapshots with a `SessionRecord` keyed to the chat session id.

### Data shape

The persisted record should continue to store:

- queue lifecycle state
- next enqueue order
- serialized queue snapshot JSON

No new queue snapshot schema is required in P0 unless testing reveals missing fields.

## Proposed architecture

### Before

`AppShellActivity`
-> build runtime
-> create fresh `AgentLoop`
-> use `InMemorySessionQueueSnapshotStore`
-> submit prompt task
-> drain immediately

### After

`AppShellActivity`
-> ask session runtime owner for the loop for `chatSessionId`
-> loop is backed by `SessionStoreQueueSnapshotStore`
-> submit prompt task
-> queue snapshot persists on every transition
-> reopening session restores queue snapshot through the same persistent store

## Data flow

### Submit flow

1. User sends a message in chat session `session-X`.
2. Host resolves queue snapshot store for `session-X`.
3. Host creates or reuses an `AgentLoop` using persistent snapshot store.
4. Task is enqueued.
5. Queue persists snapshot immediately.
6. Runtime executes task.
7. Every lifecycle transition persists the queue snapshot.

### Restore flow

1. App restarts.
2. User reopens `session-X`.
3. Host resolves persistent snapshot store for `session-X`.
4. `SessionQueue` restores from stored snapshot.
5. Restored queue is normalized according to existing restart logic.
6. Host can resume execution or show recoverable state.

## Proposed Code Changes

## 1. Introduce a queue snapshot store resolver

Add a small host-side component that resolves the queue snapshot store for a given chat session id.

Suggested responsibility:

- map `chatSessionId` -> `SessionStoreQueueSnapshotStore`
- hide file and store selection from UI code

Suggested file:

- new: `app/src/main/kotlin/com/opencray/app/AgentQueueSnapshotStoreFactory.kt`

## 2. Replace direct `InMemorySessionQueueSnapshotStore` usage

In `AppShellActivity`, remove direct creation of `InMemorySessionQueueSnapshotStore` from the live chat execution path.

Instead:

- resolve the persistent store for the current chat session
- pass it into loop creation

## 3. Keep queue creation external to UI details

This design should be implemented in a way that naturally feeds into `P0-2` session runtime manager.

That means:

- avoid leaving persistence decisions embedded in `AppShellActivity`
- prefer a small factory or manager boundary now

## 4. Keep restore behavior in queue layer, not UI layer

Restoration semantics such as:

- mapping running to queued
- normalizing cancel-requested tasks

already belong in `SessionQueue`.

Do not duplicate restoration logic in app code.

## API Sketch

This sketch is intentionally concrete rather than generic.

```kotlin
internal interface AgentQueueSnapshotStoreFactory {
  fun forChatSession(sessionId: String): SessionQueueSnapshotStore
}
```

Possible implementation:

```kotlin
internal class FileBackedAgentQueueSnapshotStoreFactory(
  private val context: Context,
) : AgentQueueSnapshotStoreFactory {
  override fun forChatSession(sessionId: String): SessionQueueSnapshotStore {
    val sessionStore = JsonFileSessionStore(directoryForSession(sessionId))
    return SessionStoreQueueSnapshotStore(sessionStore)
  }
}
```

This is only a sketch. The final design may decide to colocate queue state differently.

## Directory Strategy Options

There are two reasonable options.

### Option A: Dedicated runtime directory per chat session

Example shape:

- `filesDir/agent-runtime/<chatSessionId>/session.json`

Pros:

- clear separation of runtime state from chat transcript storage
- simple reasoning
- lower accidental coupling

Cons:

- another storage hierarchy to manage
- more host plumbing

### Option B: Reuse the same storage root pattern as the existing chat session model

Pros:

- simpler conceptual model
- less storage sprawl

Cons:

- tighter coupling between transcript persistence and runtime persistence
- future migrations may be trickier

## Recommendation

Use Option A for P0:

- dedicated runtime directory per chat session

Reason:

- it keeps queue persistence isolated
- it avoids contaminating transcript storage design
- it makes debugging simpler

## Restoration UX

P0 should not overdesign the user experience.

The minimum acceptable behavior is:

- if a session has persisted queue state, the host restores it
- if tasks are still queued after normalization, the host can resume them
- if the queue was idle, the restored state remains inert

What P0 does not need yet:

- sophisticated resume banners
- task graph visualizations
- queue history views

## Failure Modes

### Failure mode 1: snapshot missing

Behavior:

- create empty queue

### Failure mode 2: persisted snapshot decode failure

Behavior:

- surface recoverable error
- avoid silent corruption
- optionally clear bad snapshot only after explicit fallback policy is defined

### Failure mode 3: chat session exists but queue session directory does not

Behavior:

- create new queue store
- do not fail chat open

## Testing Strategy

## JVM tests

Add tests for:

- factory returns stable store for same session id
- queue restoration uses persistent snapshot store path
- corrupted snapshot handling behavior

Suggested file:

- `app/src/test/kotlin/com/opencray/app/AgentQueueSnapshotStoreFactoryTest.kt`

## Android integration tests

Add tests for:

- run starts and persists queue state in live app path
- restarting and reopening the same session restores queue state
- different chat sessions do not share queue state

Suggested file:

- `app/src/androidTest/kotlin/com/opencray/app/AgentLiveQueuePersistenceTest.kt`

## Migration Plan

### Step 1

Introduce snapshot store factory and wire it into a test-only path first.

### Step 2

Switch live app path from in-memory snapshot store to persistent store.

### Step 3

Add restoration on session reopen.

### Step 4

Refactor integration into `P0-2` session runtime manager once that abstraction exists.

## Non-Goals

P0-1 does not attempt to solve:

- full runtime lifecycle management
- memory writing
- skill execution
- hook execution
- sub-agent restoration

Those belong to later work.

## Risks

### Risk: storage fragmentation

If each chat session creates many tiny runtime files or multiple stores, filesystem hygiene may get messy.

Mitigation:

- keep one runtime directory and one session record per chat session for now

### Risk: queue state and transcript state drift

If queue persistence and chat persistence use different identity assumptions, restoration may misalign.

Mitigation:

- use chat session id as canonical runtime identity in P0

### Risk: restoration behavior surprises the UI

If restored tasks reappear in ways the UI does not expect, visible inconsistency may result.

Mitigation:

- keep restoration logic normalized in `SessionQueue`
- keep UI behavior minimal in P0

## Definition of Done

P0-1 is complete when:

- the live chat runtime path does not use `InMemorySessionQueueSnapshotStore`
- queue snapshots persist per chat session
- reopening the same chat session restores the queue snapshot
- integration tests cover persistence and restoration behavior
