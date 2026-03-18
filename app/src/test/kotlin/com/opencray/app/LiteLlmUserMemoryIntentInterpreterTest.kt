package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmUserMemoryIntentInterpreterTest {
  @Test
  fun interpretReturnsStructuredGenericAndSoulMemoryIntentsFromJsonPayload() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"intents":[
            {
              "kind":"user_preference",
              "scope":"user",
              "content":"Default to Simplified Chinese for explanations"
            },
            {
              "kind":"durable_instruction",
              "scope":"workspace",
              "content":"Do not use git reset --hard in this repo"
            },
            {
              "kind":"user_preference",
              "scope":"user",
              "preference_key":"agent_display_name",
              "preference_value":"小白",
              "soul_extensions":{"soul_display_name":"小白"}
            },
            {
              "kind":"user_preference",
              "scope":"user",
              "preference_key":"interaction_preference_signal",
              "preference_value":"adaptive",
              "preference_extensions":{
                "interaction_preference_warmth_direction":"higher",
                "interaction_preference_formality_direction":"lower"
              }
            },
            {
              "kind":"user_preference",
              "scope":"user",
              "preference_key":"user_preferred_name",
              "preference_value":"阿澄",
              "soul_extensions":{"soul_preferred_naming":"阿澄"}
            },
            {
              "kind":"user_preference",
              "scope":"user",
              "preference_key":"user_address_style",
              "preference_value":"friendly",
              "soul_extensions":{"soul_preferred_address_style":"friendly"}
            }
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmUserMemoryIntentInterpreter(
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
      UserMemoryIntentRequest(
        sessionId = "session-1",
        workspaceId = "workspace-main",
        userInput = "以后解释都用简体中文，这个仓库不要用 git reset --hard，以后叫你小白。",
      ),
    )

    val success = result as UserMemoryIntentInterpretation.Success
    assertEquals(6, success.intents.size)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertEquals("Bearer test-key", providerClient.lastRequest?.request?.authHeaders?.get("Authorization"))
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("git reset --hard"))
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
    val preference = success.intents.first { intent -> intent.content?.contains("Simplified Chinese") == true }
    val instruction = success.intents.first { intent -> intent.kind == MemoryKind.DURABLE_INSTRUCTION }
    val displayName = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.AGENT_DISPLAY_NAME
    }
    val interactionPreferenceSignal = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL
    }
    val preferredName = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    val addressStyle = success.intents.first { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.USER_ADDRESS_STYLE
    }
    assertEquals(MemoryKind.USER_PREFERENCE, preference.kind)
    assertEquals(MemoryScope.USER, preference.scope)
    assertEquals(MemoryScope.WORKSPACE, instruction.scope)
    assertEquals("小白", displayName.soulExtensions[MemorySoulExtensionKeys.DISPLAY_NAME])
    assertEquals("higher", interactionPreferenceSignal.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION])
    assertEquals("lower", interactionPreferenceSignal.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION])
    assertEquals("阿澄", preferredName.soulExtensions[MemorySoulExtensionKeys.PREFERRED_NAMING])
    assertEquals("friendly", addressStyle.soulExtensions[MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE])
  }

  @Test
  fun interpretSuppressesKeywordFallbackWhenModelResponseIsMalformed() {
    val interpreter = LiteLlmUserMemoryIntentInterpreter(
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
      UserMemoryIntentRequest(
        sessionId = "session-2",
        userInput = "以后都用 PowerShell。",
      ),
    )

    val unavailable = result as UserMemoryIntentInterpretation.Unavailable
    assertEquals(false, unavailable.allowHeuristicFallback)
  }

  @Test
  fun interpretAllowsFallbackWhenNoLlmIsConfigured() {
    val interpreter = LiteLlmUserMemoryIntentInterpreter(
      llmSettingsProvider = { LlmSettingsState() },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = """{"intents":[]}"""),
      ),
    )

    val result = interpreter.interpret(
      UserMemoryIntentRequest(
        sessionId = "session-3",
        userInput = "以后都用 PowerShell。",
      ),
    )

    val unavailable = result as UserMemoryIntentInterpretation.Unavailable
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
