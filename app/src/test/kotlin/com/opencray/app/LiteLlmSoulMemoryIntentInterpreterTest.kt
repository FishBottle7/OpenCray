package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.SoulMemoryIntentInterpretation
import com.opencray.runtime.memory.SoulMemoryIntentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmSoulMemoryIntentInterpreterTest {
  @Test
  fun interpretReturnsStructuredSoulIntentsFromJsonPayload() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"intents":[
            {
              "preference_key":"agent_display_name",
              "preference_value":"小白",
              "scope":"user",
              "soul_extensions":{"soul_display_name":"小白"}
            },
            {
              "preference_key":"agent_style_profile",
              "preference_value":"warm",
              "scope":"session",
              "soul_extensions":{
                "soul_tone":"warm",
                "soul_voice":"warm and gentle",
                "soul_user_relationship_style":"supportive"
              }
            }
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmSoulMemoryIntentInterpreter(
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
    )

    val result = interpreter.interpret(
      SoulMemoryIntentRequest(
        sessionId = "session-1",
        workspaceId = "workspace-main",
        userInput = "以后叫你小白，这次温柔一点。",
      ),
    )

    val success = result as SoulMemoryIntentInterpretation.Success
    assertEquals(2, success.intents.size)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertEquals("Bearer test-key", providerClient.lastRequest?.request?.authHeaders?.get("Authorization"))
    assertTrue(providerClient.lastRequest?.request?.prompt.orEmpty().contains("以后叫你小白"))
    val displayNameIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.AGENT_DISPLAY_NAME
    }
    val styleIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.AGENT_STYLE_PROFILE
    }
    assertEquals(MemoryScope.USER, displayNameIntent.scope)
    assertEquals("小白", displayNameIntent.soulExtensions[MemorySoulExtensionKeys.DISPLAY_NAME])
    assertEquals(MemoryScope.SESSION, styleIntent.scope)
    assertEquals("warm", styleIntent.soulExtensions[MemorySoulExtensionKeys.TONE])
  }

  @Test
  fun interpretSuppressesKeywordFallbackWhenModelResponseIsMalformed() {
    val interpreter = LiteLlmSoulMemoryIntentInterpreter(
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
        result = LiteLlmProviderResult.Success(
          outputText = "not json",
        ),
      ),
    )

    val result = interpreter.interpret(
      SoulMemoryIntentRequest(
        sessionId = "session-2",
        userInput = "以后叫你小白。",
      ),
    )

    val unavailable = result as SoulMemoryIntentInterpretation.Unavailable
    assertEquals(false, unavailable.allowHeuristicFallback)
  }

  @Test
  fun interpretAllowsFallbackWhenNoLlmIsConfigured() {
    val interpreter = LiteLlmSoulMemoryIntentInterpreter(
      llmSettingsProvider = { LlmSettingsState() },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = """{"intents":[]}"""),
      ),
    )

    val result = interpreter.interpret(
      SoulMemoryIntentRequest(
        sessionId = "session-3",
        userInput = "以后叫你小白。",
      ),
    )

    val unavailable = result as SoulMemoryIntentInterpretation.Unavailable
    assertEquals(true, unavailable.allowHeuristicFallback)
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
