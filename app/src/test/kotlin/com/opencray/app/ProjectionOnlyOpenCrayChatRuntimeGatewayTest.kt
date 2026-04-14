package com.opencray.app

import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectionOnlyOpenCrayChatRuntimeGatewayTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun projectionOnlyChatRuntimeGatewayIgnoresCheckpointJournalEntriesWhenLoadingRunSnapshots() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-checkpoint-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-checkpoint-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "run-checkpoint-only"
    val taskId = "task-checkpoint-only"

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    journalFactory.forChatSession(sessionId).appendCheckpoint(
      runId = runId,
      taskId = taskId,
      emission = OpenCrayPromptCheckpointEmission(
        boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
        state = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 0,
        ),
        emittedAtEpochMs = 1_100L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val runSnapshot = gateway.loadChatRunSnapshot(runId)

    assertEquals(sessionId, runtimeSnapshot["sessionId"])
    assertTrue(events.isEmpty())
    assertEquals(runId, runSnapshot?.get("runId"))
    assertTrue(runSnapshot?.containsKey("lastEvent") == false)
  }

  private fun projectionTestStrings(): ProjectionOnlyChatStrings = ProjectionOnlyChatStrings(
    localeTag = "en",
    screenTitle = "Chat",
    modeLabel = "AUTO",
    sessionButtonLabel = "Sessions",
    recentSessionsEyebrow = "Recent sessions",
    recentSessionsTitle = "Recent sessions",
    newSessionLabel = "New session",
    defaultSessionTitle = "New chat",
    messagesBadge = { count -> "$count messages" },
    summaryReplyInProgress = "Reply in progress",
    summaryStartNewSession = "Start a new session",
    summaryRestored = "Restored",
    summaryApprovalRequired = "Approval required before the agent can continue.",
    approvalRequiredTitle = "Approval required",
    highRiskApprovalRequiredTitle = "High-risk approval required",
    highRiskApprovalRequiredBody =
      "High-risk approval required. Review this request carefully before approving.",
    approvalApproveLabel = "Approve",
    approvalApproveForSessionLabel = "Allow session",
    approvalRejectLabel = "Reject",
    composerPlaceholder = "Message OpenCray",
    composerRejectedPlaceholder = "Message OpenCray differently",
  )
}
