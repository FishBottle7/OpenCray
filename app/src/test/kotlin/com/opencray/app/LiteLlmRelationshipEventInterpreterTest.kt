package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.soul.RelationshipEventConfidence
import com.opencray.runtime.soul.RelationshipEventInterpretation
import com.opencray.runtime.soul.RelationshipEventRequest
import com.opencray.runtime.soul.RelationshipEventScope
import com.opencray.runtime.soul.RelationshipEventType
import com.opencray.runtime.soul.RelationshipEventValence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmRelationshipEventInterpreterTest {
  @Test
  fun interpretReturnsStructuredRelationshipEventsFromJsonPayload() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"events":[
            {
              "event_type":"SUPPORTIVE_RESPONSE",
              "valence":"POSITIVE",
              "confidence":"MEDIUM",
              "scope":"USER",
              "summary":"Assistant responded supportively after the user expressed stress."
            },
            {
              "event_type":"WARM_REQUEST_WITHOUT_HISTORY",
              "valence":"MIXED",
              "confidence":"HIGH",
              "scope":"USER",
              "summary":"User requested more warmth without relational support in this turn."
            }
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmRelationshipEventInterpreter(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerId = "openai",
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          apiKey = "test-key",
          model = "gpt-4o-mini",
        )
      },
      providerClient = providerClient,
      clock = { 9_000L },
    )

    val result = interpreter.interpret(
      RelationshipEventRequest(
        sessionId = "session-1",
        workspaceId = "workspace-main",
        userInput = "我最近压力很大，以后你能不能对我更温柔一点。",
        assistantOutput = "我在，你不用一个人扛，我们一步一步来。",
        toolObservations = listOf("Checked the latest deployment log."),
      ),
    )

    val success = result as RelationshipEventInterpretation.Success
    assertEquals(2, success.events.size)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertEquals("Bearer test-key", providerClient.lastRequest?.request?.authHeaders?.get("Authorization"))
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("WARM_REQUEST_WITHOUT_HISTORY"))
    assertTrue(prompt.contains("Do not infer romance, deep affection, or strong intimacy from repeated verbal requests alone."))
    assertTrue(prompt.contains("Assistant reply:"))
    assertTrue(prompt.contains("Tool observations:"))

    val supportiveEvent = success.events.first { event ->
      event.eventType == RelationshipEventType.SUPPORTIVE_RESPONSE
    }
    val warmRequestEvent = success.events.first { event ->
      event.eventType == RelationshipEventType.WARM_REQUEST_WITHOUT_HISTORY
    }
    assertEquals(RelationshipEventValence.POSITIVE, supportiveEvent.valence)
    assertEquals(RelationshipEventConfidence.MEDIUM, supportiveEvent.confidence)
    assertEquals(RelationshipEventScope.USER, supportiveEvent.scope)
    assertEquals("session-1", supportiveEvent.sourceSessionId)
    assertEquals(9_000L, supportiveEvent.occurredAtEpochMs)
    assertEquals(RelationshipEventValence.MIXED, warmRequestEvent.valence)
    assertEquals(RelationshipEventConfidence.HIGH, warmRequestEvent.confidence)
  }

  @Test
  fun interpretReturnsUnavailableWhenModelResponseIsMalformed() {
    val interpreter = LiteLlmRelationshipEventInterpreter(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerId = "openai",
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          apiKey = "test-key",
          model = "gpt-4o-mini",
        )
      },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = "not json"),
      ),
    )

    val result = interpreter.interpret(
      RelationshipEventRequest(
        sessionId = "session-2",
        userInput = "以后对我温柔一点。",
      ),
    )

    assertTrue(result === RelationshipEventInterpretation.Unavailable)
  }

  @Test
  fun interpretReturnsUnavailableWhenNoLlmIsConfigured() {
    val interpreter = LiteLlmRelationshipEventInterpreter(
      llmSettingsProvider = { LlmSettingsState() },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = """{"events":[]}"""),
      ),
    )

    val result = interpreter.interpret(
      RelationshipEventRequest(
        sessionId = "session-3",
        userInput = "以后对我温柔一点。",
      ),
    )

    assertTrue(result === RelationshipEventInterpretation.Unavailable)
  }

  private class RecordingProviderClient(
    private val result: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    var lastRequest: LiteLlmProviderRequest? = null

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      lastRequest = request
      return result
    }
  }
}
