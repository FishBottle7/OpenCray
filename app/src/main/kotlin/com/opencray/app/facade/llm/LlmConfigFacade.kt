package com.opencray.app.facade.llm

import android.content.Context
import com.opencray.app.LlmProviderCatalog
import com.opencray.app.LlmProviderPreset
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import java.net.URI

data class LlmProviderOptionSnapshot(
  val id: String,
  val title: String,
  val subtitle: String,
  val defaultBaseUrl: String,
  val defaultModel: String,
  val isCustom: Boolean,
)

data class LlmConfigSnapshot(
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

interface LlmConfigFacade {
  fun load(): LlmConfigSnapshot

  fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot

  fun validate(request: ValidateLlmConfigRequest): LlmValidationResult
}

internal class LocalLlmConfigFacade private constructor(
  private val llmSettingsStore: LlmSettingsStore,
  private val providerClient: LiteLlmProviderClient,
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
      throw IllegalArgumentException("Base URL is required when the provider is enabled.")
    }
    if (baseUrl.isNotBlank()) {
      requireValidBaseUrl(baseUrl)
    }
    val savedState = LlmSettingsState(
      enabled = request.enabled,
      providerId = providerPreset.id,
      protocol = protocol,
      providerName = request.providerName.trim().ifBlank {
        if (providerPreset.isCustom) "Custom provider" else providerPreset.title
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
      throw IllegalArgumentException("Base URL is required to validate the model.")
    }
    if (model.isBlank()) {
      throw IllegalArgumentException("Model is required to validate the model.")
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
        message = "Connection verified for $model.",
      )
      LiteLlmGatewayStatus.TIMEOUT -> LlmValidationResult(
        isSuccess = false,
        message = result.errorMessage?.ifBlank {
          "Validation timed out after ${VALIDATION_TIMEOUT_MS / 1000} seconds."
        } ?: "Validation timed out after ${VALIDATION_TIMEOUT_MS / 1000} seconds.",
      )
      LiteLlmGatewayStatus.RATE_LIMITED -> LlmValidationResult(
        isSuccess = false,
        message = result.errorMessage?.ifBlank {
          "Provider rate limited the validation request."
        } ?: "Provider rate limited the validation request.",
      )
      LiteLlmGatewayStatus.FAILED -> LlmValidationResult(
        isSuccess = false,
        message = result.errorMessage?.ifBlank { "Validation failed." } ?: "Validation failed.",
      )
    }
  }

  private fun snapshotFor(state: LlmSettingsState): LlmConfigSnapshot {
    val sanitized = state.sanitized()
    return LlmConfigSnapshot(
      enabled = sanitized.enabled,
      providerId = sanitized.providerId,
      protocol = sanitized.protocol,
      providerOptions = LlmProviderCatalog.presets.map(::toSnapshot),
      providerName = sanitized.providerName.ifBlank {
        LlmProviderCatalog.displayNameFor(
          providerId = sanitized.providerId,
          baseUrl = sanitized.baseUrl,
        )
      },
      providerNotes = sanitized.providerNotes,
      baseUrl = sanitized.baseUrl,
      apiKey = sanitized.apiKey,
      model = sanitized.model,
      reasoningEffort = sanitized.reasoningEffort,
      systemPrompt = sanitized.systemPrompt,
      helperText = "This build supports OpenAI-compatible and Anthropic endpoints. Base URL and API key must be ready before chat can call the provider.",
    )
  }

  private fun toSnapshot(preset: LlmProviderPreset): LlmProviderOptionSnapshot =
    LlmProviderOptionSnapshot(
      id = preset.id,
      title = preset.title,
      subtitle = preset.subtitle,
      defaultBaseUrl = preset.defaultBaseUrl,
      defaultModel = preset.defaultModel,
      isCustom = preset.isCustom,
    )

  private fun requireValidBaseUrl(baseUrl: String) {
    val parsed = runCatching { URI(baseUrl) }.getOrElse {
      throw IllegalArgumentException("Base URL must be a valid http or https URL.")
    }
    val scheme = parsed.scheme.orEmpty().lowercase()
    if (scheme != "http" && scheme != "https") {
      throw IllegalArgumentException("Base URL must start with http:// or https://.")
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
    fun fromContext(context: Context): LlmConfigFacade = LocalLlmConfigFacade(
      llmSettingsStore = LlmSettingsStore.fromContext(context.applicationContext),
      providerClient = OpenAiCompatibleLiteLlmProviderClient(),
    )

    internal fun createForTest(
      llmSettingsStore: LlmSettingsStore,
      providerClient: LiteLlmProviderClient,
    ): LlmConfigFacade = LocalLlmConfigFacade(
      llmSettingsStore = llmSettingsStore,
      providerClient = providerClient,
    )

    private const val VALIDATION_PROFILE_ID: String = "profile-validation"
    private const val VALIDATION_TIMEOUT_MS: Long = 15_000L
    private const val VALIDATION_PROMPT: String = "Reply with OK."
  }
}

internal object EmptyLlmConfigFacade : LlmConfigFacade {
  override fun load(): LlmConfigSnapshot = LlmConfigSnapshot(
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
