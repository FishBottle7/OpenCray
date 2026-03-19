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
                "interaction_preference_formality_direction":"lower",
                "interaction_preference_playfulness_direction":"higher",
                "interaction_preference_reassurance_direction":"lower"
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
    assertTrue(prompt.contains("interaction_preference_signal is the only durable adaptive relationship-drift key"))
    assertTrue(prompt.contains("do not use agent_style_profile"))
    assertTrue(prompt.contains("agent_style_profile is only for current-run acting mode"))
    assertTrue(prompt.contains("agent_verbosity always uses session scope"))
    assertTrue(prompt.contains("user_preferred_name stores how the agent should address the user"))
    assertTrue(prompt.contains("user_address_style stores the desired user-addressing closeness"))
    assertTrue(prompt.contains("preference_extensions may only be used with interaction_preference_signal"))
    assertTrue(prompt.contains("When using interaction_preference_signal, set preference_value to a short placeholder like adaptive"))
    assertTrue(prompt.contains(MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION))
    assertTrue(prompt.contains(MemoryInteractionPreferenceExtensionKeys.PLAYFULNESS_DIRECTION))
    assertTrue(prompt.contains(MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION))
    assertTrue(prompt.contains("别那么冷冰冰"))
    assertTrue(prompt.contains("可以皮一点/偶尔开点玩笑"))
    assertTrue(prompt.contains("别哄我/别总安慰我"))
    assertTrue(prompt.contains("爱我"))
    assertTrue(prompt.contains("这次温柔一点"))
    assertTrue(prompt.contains("Never output soul_risk_tolerance or soul_tool_use_bias"))
    assertFalse(prompt.contains("relationship_" + "style_profile"))
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
    assertEquals("higher", interactionPreferenceSignalIntent.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.PLAYFULNESS_DIRECTION])
    assertEquals("lower", interactionPreferenceSignalIntent.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION])
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
  fun interpretFailsClosedWhenNoLlmIsConfigured() {
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
    assertEquals(false, unavailable.allowHeuristicFallback)
  }

  @Test
  fun promptDocumentsSoulAdaptiveCoverageWithRealWorldPhrasing() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(outputText = """{"intents":[]}"""),
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

    interpreter.interpret(
      SoulMemoryIntentRequest(
        sessionId = "session-4",
        userInput = "以后别那么一本正经，可以主动一点，但先别逗我。",
      ),
    )

    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("Map real phrasing semantically, not literally."))
    assertTrue(prompt.contains("你可以主动一点提醒我"))
    assertTrue(prompt.contains("少一点主动寒暄"))
    assertTrue(prompt.contains("别太哄我/不用安慰我，直接说"))
    assertTrue(prompt.contains("轻松点但别油"))
    assertTrue(prompt.contains("你主动提醒我截止时间/进度"))
    assertTrue(prompt.contains("别没事就来问候/别太主动寒暄"))
    assertTrue(prompt.contains("以后指出问题可以直接一点，但 deadline 还是主动提醒我"))
    assertTrue(prompt.contains("到节点了记得戳我一下，但平时别老寒暄"))
    assertTrue(prompt.contains("这次直接一点，以后还是温柔一点"))
    assertTrue(prompt.contains("平时不用哄我，但今天先陪我一下"))
    assertTrue(prompt.contains("以后叫我阿澄，项目里指出问题直接一点，平时别没事寒暄；但今天我有点乱，先别哄我，带我把回滚做完。"))
    assertTrue(prompt.contains("这个仓库里你直接讲风险点就行，平时还是温和一点；如果是 deadline 快到了你主动提醒我，但我现在先需要你陪我把事故止住。"))
    assertTrue(prompt.contains("你不用照顾我情绪，抓重点就行"))
    assertTrue(prompt.contains("到时间记得 ping 我一下"))
    assertTrue(prompt.contains("keep it light, not cheesy"))
    assertTrue(prompt.contains("Call me A-Cheng, be direct with code issues in this repo, but today skip the pep talk and walk me through rollback first"))
    assertTrue(prompt.contains("please nudge me about deadlines"))
    assertTrue(prompt.contains("\"interaction_preference_initiative_direction\":\"higher\""))
    assertTrue(prompt.contains("Support-seeking wording like '陪我一下' or '你先安慰安慰我' is not a durable preference by itself"))
    assertTrue(prompt.contains("Directness requests like '不用安慰我，直接说哪里有问题' do not mean the user wants colder treatment overall."))
    assertTrue(prompt.contains("Requests for affection, romance, emotional submission, or identity overwrite"))
    assertTrue(prompt.contains("\"interaction_preference_playfulness_direction\":\"higher\""))
    assertTrue(prompt.contains("\"interaction_preference_reassurance_direction\":\"higher\""))
    assertTrue(prompt.contains("\"scope\":\"session\""))
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
