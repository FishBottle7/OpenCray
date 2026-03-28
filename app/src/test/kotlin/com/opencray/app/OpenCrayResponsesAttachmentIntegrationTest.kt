package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.FallbackTriggerPolicy
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayResponsesAttachmentIntegrationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun responsesRouteReplaysFullTranscriptBeforeReturningWorkspaceImageAttachment() {
    val workspaceRoot = temporaryFolder.newFolder("responses-attachment-integration").toPath()
    val firstRequestBody = AtomicReference<String>()
    val secondRequestBody = AtomicReference<String>()
    val requestsServed = CountDownLatch(2)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(
            client = client,
            requestBody = firstRequestBody,
          )
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "resp_attach_1",
                "status": "completed",
                "output": [
                  {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "Write",
                    "arguments": "{\"file_path\":\"outputs/diagram.png\",\"content\":\"png-placeholder\"}"
                  }
                ]
              }
            """.trimIndent(),
          )
          requestsServed.countDown()
        }
        listeningSocket.accept().use { client ->
          readHttpRequest(
            client = client,
            requestBody = secondRequestBody,
          )
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "resp_attach_2",
                "status": "completed",
                "output": [
                  {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                      {
                        "type": "output_text",
                        "text": "{\"type\":\"final\",\"answer\":\"\",\"attachments\":[{\"relative_path\":\"outputs/diagram.png\",\"kind\":\"image\"}]}"
                      }
                    ]
                  }
                ]
              }
            """.trimIndent(),
          )
          requestsServed.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val route = ProviderRoute(
        id = "route-openai-responses",
        providerId = "openai",
        baseUrl = "http://127.0.0.1:${server.localPort}/v1",
        model = "gpt-5-mini",
        timeoutMs = 5_000L,
        metadata = mapOf(
          "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
          "responsesContinuationSupported" to "true",
        ),
      )
      val gateway = DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(
          ProviderRouting(
            activeProfileId = "profile-openai-responses",
            profiles = listOf(
              ModelProfile(
                id = "profile-openai-responses",
                displayName = "OpenAI Responses",
                primaryRouteId = route.id,
                routes = listOf(route),
                fallbackPolicy = FallbackTriggerPolicy(),
              ),
            ),
          ),
        ),
        providerClient = OpenAiCompatibleLiteLlmProviderClient(),
        clock = IncrementingClock(start = 70_000L)::next,
      )
      val runtime = OpenCrayAgentRuntime(
        gateway = gateway,
        toolDispatcher = OpenCrayToolDispatcher(
          OpenCrayToolDispatcherConfig(
            workspaceRoots = setOf(workspaceRoot),
          ),
        ),
        config = OpenCrayAgentRuntimeConfig(
          maxTurns = 4,
          maxToolCalls = 2,
          llmMetadata = mapOf(
            "protocol" to "openai_responses",
            "responseApiPreferred" to "true",
            "nativeToolCallingAvailable" to "true",
            "responsesContinuationSupported" to "true",
            "nativeWebSearchEnabled" to "false",
          ),
        ),
        clock = IncrementingClock(start = 70_500L)::next,
      )

      val result = runtime.execute(
        task = promptTask(input = "Create a diagram and send it back as an image attachment."),
        hooks = runtimeHooks(),
      )

      assertTrue(requestsServed.await(5, TimeUnit.SECONDS))
      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("", result.stdout)

      val firstPayload = JSONObject(firstRequestBody.get())
      val secondPayload = JSONObject(secondRequestBody.get())
      assertFalse(firstPayload.has("previous_response_id"))
      assertFalse(secondPayload.has("previous_response_id"))

      val secondInput = secondPayload.getJSONArray("input")
      assertTrue(
        secondInput.anyObject { item ->
          item.getString("type") == "function_call" &&
            item.getString("call_id") == "call_1" &&
            item.getString("name") == "Write"
        },
      )
      assertTrue(
        secondInput.anyObject { item ->
          item.getString("type") == "function_call_output" &&
            item.getString("call_id") == "call_1"
        },
      )

      val attachments = Json.decodeFromString(
        ListSerializer(OpenCrayFinalAttachment.serializer()),
        result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty(),
      )
      assertEquals(1, attachments.size)
      assertEquals("outputs/diagram.png", attachments.single().relativePath)
      assertEquals("image", attachments.single().kind)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun promptTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_TEST",
    ),
    metadata = metadata,
    createdAtEpochMs = 500L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in integration test.") },
    requestSuspend = { _: SuspensionRequest -> error("Suspend not expected in integration test.") },
  )

  private fun readHttpRequest(
    client: Socket,
    requestBody: AtomicReference<String>,
  ) {
    val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
    reader.readLine()
    var contentLength = 0
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) {
        break
      }
      val separatorIndex = line.indexOf(':')
      if (separatorIndex <= 0) {
        continue
      }
      val headerName = line.substring(0, separatorIndex).trim()
      val headerValue = line.substring(separatorIndex + 1).trim()
      if (headerName.equals("Content-Length", ignoreCase = true)) {
        contentLength = headerValue.toIntOrNull() ?: 0
      }
    }
    if (contentLength <= 0) {
      requestBody.set("")
      return
    }
    val buffer = CharArray(contentLength)
    var totalRead = 0
    while (totalRead < contentLength) {
      val read = reader.read(buffer, totalRead, contentLength - totalRead)
      if (read <= 0) {
        break
      }
      totalRead += read
    }
    requestBody.set(String(buffer, 0, totalRead))
  }

  private fun writeHttpResponse(
    client: Socket,
    body: String,
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: application/json\r\n")
      append("Content-Length: ${bodyBytes.size}\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    client.getOutputStream().use { output ->
      output.write(header)
      output.write(bodyBytes)
      output.flush()
    }
  }

  private fun JSONArray.anyObject(
    predicate: (JSONObject) -> Boolean,
  ): Boolean {
    for (index in 0 until length()) {
      val candidate = optJSONObject(index) ?: continue
      if (predicate(candidate)) {
        return true
      }
    }
    return false
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var current: Long = start

    fun next(): Long = current++
  }
}
