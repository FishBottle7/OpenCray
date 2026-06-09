# Working State Layer Design

Last updated: 2026-03-27

## Status

Planning document.

## Goal

Define a `Working State layer` for OpenCray that preserves short-term procedural continuity without collapsing that responsibility into either:

- raw transcript replay
- durable memory
- host-side compaction summaries alone

The design target is a hybrid:

- keep OpenCray's current sensitivity to user preference, project convention, and durable instruction
- add stronger Codex-like continuity for long, tool-heavy, branching tasks

This document proposes the missing middle layer.

## Why this layer exists

OpenCray already has:

- transcript replay
- prompt-local pruning
- prompt-local compaction summaries
- pre-compaction memory flush
- durable memory recall
- durable compaction summaries

That is enough to preserve high-level direction and durable facts.
It is not enough to reliably preserve short-term procedural state.

Today, the system can still lose:

- which file it just edited
- which command it just ran
- which branch of investigation failed
- which exact next step is pending
- which local finding changed the plan

This is the main gap between:

- OpenClaw-style structured context sensitivity
- Codex-style long-task procedural continuity

## Design summary

OpenCray should add a distinct `Working State layer` with these properties:

- centered on the current task or active run
- short-term and overwrite-friendly
- compact enough to inject every turn
- durable enough to survive compaction, approval waits, host restore, and long multi-turn work
- explicitly not the same thing as long-term memory

The layer should behave like a small, high-value "work panel" for the model.

Related design:

- `docs/codex-claude-balanced-context-management-plan.md`

## Conceptual model

OpenCray should distinguish four different stores or views:

### 1. Transcript

Purpose:

- preserve the chronological record of what happened

Contains:

- user messages
- assistant messages
- tool calls
- tool results
- progress messages
- system/runtime inserts

Good at:

- auditability
- replay
- provenance

Weak at:

- compact procedural continuation under pressure

### 2. Working State

Purpose:

- preserve the current procedural state needed to continue work cleanly

Contains:

- current objective
- current subgoal
- recent confirmed findings
- recent mutations
- recent executions
- rejected or failed branches
- current blockers
- next intended actions

Good at:

- helping the agent remember what it just did
- preserving branch rationale
- preserving near-term continuity across compaction and restore

Weak at:

- acting as the full historical record
- acting as durable knowledge storage

### 3. Durable Memory

Purpose:

- preserve stable knowledge that should survive beyond the current work episode

Contains:

- user preference
- project fact
- durable instruction
- task commitment

Good at:

- preference sensitivity
- durable continuity

Weak at:

- preserving detailed procedural state

### 4. Archived / Compacted History

Purpose:

- preserve reduced historical continuity after transcript pressure

Contains:

- durable compaction summaries
- later, provider-native compaction state when available

Good at:

- keeping long history bounded

Weak at:

- replacing a real working-state panel

## Proposed layer stack

The current `context-management-design.md` layers should evolve toward this runtime shape:

1. identity and base runtime contract
2. soul and preference contract
3. session directives
4. working state
5. bounded working transcript
6. retrieved durable memory
7. archived / compacted history
8. skill and execution capsules
9. task protocol

The key change is:

- `working state` should no longer be treated as only "bounded reconstruction of recent transcript"

Instead:

- transcript and working state should be separate layers with different ownership and compaction rules

## What Working State should contain

The first version should stay narrow and explicit.

### A. Objective block

Fields:

- `taskId`
- `runId`
- `primaryGoal`
- `currentSubgoal`
- `status`

Purpose:

- keep the model oriented to the exact current objective

### B. Recent findings block

Fields:

- small list of confirmed findings
- each finding includes source type and short rationale

Examples:

- "Compaction currently uses a 12-message transcript window."
- "Durable compaction rewrites transcript tail after summary persistence."

Purpose:

- preserve the facts that changed the plan

### C. Recent actions block

Fields:

- recent file reads
- recent file writes or edits
- recent commands or processes
- recent delegation results

Each action should be represented as a compact structured item, not a full transcript replay.

Examples:

- `Read README.md lines 1-80`
- `Edit runtime/context/CompactionPolicy.kt`
- `Bash gradlew test`
- `Task child summarized durable compaction`

Purpose:

- preserve exact procedural state that the current observation layer only preserves partially

### D. Branch and decision block

Fields:

- accepted branch decisions
- rejected paths
- reason each branch changed

Examples:

- "Do not treat durable memory as procedural working memory."
- "Do not model compact as one global prompt string rewrite."

Purpose:

- avoid repeating failed or superseded approaches

### E. Blockers and next actions block

Fields:

- current blocker
- unresolved question
- next planned actions

Examples:

- "Need to confirm Codex auto-compact trigger path in source."
- "Next: inspect recent observation coverage for mutation tools."

Purpose:

- make continuation quality less dependent on raw recency alone

## What Working State should not contain

To stay useful, the layer must be selective.

It should not become:

- a second transcript
- a long freeform summary blob
- a duplicate durable memory store
- a hidden chain-of-thought dump

It should avoid:

- large raw tool outputs
- large code snippets
- speculative reasoning text
- stale entries that no longer affect the current task

## Lifecycle

### 1. Creation

Create or refresh working state at prompt-task start for the active task or run.

Primary sources:

- current task metadata
- recent transcript tail
- recent tool observations
- resume checkpoints
- lightweight runtime state derived from recent actions

### 2. Updates during the run

Working state should update after meaningful events, not after every token.

Recommended update points:

- after a confirmed tool result
- after a mutation
- after a branch-decision change
- after a delegation result
- after a blocker is discovered or cleared
- before suspension / approval wait / checkpoint save

### 3. Prompt injection

Inject the working-state layer every turn with its own explicit size budget.

This layer should have higher continuation priority than:

- old transcript tail
- generic compaction summaries

This priority should be enforced by the global context budget coordinator rather than by working-state logic alone.

### 4. Compaction behavior

Working state should compact differently from transcript.

Rules:

- keep only entries relevant to the active objective
- drop stale actions once absorbed into findings or decisions
- collapse repeated actions into one normalized item
- preserve the latest accepted branch and latest blocker
- preserve the latest mutation and latest execution facts longer than discovery noise

### 5. End-of-task behavior

At task completion:

- clear most working-state entries
- optionally retain a tiny handoff summary for the next follow-up turn
- flush durable facts into memory only through existing memory policy paths

## Persistence model

Working state is logically runtime context, but it should not be purely in-memory.

Recommended persistence:

- per session
- optionally keyed by active run or active task
- short-term durable across:
  - approval pauses
  - host rebuild
  - runtime restore
  - transcript compaction

It should be easy to clear, rotate, or rebuild.

It should not be treated as permanent user memory.

## Relationship to transcript

Transcript and working state should cooperate like this:

- transcript remains the source of chronological truth
- working state is the compact operational projection used for continuation

Transcript answers:

- what happened

Working state answers:

- what matters now

## Relationship to durable memory

Durable memory and working state should cooperate like this:

- durable memory preserves stable facts across tasks and sessions
- working state preserves task-local procedural continuity

Durable memory answers:

- what should still matter later

Working state answers:

- what the agent is doing right now

This distinction is critical.
If working state is merged into durable memory, the memory store becomes noisy and procedural.
If durable memory is asked to replace working state, continuation quality remains weak.

## Relationship to durable compaction

Durable compaction should not be the primary mechanism for preserving working state.

Instead:

- durable compaction preserves reduced older history
- working state preserves the active operational slice

When compaction pressure increases, the runtime should prefer:

1. keep working state stable
2. shrink transcript tail
3. summarize archived history

not:

1. summarize everything into one host-authored text block

## Relationship to TodoWrite

`TodoWrite` is useful but not sufficient.

`TodoWrite` captures:

- explicit user-visible plan state

Working state should capture more than that:

- recent evidence
- precise recent actions
- accepted and rejected branches
- blockers
- next step reasoning at the public procedural level

So the right relationship is:

- Todo state is one input into working state
- working state is not merely the todo list

## Recommended first implementation

Do not start with a large generic schema.
Start with a small testable object.

### First-pass state shape

```text
WorkingState
  objective:
    primaryGoal
    currentSubgoal
    status
  findings:
    up to 6 entries
  recentActions:
    up to 8 entries
  decisions:
    up to 4 entries
  blockers:
    up to 3 entries
  nextActions:
    up to 4 entries
  updatedAt
```

### First-pass update policy

Update from:

- `Read`, `LS`, `Grep`, `Glob`
- `Write`, `Edit`, `MultiEdit`
- `Bash`, `python_exec`
- `Process*`
- delegation tools
- approval / resume boundaries

### First-pass prompt budget

Use a hard bounded layer budget with:

- per-entry caps
- total layer cap
- model-aware total prompt allocator above it

The exact numbers can be tuned later.
The important change is structural separation, not the first numeric guess.

## Recommended integration points

Potential implementation ownership:

- `runtime/workingstate/*` owns schema, update policy, renderer, and persistence
- `ContextManager` treats rendered working state as one named layer with its own budget
- `AppAgentSessionTaskRuntimeFactory` loads and saves short-term working state around prompt-task lifecycle
- `OpenCrayAgentRuntime` emits the action events that update the working-state model

## Migration strategy

### Phase 1

- add working-state store
- add renderer
- inject layer into prompt
- update from recent discovery, mutation, execution, and delegation events

### Phase 2

- make compaction prefer preserving working state over old transcript detail
- persist through restore and approval boundaries
- expose debug projection and snapshot trace

### Phase 3

- on supported providers, combine local working state with provider-native continuation / compaction
- keep OpenCray-owned working state even when opaque provider compaction exists

This hybrid keeps:

- OpenCray control over preference sensitivity and runtime structure
- stronger Codex-like long-task continuity

Related design:

- `docs/codex-claude-balanced-context-management-plan.md`

## Bottom line

If OpenCray wants both:

- OpenClaw-style sensitivity to durable user and project context
- Codex-style continuity on long tool-heavy tasks

then the missing piece is not "more memory" in the generic sense.

The missing piece is:

- a distinct short-term procedural `Working State layer`

Without it, transcript, memory, and compaction each do part of the job, but none of them cleanly owns the agent's current work surface.
