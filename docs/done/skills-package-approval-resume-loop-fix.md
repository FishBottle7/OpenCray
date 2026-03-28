## Skills Package Approval Resume Loop Fix

Status: Done

### Problem

`SkillsFind` and the related skill package tools can get stuck in an approval loop:

- the tool asks for approval
- the user approves it
- the same approval prompt appears again

The visible symptom is strongest on `SkillsFind`, but the underlying bug is shared by the helper gates used by `SkillsInspect`, `SkillsCheck`, `SkillsAdd`, `SkillsAddBatch`, and `SkillsUpdate`.

### Root Cause

The approval system currently mixes two different tool names:

1. The policy layer correctly evaluates the canonical underlying tool.
   - remote skill access is evaluated as `WebFetch`
   - local source reads are evaluated as `Read`
2. The denied result metadata is then rewritten to the wrapper tool surface.
   - for example `SkillsFind`
3. Approval resume later reuses the rewritten display name instead of the canonical policy name.
4. `ToolPolicyEvaluator` only honors a retry grant when the approved tool name exactly matches the next gated tool.

That means the retry grant is stored as `SkillsFind`, but the next policy gate still asks about `WebFetch`, so the approval never matches and the run loops back into `APPROVAL_REQUIRED`.

There is a second, separate UX issue: chat projection currently hides tool call/result runtime messages, which makes it look like there was no tool call record even though runtime events were emitted.

### Fix Scope

1. Keep policy classification on the canonical underlying tool so safety semantics do not change.
2. Add a dedicated metadata field for approval resume, separate from the user-facing tool name.
3. Make runtime sub-agent resume and host-level approval resume both use the dedicated resume field.
4. Persist the resume tool name through durable approval checkpoints.
5. Add regression tests for:
   - `SkillsFind` approval -> retry -> success
   - host approval snapshots that display `SkillsFind` but resume with `WebFetch`

### Implemented

1. Added `approvalResumeToolName` metadata on the shared skill package approval helpers.
2. Updated runtime approval resume to prefer `approvalResumeToolName` and forward it through child approval metadata.
3. Updated host approval handling to:
   - keep the wrapper tool name for UI and replay text
   - store the canonical resume tool name for retry grants and durable checkpoints
4. Added regression coverage for:
   - dispatcher retry after `SkillsFind` approval
   - host approval resume with `SkillsFind` display name and `WebFetch` retry name

### Verification

- Added targeted regression tests in:
  - `runtime/src/test/kotlin/com/opencray/runtime/OpenCrayToolDispatcherSkillPackageToolTest.kt`
  - `app/src/test/kotlin/com/opencray/app/OpenCrayHostRuntimeTest.kt`
- Attempted to run:
  - `./gradlew.bat :runtime:testDebugUnitTest :app:testDebugUnitTest --tests "com.opencray.runtime.OpenCrayToolDispatcherSkillPackageToolTest" --tests "com.opencray.app.OpenCrayHostRuntimeTest"`
- Verification is currently blocked by unrelated existing compilation failures elsewhere in the worktree:
  - `app:compileDebugKotlin` already fails on missing `loadNotificationSettings` / `notificationSettingsFacade`
  - `runtime:compileDebugUnitTestKotlin` already fails on unrelated unresolved symbols in other test files

### Non-goals

- Do not weaken `NETWORK_ACCESS` approvals by reclassifying these operations as `SkillsFind`.
- Do not change the visible approval title/body away from the wrapper tool name when that wrapper is the user-facing action.
