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
                "interaction_preference_formality_direction":"lower",
                "interaction_preference_playfulness_direction":"higher",
                "interaction_preference_reassurance_direction":"lower"
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
    assertEquals("low", providerClient.lastRequest?.route?.metadata?.get("reasoning_effort"))
    assertEquals("512", providerClient.lastRequest?.route?.metadata?.get("max_tokens"))
    assertEquals("0", providerClient.lastRequest?.route?.metadata?.get("temperature"))
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("git reset --hard"))
    assertTrue(prompt.contains("A single message may yield multiple durable intents"))
    assertTrue(prompt.contains("If the user tells the agent how to address them"))
    assertTrue(prompt.contains("If one message contains both a durable workspace rule and a durable workspace fact"))
    assertTrue(prompt.contains("Example mapping: '以后叫我阿澄。'"))
    assertTrue(prompt.contains("Example mapping: '以后不要再叫我阿澄了。' -> {\"intents\":[]}"))
    assertTrue(prompt.contains("Example mapping: '别再叫我阿澄了，以后叫我阿青。'"))
    assertTrue(prompt.contains("Example mapping: '以后这个项目不要用 git reset --hard，而且这个项目使用 Gradle wrapper。'"))
    assertTrue(prompt.contains("\"preference_key\":\"user_preferred_name\""))
    assertTrue(prompt.contains("This project uses Gradle wrapper"))
    assertTrue(prompt.contains("interaction_preference_signal is the only durable adaptive relationship-drift key"))
    assertTrue(prompt.contains("do not use agent_style_profile"))
    assertTrue(prompt.contains("agent_style_profile is only for current-run acting mode"))
    assertTrue(prompt.contains("agent_verbosity always uses session scope"))
    assertTrue(prompt.contains("user_preferred_name stores how the agent should address the user"))
    assertTrue(prompt.contains("user_address_style stores the desired user-addressing closeness"))
    assertTrue(prompt.contains("A durable naming request like '以后叫我阿澄。', '以后称呼我阿澄。', or '今后直接叫我 A-Cheng。' should emit exactly one user_preference intent"))
    assertTrue(prompt.contains("Only emit user_preferred_name or user_address_style when the user gives a concrete preferred value to use."))
    assertTrue(prompt.contains("If the user only invalidates an existing naming or addressing preference without naming a replacement"))
    assertTrue(prompt.contains("Do not turn pure negations like '以后不要再叫我阿澄了。' into generic durable memories"))
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
    assertEquals("higher", interactionPreferenceSignal.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.PLAYFULNESS_DIRECTION])
    assertEquals("lower", interactionPreferenceSignal.preferenceExtensions[MemoryInteractionPreferenceExtensionKeys.REASSURANCE_DIRECTION])
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
  fun interpretFailsClosedWhenNoLlmIsConfigured() {
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
    assertEquals(false, unavailable.allowHeuristicFallback)
  }

  @Test
  fun promptDocumentsAdaptivePreferenceCoverageWithRealWorldPhrasing() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(outputText = """{"intents":[]}"""),
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

    interpreter.interpret(
      UserMemoryIntentRequest(
        sessionId = "session-4",
        userInput = "以后别那么冷冰冰，可以皮一点，但别老哄我。",
      ),
    )

    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("Map real phrasing semantically, not literally."))
    assertTrue(prompt.contains("Example mapping: '以后叫我阿澄。'"))
    assertTrue(prompt.contains("This project uses Gradle wrapper"))
    assertTrue(prompt.contains("别那么一本正经"))
    assertTrue(prompt.contains("少一点主动寒暄"))
    assertTrue(prompt.contains("别老逗我/别贫了"))
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

  @Test
  fun promptTreatsPreferredNameInvalidationWithoutReplacementAsRecordOnlyMaintenance() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(outputText = """{"intents":[]}"""),
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
        sessionId = "session-5",
        userInput = "以后不要再叫我阿澄了。",
      ),
    )

    val success = result as UserMemoryIntentInterpretation.Success
    assertTrue(success.intents.isEmpty())
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("Only emit user_preferred_name or user_address_style when the user gives a concrete preferred value to use."))
    assertTrue(prompt.contains("A durable naming request like '以后叫我阿澄。', '以后称呼我阿澄。', or '今后直接叫我 A-Cheng。' should emit exactly one user_preference intent"))
    assertTrue(prompt.contains("If the user only invalidates an existing naming or addressing preference without naming a replacement"))
    assertTrue(prompt.contains("Do not turn pure negations like '以后不要再叫我阿澄了。' into generic durable memories such as 'Do not call the user 阿澄'."))
    assertTrue(prompt.contains("Example mapping: '以后不要再叫我阿澄了。' -> {\"intents\":[]}"))
    assertTrue(prompt.contains("Example mapping: '别再叫我阿澄了，以后叫我阿青。'"))
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
