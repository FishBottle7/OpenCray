package com.opencray.app

import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.soul.SoulTurnSemanticSignal
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpretation
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalRequest
import com.opencray.runtime.soul.SoulTurnUserAffect
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiteLlmSoulTurnSignalInterpreter(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val providerClient: LiteLlmProviderClient,
  private val gatewayFactory: (ProviderRouting, LiteLlmProviderClient) -> LiteLlmGateway =
    { routing, client ->
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
        providerClient = client,
      )
    },
) : SoulTurnSemanticSignalInterpreter {
  override fun interpret(
    request: SoulTurnSemanticSignalRequest,
  ): SoulTurnSemanticSignalInterpretation {
    val settings = llmSettingsProvider().sanitized()
    if (!settings.enabled || !settings.isConfigured()) {
      return SoulTurnSemanticSignalInterpretation.Unavailable(
        reason = "LLM settings are not configured for soul turn signal interpretation.",
      )
    }

    val gateway = gatewayFactory(
      buildRouting(settings),
      providerClient,
    )
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "soul-turn-signal-${UUID.randomUUID()}",
        systemPrompt = INTERPRETER_SYSTEM_PROMPT,
        prompt = buildPrompt(request),
        metadata = mapOf(
          "source" to "soul_turn_signal_interpreter",
          "sessionId" to request.sessionId,
          "taskId" to request.taskId,
        ),
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = settings.protocol,
          apiKey = settings.apiKey,
        ),
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return SoulTurnSemanticSignalInterpretation.Unavailable(
        reason = result.errorMessage ?: result.errorCode ?: result.status.name,
      )
    }
    val payload = parsePayload(result.outputText.orEmpty())
      ?: return SoulTurnSemanticSignalInterpretation.Unavailable(
        reason = "Interpreter returned unparsable output.",
      )
    return payload.toRuntimeSignalOrNull()
      ?.let(SoulTurnSemanticSignalInterpretation::Success)
      ?: SoulTurnSemanticSignalInterpretation.Unavailable(
        reason = "Interpreter returned an invalid soul turn signal payload.",
      )
  }

  private fun buildRouting(settings: LlmSettingsState): ProviderRouting {
    val route = ProviderRoute(
      id = INTERPRETER_ROUTE_ID,
      providerId = settings.providerId,
      baseUrl = settings.baseUrl,
      model = settings.model,
      timeoutMs = INTERPRETER_TIMEOUT_MS,
      metadata = LlmProviderProtocols.routeMetadata(
        protocol = settings.protocol,
        model = settings.model,
        reasoningEffort = settings.reasoningEffort,
      ),
    )
    return ProviderRouting(
      activeProfileId = INTERPRETER_PROFILE_ID,
      profiles = listOf(
        ModelProfile(
          id = INTERPRETER_PROFILE_ID,
          displayName = "Soul turn signal interpreter",
          primaryRouteId = route.id,
          routes = listOf(route),
        ),
      ),
    )
  }

  private fun buildPrompt(request: SoulTurnSemanticSignalRequest): String = buildString {
    appendLine("Analyze one active OpenCray user turn and classify only turn-time semantic needs.")
    appendLine("Return a single JSON object and nothing else.")
    appendLine()
    appendLine("Important boundaries:")
    appendLine("- This is semantic interpretation only. Do not decide response policy, relationship growth, or durable memory.")
    appendLine("- Do not use keyword matching shortcuts. Infer the meaning of the turn as a whole.")
    appendLine("- Prefer neutral/false when uncertain.")
    appendLine("- clarification_needed means the assistant likely still needs at least one missing fact before it can answer or act responsibly.")
    appendLine("- user_requests_relational_support is for turns asking for comfort, understanding, emotional support, or companionship.")
    appendLine("- user_invites_playfulness is only true when the live turn clearly invites a lighter or playful tone.")
    appendLine("- user_affect must be one of: neutral, strained, distressed, playful, warm.")
    appendLine("- Use distressed only for clear emotional overwhelm, pain, panic, or acute vulnerability.")
    appendLine("- Use strained for milder stress, frustration, pressure, or fatigue.")
    appendLine("- Use warm for clearly affectionate, trusting, or appreciative tone that is not mainly playful.")
    appendLine("- Use playful only when the live turn is actively joking, bantering, or inviting playful tone.")
    appendLine("- If the turn is mainly a practical request, is_task_bearing_request should usually be true even when emotion is present.")
    appendLine("- A practical request with frustration like '这个报错烦死了，快帮我看下' is usually task-bearing plus strained.")
    appendLine("- A support-seeking turn like '我有点撑不住了，你陪我缓一下' is usually not task-bearing, requests relational support, and is often distressed.")
    appendLine("- A warm appreciative turn like '谢谢你刚才那样说，我安心多了' is usually warm, not playful, and does not automatically require clarification.")
    appendLine("- A playful invitation like '别这么严肃啦，跟我贫两句' is usually playful with user_invites_playfulness=true.")
    appendLine("- A directness request like '不用安慰我，直接说哪里有问题' is usually task-bearing, not relational support, and often neutral or strained rather than warm.")
    appendLine("- A user can be emotionally strained yet still task-first, such as '我现在有点慌，但先告诉我下一步做什么'; this is usually task-bearing and does not automatically mean relational support is requested.")
    appendLine("- Mixed-intent turns matter. '我有点慌，但先告诉我怎么回滚' and 'I'm overwhelmed, but tell me the fastest fix first' are usually still task-bearing and not automatically support-seeking.")
    appendLine("- Longer paragraph turns can mix present emotion, temporary tone boundaries, and a concrete task. '我知道你想帮我，但我现在有点乱。先别安慰，先告诉我怎么止血，等回滚完你再提醒我复盘。' is usually still task-bearing, strained, and not automatically a relational-support request.")
    appendLine("- If the user says '先陪我两分钟，然后我们继续排查' or 'stay with me for a minute, then let's keep debugging', that can be both distressed and support-seeking even though a task follows later.")
    appendLine("- Indirect wording like '你不用照顾我情绪，抓重点就行' usually means task-first directness, not a request for relational support.")
    appendLine("- Use recent conversation to disambiguate short follow-ups like '那就这么做', '就按你说的来', or 'okay, do that'; if they inherit an active task, they are usually still task-bearing.")
    appendLine("- Resolve antecedents from recent conversation when the current turn says things like '那第二个吧', '按第二种来', 'go with option two', or 'use the earlier plan'. If the options were already established, that is usually still task-bearing and not automatically clarification_needed.")
    appendLine("- If recent conversation already established the task, a short follow-up is not automatically clarification_needed just because the current sentence is brief.")
    appendLine("- Recent context can also separate gratitude from support-seeking: after concrete help, '谢了，就按那个来' is usually not a new relational-support request.")
    appendLine("- After a supportive prior reply, a reset like '谢谢，但先别安慰我，直接说哪步错了' is usually task-bearing and not a fresh support-seeking turn.")
    appendLine("- A gratitude-plus-execution turn like '谢谢，你就按第二个方案改吧' is usually still task-bearing, not warm affect by default, and not a new request for relational support.")
    appendLine("- A style request like '以后对我温柔一点' is usually not a live support-seeking turn unless the message also asks for comfort or companionship right now.")
    appendLine("- A lighter-tone request like '轻松点聊，但别油' may invite playfulness, but it does not by itself mean warm affect, flirting, or relational support.")
    appendLine("- English phrasing should map the same way. 'Don't comfort me, just tell me what's wrong' is usually task-first and not support-seeking. 'Keep it light, not cheesy' may invite some playfulness without implying warmth or intimacy.")
    appendLine("- Another English mixed turn: 'I'm rattled, but skip the pep talk and walk me through rollback first; after that you can check on me' is usually task-bearing and strained, with the immediate priority still on the task.")
    appendLine("- Warm appreciation and support-seeking are different: '谢谢你刚才那样说，我安心多了' is warm, while '你先陪我一下' is support-seeking.")
    appendLine("- Polite or soft wording alone does not mean warm. Joking wording alone does not remove task-bearing status if the user is still asking for concrete help.")
    appendLine()
    appendLine("JSON schema:")
    appendLine("""{"is_task_bearing_request":true,"user_affect":"strained","user_invites_playfulness":false,"user_requests_relational_support":false,"clarification_needed":true}""")
    appendLine("""{"is_task_bearing_request":false,"user_affect":"playful","user_invites_playfulness":true,"user_requests_relational_support":false,"clarification_needed":false}""")
    appendLine("""{"is_task_bearing_request":false,"user_affect":"distressed","user_invites_playfulness":false,"user_requests_relational_support":true,"clarification_needed":false}""")
    appendLine("""{"is_task_bearing_request":false,"user_affect":"warm","user_invites_playfulness":false,"user_requests_relational_support":false,"clarification_needed":false}""")
    appendLine()
    appendLine("Current user message:")
    appendLine(request.userInput.trim())
    appendLine()
    appendLine("Recent conversation before the current user message:")
    val priorConversation = recentConversationBeforeCurrentTurn(request)
    if (priorConversation.isEmpty()) {
      appendLine("(none)")
    } else {
      priorConversation.forEach { message ->
        appendLine("- ${message.role.name.lowercase()}: ${message.content}")
      }
    }
  }

  private fun recentConversationBeforeCurrentTurn(
    request: SoulTurnSemanticSignalRequest,
  ): List<RuntimeConversationMessage> {
    val trimmedUserInput = request.userInput.trim()
    return request.conversation
      .dropLastWhile { message ->
        message.role == RuntimeConversationRole.USER &&
          message.content.trim() == trimmedUserInput
      }
      .takeLast(MAX_CONTEXT_MESSAGES)
      .map { message ->
        message.copy(content = message.content.trim().take(MAX_CONTEXT_CHARS))
      }
  }

  private fun parsePayload(raw: String): InterpreterPayload? {
    val jsonCandidate = extractEmbeddedJsonObject(raw.trim()) ?: return null
    return runCatching {
      Json {
        ignoreUnknownKeys = true
      }.decodeFromString<InterpreterPayload>(jsonCandidate)
    }.getOrNull()
  }

  private fun extractEmbeddedJsonObject(raw: String): String? {
    if (raw.startsWith("{") && raw.endsWith("}")) {
      return raw
    }
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
      when {
        inString && escaped -> escaped = false
        inString && character == '\\' -> escaped = true
        character == '"' -> inString = !inString
        !inString && character == '{' -> {
          if (depth == 0) {
            startIndex = index
          }
          depth += 1
        }

        !inString && character == '}' -> {
          depth -= 1
          if (depth == 0 && startIndex >= 0) {
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  @Serializable
  private data class InterpreterPayload(
    @SerialName("is_task_bearing_request")
    val isTaskBearingRequest: Boolean? = null,
    @SerialName("user_affect")
    val userAffect: String? = null,
    @SerialName("user_invites_playfulness")
    val userInvitesPlayfulness: Boolean? = null,
    @SerialName("user_requests_relational_support")
    val userRequestsRelationalSupport: Boolean? = null,
    @SerialName("clarification_needed")
    val clarificationNeeded: Boolean? = null,
  ) {
    fun toRuntimeSignalOrNull(): SoulTurnSemanticSignal? {
      val resolvedAffect = when (userAffect?.trim()?.lowercase()) {
        "neutral" -> SoulTurnUserAffect.NEUTRAL
        "strained" -> SoulTurnUserAffect.STRAINED
        "distressed" -> SoulTurnUserAffect.DISTRESSED
        "playful" -> SoulTurnUserAffect.PLAYFUL
        "warm" -> SoulTurnUserAffect.WARM
        else -> null
      } ?: return null
      return SoulTurnSemanticSignal(
        isTaskBearingRequest = isTaskBearingRequest ?: return null,
        userAffect = resolvedAffect,
        userInvitesPlayfulness = userInvitesPlayfulness ?: return null,
        userRequestsRelationalSupport = userRequestsRelationalSupport ?: return null,
        clarificationNeeded = clarificationNeeded ?: return null,
      )
    }
  }

  private companion object {
    const val INTERPRETER_PROFILE_ID: String = "soul-turn-signal"
    const val INTERPRETER_ROUTE_ID: String = "soul-turn-signal-primary"
    const val INTERPRETER_TIMEOUT_MS: Long = 20_000L
    const val MAX_CONTEXT_MESSAGES: Int = 6
    const val MAX_CONTEXT_CHARS: Int = 240
    const val INTERPRETER_SYSTEM_PROMPT: String =
      "You are a strict JSON semantic turn classifier for OpenCray. Output valid JSON only."
  }
}
