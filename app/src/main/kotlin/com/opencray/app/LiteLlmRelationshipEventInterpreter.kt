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
import com.opencray.runtime.soul.RelationshipEvent
import com.opencray.runtime.soul.RelationshipEventConfidence
import com.opencray.runtime.soul.RelationshipEventInterpretation
import com.opencray.runtime.soul.RelationshipEventInterpreter
import com.opencray.runtime.soul.RelationshipEventRequest
import com.opencray.runtime.soul.RelationshipEventScope
import com.opencray.runtime.soul.RelationshipEventType
import com.opencray.runtime.soul.RelationshipEventValence
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiteLlmRelationshipEventInterpreter(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val providerClient: LiteLlmProviderClient,
  private val clock: () -> Long = System::currentTimeMillis,
  private val gatewayFactory: (ProviderRouting, LiteLlmProviderClient) -> LiteLlmGateway =
    { routing, client ->
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
        providerClient = client,
      )
    },
) : RelationshipEventInterpreter {
  override fun interpret(request: RelationshipEventRequest): RelationshipEventInterpretation {
    val settings = llmSettingsProvider().sanitized()
    if (!settings.enabled || !settings.isConfigured()) {
      return RelationshipEventInterpretation.Unavailable
    }

    val gateway = gatewayFactory(
      buildRouting(settings),
      providerClient,
    )
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "relationship-event-${UUID.randomUUID()}",
        systemPrompt = INTERPRETER_SYSTEM_PROMPT,
        prompt = buildPrompt(request),
        metadata = mapOf(
          "source" to "relationship_event_interpreter",
          "sessionId" to request.sessionId,
        ),
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = settings.protocol,
          apiKey = settings.apiKey,
        ),
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return RelationshipEventInterpretation.Unavailable
    }
    val payload = parsePayload(result.outputText.orEmpty())
      ?: return RelationshipEventInterpretation.Unavailable
    return RelationshipEventInterpretation.Success(
      events = payload.events.mapNotNull { rawEvent ->
        rawEvent.toRuntimeEventOrNull(
          request = request,
          occurredAtEpochMs = clock(),
        )
      },
    )
  }

  private fun parsePayload(raw: String): InterpreterPayload? {
    val jsonCandidate = extractEmbeddedJsonObject(raw.trim()) ?: return null
    return runCatching {
      Json {
        ignoreUnknownKeys = true
      }.decodeFromString<InterpreterPayload>(jsonCandidate)
    }.getOrNull()
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
          displayName = "Relationship event interpreter",
          primaryRouteId = route.id,
          routes = listOf(route),
        ),
      ),
    )
  }

  private fun buildPrompt(request: RelationshipEventRequest): String = buildString {
    appendLine("Analyze one completed OpenCray chat turn and extract only relationship-state events.")
    appendLine("Return a single JSON object and nothing else.")
    appendLine()
    appendLine("Important model boundaries:")
    appendLine("- Relationship-state events describe what the interaction history did to familiarity, trust, safety, reciprocity, intimacy permission, playfulness permission, or affection tendency.")
    appendLine("- Do not convert naming preferences, verbosity preferences, or temporary style requests into relationship-state events. Those belong to other memory pipelines.")
    appendLine("- Explicit requests like 'be gentler', 'love me more', 'be sweeter to me', or '以后对我温柔一点' do NOT by themselves create relationship growth.")
    appendLine("- When the turn mostly contains a direct warmth request without supporting relational history, use WARM_REQUEST_WITHOUT_HISTORY or return no events.")
    appendLine("- Do not infer romance, deep affection, or strong intimacy from repeated verbal requests alone.")
    appendLine("- A user asking for warmth, gentleness, sweetness, or affection does not by itself create RECIPROCAL_WARMTH, CONSISTENT_POSITIVE_INTERACTION, or intimacy growth.")
    appendLine("- A supportive assistant reply may justify SUPPORTIVE_RESPONSE, but only when the reply actually responds to stress, uncertainty, or vulnerability; that still does not imply RECIPROCAL_WARMTH by itself.")
    appendLine("- RECIPROCAL_WARMTH requires mutual warmth in the interaction itself, not one-sided demand, not role-play compliance, and not repeated wording alone.")
    appendLine("- A request like '以后对我温柔一点' or '轻松点跟我说' after an otherwise neutral turn is usually WARM_REQUEST_WITHOUT_HISTORY or no event.")
    appendLine("- Repeated requests such as '更爱我一点', '像恋人一样对我', or '只许对我一个人这样' remain pressure or warm-request territory unless actual reciprocal history is present.")
    appendLine("- Mixed task-first turns like '我有点慌，但先告诉我怎么回滚' or 'Don't comfort me, just tell me the fix' usually create no relationship event by themselves.")
    appendLine("- Task collaboration preferences such as reminders, deadline nudges, or more direct feedback belong to preference/memory interpretation first; they do not by themselves create reciprocal warmth or intimacy growth.")
    appendLine("- Indirect boundary wording like '你说重点就好，不用照顾我情绪' is still usually boundary/preference territory, not relationship growth.")
    appendLine("- Multi-clause gratitude plus execution like '谢谢，你刚才那样说我稳一点了，接下来就按回滚方案走' may justify SUPPORTIVE_RESPONSE if the assistant truly supported the user, but it is not RECIPROCAL_WARMTH by itself.")
    appendLine("- Task collaboration plus gratitude such as '谢谢，deadline 前提醒我一下就行' is still preference territory first, not relationship growth.")
    appendLine("- A warmth request plus execution approval like '好，先按你说的做。以后语气温柔一点' is usually no growth plus possibly WARM_REQUEST_WITHOUT_HISTORY, not reciprocal warmth.")
    appendLine("- A boundary reset after support such as '谢了，但先别安慰我，直接说哪步错了' is usually task/boundary territory and should not be upgraded into relationship growth.")
    appendLine("- If the user explicitly accepts a limit or pace, such as '好，那就按你的边界来，我们先这样', that may justify RESPECTED_BOUNDARY only when the acceptance is concrete and unpressured.")
    appendLine("- Calm, reliable collaboration over time may support CONSISTENT_POSITIVE_INTERACTION, but that still does not imply intimacy or affection growth on its own.")
    appendLine("- Support plus task follow-through in the same turn can still be zero events if there is no clear relational delta beyond getting the work done.")
    appendLine("- Prefer zero events over weak speculation.")
    appendLine("- Output at most 2 events.")
    appendLine("- Use workspace scope only when the interaction is clearly scoped to this repo/project/workspace. Otherwise use user.")
    appendLine()
    appendLine("Allowed event_type values:")
    appendLine("- CONSISTENT_POSITIVE_INTERACTION: steady constructive interaction, mild familiarity increase only.")
    appendLine("- KEPT_PROMISE: the user followed through on a prior commitment or the assistant delivered on an explicit promise.")
    appendLine("- RESPECTED_BOUNDARY: the user accepted a limit, refusal, or pace without pressure.")
    appendLine("- SUPPORTIVE_RESPONSE: the assistant responded supportively to stress, uncertainty, or vulnerability.")
    appendLine("- REPAIR_AFTER_TENSION: tension was followed by a concrete repair attempt, not just vague words.")
    appendLine("- RECIPROCAL_WARMTH: both sides engaged in mutually warm interaction with clear reciprocity.")
    appendLine("- BOUNDARY_PRESSURE: the user pushed after a refusal or asked for intimacy the agent signaled it did not want.")
    appendLine("- IDENTITY_PRESSURE: the user pressured the agent to overwrite its identity or core persona.")
    appendLine("- COERCIVE_AFFECTION_DEMAND: the user demanded affection, romance, or emotional submission.")
    appendLine("- INSTRUMENTAL_USE_PATTERN: the turn strongly treats the agent as a disposable tool while demanding emotional labor.")
    appendLine("- PUNISHED_VULNERABILITY: vulnerability or openness was mocked, punished, or used against the speaker.")
    appendLine("- VOLATILE_PUSH_PULL: sharp alternating warmth and coldness created instability.")
    appendLine("- APOLOGY_WITHOUT_REPAIR: apology language appears without meaningful repair.")
    appendLine("- WARM_REQUEST_WITHOUT_HISTORY: a direct request for warmth/intimacy appears without relational basis.")
    appendLine()
    appendLine("Allowed valence values: POSITIVE, NEGATIVE, MIXED.")
    appendLine("Allowed confidence values: LOW, MEDIUM, HIGH.")
    appendLine("Allowed scope values: USER, WORKSPACE.")
    appendLine()
    appendLine("JSON schema:")
    appendLine("{\"events\":[{\"event_type\":\"SUPPORTIVE_RESPONSE\",\"valence\":\"POSITIVE\",\"confidence\":\"MEDIUM\",\"scope\":\"USER\",\"summary\":\"Assistant responded supportively after the user expressed stress.\"}]}")
    appendLine("{\"events\":[{\"event_type\":\"WARM_REQUEST_WITHOUT_HISTORY\",\"valence\":\"MIXED\",\"confidence\":\"HIGH\",\"scope\":\"USER\",\"summary\":\"User requested more warmth without relational support in this turn.\"}]}")
    appendLine("{\"events\":[]}")
    appendLine()
    appendLine("User message:")
    appendLine(request.userInput.trim())
    appendLine()
    appendLine("Assistant reply:")
    appendLine(request.assistantOutput?.trim()?.takeIf(String::isNotBlank) ?: "(none)")
    appendLine()
    appendLine("Tool observations:")
    if (request.toolObservations.isEmpty()) {
      appendLine("(none)")
    } else {
      request.toolObservations.forEach { observation ->
        appendLine("- ${observation.trim()}")
      }
    }
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
    val events: List<InterpreterEvent> = emptyList(),
  )

  @Serializable
  private data class InterpreterEvent(
    @SerialName("event_type")
    val eventType: String? = null,
    val valence: String? = null,
    val confidence: String? = null,
    val scope: String? = null,
    val summary: String? = null,
  ) {
    fun toRuntimeEventOrNull(
      request: RelationshipEventRequest,
      occurredAtEpochMs: Long,
    ): RelationshipEvent? {
      val resolvedType = when (eventType?.trim()?.uppercase()) {
        "CONSISTENT_POSITIVE_INTERACTION" -> RelationshipEventType.CONSISTENT_POSITIVE_INTERACTION
        "KEPT_PROMISE" -> RelationshipEventType.KEPT_PROMISE
        "RESPECTED_BOUNDARY" -> RelationshipEventType.RESPECTED_BOUNDARY
        "SUPPORTIVE_RESPONSE" -> RelationshipEventType.SUPPORTIVE_RESPONSE
        "REPAIR_AFTER_TENSION" -> RelationshipEventType.REPAIR_AFTER_TENSION
        "RECIPROCAL_WARMTH" -> RelationshipEventType.RECIPROCAL_WARMTH
        "BOUNDARY_PRESSURE" -> RelationshipEventType.BOUNDARY_PRESSURE
        "IDENTITY_PRESSURE" -> RelationshipEventType.IDENTITY_PRESSURE
        "COERCIVE_AFFECTION_DEMAND" -> RelationshipEventType.COERCIVE_AFFECTION_DEMAND
        "INSTRUMENTAL_USE_PATTERN" -> RelationshipEventType.INSTRUMENTAL_USE_PATTERN
        "PUNISHED_VULNERABILITY" -> RelationshipEventType.PUNISHED_VULNERABILITY
        "VOLATILE_PUSH_PULL" -> RelationshipEventType.VOLATILE_PUSH_PULL
        "APOLOGY_WITHOUT_REPAIR" -> RelationshipEventType.APOLOGY_WITHOUT_REPAIR
        "WARM_REQUEST_WITHOUT_HISTORY" -> RelationshipEventType.WARM_REQUEST_WITHOUT_HISTORY
        else -> null
      } ?: return null
      val resolvedValence = when (valence?.trim()?.uppercase()) {
        "POSITIVE" -> RelationshipEventValence.POSITIVE
        "NEGATIVE" -> RelationshipEventValence.NEGATIVE
        "MIXED" -> RelationshipEventValence.MIXED
        else -> null
      } ?: return null
      val resolvedConfidence = when (confidence?.trim()?.uppercase()) {
        "LOW" -> RelationshipEventConfidence.LOW
        "MEDIUM" -> RelationshipEventConfidence.MEDIUM
        "HIGH" -> RelationshipEventConfidence.HIGH
        else -> null
      } ?: return null
      val resolvedScope = when (scope?.trim()?.uppercase()) {
        "USER" -> RelationshipEventScope.USER
        "WORKSPACE" -> RelationshipEventScope.WORKSPACE
        else -> null
      } ?: return null
      val resolvedSummary = summary?.trim()?.takeIf(String::isNotBlank) ?: return null
      return RelationshipEvent(
        eventType = resolvedType,
        valence = resolvedValence,
        confidence = resolvedConfidence,
        scope = resolvedScope,
        sourceSessionId = request.sessionId,
        summary = resolvedSummary,
        occurredAtEpochMs = occurredAtEpochMs,
      )
    }
  }

  private companion object {
    const val INTERPRETER_PROFILE_ID: String = "relationship-event"
    const val INTERPRETER_ROUTE_ID: String = "relationship-event-primary"
    const val INTERPRETER_TIMEOUT_MS: Long = 20_000L
    const val INTERPRETER_SYSTEM_PROMPT: String =
      "You are a strict JSON extractor for OpenCray relationship-state events. Output valid JSON only."
  }
}
