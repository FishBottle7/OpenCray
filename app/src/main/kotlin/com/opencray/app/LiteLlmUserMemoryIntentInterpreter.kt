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
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiteLlmUserMemoryIntentInterpreter(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val providerClient: LiteLlmProviderClient,
  private val gatewayFactory: (ProviderRouting, LiteLlmProviderClient) -> LiteLlmGateway =
    { routing, client ->
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
        providerClient = client,
      )
    },
) : UserMemoryIntentInterpreter {
  override fun interpret(
    request: UserMemoryIntentRequest,
  ): UserMemoryIntentInterpretation {
    val settings = llmSettingsProvider().sanitized()
    if (!settings.enabled || !settings.isConfigured()) {
      return UserMemoryIntentInterpretation.Unavailable(
        allowHeuristicFallback = true,
        reason = "LLM settings are not configured for user memory interpretation.",
      )
    }

    val gateway = gatewayFactory(
      buildRouting(settings),
      providerClient,
    )
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "memory-intent-${UUID.randomUUID()}",
        systemPrompt = INTERPRETER_SYSTEM_PROMPT,
        prompt = buildPrompt(request),
        metadata = mapOf(
          "source" to "user_memory_intent_interpreter",
          "sessionId" to request.sessionId,
        ),
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = settings.protocol,
          apiKey = settings.apiKey,
        ),
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return UserMemoryIntentInterpretation.Unavailable(
        allowHeuristicFallback = false,
        reason = result.errorMessage ?: result.errorCode ?: result.status.name,
      )
    }
    val payload = parsePayload(result.outputText.orEmpty())
      ?: return UserMemoryIntentInterpretation.Unavailable(
        allowHeuristicFallback = false,
        reason = "Interpreter returned unparsable output.",
      )
    return UserMemoryIntentInterpretation.Success(
      intents = payload.intents.mapNotNull { rawIntent -> rawIntent.toRuntimeIntentOrNull() },
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
          displayName = "User memory interpreter",
          primaryRouteId = route.id,
          routes = listOf(route),
        ),
      ),
    )
  }

  private fun buildPrompt(request: UserMemoryIntentRequest): String = buildString {
    appendLine("Analyze the user's message and extract only explicit durable memory intents.")
    appendLine("Return a single JSON object and nothing else.")
    appendLine()
    appendLine("Rules:")
    appendLine("- Only capture explicit durable or semi-durable memory worth recalling later.")
    appendLine("- Allowed kind values: user_preference, durable_instruction, project_fact.")
    appendLine("- Do not capture the user's immediate task request unless they clearly want it remembered beyond this turn.")
    appendLine("- Use session for temporary requests like 'this time', 'for now', '这次', '先', '暂时'.")
    appendLine("- Use user for durable preferences like 'from now on', 'always', '以后', '默认', '今后'.")
    appendLine("- Use workspace for repo/project-scoped rules or facts.")
    appendLine("- For generic memories, fill content with a short canonical sentence and omit preference_key/preference_value.")
    appendLine("- For soul-related naming or speaking preferences, kind must be user_preference.")
    appendLine("- Allowed preference_key values: agent_display_name, agent_style_profile, relationship_style_profile, interaction_preference_signal, agent_verbosity, user_preferred_name, user_address_style.")
    appendLine("- agent_display_name may use session, user, or workspace when the user clearly scopes it.")
    appendLine("- agent_style_profile preference_value should be a stable label such as warm or serious.")
    appendLine("- agent_style_profile is only for current-run acting mode and always uses session scope.")
    appendLine("- interaction_preference_signal is the preferred key for durable adaptive relationship drift and may use user or workspace scope.")
    appendLine("- relationship_style_profile is a legacy compatibility key for durable relationship evolution and may use user or workspace scope.")
    appendLine("- agent_verbosity preference_value should be terse, balanced, or expansive.")
    appendLine("- agent_verbosity always uses session scope, even if the user phrases it as a long-term request.")
    appendLine("- user_preferred_name stores how the agent should address the user. It may use session, user, or workspace scope.")
    appendLine("- user_address_style stores the desired user-addressing closeness and should use one of: neutral, friendly, intimate.")
    appendLine("- user_address_style may use session, user, or workspace scope when the user clearly scopes it.")
    appendLine("- soul_extensions may only contain soul_display_name for display-name intents, soul_voice/soul_tone/soul_user_relationship_style for style or relationship-style intents, soul_verbosity for verbosity intents, soul_preferred_naming for user_preferred_name, or soul_preferred_address_style for user_address_style.")
    appendLine("- preference_extensions may only be used with interaction_preference_signal or the legacy relationship_style_profile key.")
    appendLine("- Allowed preference_extensions keys for interaction_preference_signal and relationship_style_profile: ${MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION}, ${MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION}, ${MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION}.")
    appendLine("- Allowed preference_extensions values are higher or lower.")
    appendLine("- Prefer interaction_preference_signal for durable adaptive-style drift. When using it, set preference_value to a short placeholder like adaptive; runtime will canonicalize it from preference_extensions.")
    appendLine("- Use relationship_style_profile only when you intentionally need the legacy compatibility label, and still include preference_extensions when possible.")
    appendLine("- Never output soul_risk_tolerance or soul_tool_use_bias from direct chat.")
    appendLine("- project_fact should only be used for durable repo/project facts, not one-off task state.")
    appendLine("- If there is nothing durable to remember, return {\"intents\":[]}.")
    appendLine("- Do not infer memories that were not clearly stated.")
    appendLine()
    appendLine("JSON schema examples:")
    appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"content\":\"Default to Simplified Chinese for explanations\"}]}")
    appendLine("{\"intents\":[{\"kind\":\"durable_instruction\",\"scope\":\"workspace\",\"content\":\"Do not use git reset --hard in this repo\"}]}")
    appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"agent_display_name\",\"preference_value\":\"Xiao Bai\",\"soul_extensions\":{\"soul_display_name\":\"Xiao Bai\"}}]}")
    appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"interaction_preference_signal\",\"preference_value\":\"adaptive\",\"preference_extensions\":{\"interaction_preference_warmth_direction\":\"higher\",\"interaction_preference_formality_direction\":\"lower\"}}]}")
    appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"relationship_style_profile\",\"preference_value\":\"warm\",\"preference_extensions\":{\"interaction_preference_warmth_direction\":\"higher\",\"interaction_preference_formality_direction\":\"lower\"},\"soul_extensions\":{\"soul_tone\":\"warm\",\"soul_voice\":\"warm and gentle\",\"soul_user_relationship_style\":\"supportive\"}}]}")
    appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"user_preferred_name\",\"preference_value\":\"A Cheng\",\"soul_extensions\":{\"soul_preferred_naming\":\"A Cheng\"}}]}")
    appendLine("{\"intents\":[{\"kind\":\"user_preference\",\"scope\":\"user\",\"preference_key\":\"user_address_style\",\"preference_value\":\"friendly\",\"soul_extensions\":{\"soul_preferred_address_style\":\"friendly\"}}]}")
    appendLine()
    appendLine("User message:")
    append(request.userInput.trim())
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
    val intents: List<InterpreterIntent> = emptyList(),
  )

  @Serializable
  private data class InterpreterIntent(
    val kind: String? = null,
    val scope: String? = null,
    val content: String? = null,
    @SerialName("preference_key")
    val preferenceKey: String? = null,
    @SerialName("preference_value")
    val preferenceValue: String? = null,
    @SerialName("soul_extensions")
    val soulExtensions: Map<String, String> = emptyMap(),
    @SerialName("preference_extensions")
    val preferenceExtensions: Map<String, String> = emptyMap(),
  ) {
    fun toRuntimeIntentOrNull(): UserMemoryIntent? {
      val resolvedKind = when (kind?.trim()?.lowercase()) {
        "user_preference" -> MemoryKind.USER_PREFERENCE
        "durable_instruction" -> MemoryKind.DURABLE_INSTRUCTION
        "project_fact" -> MemoryKind.PROJECT_FACT
        else -> null
      } ?: return null
      val resolvedScope = when (scope?.trim()?.lowercase()) {
        "session" -> MemoryScope.SESSION
        "workspace" -> MemoryScope.WORKSPACE
        "user" -> MemoryScope.USER
        else -> null
      } ?: return null
      val resolvedContent = content?.trim()?.takeIf(String::isNotBlank)
      val resolvedKey = preferenceKey?.trim()?.takeIf(String::isNotBlank)
      val resolvedValue = preferenceValue?.trim()?.takeIf(String::isNotBlank)
      if (resolvedContent == null && (resolvedKey == null || resolvedValue == null)) {
        return null
      }
      return UserMemoryIntent(
        kind = resolvedKind,
        scope = resolvedScope,
        content = resolvedContent,
        preferenceKey = resolvedKey,
        preferenceValue = resolvedValue,
        soulExtensions = soulExtensions,
        preferenceExtensions = preferenceExtensions,
      )
    }
  }

  private companion object {
    const val INTERPRETER_PROFILE_ID: String = "user-memory-intent"
    const val INTERPRETER_ROUTE_ID: String = "user-memory-intent-primary"
    const val INTERPRETER_TIMEOUT_MS: Long = 20_000L
    const val INTERPRETER_SYSTEM_PROMPT: String =
      "You are a strict JSON information extractor for OpenCray memory. Output valid JSON only."
  }
}
