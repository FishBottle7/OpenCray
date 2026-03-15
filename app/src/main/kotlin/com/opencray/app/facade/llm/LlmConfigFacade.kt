package com.opencray.app.facade.llm

import android.content.Context
import com.opencray.app.LlmProviderCatalog
import com.opencray.app.LlmProviderPreset
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.OpenCrayUserAgent
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import java.net.URI
import org.opencray.app.R

data class LlmProviderOptionSnapshot(
  val id: String,
  val title: String,
  val subtitle: String,
  val defaultBaseUrl: String,
  val defaultModel: String,
  val isCustom: Boolean,
)

data class LlmConfigSnapshot(
  val localeTag: String,
  val enabled: Boolean,
  val providerId: String,
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
)

data class SaveLlmConfigRequest(
  val enabled: Boolean,
  val providerId: String,
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
  val baseUrlRequiredEnabled: String,
  val baseUrlValidateRequired: String,
  val modelValidateRequired: String,
  val baseUrlInvalid: String,
  val baseUrlScheme: String,
)

interface LlmConfigFacade {
  fun load(): LlmConfigSnapshot

  fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot

  fun validate(request: ValidateLlmConfigRequest): LlmValidationResult
}

internal class LocalLlmConfigFacade private constructor(
  private val llmSettingsStore: LlmSettingsStore,
  private val providerClient: LiteLlmProviderClient,
  private val strings: LlmConfigStrings,
) : LlmConfigFacade {
  override fun load(): LlmConfigSnapshot = snapshotFor(llmSettingsStore.load())

  override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot {
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
    val savedState = LlmSettingsState(
      enabled = request.enabled,
      providerId = providerPreset.id,
      protocol = protocol,
      providerName = request.providerName.trim().ifBlank {
        localizedProviderTitle(providerPreset)
      },
      providerNotes = request.providerNotes.trim(),
      baseUrl = baseUrl,
      apiKey = request.apiKey.trim(),
      model = model,
      reasoningEffort = request.reasoningEffort.trim().ifBlank {
        LlmSettingsState.DEFAULT_REASONING_EFFORT
      },
      systemPrompt = request.systemPrompt.trim(),
    ).sanitized()
    llmSettingsStore.save(savedState)
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
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        prompt = VALIDATION_PROMPT,
        metadata = mapOf("source" to "settings_validation"),
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = protocol,
          apiKey = request.apiKey,
        ),
      ),
    )
    return when (result.status) {
      LiteLlmGatewayStatus.SUCCESS -> LlmValidationResult(
        isSuccess = true,
        message = strings.validationSuccess(model),
      )
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
  }

  private fun snapshotFor(state: LlmSettingsState): LlmConfigSnapshot {
    val sanitized = state.sanitized()
    return LlmConfigSnapshot(
      localeTag = strings.localeTag,
      enabled = sanitized.enabled,
      providerId = sanitized.providerId,
      protocol = sanitized.protocol,
      providerOptions = LlmProviderCatalog.presets.map(::toSnapshot),
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
    )
  }

  private fun toSnapshot(preset: LlmProviderPreset): LlmProviderOptionSnapshot =
    LlmProviderOptionSnapshot(
      id = preset.id,
      title = localizedProviderTitle(preset),
      subtitle = localizedProviderSubtitle(preset),
      defaultBaseUrl = preset.defaultBaseUrl,
      defaultModel = preset.defaultModel,
      isCustom = preset.isCustom,
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
      baseUrlRequiredEnabled = "Base URL is required when the provider is enabled.",
      baseUrlValidateRequired = "Base URL is required to validate the model.",
      modelValidateRequired = "Model is required to validate the model.",
      baseUrlInvalid = "Base URL must be a valid http or https URL.",
      baseUrlScheme = "Base URL must start with http:// or https://.",
    )

    private const val VALIDATION_PROFILE_ID: String = "profile-validation"
    private const val VALIDATION_TIMEOUT_MS: Long = 15_000L
    private const val VALIDATION_PROMPT: String = "Reply with OK."
  }
}

internal object EmptyLlmConfigFacade : LlmConfigFacade {
  override fun load(): LlmConfigSnapshot = LlmConfigSnapshot(
    localeTag = "en",
    enabled = false,
    providerId = "custom",
    protocol = LlmProviderProtocols.OPENAI,
    providerOptions = LlmProviderCatalog.presets.map { preset ->
      LlmProviderOptionSnapshot(
        id = preset.id,
        title = preset.title,
        subtitle = preset.subtitle,
        defaultBaseUrl = preset.defaultBaseUrl,
        defaultModel = preset.defaultModel,
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
  )

  override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
    throw IllegalStateException("LLM settings host support is unavailable.")

  override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult =
    LlmValidationResult(
      isSuccess = false,
      message = "LLM settings host support is unavailable.",
    )
}
