package com.opencray.app

import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.runtime.memory.MemoryStewardshipAction
import com.opencray.runtime.memory.MemoryStewardshipDecision
import com.opencray.runtime.memory.MemoryStewardshipInterpretation
import com.opencray.runtime.memory.MemoryStewardshipInterpreter
import com.opencray.runtime.memory.MemoryStewardshipRequest
import com.opencray.runtime.memory.MemoryStewardshipResolutionReason
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiteLlmMemoryStewardshipInterpreter(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val providerClient: LiteLlmProviderClient,
  private val gatewayFactory: (ProviderRouting, LiteLlmProviderClient) -> LiteLlmGateway =
    { routing, client ->
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
        providerClient = client,
      )
    },
) : MemoryStewardshipInterpreter {
  override fun interpret(
    request: MemoryStewardshipRequest,
  ): MemoryStewardshipInterpretation {
    if (request.proposedCandidates.isEmpty() && request.activeRecords.isEmpty()) {
      return MemoryStewardshipInterpretation.Success(decisions = emptyList())
    }
    val settings = llmSettingsProvider().sanitized()
    if (!settings.enabled || !settings.isConfigured()) {
      return MemoryStewardshipInterpretation.Unavailable(
        reason = "LLM settings are not configured for memory stewardship.",
      )
    }

    val gateway = gatewayFactory(
      buildRouting(settings),
      providerClient,
    )
    val prompt = buildPrompt(request)
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "memory-stewardship-${UUID.randomUUID()}",
        systemPrompt = INTERPRETER_SYSTEM_PROMPT,
        messages = listOf(
          LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.USER,
            content = prompt,
          ),
        ),
        metadata = mapOf(
          "source" to "memory_stewardship_interpreter",
          "sessionId" to request.sessionId,
        ),
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = settings.protocol,
          apiKey = settings.apiKey,
        ),
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return MemoryStewardshipInterpretation.Unavailable(
        reason = result.errorMessage ?: result.errorCode ?: result.status.name,
      )
    }
    val payload = parsePayload(result.outputText.orEmpty())
      ?: return MemoryStewardshipInterpretation.Unavailable(
        reason = "Interpreter returned unparsable output.",
      )
    return MemoryStewardshipInterpretation.Success(
      decisions = payload.decisions.mapNotNull { rawDecision ->
        rawDecision.toRuntimeDecisionOrNull()
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
      timeoutMs = recommendedInterpreterProviderRouteTimeoutMs(settings.model),
      metadata = LiteLlmJsonExtractionSupport.routeMetadata(settings),
    )
    return ProviderRouting(
      activeProfileId = INTERPRETER_PROFILE_ID,
      profiles = listOf(
        ModelProfile(
          id = INTERPRETER_PROFILE_ID,
          displayName = "Memory stewardship interpreter",
          primaryRouteId = route.id,
          routes = listOf(route),
        ),
      ),
    )
  }

  private fun buildPrompt(request: MemoryStewardshipRequest): String = buildString {
    appendLine("Analyze the current turn and maintain the listed memory records with bounded actions only.")
    appendLine("Return a single JSON object and nothing else.")
    appendLine()
    appendLine("Rules:")
    appendLine("- You may only act on active records and proposed candidates that are listed below.")
    appendLine("- Allowed action values: refresh_record_with_candidate, merge_record_with_candidate, reaffirm_record, resolve_record, supersede_record_with_candidate, drop_candidate.")
    appendLine("- resolve_record requires resolution_reason with one of: invalidated, obsolete, duplicate.")
    appendLine("- If there are no proposed candidates, only use resolve_record or reaffirm_record on the listed active records.")
    appendLine("- refresh_record_with_candidate means the candidate is just fresh confirming evidence for that record; drop the candidate and update the record freshness without creating a new memory row.")
    appendLine("- Only choose refresh_record_with_candidate when the candidate does not add new durable detail and does not change a key value inside that same memory.")
    appendLine("- merge_record_with_candidate means the record and candidate describe the same underlying project fact or durable instruction, but the candidate adds compatible durable detail that should be folded into one replacement memory row.")
    appendLine("- Only use merge_record_with_candidate for project_fact or durable_instruction. Do not use it for user_preference.")
    appendLine("- Prefer merge_record_with_candidate over supersede_record_with_candidate when the new candidate extends the same memory with compatible extra detail instead of correcting or replacing a key value.")
    appendLine("- Do not use merge_record_with_candidate when the candidate changes a key value such as a name, port, version, path, or other scalar detail that should replace the old row instead of being combined with it.")
    appendLine("- supersede_record_with_candidate means the candidate should replace that record for the same underlying preference, instruction, or fact.")
    appendLine("- Prefer refresh_record_with_candidate over supersede_record_with_candidate when the underlying memory stays the same and only the current turn re-confirms it.")
    appendLine("- Only use refresh_record_with_candidate, merge_record_with_candidate, or supersede_record_with_candidate when the record and candidate clearly refer to the same underlying memory topic.")
    appendLine("- Prefer supersede_record_with_candidate over resolve_record when a listed candidate is the clear replacement.")
    appendLine("- Use drop_candidate only when the candidate is clearly transient, duplicate, or not durable enough after considering the current turn and listed records.")
    appendLine("- If there are no active records, you may still use drop_candidate to prune redundant or conflicting proposed candidates from the same turn.")
    appendLine("- For user preferences, drop one-turn formatting asks, temporary tone requests, or task-local response preferences unless the user clearly frames them as a durable ongoing preference.")
    appendLine("- A durable naming or addressing preference such as '以后叫我阿澄' or '以后称呼我亲切一点' should usually be kept when there is no conflicting active record.")
    appendLine("- For project facts, drop speculative, uncertain, guessed, or one-turn-only details instead of storing them as durable memory.")
    appendLine("- For durable instructions, drop one-turn execution directions, temporary task-local asks, or ephemeral operating preferences that should stay in the transcript instead of durable memory.")
    appendLine("- Prefer the current turn's explicit evidence over older conflicting records, especially when the user is clearly correcting or replacing a prior fact, preference, or durable rule.")
    appendLine("- If the user explicitly corrects a preferred name such as '别再叫我阿澄了，以后叫我阿青', prefer supersede_record_with_candidate for the old preferred-name record when the listed candidate is the clear replacement.")
    appendLine("- If an active user_preferred_name or user_address_style record is explicitly invalidated without a replacement candidate, prefer resolve_record on the old record rather than preserving it.")
    appendLine("- If a proposed candidate is only a negative restatement such as 'Do not call the user 阿澄', drop_candidate that negative candidate and resolve_record the old preferred-name record instead of keeping both.")
    appendLine("- If the same turn also contains an unrelated durable fact or instruction candidate, keep that unrelated candidate when it is independently valid. Resolving an old preference record should not cause you to drop a separate valid workspace fact.")
    appendLine("- Use reaffirm_record only when the listed record still matches the current turn after considering evidence source and recency metadata, and no replacement or refresh is needed.")
    appendLine("- If the evidence is ambiguous, return no decision for that item.")
    appendLine("- Never invent new records or candidates.")
    appendLine("- Keep changes narrow; do not cascade one ambiguous turn into broad memory rewrites.")
    appendLine("- If nothing should change, return {\"decisions\":[]}.")
    appendLine()
    appendLine("Current turn evidence:")
    appendLine("User input:")
    appendLine(request.userInput.trim())
    appendLine()
    appendLine("Assistant output:")
    appendLine(request.assistantOutput?.trim().takeUnless(String?::isNullOrBlank) ?: "<none>")
    appendLine()
    appendLine("Tool observations:")
    if (request.toolObservations.isEmpty()) {
      appendLine("<none>")
    } else {
      request.toolObservations.forEach { observation ->
        appendLine("- ${observation.trim()}")
      }
    }
    appendLine()
    appendLine("Active related records:")
    if (request.activeRecords.isEmpty()) {
      appendLine("<none>")
    } else {
      request.activeRecords.forEach { record ->
        append("- id=${record.id}")
        append(", kind=${record.kind.name.lowercase()}")
        append(", scope=${record.scope.name.lowercase()}")
        record.source?.let { source -> append(", source=${source.name.lowercase()}") }
        record.updatedAtEpochMs?.let { updatedAtEpochMs -> append(", updated_at_epoch_ms=$updatedAtEpochMs") }
        record.lastConfirmedAtEpochMs?.let { lastConfirmedAtEpochMs ->
          append(", last_confirmed_at_epoch_ms=$lastConfirmedAtEpochMs")
        }
        record.preferenceKey?.let { key -> append(", preference_key=$key") }
        record.preferenceValue?.let { value -> append(", preference_value=$value") }
        append(", content=${record.content}")
        appendLine()
      }
    }
    appendLine()
    appendLine("Proposed candidates:")
    if (request.proposedCandidates.isEmpty()) {
      appendLine("<none>")
    } else {
      request.proposedCandidates.forEach { candidate ->
        append("- index=${candidate.index}")
        append(", kind=${candidate.kind.name.lowercase()}")
        append(", scope=${candidate.scope.name.lowercase()}")
        append(", source=${candidate.source.name.lowercase()}")
        candidate.sourceTaskId?.let { sourceTaskId -> append(", source_task_id=$sourceTaskId") }
        candidate.preferenceKey?.let { key -> append(", preference_key=$key") }
        candidate.preferenceValue?.let { value -> append(", preference_value=$value") }
        append(", content=${candidate.content}")
        appendLine()
      }
    }
    appendLine()
    appendLine("JSON schema examples:")
    appendLine("{\"decisions\":[]}")
    appendLine("{\"decisions\":[{\"action\":\"refresh_record_with_candidate\",\"record_id\":\"mem-old\",\"candidate_index\":0}]}")
    appendLine("{\"decisions\":[{\"action\":\"merge_record_with_candidate\",\"record_id\":\"fact-old\",\"candidate_index\":0}]}")
    appendLine("{\"decisions\":[{\"action\":\"supersede_record_with_candidate\",\"record_id\":\"mem-old\",\"candidate_index\":0}]}")
    appendLine("{\"decisions\":[{\"action\":\"supersede_record_with_candidate\",\"record_id\":\"pref-old-name\",\"candidate_index\":0}]}")
    appendLine("{\"decisions\":[{\"action\":\"resolve_record\",\"record_id\":\"mem-old\",\"resolution_reason\":\"obsolete\"}]}")
    appendLine("{\"decisions\":[{\"action\":\"resolve_record\",\"record_id\":\"pref-old-name\",\"resolution_reason\":\"invalidated\"},{\"action\":\"drop_candidate\",\"candidate_index\":0}]}")
    appendLine("{\"decisions\":[{\"action\":\"resolve_record\",\"record_id\":\"pref-old-name\",\"resolution_reason\":\"invalidated\"}]}")
    appendLine("{\"decisions\":[{\"action\":\"drop_candidate\",\"candidate_index\":1}]}")
    appendLine("{\"decisions\":[{\"action\":\"drop_candidate\",\"candidate_index\":0}]}")
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
    val decisions: List<InterpreterDecision> = emptyList(),
  )

  @Serializable
  private data class InterpreterDecision(
    val action: String? = null,
    @SerialName("record_id")
    val recordId: String? = null,
    @SerialName("candidate_index")
    val candidateIndex: Int? = null,
    @SerialName("resolution_reason")
    val resolutionReason: String? = null,
  ) {
    fun toRuntimeDecisionOrNull(): MemoryStewardshipDecision? {
      val resolvedAction = MemoryStewardshipAction.fromWireValue(action) ?: return null
      return MemoryStewardshipDecision(
        action = resolvedAction,
        recordId = recordId?.trim()?.takeIf(String::isNotBlank),
        candidateIndex = candidateIndex,
        resolutionReason = MemoryStewardshipResolutionReason.fromWireValue(resolutionReason),
      )
    }
  }

  private companion object {
    const val INTERPRETER_PROFILE_ID: String = "memory-stewardship"
    const val INTERPRETER_ROUTE_ID: String = "memory-stewardship-primary"
    const val INTERPRETER_TIMEOUT_MS: Long = LiteLlmJsonExtractionSupport.DEFAULT_TIMEOUT_MS
    const val INTERPRETER_SYSTEM_PROMPT: String =
      "You are a strict JSON information extractor for OpenCray bounded memory stewardship. Output valid JSON only."
  }
}
