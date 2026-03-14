package com.opencray.app

import java.net.URI

internal data class LlmProviderPreset(
  val id: String,
  val title: String,
  val subtitle: String,
  val defaultBaseUrl: String,
  val defaultModel: String,
  val defaultProtocol: String = LlmProviderProtocols.OPENAI,
  val isCustom: Boolean = false,
)

internal object LlmProviderCatalog {
  private val presetList = listOf(
    LlmProviderPreset(
      id = "openai",
      title = "OpenAI",
      subtitle = "Official OpenAI-compatible endpoint.",
      defaultBaseUrl = "https://api.openai.com/v1",
      defaultModel = "gpt-4o-mini",
    ),
    LlmProviderPreset(
      id = "deepseek",
      title = "DeepSeek",
      subtitle = "DeepSeek OpenAI-compatible API.",
      defaultBaseUrl = "https://api.deepseek.com/v1",
      defaultModel = "deepseek-chat",
    ),
    LlmProviderPreset(
      id = "openrouter",
      title = "OpenRouter",
      subtitle = "OpenAI-compatible routing across multiple providers.",
      defaultBaseUrl = "https://openrouter.ai/api/v1",
      defaultModel = "openai/gpt-4o-mini",
    ),
    LlmProviderPreset(
      id = "custom",
      title = "Custom provider",
      subtitle = "Any OpenAI-compatible or Anthropic endpoint.",
      defaultBaseUrl = "",
      defaultModel = "",
      isCustom = true,
    ),
  )

  val presets: List<LlmProviderPreset>
    get() = presetList

  fun defaultPreset(): LlmProviderPreset = presetList.first()

  fun presetById(id: String?): LlmProviderPreset? =
    presetList.firstOrNull { preset -> preset.id == id?.trim() }

  fun inferPresetId(baseUrl: String): String {
    val host = runCatching { URI(baseUrl.trim()).host.orEmpty() }.getOrDefault("")
    return when {
      host.contains("openai.com", ignoreCase = true) -> "openai"
      host.contains("deepseek.com", ignoreCase = true) -> "deepseek"
      host.contains("openrouter.ai", ignoreCase = true) -> "openrouter"
      else -> "custom"
    }
  }

  fun displayNameFor(providerId: String, baseUrl: String): String {
    val preset = presetById(providerId)
    if (preset != null && !preset.isCustom) {
      return preset.title
    }
    val host = runCatching { URI(baseUrl.trim()).host.orEmpty() }.getOrDefault("")
    return when {
      preset != null -> preset.title
      host.isNotBlank() -> host
      else -> "Custom provider"
    }
  }
}
