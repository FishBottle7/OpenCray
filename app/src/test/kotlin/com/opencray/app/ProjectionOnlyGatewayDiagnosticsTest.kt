package com.opencray.app

import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectionOnlyGatewayDiagnosticsTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun projectionShellAndChatGatewaysReuseExplicitHostLifecycleDescriptor() {
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store"))
    val hostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(
      processStartId = "process-shared",
      hostInstanceId = "host-shared",
      runtimeOwnerId = "owner-shared",
    )
    val connectionState = RuntimeServiceConnectionState.bindingPending()
    val shellGateway = ProjectionOnlyOpenCrayShellGateway(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      localeTagProvider = { "en" },
      hostLabel = "HOST",
      hostSummary = "Projection fallback",
      connectionStateProvider = { connectionState },
      hostLifecycleDescriptor = hostLifecycleDescriptor,
    )
    val chatGateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot),
      runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      processRegistryFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot),
      supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot),
      strings = projectionStrings(),
      connectionStateProvider = { connectionState },
      hostLifecycleDescriptor = hostLifecycleDescriptor,
    )

    val shellHostLifecycle = shellGateway.loadShellSnapshot()["hostLifecycle"] as Map<*, *>
    val chatHostLifecycle = chatGateway.loadChatRuntimeSnapshot()["hostLifecycle"] as Map<*, *>

    assertEquals(shellHostLifecycle, chatHostLifecycle)
    assertEquals("host-shared", shellHostLifecycle["hostInstanceId"])
    assertEquals("owner-shared", shellHostLifecycle["runtimeOwnerId"])
  }

  private fun projectionStrings(): ProjectionOnlyChatStrings = ProjectionOnlyChatStrings(
    localeTag = "en",
    screenTitle = "Chat",
    modeLabel = "Auto",
    sessionButtonLabel = "Sessions",
    recentSessionsEyebrow = "Recent",
    recentSessionsTitle = "Recent Sessions",
    newSessionLabel = "New",
    defaultSessionTitle = "New Session",
    messagesBadge = { count -> "$count" },
    summaryReplyInProgress = "Reply in progress",
    summaryStartNewSession = "Start a new session",
    summaryRestored = "Restored",
    summaryApprovalRequired = "Approval required",
    approvalRequiredTitle = "Approval required",
    highRiskApprovalRequiredTitle = "High risk approval",
    highRiskApprovalRequiredBody = "Approval is required.",
    approvalApproveLabel = "Approve",
    approvalApproveForSessionLabel = "Approve for session",
    approvalRejectLabel = "Reject",
    composerPlaceholder = "Ask anything",
    composerRejectedPlaceholder = "Give the next instruction",
  )
}
