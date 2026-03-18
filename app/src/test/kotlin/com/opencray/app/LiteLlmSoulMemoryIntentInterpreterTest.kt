package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.SoulMemoryIntentInterpretation
import com.opencray.runtime.memory.SoulMemoryIntentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            },
            {
              "preference_key":"interaction_preference_signal",
              "preference_value":"adaptive",
              "scope":"user",
              "preference_extensions":{
                "interaction_preference_warmth_direction":"higher",
                "interaction_preference_formality_direction":"lower"
              }
            },
            {
              "preference_key":"user_preferred_name",
              "preference_value":"阿澄",
              "scope":"user",
              "soul_extensions":{"soul_preferred_naming":"阿澄"}
            },
            {
              "preference_key":"user_address_style",
              "preference_value":"friendly",
              "scope":"user",
              "soul_extensions":{"soul_preferred_address_style":"friendly"}
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
        userInput = "以后叫你小白，以后对我温柔一点，这次温柔一点。",
      ),
    )

    val success = result as SoulMemoryIntentInterpretation.Success
    assertEquals(5, success.intents.size)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertEquals("Bearer test-key", providerClient.lastRequest?.request?.authHeaders?.get("Authorization"))
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("以后叫你小白"))
    assertTrue(prompt.contains("interaction_preference_signal is the preferred key for durable adaptive relationship drift"))
    assertTrue(prompt.contains("relationship_style_profile is a legacy compatibility key"))
    assertTrue(prompt.contains("agent_style_profile is only for current-run acting mode"))
    assertTrue(prompt.contains("agent_verbosity always uses session scope"))
    assertTrue(prompt.contains("user_preferred_name stores how the agent should address the user"))
    assertTrue(prompt.contains("user_address_style stores the desired user-addressing closeness"))
    assertTrue(prompt.contains("preference_extensions may only be used with interaction_preference_signal or the legacy relationship_style_profile key"))
    assertTrue(prompt.contains("Prefer interaction_preference_signal for durable adaptive-style drift"))
    assertTrue(prompt.contains(MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION))
    assertTrue(prompt.contains("Never output soul_risk_tolerance or soul_tool_use_bias"))
    assertFalse(
      prompt.contains(
        "soul_display_name, soul_voice, soul_tone, soul_verbosity, soul_user_relationship_style, soul_risk_tolerance, soul_tool_use_bias",
      ),
    )
    val displayNameIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.AGENT_DISPLAY_NAME
    }
    val styleIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.AGENT_STYLE_PROFILE
    }
    val interactionPreferenceSignalIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL
    }
    val preferredNameIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    val addressStyleIntent = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.USER_ADDRESS_STYLE
    }
    assertEquals(MemoryScope.USER, displayNameIntent.scope)
    assertEquals("小白", displayNameIntent.soulExtensions[MemorySoulExtensionKeys.DISPLAY_NAME])
    assertEquals(MemoryScope.SESSION, styleIntent.scope)
    assertEquals("warm", styleIntent.soulExtensions[MemorySoulExtensionKeys.TONE])
    assertEquals(MemoryScope.USER, interactionPreferenceSignalIntent.scope)
    assertEquals("higher", interactionPreferenceSignalIntent.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION])
    assertEquals("lower", interactionPreferenceSignalIntent.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION])
    assertEquals("阿澄", preferredNameIntent.soulExtensions[MemorySoulExtensionKeys.PREFERRED_NAMING])
    assertEquals("friendly", addressStyleIntent.soulExtensions[MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE])
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
