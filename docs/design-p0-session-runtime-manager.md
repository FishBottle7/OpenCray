# Design: P0-2 Session Runtime Manager

Last updated: 2026-03-12

## Status

Draft

## Related backlog items

- `docs/agent-runtime-issues.md` Issue P0-2
- `docs/agent-runtime-roadmap.md` M1 Stateful Runtime Foundation

## Goal

Introduce a session-scoped runtime manager that owns the lifecycle of chat-backed agent runtimes.

This manager should become the main host boundary between:

- Android shell UI
- runtime construction
- queue persistence
- task submission and control operations

## Problem Statement

The current app path creates a runtime and loop directly inside `AppShellActivity.runAgentPrompt()`.

This causes several problems:

- runtime ownership is embedded in UI code
- each prompt creates a fresh loop
- cancel and retry are not naturally modeled
- restoration has no clean owner
- later work on memory, skills, and traces will keep inflating Activity code

The core issue is not only code organization. It is lifecycle ownership.

## Design Principles

- A chat session should have one runtime owner.
- UI should coordinate, not own, runtime state transitions.
- The manager should be thin but explicit.
- It should not become a generic service locator.
- It should work with the current queue model, not replace it.

## Responsibilities

The session runtime manager should own:

- runtime creation for a given chat session
- loop creation and restoration
- queue snapshot store resolution
- submit, cancel, retry, and resume entry points
- runtime event subscription
- cleanup or release strategy for inactive sessions

The manager should not own:

- prompt assembly internals
- memory write policy
- skill execution logic
- UI rendering

## Proposed Architecture

## Main objects

### 1. `AgentSessionRuntimeManager`

Top-level host-side coordinator.

Responsibilities:

- return a runtime session handle for a chat session id
- create one if missing
- restore one if previously persisted
- release idle sessions when appropriate

### 2. `AgentSessionHandle`

A session-scoped runtime wrapper.

Responsibilities:

- expose submit/cancel/retry/resume operations
- expose a stable `AgentLoop`
- expose lightweight runtime state snapshot
- expose event subscription

### 3. `AgentRuntimeDependenciesFactory`

Creates:

- runtime
- tool dispatcher
- queue snapshot store
- event sink

This avoids hardcoding dependency creation in Activity code.

## Suggested relationships

`AppShellActivity`
-> `AgentSessionRuntimeManager`
-> `AgentSessionHandle`
-> `AgentLoop`
-> `SessionQueue`
-> `OpenCrayAgentRuntime`

## Session identity

The manager should use chat session id as the key for runtime ownership in P0.

Key:

- `chatSessionId`

This keeps restoration and UI wiring aligned with current product concepts.

## Session handle contract

The manager should expose a concrete API, not a framework-heavy abstraction.

Example sketch:

```kotlin
internal interface AgentSessionHandle {
  val sessionId: String

  fun submitPrompt(
    userText: String,
    policyDecision: PolicyDecision,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask

  fun runUntilIdle(maxTasks: Int = Int.MAX_VALUE): List<ExecutionResult>

  fun requestCancel(taskId: String): Boolean

  fun requestRetry(taskId: String): Boolean

  fun resume(): SessionLifecycleState

  fun snapshot(): SessionQueueSnapshot
}
```

This sketch can evolve, but the important part is that host code stops manipulating queue and runtime construction directly.

## Runtime manager contract

Example sketch:

```kotlin
internal interface AgentSessionRuntimeManager {
  fun forSession(sessionId: String): AgentSessionHandle
  fun release(sessionId: String)
  fun releaseIdleSessions()
}
```

## Construction flow

### First access flow

1. UI asks manager for runtime handle for `session-X`.
2. Manager checks cache.
3. If absent, manager builds:
   - runtime
   - snapshot store
   - event sink
   - `AgentLoop`
4. Manager returns session handle.

### Re-access flow

1. UI asks manager for runtime handle for `session-X`.
2. Manager returns cached handle if still live.
3. If no cached handle exists, manager rebuilds from persistent queue state.

## Proposed internal composition

### `ManagedAgentSession`

Internal concrete implementation holding:

- `sessionId`
- `agentId`
- `AgentLoop`
- `OpenCrayAgentRuntime`
- lightweight usage metadata such as last access time

### `SessionRuntimeDependencies`

Simple internal container holding:

- runtime instance
- queue snapshot store
- tool dispatcher
- event sink

This avoids recomputing and rethreading objects manually in multiple places.

## Event handling

The current app already has runtime event tracing through `OpenCrayAgentRuntimeEventSink`.

The manager should centralize event sink creation per session so the UI does not have to build per-run event callbacks itself.

Initial event handling can remain simple:

- tool call events
- tool result events

Later features can add:

- memory writes
- skill execution
- approval results

## Task submission model

The manager should submit new prompt tasks to the session handle rather than directly calling runtime methods.

Initial path:

- create `AgentTask(type = PROMPT)`
- enqueue in loop
- run until idle

This still supports current behavior while making room for:

- pause and resume
- batched task submission
- user-facing retry

## Interaction with chat persistence

The manager should not replace `ChatSessionLocalStore`.

Instead:

- `ChatSessionLocalStore` remains the source of transcript persistence
- the manager becomes the source of live runtime ownership

The only required alignment is:

- both must agree on `chatSessionId`

## Threading and lifecycle

### Short-term approach

Continue to use the existing background execution model, but move orchestration behind the manager.

### P0 design rule

The manager should be thread-safe enough for:

- repeated access from Activity lifecycle events
- background agent execution callbacks

It does not need to become a full coroutine actor system in P0 unless current threading proves too fragile.

## Caching strategy

### Required

- cache runtime handles per session id

### Optional in P0

- LRU eviction for many inactive sessions

### Recommendation

Keep P0 simple:

- hold runtime handles in a map
- release handles explicitly when the Activity or host determines they are no longer needed
- add idle eviction only if needed after instrumentation

## Failure handling

### If runtime creation fails

- surface failure to host
- do not partially register broken handle in cache

### If loop restoration fails

- fail cleanly
- allow host to recover with clear messaging
- do not silently create a fresh queue if a corrupted persisted queue exists unless that fallback is explicitly approved

## Testing Strategy

## JVM tests

Suggested file:

- `app/src/test/kotlin/com/opencray/app/AgentSessionRuntimeManagerTest.kt`

Cover:

- same session id returns same handle while cached
- release removes cached handle
- recreated handle restores from persistent queue state
- manager does not duplicate runtime ownership for one session

## Android integration tests

Suggested file:

- `app/src/androidTest/kotlin/com/opencray/app/AgentSessionManagerIntegrationTest.kt`

Cover:

- session reopen reuses runtime identity
- cancel and retry are accessible through host code
- runtime events remain session-scoped

## Migration Plan

### Step 1

Introduce manager and route new runtime construction through it.

### Step 2

Move queue snapshot store creation behind the manager.

### Step 3

Replace direct loop creation in `AppShellActivity`.

### Step 4

Migrate event handling to session-scoped sink creation.

## Risks

### Risk: manager becomes too abstract

If the manager is designed like a generic framework, implementation will slow down.

Mitigation:

- keep API narrow
- model only current session runtime needs

### Risk: duplicated state between manager and UI

If UI starts tracking runtime lifecycle separately from the manager, divergence is likely.

Mitigation:

- make manager the single source of runtime ownership

### Risk: restoration and caching semantics conflict

If cached handles and restored handles use different creation paths, bugs may appear.

Mitigation:

- force both to go through the same dependency factory path

## Definition of Done

P0-2 is complete when:

- runtime construction no longer lives directly in `AppShellActivity`
- one chat session maps to one runtime owner
- submit, cancel, retry, and resume are exposed through a session handle
- session restoration uses the same runtime ownership path as normal execution
