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
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentInterpreter
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiteLlmTaskCommitmentIntentInterpreter(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val providerClient: LiteLlmProviderClient,
  private val gatewayFactory: (ProviderRouting, LiteLlmProviderClient) -> LiteLlmGateway =
    { routing, client ->
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
        providerClient = client,
      )
    },
) : TaskCommitmentIntentInterpreter {
  override fun interpret(
    request: TaskCommitmentIntentRequest,
  ): TaskCommitmentIntentInterpretation {
    if (request.commitments.isEmpty()) {
      return TaskCommitmentIntentInterpretation.Success(decisions = emptyList())
    }
    val settings = llmSettingsProvider().sanitized()
    if (!settings.enabled || !settings.isConfigured()) {
      return TaskCommitmentIntentInterpretation.Unavailable(
        allowHeuristicFallback = false,
        reason = "LLM settings are not configured for task commitment interpretation.",
      )
    }

    val gateway = gatewayFactory(
      buildRouting(settings),
      providerClient,
    )
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "task-commitment-intent-${UUID.randomUUID()}",
        systemPrompt = INTERPRETER_SYSTEM_PROMPT,
        prompt = buildPrompt(request),
        metadata = mapOf(
          "source" to "task_commitment_intent_interpreter",
          "sessionId" to request.sessionId,
        ),
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = settings.protocol,
          apiKey = settings.apiKey,
        ),
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return TaskCommitmentIntentInterpretation.Unavailable(
        allowHeuristicFallback = false,
        reason = result.errorMessage ?: result.errorCode ?: result.status.name,
      )
    }
    val payload = parsePayload(result.outputText.orEmpty())
      ?: return TaskCommitmentIntentInterpretation.Unavailable(
        allowHeuristicFallback = false,
        reason = "Interpreter returned unparsable output.",
      )
    return TaskCommitmentIntentInterpretation.Success(
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
          displayName = "Task commitment interpreter",
          primaryRouteId = route.id,
          routes = listOf(route),
        ),
      ),
    )
  }

  private fun buildPrompt(request: TaskCommitmentIntentRequest): String = buildString {
    appendLine("Analyze the current session evidence and maintain open task commitments.")
    appendLine("Return a single JSON object and nothing else.")
    appendLine()
    appendLine("Rules:")
    appendLine("- You may only decide actions for commitments and proposed commitments that are listed below.")
    appendLine("- Allowed action values: resolve, reaffirm, abandon, supersede_with_proposed, drop_proposed.")
    appendLine("- resolve means the evidence clearly shows the commitment was completed in this turn.")
    appendLine("- reaffirm means the evidence shows the commitment is still active, still being worked on, or will continue next.")
    appendLine("- abandon means the evidence shows the commitment is no longer being pursued and there is no replacement commitment to keep.")
    appendLine("- supersede_with_proposed means an existing commitment is no longer the active plan and one listed proposed commitment replaces it.")
    appendLine("- drop_proposed means a listed proposed commitment should not be written because it is redundant, duplicate, or no longer needed.")
    appendLine("- If the evidence is ambiguous or unrelated, emit no decision for that commitment.")
    appendLine("- Do not invent new commitments.")
    appendLine("- At most one decision per commitment id.")
    appendLine("- At most one decision per proposed_commitment_index.")
    appendLine("- resolve, reaffirm, and abandon require commitment_id.")
    appendLine("- supersede_with_proposed requires both commitment_id and proposed_commitment_index.")
    appendLine("- drop_proposed requires proposed_commitment_index.")
    appendLine("- If nothing should change, return {\"decisions\":[]}.")
    appendLine()
    appendLine("Open commitments:")
    request.commitments.forEach { commitment ->
      appendLine("- ${commitment.id}: ${commitment.content}")
    }
    appendLine()
    appendLine("Proposed commitments from this turn:")
    if (request.proposedCommitments.isEmpty()) {
      appendLine("<none>")
    } else {
      request.proposedCommitments.forEach { commitment ->
        appendLine("- candidate ${commitment.candidateIndex}: ${commitment.content}")
      }
    }
    appendLine()
    appendLine("User input:")
    appendLine(request.userInput?.trim().takeUnless(String?::isNullOrBlank) ?: "<none>")
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
    appendLine("JSON schema example:")
    appendLine(
      "{\"decisions\":[{\"commitment_id\":\"commitment-1\",\"action\":\"resolve\"}," +
        "{\"commitment_id\":\"commitment-2\",\"action\":\"supersede_with_proposed\",\"proposed_commitment_index\":0}," +
        "{\"proposed_commitment_index\":1,\"action\":\"drop_proposed\"}]}",
    )
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
    @SerialName("commitment_id")
    val commitmentId: String? = null,
    @SerialName("proposed_commitment_index")
    val proposedCommitmentIndex: Int? = null,
    val action: String? = null,
  ) {
    fun toRuntimeDecisionOrNull(): TaskCommitmentIntentDecision? {
      val resolvedAction = when (action?.trim()?.lowercase()) {
        "resolve" -> TaskCommitmentIntentAction.RESOLVE
        "reaffirm" -> TaskCommitmentIntentAction.REAFFIRM
        "abandon" -> TaskCommitmentIntentAction.ABANDON
        "supersede_with_proposed" -> TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED
        "drop_proposed" -> TaskCommitmentIntentAction.DROP_PROPOSED
        else -> null
      } ?: return null
      val resolvedCommitmentId = commitmentId?.trim()?.takeIf(String::isNotBlank)
      val resolvedProposedCommitmentIndex = proposedCommitmentIndex?.takeIf { index -> index >= 0 }
      when (resolvedAction) {
        TaskCommitmentIntentAction.RESOLVE,
        TaskCommitmentIntentAction.REAFFIRM,
        TaskCommitmentIntentAction.ABANDON,
        -> {
          if (resolvedCommitmentId == null) {
            return null
          }
        }

        TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED -> {
          if (resolvedCommitmentId == null || resolvedProposedCommitmentIndex == null) {
            return null
          }
        }

        TaskCommitmentIntentAction.DROP_PROPOSED -> {
          if (resolvedProposedCommitmentIndex == null) {
            return null
          }
        }
      }
      return TaskCommitmentIntentDecision(
        commitmentId = resolvedCommitmentId,
        action = resolvedAction,
        proposedCommitmentIndex = resolvedProposedCommitmentIndex,
      )
    }
  }

  private companion object {
    const val INTERPRETER_PROFILE_ID: String = "task-commitment-intent"
    const val INTERPRETER_ROUTE_ID: String = "task-commitment-intent-primary"
    const val INTERPRETER_TIMEOUT_MS: Long = 20_000L
    const val INTERPRETER_SYSTEM_PROMPT: String =
      "You are a strict JSON information extractor for OpenCray task commitment maintenance. Output valid JSON only."
  }
}
