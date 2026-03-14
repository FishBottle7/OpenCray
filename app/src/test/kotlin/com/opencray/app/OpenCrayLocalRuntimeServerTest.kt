package com.opencray.app

import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayLocalRuntimeServerTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun exposesShellSnapshotOverLoopbackHttp() {
    val server = localRuntimeServer()
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/shell_snapshot")
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertEquals("HOST CONNECTED", payload.getString("hostLabel"))
      assertTrue(payload.getBoolean("isHostConnected"))
    } finally {
      server.close()
    }
  }

  @Test
  fun forwardsValidationRequestsToHostRuntime() {
    val llmConfigFacade = RecordingLlmConfigFacade()
    val server = localRuntimeServer(llmConfigFacade = llmConfigFacade)
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/validate_llm_config",
        body = JSONObject().apply {
          put("providerId", "openai")
          put("protocol", "openai")
          put("baseUrl", "https://api.openai.com/v1")
          put("apiKey", "secret")
          put("model", "gpt-4o-mini")
          put("reasoningEffort", "medium")
        }.toString(),
      )
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertTrue(payload.getBoolean("isSuccess"))
      assertEquals("Validation succeeded.", payload.getString("message"))
      assertEquals(
        ValidateLlmConfigRequest(
          providerId = "openai",
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          apiKey = "secret",
          model = "gpt-4o-mini",
          reasoningEffort = "medium",
        ),
        llmConfigFacade.lastValidationRequest,
      )
    } finally {
      server.close()
    }
  }

  private fun localRuntimeServer(
    llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
  ): OpenCrayLocalRuntimeServer = OpenCrayLocalRuntimeServer(
    hostRuntimeProvider = {
      OpenCrayHostRuntime.createForTest(
        stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
        chatSessionStore = ChatSessionLocalStore(
          temporaryFolder.newFolder("chat-store-${System.nanoTime()}"),
        ),
        settingsFacade = NoOpSettingsFacade,
        llmConfigFacade = llmConfigFacade,
        sessionRuntimeManager = NoOpRuntimeManager(),
        strings = HostRuntimeStrings(
          composerPlaceholder = "Message OpenCray",
          agentThinking = "Thinking",
          agentCancelled = "Cancelled",
          agentMissingLlm = "Missing LLM",
          agentEmptyAnswer = "The model returned an empty answer.",
          agentFailed = { detail -> "Failed: $detail" },
        ),
      )
    },
    requestedPort = 0,
    shutdownExecutorOnClose = true,
  )

  private fun request(
    server: OpenCrayLocalRuntimeServer,
    method: String,
    path: String,
    body: String? = null,
  ): HttpResponse {
    val connection = URL("http://127.0.0.1:${server.listeningPort}$path")
      .openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.setRequestProperty("Accept", "application/json")
    if (body != null) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { output ->
        output.write(body.toByteArray(Charsets.UTF_8))
      }
    }
    val statusCode = connection.responseCode
    val bodyText = (if (statusCode >= 400) {
      connection.errorStream
    } else {
      connection.inputStream
    })?.bufferedReader()?.use { reader ->
      reader.readText()
    }.orEmpty()
    connection.disconnect()
    return HttpResponse(statusCode = statusCode, body = bodyText)
  }

  private data class HttpResponse(
    val statusCode: Int,
    val body: String,
  )

  private object NoOpSettingsFacade : SettingsFacade {
    override fun loadOverview(): SettingsOverviewSnapshot = SettingsOverviewSnapshot(
      eyebrow = "",
      title = "",
      subtitle = "",
      deviceTitle = "",
      deviceSummary = "",
      entries = emptyList(),
    )

    override fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot = SettingsDetailSnapshot(
      routeId = routeId,
      title = "",
      subtitle = "",
      sections = emptyList(),
    )
  }

  private class RecordingLlmConfigFacade : LlmConfigFacade {
    var lastValidationRequest: ValidateLlmConfigRequest? = null

    override fun load(): LlmConfigSnapshot = EmptyLlmConfigFacade.load()

    override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
      throw UnsupportedOperationException("save is not used in this test")

    override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult {
      lastValidationRequest = request
      return LlmValidationResult(
        isSuccess = true,
        message = "Validation succeeded.",
      )
    }
  }

  private class NoOpRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = NoOpSessionHandle(sessionId)

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = {}

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class NoOpSessionHandle(
    override val sessionId: String,
  ) : AgentSessionHandle {
    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentTask = throw UnsupportedOperationException("submitPrompt is not used in this test")

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 0L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = false
  }
}
