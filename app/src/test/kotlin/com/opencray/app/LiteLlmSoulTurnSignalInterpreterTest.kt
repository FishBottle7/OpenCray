package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpretation
import com.opencray.runtime.soul.SoulTurnSemanticSignalRequest
import com.opencray.runtime.soul.SoulTurnUserAffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmSoulTurnSignalInterpreterTest {
  @Test
  fun interpretReturnsStructuredSignalFromJsonPayload() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {
            "is_task_bearing_request": true,
            "user_affect": "strained",
            "user_invites_playfulness": false,
            "user_requests_relational_support": false,
            "clarification_needed": true
          }
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmSoulTurnSignalInterpreter(
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
      SoulTurnSemanticSignalRequest(
        sessionId = "session-1",
        taskId = "task-1",
        userInput = "这个接口报错了，帮我看看还缺什么信息。",
        conversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "上次你让我要日志。"),
          RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "可以，把报错和调用方式贴出来。"),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "这个接口报错了，帮我看看还缺什么信息。"),
        ),
      ),
    )

    val success = result as SoulTurnSemanticSignalInterpretation.Success
    assertEquals(true, success.signal.isTaskBearingRequest)
    assertEquals(SoulTurnUserAffect.STRAINED, success.signal.userAffect)
    assertEquals(true, success.signal.clarificationNeeded)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertEquals("Bearer test-key", providerClient.lastRequest?.request?.authHeaders?.get("Authorization"))
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("clarification_needed means the assistant likely still needs at least one missing fact"))
    assertTrue(prompt.contains("Current user message:"))
    assertTrue(prompt.contains("Recent conversation before the current user message:"))
    assertTrue(prompt.contains("这个报错烦死了，快帮我看下"))
    assertTrue(prompt.contains("我有点撑不住了，你陪我缓一下"))
    assertTrue(prompt.contains("谢谢你刚才那样说，我安心多了"))
    assertTrue(prompt.contains("别这么严肃啦，跟我贫两句"))
  }

  @Test
  fun interpretReturnsUnavailableWhenModelResponseIsMalformed() {
    val interpreter = LiteLlmSoulTurnSignalInterpreter(
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
      SoulTurnSemanticSignalRequest(
        sessionId = "session-2",
        taskId = "task-2",
        userInput = "继续。",
      ),
    )

    assertTrue(result is SoulTurnSemanticSignalInterpretation.Unavailable)
  }

  @Test
  fun interpretReturnsUnavailableWhenOnDeviceModeIsSelected() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(outputText = "{}"),
    )
    val interpreter = LiteLlmSoulTurnSignalInterpreter(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          selectedOnDeviceModelId = "gemma-4-e2b-it",
        )
      },
      providerClient = providerClient,
    )

    val result = interpreter.interpret(
      SoulTurnSemanticSignalRequest(
        sessionId = "session-on-device",
        taskId = "task-on-device",
        userInput = "继续。",
      ),
    )

    val unavailable = result as SoulTurnSemanticSignalInterpretation.Unavailable
    assertTrue(unavailable.reason.orEmpty().contains("On-device LLM mode"))
    assertTrue(providerClient.lastRequest == null)
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

  @Test
  fun promptDocumentsAffectBoundaryCoverage() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {
            "is_task_bearing_request": false,
            "user_affect": "warm",
            "user_invites_playfulness": false,
            "user_requests_relational_support": false,
            "clarification_needed": false
          }
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmSoulTurnSignalInterpreter(
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
      SoulTurnSemanticSignalRequest(
        sessionId = "session-3",
        taskId = "task-3",
        userInput = "谢谢你刚才那样说，我安心多了。",
      ),
    )

    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("Polite or soft wording alone does not mean warm."))
    assertTrue(prompt.contains("Joking wording alone does not remove task-bearing status"))
    assertTrue(prompt.contains("不用安慰我，直接说哪里有问题"))
    assertTrue(prompt.contains("我现在有点慌，但先告诉我下一步做什么"))
    assertTrue(prompt.contains("我有点慌，但先告诉我怎么回滚"))
    assertTrue(prompt.contains("I'm overwhelmed, but tell me the fastest fix first"))
    assertTrue(prompt.contains("我知道你想帮我，但我现在有点乱。先别安慰，先告诉我怎么止血，等回滚完你再提醒我复盘。"))
    assertTrue(prompt.contains("先陪我两分钟，然后我们继续排查"))
    assertTrue(prompt.contains("stay with me for a minute, then let's keep debugging"))
    assertTrue(prompt.contains("你不用照顾我情绪，抓重点就行"))
    assertTrue(prompt.contains("那就这么做"))
    assertTrue(prompt.contains("就按你说的来"))
    assertTrue(prompt.contains("okay, do that"))
    assertTrue(prompt.contains("那第二个吧"))
    assertTrue(prompt.contains("按第二种来"))
    assertTrue(prompt.contains("go with option two"))
    assertTrue(prompt.contains("谢了，就按那个来"))
    assertTrue(prompt.contains("谢谢，但先别安慰我，直接说哪步错了"))
    assertTrue(prompt.contains("谢谢，你就按第二个方案改吧"))
    assertTrue(prompt.contains("以后对我温柔一点"))
    assertTrue(prompt.contains("轻松点聊，但别油"))
    assertTrue(prompt.contains("Don't comfort me, just tell me what's wrong"))
    assertTrue(prompt.contains("Keep it light, not cheesy"))
    assertTrue(prompt.contains("I'm rattled, but skip the pep talk and walk me through rollback first; after that you can check on me"))
    assertTrue(prompt.contains("你先陪我一下"))
    assertTrue(prompt.contains("\"user_affect\":\"distressed\""))
    assertTrue(prompt.contains("\"user_affect\":\"warm\""))
  }

  @Test
  fun promptUsesRecentConversationToDisambiguateShortFollowUpAndBoundsWindow() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {
            "is_task_bearing_request": true,
            "user_affect": "neutral",
            "user_invites_playfulness": false,
            "user_requests_relational_support": false,
            "clarification_needed": false
          }
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmSoulTurnSignalInterpreter(
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
      SoulTurnSemanticSignalRequest(
        sessionId = "session-4",
        taskId = "task-4",
        userInput = "就按你说的来",
        conversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "最开始那条老消息"),
          RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "最开始那条旧回复"),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "先帮我确认回滚步骤"),
          RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "先确认版本和数据库变更。"),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "数据库没有迁移。"),
          RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "那可以先回滚应用版本。"),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "要不要先看日志？"),
          RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "可以，但不阻塞先回滚。"),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "就按你说的来"),
        ),
      ),
    )

    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("- user: 先帮我确认回滚步骤"))
    assertTrue(prompt.contains("- assistant: 可以，但不阻塞先回滚。"))
    assertTrue(prompt.contains("- assistant: 那可以先回滚应用版本。"))
    assertTrue(prompt.contains("Current user message:\n就按你说的来"))
    assertTrue(!prompt.contains("- user: 就按你说的来"))
    assertTrue(!prompt.contains("最开始那条老消息"))
    assertTrue(!prompt.contains("最开始那条旧回复"))
  }
}
