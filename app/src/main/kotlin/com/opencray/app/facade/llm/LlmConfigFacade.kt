package com.opencray.app.facade.llm

import android.content.Context
import com.opencray.app.LlmAgentCapabilitySnapshot
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
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
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
  val helperText: String,
  val agentCapability: LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(),
)

data class SaveLlmConfigRequest(
  val enabled: Boolean,
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
)

data class SaveCustomLlmProviderRequest(
  val selectedProviderOptionId: String,
  val protocol: String,
  val providerName: String,
  val providerNotes: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String,
  val systemPrompt: String,
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

    val route = ProviderRoute(
      id = "validate-${providerPreset.id}",
      providerId = providerPreset.id,
      baseUrl = baseUrl,
      model = model,
      timeoutMs = VALIDATION_TIMEOUT_MS,
      metadata = validationMetadataFor(
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
    )?.let { failure ->
      return failure
    }
    val nativeProbe = executeCapabilityProbe(
      gateway = gateway,
      authHeaders = authHeaders,
      stage = "native_tool_probe",
      expectedEcho = NATIVE_TOOL_PROBE_ECHO,
    )
    val capability = if (!nativeProbe.supported) {
      verifiedCapabilitySnapshot(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
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
      verifiedCapabilitySnapshot(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        nativeToolCallingAvailable = true,
        toolChoiceSupported = controlProbe.supported,
        parallelToolCallsSupported = controlProbe.supported,
        strictToolSchemaSupported = strictProbe.supported,
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
    protocol: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, String> = LlmProviderProtocols.routeMetadata(
    protocol = protocol,
    model = model,
    reasoningEffort = reasoningEffort,
  )

  private fun resolvedProtocol(
    providerPreset: LlmProviderPreset,
    requestedProtocol: String,
  ): String = if (providerPreset.isCustom) {
    LlmProviderProtocols.normalize(requestedProtocol)
  } else {
    providerPreset.defaultProtocol
  }

  private fun resolvedStateFromRequest(request: SaveLlmConfigRequest): LlmSettingsState {
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
    return LlmSettingsState(
      enabled = request.enabled,
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
    toolChoice: LiteLlmToolChoice? = null,
    parallelToolCalls: Boolean? = null,
  ): LiteLlmGatewayResult = gateway.execute(
    LiteLlmGatewayRequest(
      prompt = prompt,
      tools = tools,
      toolChoice = toolChoice,
      parallelToolCalls = parallelToolCalls,
      metadata = mapOf(
        "source" to "settings_validation",
        "validationStage" to stage,
      ),
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
      tools = listOf(capabilityProbeTool(expectedEcho = expectedEcho, strict = strict)),
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

  private fun validationFailureFor(result: LiteLlmGatewayResult): LlmValidationResult? = when (result.status) {
    LiteLlmGatewayStatus.SUCCESS -> null
    LiteLlmGatewayStatus.TIMEOUT -> LlmValidationResult(
      isSuccess = false,
      message = result.errorMessage?.ifBlank {
        strings.validationTimeout(VALIDATION_TIMEOUT_MS / 1000)
      } ?: strings.validationTimeout(VALIDATION_TIMEOUT_MS / 1000),
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
    expectedEcho: String,
    strict: Boolean,
  ): LiteLlmToolDefinition = LiteLlmToolDefinition(
    name = CAPABILITY_PROBE_TOOL_NAME,
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

  private fun isCapabilityProbeToolCall(
    toolCall: LiteLlmStructuredToolCall,
    expectedEcho: String,
  ): Boolean {
    if (!toolCall.toolName.equals(CAPABILITY_PROBE_TOOL_NAME, ignoreCase = true)) {
      return false
    }
    val echoedValue = toolCall.arguments["echo"]?.toString()
      ?.trim()
      ?.removeSurrounding("\"")
    return echoedValue == null || echoedValue == expectedEcho
  }

  private fun verifiedCapabilitySnapshot(
    protocol: String,
    baseUrl: String,
    model: String,
    nativeToolCallingAvailable: Boolean,
    toolChoiceSupported: Boolean = false,
    parallelToolCallsSupported: Boolean = false,
    strictToolSchemaSupported: Boolean = false,
  ): LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(
    routeFingerprint = com.opencray.app.llmRouteFingerprint(
      protocol = protocol,
      baseUrl = baseUrl,
      model = model,
    ),
    verifiedAtEpochMs = System.currentTimeMillis(),
    nativeToolCallingAvailable = nativeToolCallingAvailable,
    toolChoiceSupported = toolChoiceSupported,
    parallelToolCallsSupported = parallelToolCallsSupported,
    strictToolSchemaSupported = strictToolSchemaSupported,
  )

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
    private const val VALIDATION_TIMEOUT_MS: Long = 15_000L
    private const val VALIDATION_PROMPT: String = "Reply with OK."
    private const val CAPABILITY_PROBE_TOOL_NAME: String = "capability_probe"
    private const val NATIVE_TOOL_PROBE_ECHO: String = "native_tool_probe"
    private const val TOOL_CONTROL_PROBE_ECHO: String = "tool_choice_probe"
    private const val STRICT_SCHEMA_PROBE_ECHO: String = "strict_schema_probe"
  }
}

internal object EmptyLlmConfigFacade : LlmConfigFacade {
  override fun load(): LlmConfigSnapshot = LlmConfigSnapshot(
    localeTag = "en",
    enabled = false,
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
