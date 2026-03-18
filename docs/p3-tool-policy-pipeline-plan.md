# P3 Tool Policy Pipeline Implementation Plan

## Objective

P3 exists to centralize tool authorization and post-execution guarding into one runtime pipeline.

The target is not "more policy". The target is one consistent path for:

1. canonical tool normalization
2. target and scope resolution
3. policy evaluation
4. standard vs high-risk approval classification
5. execution handoff
6. result guarding and metadata normalization

This slice should make the runtime behave more like OpenClaw/Codex-style agents where policy is attached to the action being attempted, not to whichever tool implementation happened to handle it.

## Implementation Status

The shared runtime pipeline now exists at `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt`.

Already routed through the shared pipeline:

- mutating filesystem tools
- network tools
- command and Python execution
- managed process start / terminate
- read-only filesystem tools:
  - `workspace_list_files`
  - `workspace_read_file`
  - `LS`
  - `Read`
  - `Grep`
  - `Glob`

Explicit runtime intent models now also exist so downstream consumers do not need to infer execution semantics from tool names alone:

- `ExecutionIntent`
  - `Bash`
  - `ProcessStart`
  - `command_exec`
  - `python_exec`
- `ProcessLifecycleIntent`
  - `ProcessTerminate`

Current intent metadata fields exposed through the shared pipeline:

- `intentCategory`
- `executionIntentKind`
- `executionTransport`
- `executionCommandPreview`
- `executionScriptPath`
- `executionWorkingDirectory`
- `processLifecycleIntentKind`
- `intentProcessId`
- `intentWorkingDirectory`

Current read-policy behavior that future tool work must preserve:

- approved external read roots participate in the same policy path as workspace reads
- `SAFE` mode:
  - reading inside the workspace is allowed
  - reading an approved external read root requires standard approval
- `AUTO` mode:
  - approved external reads remain allowed
- `DEVELOPER` mode:
  - approved external reads remain allowed

## Why P3 Is Next

P0 and P2 already moved the runtime closer to a durable agent loop:

- approval requests suspend and resume instead of pretending to be final failures
- managed processes have a runtime model and durable ownership
- replay is durable enough that policy outcomes now matter across restarts

The next gap is that policy is still too dispersed inside `AgentTooling.kt`. File tools, command tools, Python execution, managed process start, and network tools still assemble decisions and metadata in slightly different ways. That makes three things harder than they should be:

- proving the workspace boundary is enforced consistently
- keeping approval behavior aligned across Safe, Auto, and Dev
- making replay and UI consume one stable policy/result shape

For execution/process tools, the runtime should now prefer an explicit intent model over tool-name inference. If a new tool crosses an execution or process boundary, add the intent model first, then thread it through policy, approval, replay, and UI metadata.

## Current State Summary

Today the main policy logic is concentrated in:

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `policy/src/main/kotlin/com/opencray/policy/ModePolicy.kt`
- `policy/src/main/kotlin/com/opencray/policy/SafetySettingsPolicy.kt`
- `runtime/src/test/kotlin/com/opencray/runtime/AgentToolPolicyGateTest.kt`

Current pain points:

- canonical Claude-style tools and legacy aliases both exist, but policy is still too close to individual handlers
- path normalization, workspace classification, and approval metadata are mixed into tool execution code
- approval-required results are assembled in multiple places
- result clipping is tool-specific instead of flowing through one result-guarding layer
- tests still prove behavior mostly through per-tool quirks instead of one shared pipeline contract

## Scope

P3 covers tools that touch filesystem, process execution, or network boundaries, plus the metadata contract used by replay/UI.

In scope:

- `LS`
- `Read`
- `Write`
- `Edit`
- `MultiEdit`
- `ImportFile`
- `Grep`
- `Glob`
- `Bash`
- `ProcessStart`
- `ProcessTerminate`
- `WebSearch`
- `WebFetch`
- legacy aliases that map to the same actions

Partially in scope:

- `TodoWrite`
  - include canonical normalization and metadata normalization
  - do not let it block the first authorization slices because it does not cross workspace or host boundaries

Out of scope for P3:

- context compaction
- soul/memory retrieval policy
- bootstrap context files such as `AGENTS.md` / `TOOLS.md`
- subagent orchestration
- PTY-style terminal UX
- host-side approval UI wording

## Policy Baseline To Preserve

P3 is a pipeline refactor first, not a product policy redesign.

During P3:

- keep `ModePolicy` and Safety settings as the source of truth for allowed vs approval-required vs denied behavior
- preserve the current "ordinary approval" vs "high-risk approval" distinction
- preserve the current "approved task/tool retry" semantics
- preserve the current workspace vs approved external read-root separation
- preserve canonical Claude-style tool names as the first-class surface, with legacy names remaining aliases

If a policy inconsistency is discovered, P3 may expose it through one classifier and one metadata shape, but it should not silently redesign the matrix without a follow-up decision.

## Target Architecture

Add a dedicated runtime package:

- `runtime/src/main/kotlin/com/opencray/runtime/policy/`

Recommended classes:

- `ToolInvocationSpec`
  - normalized tool name
  - raw tool name
  - normalized arguments
  - coarse capability class
- `ToolTargetSet`
  - resolved paths, URLs, working directory, process intent, and target summaries
- `ToolPolicyRequest`
  - execution mode
  - task metadata
  - normalized tool intent
  - resolved targets
  - side-effect class
- `ToolPolicyTrace`
  - one structured record of how the decision was reached
- `ToolPolicyPipeline`
  - orchestration entry point used by `AgentTooling`
- `ToolCallNormalizer`
  - canonical name normalization and argument alias cleanup
- `ToolTargetResolver`
  - path resolution, workspace/external-root classification, URL normalization, working-directory resolution
- `ToolPolicyEvaluator`
  - bridge from normalized request into `ModePolicy`, Safety overrides, and approval replay overrides
- `ToolResultGuard`
  - shared clipping/truncation/result metadata layer
- `ToolMetadataNormalizer`
  - emits one stable metadata shape for success, approval-required, deny, and execution failure

The `policy` module should stay focused on pure policy decisions. Runtime-specific concerns such as absolute path resolution, output clipping, tool alias normalization, and approval-token handoff belong in the runtime policy package.

## Desired Pipeline

Every covered tool call should flow through the same stages:

1. Normalize the tool surface.
   - Map legacy names to canonical action names.
   - Normalize argument aliases before policy or execution logic sees them.
2. Resolve targets.
   - Resolve paths relative to workspace or approved read roots.
   - Resolve working directories for command/process execution.
   - Resolve URL/network intent for fetch/search tools.
3. Classify the side effect.
   - Read-only, mutating filesystem, process execution, process lifecycle change, or network access.
4. Evaluate policy once.
   - Base mode policy.
   - Safety settings overrides.
   - approved-task and approved-tool overrides.
   - derive standard vs high-risk approval if the outcome is `ASK`.
5. Produce one gate result if execution is not allowed yet.
   - one error code path
   - one approval detail path
   - one metadata shape
6. Execute through the existing tool backend.
7. Guard and normalize the result.
   - clipping/truncation metadata
   - stable target metadata
   - stable policy metadata

The important invariant is: policy should depend on the normalized action and resolved target set, not on which handler was called first.

## Suggested Metadata Contract

P3 should standardize the metadata emitted from gated or successful tool calls.

Minimum common keys:

- `requestedToolName`
- `normalizedToolName`
- `policyOutcome`
- `policyReasonCode`
- `executionMode`
- `approvalRisk` when applicable
- `targetKind`
- `targetSummary`
- `workspaceRelation`
  - `workspace`
  - `approved_external_read_root`
  - `outside_approved_scope`
- `resultTruncated`
- `resultLimitKind`

Tool-specific keys can remain, but they should be additive. Replay and UI should not have to infer policy semantics from tool-specific strings.

## Recommended Implementation Order

### Slice P3-1: Extract The Neutral Pipeline Contract

Goal:
Introduce the runtime policy package and a small orchestration API without changing behavior.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/policy/`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`

Work:

- add normalized request/result data classes
- add canonical tool normalization in one place
- move tool alias mapping out of ad hoc handler logic into the normalizer
- create a `ToolPolicyPipeline` entry point that can be called from `AgentTooling`
- keep existing helper methods temporarily, but route through the new request model

Acceptance:

- no behavior change yet
- canonical and legacy tool names resolve to the same normalized action identity
- dispatcher integration tests still pass

### Slice P3-2: Centralize Gate Result Construction

Goal:
Remove duplicated approval/deny result assembly.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/policy/*`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`

Work:

- move these concerns behind the pipeline:
  - execution mode inference
  - Safety settings override application
  - coarse plus fine-grained decision merge
  - approved-task and approved-tool replay override
  - approval error code selection
  - approval detail wording
  - common metadata emission
- make file mutation, command, Python, network, and process-start tools all use the same gate result builder

Acceptance:

- one approval-required result path
- one deny result path
- `approvalRisk` and `policyReasonCode` come from the same place for all covered tools
- `AgentToolPolicyGateTest` can assert common metadata keys instead of tool-local details

### Slice P3-3: Unify Filesystem Target Resolution

Goal:
Make workspace boundary enforcement and external-read-root classification explicit and reusable.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/policy/*`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt` if working-directory checks should share the same resolver

Work:

- centralize path resolution for:
  - single readable file
  - single writable file
  - source plus destination mutation
  - import from external read root into workspace
  - directory scan roots
- emit one target summary describing:
  - resolved absolute path
  - workspace-relative label when available
  - workspace relation
- make `LS`, `Read`, `Write`, `Edit`, `MultiEdit`, `ImportFile`, `Grep`, `Glob`, and legacy file aliases consume the same target resolver

Acceptance:

- policy no longer depends on each file tool resolving paths independently
- reads outside the approved readable roots fail or request approval through the same path
- mutation tools do not mutate anything if target resolution or gating fails
- canonical and legacy file tools produce the same workspace classification metadata

### Slice P3-4: Unify Execution And Process Authorization

Goal:
Make `Bash`, legacy `command_exec`, Python execution, managed process start, and process termination share one execution policy model.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/policy/*`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `policy/src/main/kotlin/com/opencray/policy/ModePolicy.kt` only if a missing pure decision primitive must be added

Work:

- normalize `Bash` and `command_exec` into one execution intent
- normalize raw Python execution and `ProcessStart` with `script_path` into one process-execution capability class
- classify `ProcessTerminate` as a process lifecycle mutation instead of a special-case tool
- reuse the same working-directory resolution and policy gate entry point for command, Python, and process tools
- keep the existing managed-process backend; only replace the policy front door

Acceptance:

- command, Python, process start, and process terminate are gated by one policy entry path
- safe/auto/dev behavior is derived from one capability classifier, not hand-written branches per tool
- approval metadata and target summaries are consistent across `Bash`, `ProcessStart`, and Python execution

### Slice P3-5: Centralize Result Guarding

Goal:
Move clipping and normalized success metadata into one layer.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/policy/*`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`

Work:

- centralize truncation reporting for:
  - `Read`
  - `Grep`
  - `Glob`
  - `WebFetch`
  - `WebSearch`
  - command/Python/process read output where applicable
- keep tool-specific human-readable output, but make truncation metadata consistent
- ensure the same target summary and normalized tool identity is attached to successful results
- include `TodoWrite` in metadata normalization even if it remains outside workspace/network approval concerns

Acceptance:

- replay/UI can read one stable result-truncation contract
- covered tools emit `requestedToolName` and `normalizedToolName`
- output clipping is explicit instead of inferred from content strings

### Slice P3-6: Cleanup And Test Rebalance

Goal:
Finish the migration and leave one understandable test surface.

Files:

- `runtime/src/test/kotlin/com/opencray/runtime/AgentToolPolicyGateTest.kt`
- new: `runtime/src/test/kotlin/com/opencray/runtime/policy/*`
- any touched runtime sources

Work:

- add focused unit tests for:
  - tool normalization
  - target resolution
  - capability classification
  - approval-risk selection
  - metadata normalization
- keep dispatcher integration tests for representative end-to-end behavior
- remove obsolete helper paths in `AgentTooling.kt` once all covered tools flow through the pipeline

Acceptance:

- most policy assertions move into dedicated pipeline tests
- dispatcher tests only need to prove integration, mutation safety, and representative tool behavior
- `AgentTooling.kt` no longer owns policy assembly details that belong in the runtime policy package

## Recommended PR Boundaries

Keep P3 out of one giant patch. Recommended sequence:

1. PR A: `P3-1` plus `P3-2`
   - foundation plus unified gate-result construction
   - should be mostly refactor and metadata stabilization
2. PR B: `P3-3` plus `P3-4`
   - filesystem and execution/process unification
   - highest security value
3. PR C: `P3-5` plus `P3-6`
   - result guarding, metadata cleanup, and test rebalance

This keeps the first merge behavior-preserving, the second merge security-relevant, and the third merge cleanup-oriented.

## Test Plan

Add dedicated unit tests under:

- `runtime/src/test/kotlin/com/opencray/runtime/policy/`

Required coverage:

- canonical tool normalization for all Claude-style names and legacy aliases
- workspace-relative vs external-read-root path classification
- source plus destination mutation classification
- approval-required vs high-risk approval-required classification
- approved-task and approved-tool retry override behavior
- no mutation on failed target resolution or failed gating
- normalized truncation metadata for read/search/fetch/command output

Keep integration coverage in:

- `runtime/src/test/kotlin/com/opencray/runtime/AgentToolPolicyGateTest.kt`

Representative dispatcher cases:

- `Read` inside workspace
- `Read` on approved external read root
- `Write` / `Edit` / `MultiEdit` inside workspace
- `ImportFile` from approved external read root
- `Bash`
- `ProcessStart`
- `ProcessTerminate`
- `WebFetch`

## Risks And Guardrails

Main risks:

- changing approval semantics while trying to refactor structure
- breaking legacy alias compatibility while normalizing tool names
- leaking path details or allowing outside-workspace mutation through an incomplete resolver
- producing inconsistent metadata during the migration when some tools use the old path and some use the new one

Guardrails:

- start with behavior-preserving extraction
- land one shared gate-result builder before changing path resolution
- do not remove legacy aliases during P3
- require mutation-safety tests for every tool moved onto the pipeline
- keep runtime-level integration tests green before deleting old helpers

## Definition Of Done

P3 is done when all of the following are true:

- the runtime policy package owns normalized tool policy flow
- `AgentTooling.kt` delegates policy work instead of assembling it inline
- canonical and legacy aliases share the same normalized action identity
- filesystem, command, Python, managed process, and network tools emit one policy metadata contract
- approval-required and deny results come from one gate builder
- result truncation is explicit and normalized
- tests prove policy behavior through the pipeline contract instead of scattered per-tool quirks

## Immediate Next Step

Start with `P3-1` and `P3-2` together.

That gives the lowest-risk first move:

- introduce the runtime policy package
- centralize canonical-name normalization
- centralize approval/deny result construction
- stabilize metadata before touching the workspace/path resolver

That ordering reduces the chance of mixing policy semantics changes with path-safety refactors in the same patch.
