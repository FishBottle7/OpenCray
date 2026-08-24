package com.opencray.app

import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecisionState
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostApprovalTest : RuntimeServiceHostTestBase() {
  @Test
  fun runtimeServiceHostApprovePendingApprovalResumesTaskWithoutHostRuntimeFacade() {
    val resumeState = OpenCrayPromptResumeState(turnIndex = 2, toolCallCount = 1)
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-approval"),
      resumeRequestResult = true,
      resultMetadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
        state = resumeState,
        json = Json { prettyPrint = false },
        checkpointBoundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      ) + mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
      ),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(emptyList<String>(), fixture.handle.cancelledTaskIds)
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
    assertEquals(
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      fixture.checkpointStore.get(fixture.taskId)?.promptCheckpointBoundary,
    )
    assertEquals(
      resumeState,
      fixture.checkpointStore.get(fixture.taskId)?.promptResumeState,
    )
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.APPROVED, approvalEvents.lastOrNull()?.phase)
    assertEquals(
      "Approval granted. The agent is resuming.",
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text,
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForSessionResumesTaskWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-approval-for-session"),
      resumeRequestResult = true,
      resultMetadata = mapOf(
        "toolName" to "WebSearch",
        com.opencray.runtime.OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to
          ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
        ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND to
          ProviderNativeWebSearchSupport.APPROVAL_KIND,
        ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL to "true",
      ),
      checkpointToolName = ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
    )

    fixture.serviceHost.approvePendingApprovalForSession(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertTrue(fixture.chatStore.isNativeWebSearchSessionApproved(fixture.sessionId))
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
    assertEquals(
      ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      fixture.checkpointStore.get(fixture.taskId)?.toolName,
    )
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.APPROVED, approvalEvents.lastOrNull()?.phase)
    assertEquals(
      "Approval granted for this session. The agent is resuming.",
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text,
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalRecordsSubAgentReplayWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-approval-subagent"),
      resumeRequestResult = true,
      resultMetadata = mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
        "childRunId" to "child-run-1",
        "childTaskId" to "child-task-1",
        "subagentType" to "search",
        "delegationDescription" to "Delegate",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
      ),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(1, fixture.subAgentReplayEvents.size)
    assertEquals(OpenCraySubAgentPhase.RESUMED, fixture.subAgentReplayEvents.single().phase)
    assertEquals("child-run-1", fixture.subAgentReplayEvents.single().childRunId)
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForExplicitHandleResumesDetachedSubAgentActor() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit",
      childRunId = "child-run-child-explicit",
      childTaskId = "child-task-child-explicit",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-explicit-subagent"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "Edit",
        "canonicalToolName" to "edit",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "worker",
        "delegationDescription" to "Edit notes",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      subAgentHandles = listOf(waitingApprovalSubAgentHandle(agentId = "child-explicit")),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    val detachedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = fixture.sessionId,
      agentId = "child-explicit",
      parentRunId = "parent-run-child-explicit",
    )

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(1, fixture.handle.resumeSubAgentActorsCallCount)
    assertFalse(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertTrue(fixture.handle.detachedControlTasks.isEmpty())
    assertEquals(
      SubAgentPendingApprovalDecisionState.APPROVED,
      fixture.handle.listSubAgentHandles().single().pendingApprovalDecision?.state,
    )
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, detachedTaskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(detachedTaskId)?.checkpointKind,
    )
    assertTrue(
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text in setOf(
        "审批已通过，子任务正在后台继续执行。",
        "Approval granted. The delegated child is resuming in the background.",
      ),
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForSessionWithExplicitHandleResumesDetachedSubAgentActor() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit-session",
      childRunId = "child-run-child-explicit-session",
      childTaskId = "child-task-child-explicit-session",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-session-explicit-subagent"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "WebSearch",
        com.opencray.runtime.OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to
          ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
        ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND to
          ProviderNativeWebSearchSupport.APPROVAL_KIND,
        ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL to "true",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "researcher",
        "delegationDescription" to "Search docs",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      checkpointToolName = ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      subAgentHandles = listOf(waitingApprovalSubAgentHandle(agentId = "child-explicit-session")),
    )

    fixture.serviceHost.approvePendingApprovalForSession(fixture.runId, nowEpochMs = 1_500L)

    val detachedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = fixture.sessionId,
      agentId = "child-explicit-session",
      parentRunId = "parent-run-child-explicit-session",
    )

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(1, fixture.handle.resumeSubAgentActorsCallCount)
    assertTrue(fixture.chatStore.isNativeWebSearchSessionApproved(fixture.sessionId))
    assertFalse(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertTrue(fixture.handle.detachedControlTasks.isEmpty())
    assertEquals(
      SubAgentPendingApprovalDecisionState.APPROVED,
      fixture.handle.listSubAgentHandles().single().pendingApprovalDecision?.state,
    )
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, detachedTaskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(detachedTaskId)?.checkpointKind,
    )
    assertTrue(
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text in setOf(
        "审批已通过，子任务正在后台继续执行。",
        "Approval granted. The delegated child is resuming in the background.",
      ),
    )
  }

  @Test
  fun runtimeServiceHostRejectPendingApprovalCancelsTaskWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-reject-approval"),
      cancelRequestResult = true,
    )

    fixture.serviceHost.rejectPendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(listOf(fixture.taskId), fixture.handle.cancelledTaskIds)
    assertFalse(fixture.approvalRegistry.isRejected(fixture.sessionId, fixture.taskId))
    assertNull(fixture.checkpointStore.get(fixture.taskId))
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.REJECTED, approvalEvents.lastOrNull()?.phase)
    assertEquals(
      "Approval rejected. The requested action was not run.",
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text,
    )
  }

  @Test
  fun runtimeServiceHostRejectPendingApprovalRecordsSubAgentCancellationWithoutHostRuntimeFacade() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-reject-approval-subagent"),
      cancelRequestResult = true,
      resultMetadata = mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
        "childRunId" to "child-run-2",
        "childTaskId" to "child-task-2",
        "subagentType" to "search",
        "delegationDescription" to "Delegate",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
      ),
    )

    fixture.serviceHost.rejectPendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(1, fixture.subAgentReplayEvents.size)
    assertEquals(OpenCraySubAgentPhase.CANCELLED, fixture.subAgentReplayEvents.single().phase)
    assertEquals("child-run-2", fixture.subAgentReplayEvents.single().childRunId)
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalAfterInterruptDefersResumeInCheckpoint() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-deferred-approval"),
      resumeRequestResult = true,
      appendInterruptEvent = true,
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertFalse(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
    val approvalEvents = fixture.journalStore.listRuntimeEvents()
      .filterIsInstance<OpenCrayApprovalEvent>()
    assertEquals(OpenCrayApprovalPhase.APPROVED, approvalEvents.lastOrNull()?.phase)
    assertTrue(
      fixture.chatStore.loadSession(fixture.sessionId)?.messages?.lastOrNull()?.text in setOf(
        "审批已通过。此决定已记录；手动继续运行后才会生效。",
        "Approval granted. The decision is recorded and will apply when you manually resume the run.",
      ),
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalResumesDetachedApprovalRunWithoutQueueTaskSnapshot() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-detached-approve-approval"),
      resumeRequestResult = true,
      includeQueueApprovalTask = false,
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(emptyList<String>(), fixture.handle.cancelledTaskIds)
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, fixture.taskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(fixture.taskId)?.checkpointKind,
    )
  }

  @Test
  fun runtimeServiceHostRejectPendingApprovalCancelsDetachedApprovalRunWithoutQueueTaskSnapshot() {
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-detached-reject-approval"),
      cancelRequestResult = true,
      includeQueueApprovalTask = false,
    )

    fixture.serviceHost.rejectPendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertEquals(listOf(fixture.taskId), fixture.handle.cancelledTaskIds)
    assertFalse(fixture.approvalRegistry.isRejected(fixture.sessionId, fixture.taskId))
    assertNull(fixture.checkpointStore.get(fixture.taskId))
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForExplicitHandleIgnoresSameChildFromDifferentParentRun() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit",
      childRunId = "child-run-child-explicit",
      childTaskId = "child-task-child-explicit",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-explicit-subagent-parent-match"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "Edit",
        "canonicalToolName" to "edit",
        "parentRunId" to "parent-run-child-explicit",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "worker",
        "delegationDescription" to "Edit notes",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      subAgentHandles = listOf(
        waitingApprovalSubAgentHandle(agentId = "child-explicit").copy(
          parentRunId = "parent-run-wrong",
        ),
        waitingApprovalSubAgentHandle(agentId = "child-explicit").copy(
          parentRunId = "parent-run-child-explicit",
        ),
      ),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertTrue(fixture.handle.detachedControlTasks.isEmpty())
    val detachedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = fixture.sessionId,
      agentId = "child-explicit",
      parentRunId = "parent-run-child-explicit",
    )
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, detachedTaskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(detachedTaskId)?.checkpointKind,
    )
  }

  @Test
  fun runtimeServiceHostApprovePendingApprovalForExplicitHandleWithoutParentRunIdUsesUniqueHandleParentRun() {
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = "child-explicit",
      childRunId = "child-run-child-explicit",
      childTaskId = "child-task-child-explicit",
    )
    val fixture = pendingApprovalServiceHostFixture(
      root = temporaryFolder.newFolder("service-host-approve-explicit-subagent-no-parent-run"),
      resultMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
        resume = childResume,
        json = Json { prettyPrint = false },
      ) + mapOf(
        "toolName" to "Edit",
        "canonicalToolName" to "edit",
        "childRunId" to childResume.childRunId.orEmpty(),
        "childTaskId" to childResume.childTaskId.orEmpty(),
        "subagentType" to "worker",
        "delegationDescription" to "Edit notes",
        "subagentContextMode" to "delegated",
        "subagentDepth" to "2",
        SubAgentMetadataKeys.CONTROL_TOOL to "wait_agent",
      ),
      subAgentHandles = listOf(waitingApprovalSubAgentHandle(agentId = "child-explicit")),
    )

    fixture.serviceHost.approvePendingApproval(fixture.runId, nowEpochMs = 1_500L)

    assertEquals(emptyList<String>(), fixture.handle.resumedTaskIds)
    assertTrue(fixture.handle.detachedControlTasks.isEmpty())
    val detachedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = fixture.sessionId,
      agentId = "child-explicit",
      parentRunId = "parent-run-child-explicit",
    )
    assertTrue(fixture.approvalRegistry.isApproved(fixture.sessionId, detachedTaskId))
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      fixture.checkpointStore.get(detachedTaskId)?.checkpointKind,
    )
  }
}
