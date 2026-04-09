package com.opencray.app.facade.llm

import android.content.Context
import com.opencray.app.LlmAgentCapabilitySnapshot
import com.opencray.app.LlmModelCapabilityRegistry
import com.opencray.app.LlmProviderCatalog
import com.opencray.app.LlmProviderPreset
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.OpenCrayUserAgent
import com.opencray.app.SavedCustomLlmProvider
import com.opencray.app.effectiveLlmRouteMetadata
import com.opencray.app.recommendedValidationProviderRouteTimeoutMs
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import java.net.URI
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.opencray.app.R

data class LlmProviderOptionSnapshot(
  val id: String,
  val providerId: String,
  val title: String,
  val subtitle: String,
  val defaultBaseUrl: String,
  val defaultModel: String,
  val protocol: String,
  val apiKey: String,
  val isCustom: Boolean,
)

data class LlmConfigSnapshot(
  val localeTag: String,
  val enabled: Boolean,
  val streamingEnabled: Boolean = LlmSettingsState.DEFAULT_STREAMING_ENABLED,
  val providerId: String,
  val selectedProviderOptionId: String,
  val protocol: String,
  val providerOptions: List<LlmProviderOptionSnapshot>,
  val providerName: String,
  val providerNotes: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String,
  val systemPrompt: String,
  val openAiPromptCacheKeyStrategy: String? = null,
  val openAiPromptCacheRetention: String? = null,
  val anthropicPromptCachingEnabled: Boolean? = null,
  val anthropicPromptCacheTtl: String? = null,
  val helperText: String,
  val agentCapability: LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(),
)

data class SaveLlmConfigRequest(
  val enabled: Boolean,
  val streamingEnabled: Boolean? = null,
  val providerId: String,
  val selectedProviderOptionId: String,
  val protocol: String,
  val providerName: String,
  val providerNotes: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String,
  val systemPrompt: String,
  val openAiPromptCacheKeyStrategy: String? = null,
  val openAiPromptCacheRetention: String? = null,
  val anthropicPromptCachingEnabled: Boolean? = null,
  val anthropicPromptCacheTtl: String? = null,
)

data class SaveCustomLlmProviderRequest(
  val selectedProviderOptionId: String,
  val streamingEnabled: Boolean? = null,
  val protocol: String,
  val providerName: String,
  val providerNotes: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String,
  val systemPrompt: String,
  val openAiPromptCacheKeyStrategy: String =
    LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
  val openAiPromptCacheRetention: String =
    LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
  val anthropicPromptCachingEnabled: Boolean =
    LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
  val anthropicPromptCacheTtl: String = LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
)

data class ValidateLlmConfigRequest(
  val providerId: String,
  val protocol: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String,
)

data class LlmValidationResult(
  val isSuccess: Boolean,
  val message: String,
  val agentCapability: LlmAgentCapabilitySnapshot? = null,
)

internal data class LlmConfigStrings(
  val localeTag: String,
  val helperText: String,
  val customProviderTitle: String,
  val openAiSubtitle: String,
  val deepSeekSubtitle: String,
  val openRouterSubtitle: String,
  val customProviderSubtitle: String,
  val validationSuccess: (String) -> String,
  val validationTimeout: (Long) -> String,
  val validationRateLimited: String,
  val validationFailed: String,
  val validationNativeToolsUnavailable: String,
  val baseUrlRequiredEnabled: String,
  val baseUrlValidateRequired: String,
  val modelValidateRequired: String,
  val baseUrlInvalid: String,
  val baseUrlScheme: String,
)

interface LlmConfigFacade {
  fun load(): LlmConfigSnapshot

  fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot

  fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot

  fun validate(request: ValidateLlmConfigRequest): LlmValidationResult
}

internal class LocalLlmConfigFacade private constructor(
  private val llmSettingsStore: LlmSettingsStore,
  private val providerClient: LiteLlmProviderClient,
  private val strings: LlmConfigStrings,
) : LlmConfigFacade {
  override fun load(): LlmConfigSnapshot = snapshotFor(llmSettingsStore.load())

  override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot {
    val savedState = resolvedStateFromRequest(request)
    llmSettingsStore.save(
      state = savedState,
      selectedProviderOptionId = request.selectedProviderOptionId,
    )
    return snapshotFor(savedState)
  }

  override fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot {
    val savedProviders = llmSettingsStore.loadSavedCustomProviders()
    val existingProvider = savedProviders.firstOrNull { provider ->
      provider.id == request.selectedProviderOptionId
    }
    val normalizedProtocol = LlmProviderProtocols.normalize(request.protocol)
    val providerRecord = SavedCustomLlmProvider.create(
      existingId = existingProvider?.id,
      protocol = normalizedProtocol,
      providerName = resolvedCustomProviderName(
        requestedName = request.providerName,
        baseUrl = request.baseUrl,
      ),
      providerNotes = request.providerNotes.trim(),
      baseUrl = request.baseUrl.trim(),
      apiKey = request.apiKey.trim(),
      model = request.model.trim(),
    )
    llmSettingsStore.saveSavedCustomProviders(
      savedProviders
        .filterNot { provider -> provider.id == providerRecord.id }
        .plus(providerRecord),
    )
    val savedState = resolvedStateFromRequest(
      SaveLlmConfigRequest(
        enabled = providerRecord.baseUrl.isNotBlank() && providerRecord.apiKey.isNotBlank(),
        streamingEnabled = request.streamingEnabled,
        providerId = "custom",
        selectedProviderOptionId = providerRecord.id,
        protocol = providerRecord.protocol,
        providerName = providerRecord.providerName,
        providerNotes = providerRecord.providerNotes,
        baseUrl = providerRecord.baseUrl,
        apiKey = providerRecord.apiKey,
        model = providerRecord.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        openAiPromptCacheKeyStrategy = request.openAiPromptCacheKeyStrategy,
        openAiPromptCacheRetention = request.openAiPromptCacheRetention,
        anthropicPromptCachingEnabled = request.anthropicPromptCachingEnabled,
        anthropicPromptCacheTtl = request.anthropicPromptCacheTtl,
      ),
    )
    llmSettingsStore.save(
      state = savedState,
      selectedProviderOptionId = providerRecord.id,
    )
    return snapshotFor(savedState)
  }

  override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult {
    val providerPreset = LlmProviderCatalog.presetById(request.providerId)
      ?: throw IllegalArgumentException("Unsupported provider '${request.providerId}'.")
    val protocol = resolvedProtocol(
      providerPreset = providerPreset,
      requestedProtocol = request.protocol,
    )
    val baseUrl = request.baseUrl.trim().ifBlank {
      providerPreset.defaultBaseUrl
    }
    val model = request.model.trim().ifBlank {
      providerPreset.defaultModel
    }
    if (baseUrl.isBlank()) {
      throw IllegalArgumentException(strings.baseUrlValidateRequired)
    }
    if (model.isBlank()) {
      throw IllegalArgumentException(strings.modelValidateRequired)
    }
    requireValidBaseUrl(baseUrl)

    val validationTimeoutMs = recommendedValidationProviderRouteTimeoutMs(model)
    val route = ProviderRoute(
      id = "validate-${providerPreset.id}",
      providerId = providerPreset.id,
      baseUrl = baseUrl,
      model = model,
      timeoutMs = validationTimeoutMs,
      metadata = validationMetadataFor(
        providerId = providerPreset.id,
        protocol = protocol,
        model = model,
        reasoningEffort = request.reasoningEffort,
      ),
    )
    val gateway = DefaultLiteLlmGateway(
      routingStore = InMemoryLiteLlmRoutingSettingsStore(
        ProviderRouting(
          activeProfileId = VALIDATION_PROFILE_ID,
          profiles = listOf(
            ModelProfile(
              id = VALIDATION_PROFILE_ID,
              displayName = "Validation",
              primaryRouteId = route.id,
              routes = listOf(route),
            ),
          ),
        ),
      ),
      providerClient = providerClient,
    )
    val authHeaders = LlmProviderProtocols.authHeaders(
      protocol = protocol,
      apiKey = request.apiKey,
    )
    validationFailureFor(
      executeValidationRequest(
        gateway = gateway,
        authHeaders = authHeaders,
        stage = "text_connectivity",
        prompt = VALIDATION_PROMPT,
      ),
      timeoutMs = validationTimeoutMs,
    )?.let { failure ->
      return failure
    }
    val visionInputSupported = detectVisionInputSupport(
      providerId = providerPreset.id,
      protocol = protocol,
      model = model,
    )
    val pdfInputSupported = detectPdfInputSupport(
      providerId = providerPreset.id,
      protocol = protocol,
      model = model,
    )
    val nativeProbe = executeCapabilityProbe(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "native_tool_probe",
      expectedEcho = NATIVE_TOOL_PROBE_ECHO,
    )
    val capability = if (!nativeProbe.supported) {
      verifiedCapabilitySnapshot(
        providerId = providerPreset.id,
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        visionInputSupported = visionInputSupported,
        pdfInputSupported = pdfInputSupported,
        nativeToolCallingAvailable = false,
      )
    } else {
      val controlProbe = executeCapabilityProbe(
        gateway = gateway,
        authHeaders = authHeaders,
        stage = "tool_control_probe",
        expectedEcho = TOOL_CONTROL_PROBE_ECHO,
        toolChoice = LiteLlmToolChoice(
          mode = LiteLlmToolChoiceMode.TOOL,
          toolName = CAPABILITY_PROBE_TOOL_NAME,
        ),
        parallelToolCalls = false,
      )
      val strictProbe = if (controlProbe.supported) {
        executeCapabilityProbe(
          gateway = gateway,
          authHeaders = authHeaders,
          stage = "strict_schema_probe",
          expectedEcho = STRICT_SCHEMA_PROBE_ECHO,
          strict = true,
          toolChoice = LiteLlmToolChoice(
            mode = LiteLlmToolChoiceMode.TOOL,
            toolName = CAPABILITY_PROBE_TOOL_NAME,
          ),
          parallelToolCalls = false,
        )
      } else {
        CapabilityProbeOutcome.unsupported()
      }
      val parallelToolCallsProbe = if (controlProbe.supported) {
        executeParallelToolCallsProbe(
          gateway = gateway,
          authHeaders = authHeaders,
        )
      } else {
        CapabilityProbeOutcome.unsupported()
      }
      val responsesContinuationProbe = if (protocol == LlmProviderProtocols.OPENAI_RESPONSES) {
        executeResponsesContinuationProbe(
          gateway = gateway,
          authHeaders = authHeaders,
        )
      } else {
        CapabilityProbeOutcome.unsupported()
      }
      val builtinWebSearchProbe = when (protocol) {
        LlmProviderProtocols.OPENAI_RESPONSES -> executeResponsesBuiltinWebSearchProbe(
          gateway = gateway,
          authHeaders = authHeaders,
        )

        LlmProviderProtocols.OPENAI -> executeOpenAiBuiltinWebSearchProbe(
          gateway = gateway,
          authHeaders = authHeaders,
        )

        LlmProviderProtocols.ANTHROPIC -> executeAnthropicBuiltinWebSearchProbe(
          gateway = gateway,
          authHeaders = authHeaders,
        )

        else -> CapabilityProbeOutcome.unsupported()
      }
      val responsesAssistantPhaseProbeSupported = if (protocol == LlmProviderProtocols.OPENAI_RESPONSES) {
        executeResponsesAssistantPhaseProbe(
          gateway = gateway,
          authHeaders = authHeaders,
        )
      }
      else {
        false
      }
      val assistantPhaseSupported = responsesAssistantPhaseProbeSupported
      val citationIncludeSupported = builtinWebSearchProbe.result
        ?.metadata
        ?.get(LiteLlmMetadataKeys.PROVIDER_CITATION_COUNT)
        ?.toIntOrNull()
        ?.let { count -> count > 0 }
        ?: false
      verifiedCapabilitySnapshot(
        providerId = providerPreset.id,
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        visionInputSupported = visionInputSupported,
        pdfInputSupported = pdfInputSupported,
        nativeToolCallingAvailable = true,
        toolChoiceSupported = controlProbe.supported,
        parallelToolCallsSupported = parallelToolCallsProbe.supported,
        strictToolSchemaSupported = strictProbe.supported,
        responsesContinuationSupported = responsesContinuationProbe.supported,
        builtinWebSearchSupported = builtinWebSearchProbe.supported,
        assistantPhaseSupported = assistantPhaseSupported,
        citationIncludeSupported = citationIncludeSupported,
      )
    }
    llmSettingsStore.saveAgentCapability(capability)
    return if (capability.nativeToolCallingAvailable) {
      LlmValidationResult(
        isSuccess = true,
        message = strings.validationSuccess(model),
        agentCapability = capability,
      )
    } else {
      LlmValidationResult(
        isSuccess = false,
        message = strings.validationNativeToolsUnavailable,
        agentCapability = capability,
      )
    }
  }

  private fun snapshotFor(state: LlmSettingsState): LlmConfigSnapshot {
    val sanitized = state.sanitized()
    val providerOptions = providerOptions()
    val selectedProviderOptionId = llmSettingsStore.loadSelectedProviderOptionId(
      defaultProviderId = sanitized.providerId,
    ).takeIf { selectedId ->
      providerOptions.any { option -> option.id == selectedId }
    } ?: sanitized.providerId
    return LlmConfigSnapshot(
      localeTag = strings.localeTag,
      enabled = sanitized.enabled,
      streamingEnabled = sanitized.streamingEnabled,
      providerId = sanitized.providerId,
      selectedProviderOptionId = selectedProviderOptionId,
      protocol = sanitized.protocol,
      providerOptions = providerOptions,
      providerName = sanitized.providerName.ifBlank {
        localizedDisplayNameFor(
          providerId = sanitized.providerId,
          baseUrl = sanitized.baseUrl,
        )
      }.let { providerName ->
        if (sanitized.providerId == "custom" && providerName == "Custom provider") {
          strings.customProviderTitle
        } else {
          providerName
        }
      },
      providerNotes = sanitized.providerNotes,
      baseUrl = sanitized.baseUrl,
      apiKey = sanitized.apiKey,
      model = sanitized.model,
      reasoningEffort = sanitized.reasoningEffort,
      systemPrompt = sanitized.systemPrompt,
      openAiPromptCacheKeyStrategy = sanitized.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = sanitized.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = sanitized.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = sanitized.anthropicPromptCacheTtl,
      helperText = strings.helperText,
      agentCapability = sanitized.agentCapability,
    )
  }

  private fun providerOptions(): List<LlmProviderOptionSnapshot> =
    LlmProviderCatalog.presets.map(::toSnapshot) +
      llmSettingsStore.loadSavedCustomProviders().map(::toSavedCustomSnapshot)

  private fun toSnapshot(preset: LlmProviderPreset): LlmProviderOptionSnapshot =
    LlmProviderOptionSnapshot(
      id = preset.id,
      providerId = preset.id,
      title = localizedProviderTitle(preset),
      subtitle = localizedProviderSubtitle(preset),
      defaultBaseUrl = preset.defaultBaseUrl,
      defaultModel = preset.defaultModel,
      protocol = preset.defaultProtocol,
      apiKey = "",
      isCustom = preset.isCustom,
    )

  private fun toSavedCustomSnapshot(provider: SavedCustomLlmProvider): LlmProviderOptionSnapshot =
    LlmProviderOptionSnapshot(
      id = provider.id,
      providerId = "custom",
      title = provider.providerName,
      subtitle = provider.providerNotes,
      defaultBaseUrl = provider.baseUrl,
      defaultModel = provider.model,
      protocol = provider.protocol,
      apiKey = provider.apiKey,
      isCustom = true,
    )

  private fun localizedProviderTitle(preset: LlmProviderPreset): String = when (preset.id) {
    "custom" -> strings.customProviderTitle
    else -> preset.title
  }

  private fun localizedProviderSubtitle(preset: LlmProviderPreset): String = when (preset.id) {
    "openai" -> strings.openAiSubtitle
    "deepseek" -> strings.deepSeekSubtitle
    "openrouter" -> strings.openRouterSubtitle
    "custom" -> strings.customProviderSubtitle
    else -> preset.subtitle
  }

  private fun localizedDisplayNameFor(
    providerId: String,
    baseUrl: String,
  ): String {
    val preset = LlmProviderCatalog.presetById(providerId)
    if (preset != null && !preset.isCustom) {
      return localizedProviderTitle(preset)
    }
    val host = runCatching { URI(baseUrl.trim()).host.orEmpty() }.getOrDefault("")
    return when {
      preset != null -> localizedProviderTitle(preset)
      host.isNotBlank() -> host
      else -> strings.customProviderTitle
    }
  }

  private fun requireValidBaseUrl(baseUrl: String) {
    val parsed = runCatching { URI(baseUrl) }.getOrElse {
      throw IllegalArgumentException(strings.baseUrlInvalid)
    }
    val scheme = parsed.scheme.orEmpty().lowercase()
    if (scheme != "http" && scheme != "https") {
      throw IllegalArgumentException(strings.baseUrlScheme)
    }
  }

  private fun validationMetadataFor(
    providerId: String,
    protocol: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, String> = effectiveLlmRouteMetadata(
    providerId = providerId,
    protocol = protocol,
    model = model,
    reasoningEffort = reasoningEffort,
    streamingEnabled = llmSettingsStore.load().streamingEnabled,
    agentCapability = LlmAgentCapabilitySnapshot.unknown(
      protocol = protocol,
      baseUrl = "",
      model = model,
    ),
  )

  private fun resolvedProtocol(
    providerPreset: LlmProviderPreset,
    requestedProtocol: String,
  ): String = requestedProtocol
    .trim()
    .takeIf(String::isNotBlank)
    ?.let(LlmProviderProtocols::normalize)
    ?: providerPreset.defaultProtocol

  private fun resolvedStateFromRequest(request: SaveLlmConfigRequest): LlmSettingsState {
    val persisted = llmSettingsStore.load()
    val providerPreset = LlmProviderCatalog.presetById(request.providerId)
      ?: throw IllegalArgumentException("Unsupported provider '${request.providerId}'.")
    val protocol = resolvedProtocol(
      providerPreset = providerPreset,
      requestedProtocol = request.protocol,
    )
    val baseUrl = request.baseUrl.trim().ifBlank {
      providerPreset.defaultBaseUrl
    }
    val model = request.model.trim().ifBlank {
      providerPreset.defaultModel
    }
    if (request.enabled && baseUrl.isBlank()) {
      throw IllegalArgumentException(strings.baseUrlRequiredEnabled)
    }
    if (baseUrl.isNotBlank()) {
      requireValidBaseUrl(baseUrl)
    }
    val defaultProviderName = if (providerPreset.isCustom) {
      resolvedCustomProviderName(
        requestedName = request.providerName,
        baseUrl = baseUrl,
      )
    } else {
      localizedProviderTitle(providerPreset)
    }
    val promptCachingSettings = resolvedPromptCachingSettings(
      openAiPromptCacheKeyStrategy = request.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = request.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = request.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = request.anthropicPromptCacheTtl,
    )
    return LlmSettingsState(
      enabled = request.enabled,
      streamingEnabled = request.streamingEnabled ?: persisted.streamingEnabled,
      providerId = providerPreset.id,
      protocol = protocol,
      providerName = request.providerName.trim().ifBlank {
        defaultProviderName
      },
      providerNotes = request.providerNotes.trim(),
      baseUrl = baseUrl,
      apiKey = request.apiKey.trim(),
      model = model,
      reasoningEffort = request.reasoningEffort.trim().ifBlank {
        LlmSettingsState.DEFAULT_REASONING_EFFORT
      },
      systemPrompt = request.systemPrompt.trim(),
      openAiPromptCacheKeyStrategy = promptCachingSettings.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = promptCachingSettings.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = promptCachingSettings.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = promptCachingSettings.anthropicPromptCacheTtl,
      agentCapability = llmSettingsStore.loadAgentCapability(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
      ),
    ).sanitized()
  }

  private fun executeValidationRequest(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
    stage: String,
    prompt: String,
    tools: List<LiteLlmToolDefinition> = emptyList(),
    builtinTools: List<LiteLlmBuiltinToolDefinition> = emptyList(),
    toolChoice: LiteLlmToolChoice? = null,
    parallelToolCalls: Boolean? = null,
    previousResponseId: String? = null,
    metadata: Map<String, String> = emptyMap(),
  ): LiteLlmGatewayResult = gateway.execute(
    LiteLlmGatewayRequest(
      prompt = prompt,
      tools = tools,
      builtinTools = builtinTools,
      toolChoice = toolChoice,
      parallelToolCalls = parallelToolCalls,
      previousResponseId = previousResponseId,
      metadata = mapOf(
        "source" to "settings_validation",
        "validationStage" to stage,
      ) + metadata,
      authHeaders = authHeaders,
    ),
  )

  private fun executeCapabilityProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
    stage: String,
    expectedEcho: String,
    strict: Boolean = false,
    toolChoice: LiteLlmToolChoice? = null,
    parallelToolCalls: Boolean? = null,
  ): CapabilityProbeOutcome {
    val result = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = stage,
      prompt = capabilityProbePrompt(expectedEcho),
      tools = listOf(
        capabilityProbeTool(
          toolName = CAPABILITY_PROBE_TOOL_NAME,
          expectedEcho = expectedEcho,
          strict = strict,
        ),
      ),
      toolChoice = toolChoice,
      parallelToolCalls = parallelToolCalls,
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return CapabilityProbeOutcome(
        supported = false,
        result = result,
      )
    }
    val toolCallObserved = result.completion
      ?.toolCalls
      ?.any { toolCall ->
        isCapabilityProbeToolCall(
          toolCall = toolCall,
          toolName = CAPABILITY_PROBE_TOOL_NAME,
          expectedEcho = expectedEcho,
        )
      } == true
    val metadataObserved = result.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED]
      ?.trim()
      ?.lowercase() == "true"
    return CapabilityProbeOutcome(
      supported = toolCallObserved || metadataObserved,
      result = result,
    )
  }

  private fun executeParallelToolCallsProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
  ): CapabilityProbeOutcome {
    val result = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "parallel_tool_calls_probe",
      prompt = parallelCapabilityProbePrompt(),
      tools = listOf(
        capabilityProbeTool(
          toolName = PARALLEL_CAPABILITY_PROBE_TOOL_ONE,
          expectedEcho = PARALLEL_TOOL_PROBE_ONE_ECHO,
          strict = true,
        ),
        capabilityProbeTool(
          toolName = PARALLEL_CAPABILITY_PROBE_TOOL_TWO,
          expectedEcho = PARALLEL_TOOL_PROBE_TWO_ECHO,
          strict = true,
        ),
      ),
      parallelToolCalls = true,
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return CapabilityProbeOutcome(
        supported = false,
        result = result,
      )
    }
    val observedToolCalls = result.completion?.toolCalls.orEmpty()
    val observedFirst = observedToolCalls.any { toolCall ->
      isCapabilityProbeToolCall(
        toolCall = toolCall,
        toolName = PARALLEL_CAPABILITY_PROBE_TOOL_ONE,
        expectedEcho = PARALLEL_TOOL_PROBE_ONE_ECHO,
      )
    }
    val observedSecond = observedToolCalls.any { toolCall ->
      isCapabilityProbeToolCall(
        toolCall = toolCall,
        toolName = PARALLEL_CAPABILITY_PROBE_TOOL_TWO,
        expectedEcho = PARALLEL_TOOL_PROBE_TWO_ECHO,
      )
    }
    return CapabilityProbeOutcome(
      supported = observedToolCalls.size >= 2 && observedFirst && observedSecond,
      result = result,
    )
  }

  private fun executeResponsesContinuationProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
  ): CapabilityProbeOutcome {
    val seedResult = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "responses_continuation_seed",
      prompt = "Remember the exact token $RESPONSES_CONTINUATION_TOKEN and reply only with READY.",
      metadata = mapOf(
        LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CONTINUATION to "true",
      ),
    )
    if (seedResult.status != LiteLlmGatewayStatus.SUCCESS) {
      return CapabilityProbeOutcome(
        supported = false,
        result = seedResult,
      )
    }
    val previousResponseId = seedResult.providerResponseId?.trim()?.takeIf(String::isNotBlank)
      ?: return CapabilityProbeOutcome(
        supported = false,
        result = seedResult,
      )
    val followupResult = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "responses_continuation_followup",
      prompt = "What token did I ask you to remember? Reply only with the exact token.",
      previousResponseId = previousResponseId,
      metadata = mapOf(
        LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CONTINUATION to "true",
      ),
    )
    val recalledText = followupResult.outputText
      ?.trim()
      ?.ifBlank { followupResult.completion?.finalText?.trim().orEmpty() }
      .orEmpty()
    return CapabilityProbeOutcome(
      supported = followupResult.status == LiteLlmGatewayStatus.SUCCESS &&
        recalledText.contains(RESPONSES_CONTINUATION_TOKEN),
      result = followupResult,
    )
  }

  private fun executeResponsesBuiltinWebSearchProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
  ): CapabilityProbeOutcome {
    val result = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "responses_builtin_web_search_probe",
      prompt = "Use web search to find the canonical https://example.com URL and cite one source.",
      builtinTools = listOf(
        LiteLlmBuiltinToolDefinition(
          type = LiteLlmBuiltinToolType.WEB_SEARCH,
          includeSources = true,
        ),
      ),
      metadata = mapOf(
        LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CITATION_INCLUDE to "true",
      ),
    )
    return CapabilityProbeOutcome(
      supported = result.status == LiteLlmGatewayStatus.SUCCESS &&
        result.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED]
          ?.trim()
          ?.lowercase() == "true",
      result = result,
    )
  }

  private fun executeOpenAiBuiltinWebSearchProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
  ): CapabilityProbeOutcome {
    val result = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "openai_builtin_web_search_probe",
      prompt = "Use web search to find the canonical https://example.com URL and reply with that URL.",
      builtinTools = listOf(
        LiteLlmBuiltinToolDefinition(
          type = LiteLlmBuiltinToolType.WEB_SEARCH,
          includeSources = false,
        ),
      ),
    )
    return CapabilityProbeOutcome(
      supported = result.status == LiteLlmGatewayStatus.SUCCESS &&
        result.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED]
          ?.trim()
          ?.lowercase() == "true",
      result = result,
    )
  }

  private fun executeAnthropicBuiltinWebSearchProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
  ): CapabilityProbeOutcome {
    val result = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "anthropic_builtin_web_search_probe",
      prompt = "Use web search to find the canonical https://example.com URL and cite one source.",
      builtinTools = listOf(
        LiteLlmBuiltinToolDefinition(
          type = LiteLlmBuiltinToolType.WEB_SEARCH,
          includeSources = true,
        ),
      ),
    )
    return CapabilityProbeOutcome(
      supported = result.status == LiteLlmGatewayStatus.SUCCESS &&
        result.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED]
          ?.trim()
          ?.lowercase() == "true",
      result = result,
    )
  }

  private fun executeResponsesAssistantPhaseProbe(
    gateway: DefaultLiteLlmGateway,
    authHeaders: Map<String, String>,
  ): Boolean {
    val result = executeValidationRequest(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "responses_assistant_phase_probe",
      prompt = "Return a short commentary update first, then the final answer OK. Use phased assistant messages if supported.",
      metadata = mapOf(
        LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_ASSISTANT_PHASES to "true",
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return false
    }
    val commentaryObserved = result.metadata[LiteLlmMetadataKeys.RESPONSES_COMMENTARY_PHASE_OBSERVED]
      ?.trim()
      ?.lowercase() == "true"
    val finalObserved = result.metadata[LiteLlmMetadataKeys.RESPONSES_FINAL_PHASE_OBSERVED]
      ?.trim()
      ?.lowercase() == "true"
    return commentaryObserved && finalObserved
  }

  private fun validationFailureFor(
    result: LiteLlmGatewayResult,
    timeoutMs: Long,
  ): LlmValidationResult? = when (result.status) {
    LiteLlmGatewayStatus.SUCCESS -> null
    LiteLlmGatewayStatus.TIMEOUT -> LlmValidationResult(
      isSuccess = false,
      message = result.errorMessage?.ifBlank {
        strings.validationTimeout(timeoutMs / 1000)
      } ?: strings.validationTimeout(timeoutMs / 1000),
    )

    LiteLlmGatewayStatus.RATE_LIMITED -> LlmValidationResult(
      isSuccess = false,
      message = result.errorMessage?.ifBlank {
        strings.validationRateLimited
      } ?: strings.validationRateLimited,
    )

    LiteLlmGatewayStatus.FAILED -> LlmValidationResult(
      isSuccess = false,
      message = result.errorMessage?.ifBlank {
        strings.validationFailed
      } ?: strings.validationFailed,
    )
  }

  private fun capabilityProbePrompt(expectedEcho: String): String =
    """Call the $CAPABILITY_PROBE_TOOL_NAME tool exactly once with {"echo":"$expectedEcho"}. Do not answer with plain text."""

  private fun capabilityProbeTool(
    toolName: String,
    expectedEcho: String,
    strict: Boolean,
  ): LiteLlmToolDefinition = LiteLlmToolDefinition(
    name = toolName,
    description = "Validation-only probe tool. Call it exactly once with the provided echo string.",
    inputSchema = buildJsonObject {
      put("type", "object")
      put(
        "properties",
        buildJsonObject {
          put(
            "echo",
            buildJsonObject {
              put("type", "string")
              put("description", "Echo the exact validation token.")
              put(
                "enum",
                buildJsonArray {
                  add(JsonPrimitive(expectedEcho))
                },
              )
            },
          )
        },
      )
      put(
        "required",
        buildJsonArray {
          add(JsonPrimitive("echo"))
        },
      )
      put("additionalProperties", false)
    },
    strict = strict.takeIf { it },
  )

  private fun parallelCapabilityProbePrompt(): String =
    """Call both $PARALLEL_CAPABILITY_PROBE_TOOL_ONE and $PARALLEL_CAPABILITY_PROBE_TOOL_TWO exactly once in the same response. Do not answer with plain text."""

  private fun isCapabilityProbeToolCall(
    toolCall: LiteLlmStructuredToolCall,
    toolName: String,
    expectedEcho: String,
  ): Boolean {
    if (!toolCall.toolName.equals(toolName, ignoreCase = true)) {
      return false
    }
    val echoedValue = toolCall.arguments["echo"]?.toString()
      ?.trim()
      ?.removeSurrounding("\"")
    return echoedValue == null || echoedValue == expectedEcho
  }

  private fun verifiedCapabilitySnapshot(
    providerId: String,
    protocol: String,
    baseUrl: String,
    model: String,
    visionInputSupported: Boolean = false,
    pdfInputSupported: Boolean = false,
    nativeToolCallingAvailable: Boolean,
    toolChoiceSupported: Boolean = false,
    parallelToolCallsSupported: Boolean = false,
    strictToolSchemaSupported: Boolean = false,
    responsesContinuationSupported: Boolean = false,
    builtinWebSearchSupported: Boolean = false,
    assistantPhaseSupported: Boolean = false,
    citationIncludeSupported: Boolean = false,
  ): LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(
    routeFingerprint = com.opencray.app.llmRouteFingerprint(
      protocol = protocol,
      baseUrl = baseUrl,
      model = model,
    ),
    verifiedAtEpochMs = System.currentTimeMillis(),
    contextWindowTokens = LlmModelCapabilityRegistry.resolveContextWindow(
      providerId = providerId,
      protocol = protocol,
      model = model,
    ).contextWindowTokens,
    visionInputSupported = visionInputSupported,
    pdfInputSupported = pdfInputSupported,
    nativeToolCallingAvailable = nativeToolCallingAvailable,
    toolChoiceSupported = toolChoiceSupported,
    parallelToolCallsSupported = parallelToolCallsSupported,
    strictToolSchemaSupported = strictToolSchemaSupported,
    responsesContinuationSupported = responsesContinuationSupported,
    builtinWebSearchSupported = builtinWebSearchSupported,
    assistantPhaseSupported = assistantPhaseSupported,
    citationIncludeSupported = citationIncludeSupported,
  )

  private fun detectVisionInputSupport(
    providerId: String,
    protocol: String,
    model: String,
  ): Boolean = LlmModelCapabilityRegistry.resolveVisionInputSupport(
    providerId = providerId,
    protocol = protocol,
    model = model,
  )?.visionInputSupported == true

  private fun detectPdfInputSupport(
    providerId: String,
    protocol: String,
    model: String,
  ): Boolean = LlmModelCapabilityRegistry.resolvePdfInputSupport(
    providerId = providerId,
    protocol = protocol,
    model = model,
  )?.pdfInputSupported == true

  private fun resolvedCustomProviderName(
    requestedName: String,
    baseUrl: String,
  ): String {
    val trimmedName = requestedName.trim()
    if (trimmedName.isNotBlank()) {
      return trimmedName
    }
    val host = runCatching { URI(baseUrl.trim()).host.orEmpty() }.getOrDefault("")
    return host.ifBlank { strings.customProviderTitle }
  }

  private fun resolvedPromptCachingSettings(
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
  ): ResolvedPromptCachingSettings {
    val persisted = llmSettingsStore.load()
    return ResolvedPromptCachingSettings(
      openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy
        ?.let(LlmSettingsState::normalizedOpenAiPromptCacheKeyStrategy)
        ?: persisted.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = openAiPromptCacheRetention
        ?.let(LlmSettingsState::normalizedOpenAiPromptCacheRetention)
        ?: persisted.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = anthropicPromptCachingEnabled
        ?: persisted.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = anthropicPromptCacheTtl
        ?.let(LlmSettingsState::normalizedAnthropicPromptCacheTtl)
        ?: persisted.anthropicPromptCacheTtl,
    )
  }

  companion object {
    fun fromContext(context: Context): LlmConfigFacade {
      val localizedContext = OpenCrayLocaleManager.wrap(context.applicationContext)
      val localeTag = LocaleSettingsStore.fromContext(context.applicationContext).loadLanguage().tag
      return LocalLlmConfigFacade(
        llmSettingsStore = LlmSettingsStore.fromContext(context.applicationContext),
        providerClient = OpenAiCompatibleLiteLlmProviderClient(
          userAgent = OpenCrayUserAgent.fromContext(context.applicationContext),
        ),
        strings = localizedStrings(localizedContext, localeTag),
      )
    }

    internal fun createForTest(
      llmSettingsStore: LlmSettingsStore,
      providerClient: LiteLlmProviderClient,
    ): LlmConfigFacade = LocalLlmConfigFacade(
      llmSettingsStore = llmSettingsStore,
      providerClient = providerClient,
      strings = defaultStrings(),
    )

    private fun localizedStrings(context: Context, localeTag: String): LlmConfigStrings =
      LlmConfigStrings(
        localeTag = localeTag,
        helperText = context.getString(R.string.llm_settings_helper_text),
        customProviderTitle = context.getString(R.string.llm_provider_custom_title),
        openAiSubtitle = context.getString(R.string.llm_provider_openai_subtitle),
        deepSeekSubtitle = context.getString(R.string.llm_provider_deepseek_subtitle),
        openRouterSubtitle = context.getString(R.string.llm_provider_openrouter_subtitle),
        customProviderSubtitle = context.getString(R.string.llm_provider_custom_subtitle),
        validationSuccess = { model ->
          context.getString(R.string.llm_validation_success, model)
        },
        validationTimeout = { seconds ->
          context.getString(R.string.llm_validation_timeout, seconds)
        },
        validationRateLimited = context.getString(R.string.llm_validation_rate_limited),
        validationFailed = context.getString(R.string.llm_validation_failed),
        validationNativeToolsUnavailable = context.getString(R.string.llm_validation_native_tools_unavailable),
        baseUrlRequiredEnabled = context.getString(R.string.llm_error_base_url_required_enabled),
        baseUrlValidateRequired = context.getString(R.string.llm_error_base_url_validate_required),
        modelValidateRequired = context.getString(R.string.llm_error_model_validate_required),
        baseUrlInvalid = context.getString(R.string.llm_error_base_url_invalid),
        baseUrlScheme = context.getString(R.string.llm_error_base_url_scheme),
      )

    private fun defaultStrings(): LlmConfigStrings = LlmConfigStrings(
      localeTag = "en",
      helperText = "This build supports OpenAI-compatible and Anthropic endpoints. Base URL and API key must be ready before Chat can call the provider.",
      customProviderTitle = "Custom provider",
      openAiSubtitle = "Official OpenAI-compatible endpoint.",
      deepSeekSubtitle = "DeepSeek OpenAI-compatible API.",
      openRouterSubtitle = "OpenAI-compatible routing across multiple providers.",
      customProviderSubtitle = "Any OpenAI-compatible or Anthropic endpoint.",
      validationSuccess = { model -> "Connection verified for $model." },
      validationTimeout = { seconds -> "Validation timed out after $seconds seconds." },
      validationRateLimited = "Provider rate limited the validation request.",
      validationFailed = "Validation failed.",
      validationNativeToolsUnavailable = "Text connection works, but native tool calling could not be verified. This route will use JSON fallback until native tools are verified.",
      baseUrlRequiredEnabled = "Base URL is required when the provider is enabled.",
      baseUrlValidateRequired = "Base URL is required to validate the model.",
      modelValidateRequired = "Model is required to validate the model.",
      baseUrlInvalid = "Base URL must be a valid http or https URL.",
      baseUrlScheme = "Base URL must start with http:// or https://.",
    )

    private const val VALIDATION_PROFILE_ID: String = "profile-validation"
    private const val VALIDATION_PROMPT: String = "Reply with OK."
    private const val CAPABILITY_PROBE_TOOL_NAME: String = "capability_probe"
    private const val NATIVE_TOOL_PROBE_ECHO: String = "native_tool_probe"
    private const val TOOL_CONTROL_PROBE_ECHO: String = "tool_choice_probe"
    private const val STRICT_SCHEMA_PROBE_ECHO: String = "strict_schema_probe"
    private const val PARALLEL_CAPABILITY_PROBE_TOOL_ONE: String = "parallel_probe_one"
    private const val PARALLEL_CAPABILITY_PROBE_TOOL_TWO: String = "parallel_probe_two"
    private const val PARALLEL_TOOL_PROBE_ONE_ECHO: String = "parallel_tool_probe_one"
    private const val PARALLEL_TOOL_PROBE_TWO_ECHO: String = "parallel_tool_probe_two"
    private const val RESPONSES_CONTINUATION_TOKEN: String = "responses_continuation_probe_token"
  }
}

internal object EmptyLlmConfigFacade : LlmConfigFacade {
  override fun load(): LlmConfigSnapshot = LlmConfigSnapshot(
    localeTag = "en",
    enabled = false,
    streamingEnabled = LlmSettingsState.DEFAULT_STREAMING_ENABLED,
    providerId = "custom",
    selectedProviderOptionId = "custom",
    protocol = LlmProviderProtocols.OPENAI,
    providerOptions = LlmProviderCatalog.presets.map { preset ->
      LlmProviderOptionSnapshot(
        id = preset.id,
        providerId = preset.id,
        title = preset.title,
        subtitle = preset.subtitle,
        defaultBaseUrl = preset.defaultBaseUrl,
        defaultModel = preset.defaultModel,
        protocol = preset.defaultProtocol,
        apiKey = "",
        isCustom = preset.isCustom,
      )
    },
    providerName = "Custom provider",
    providerNotes = "",
    baseUrl = "",
    apiKey = "",
    model = "",
    reasoningEffort = LlmSettingsState.DEFAULT_REASONING_EFFORT,
    systemPrompt = "",
    openAiPromptCacheKeyStrategy = LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
    openAiPromptCacheRetention = LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
    anthropicPromptCachingEnabled = LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
    anthropicPromptCacheTtl = LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
    helperText = "LLM settings host support is unavailable.",
    agentCapability = LlmAgentCapabilitySnapshot(),
  )

  override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
    throw IllegalStateException("LLM settings host support is unavailable.")

  override fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot =
    throw IllegalStateException("LLM settings host support is unavailable.")

  override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult =
    LlmValidationResult(
      isSuccess = false,
      message = "LLM settings host support is unavailable.",
    )
}

private data class CapabilityProbeOutcome(
  val supported: Boolean,
  val result: LiteLlmGatewayResult? = null,
) {
  companion object {
    fun unsupported(): CapabilityProbeOutcome = CapabilityProbeOutcome(
      supported = false,
      result = null,
    )
  }
}

private data class ResolvedPromptCachingSettings(
  val openAiPromptCacheKeyStrategy: String,
  val openAiPromptCacheRetention: String,
  val anthropicPromptCachingEnabled: Boolean,
  val anthropicPromptCacheTtl: String,
)
