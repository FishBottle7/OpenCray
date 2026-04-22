package com.opencray.app

import com.opencray.runtime.OpenCrayFinalAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOwnedChatRuntimeGatewayWarmupFailureTest {
  @Test
  fun loadChatSnapshotSurfacesWarmupFailureWithoutDisablingInput() {
    val warmupAccess = RecordingOnDeviceWarmupAccess(
      state = OnDeviceLlmWarmupState(
        phase = OnDeviceLlmWarmupPhase.FAILED,
        failureMessage = "Model warmup failed.",
      ),
    )
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = StaticChatGateway(
        chatPayload = mapOf(
          "composerPlaceholder" to "Message OpenCray",
          "isInputEnabled" to true,
        ),
      ),
      onDeviceWarmupAccess = warmupAccess,
      onDevicePreparingPlaceholder = "Preparing on-device model",
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val payload = gateway.loadChatSnapshot()

    assertEquals("Model warmup failed.", payload["composerPlaceholder"])
    assertEquals(true, payload["isInputEnabled"])
    assertTrue(warmupAccess.ensureWarmForActiveSessionCallCount >= 1)
  }

  private class RecordingOnDeviceWarmupAccess(
    private val state: OnDeviceLlmWarmupState,
  ) : OnDeviceLlmWarmupAccess {
    var ensureWarmForActiveSessionCallCount: Int = 0
      private set

    override fun ensureWarmForSession(sessionId: String): OnDeviceLlmWarmupState = state

    override fun ensureWarmForActiveSession(): OnDeviceLlmWarmupState {
      ensureWarmForActiveSessionCallCount += 1
      return state
    }

    override fun clear(): OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()
  }

  private class StaticChatGateway(
    private val chatPayload: Map<String, Any?>,
  ) : OpenCrayChatRuntimeGateway {
    override fun loadChatSnapshot(): Map<String, Any?> = chatPayload

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit = {}

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = emptyMap()

    override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit = {}

    override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = null

    override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = null

    override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit = {}

    override fun refreshSandboxSessionInfo() = Unit

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun searchMemoryDebug(query: String, maxResults: Int, minScore: Int): Map<String, Any?> =
      emptyMap()

    override fun getMemoryDebugSlice(path: String, fromLine: Int?, lines: Int): Map<String, Any?> =
      emptyMap()

    override fun applyMemoryDebugAction(recordId: String, actionId: String): Map<String, Any?> =
      emptyMap()

    override fun createChatSession() = Unit

    override fun copyChatSession(sessionId: String) = Unit

    override fun deleteChatSession(sessionId: String) = Unit

    override fun selectChatSession(sessionId: String) = Unit

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) = Unit

    override fun deleteChatMessage(sessionId: String, messageId: String) = Unit

    override fun recallChatMessage(sessionId: String, messageId: String) = Unit

    override fun submitChatMessage(
      text: String,
      attachments: List<OpenCrayFinalAttachment>,
    ): Map<String, Any?>? = null

    override fun approveChatApproval(taskIdOrRunId: String) = Unit

    override fun approveChatApprovalForSession(taskIdOrRunId: String) = Unit

    override fun rejectChatApproval(taskIdOrRunId: String) = Unit

    override fun interruptChatRun(taskIdOrRunId: String) = Unit

    override fun retryChatRun(taskIdOrRunId: String) = Unit
  }
}
