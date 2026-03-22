package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal data class LocalLiveLlmTestConfig(
  val protocol: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String,
) {
  fun toSettingsState(): LlmSettingsState = LlmSettingsState(
    enabled = true,
    providerId = LlmSettingsState.inferProviderId(baseUrl),
    protocol = LlmProviderProtocols.normalize(protocol),
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = reasoningEffort,
  )

  companion object {
    const val CONFIG_PATH_PROPERTY: String = "opencray.liveLlmConfig"
    const val CONFIG_PATH_ENV: String = "OPENCRAY_LIVE_LLM_CONFIG"
    const val DEFAULT_CONFIG_RELATIVE_PATH: String = ".opencray/live-llm-test-config.json"

    fun load(): LocalLiveLlmTestConfig? = load(resolveConfiguredPath())

    fun defaultConfigPath(): Path = workspaceRoot().resolve(DEFAULT_CONFIG_RELATIVE_PATH).normalize()

    private fun load(path: Path): LocalLiveLlmTestConfig? {
      if (!Files.exists(path)) {
        return null
      }
      val payload = runCatching {
        JSONObject(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      }.getOrElse { error ->
        throw IllegalStateException(
          "Local live LLM test config at '$path' is not valid JSON: ${error.message}",
          error,
        )
      }
      val protocol = payload.optString("protocol", LlmProviderProtocols.OPENAI)
        .trim()
        .ifBlank { LlmProviderProtocols.OPENAI }
      val baseUrl = payload.optString("baseUrl").trim()
      val apiKey = payload.optString("apiKey").trim()
      val model = payload.optString("model").trim()
      val reasoningEffort = payload.optString(
        "reasoningEffort",
        LlmSettingsState.DEFAULT_REASONING_EFFORT,
      ).trim().ifBlank {
        LlmSettingsState.DEFAULT_REASONING_EFFORT
      }
      require(baseUrl.isNotBlank()) {
        "Local live LLM test config at '$path' is missing a non-blank 'baseUrl'."
      }
      require(apiKey.isNotBlank()) {
        "Local live LLM test config at '$path' is missing a non-blank 'apiKey'."
      }
      require(model.isNotBlank()) {
        "Local live LLM test config at '$path' is missing a non-blank 'model'."
      }
      return LocalLiveLlmTestConfig(
        protocol = protocol,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
      )
    }

    private fun resolveConfiguredPath(): Path {
      val override = System.getProperty(CONFIG_PATH_PROPERTY)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: System.getenv(CONFIG_PATH_ENV)
          ?.trim()
          ?.takeIf(String::isNotBlank)
      if (override != null) {
        return Paths.get(override).toAbsolutePath().normalize()
      }
      return defaultConfigPath()
    }

    private fun workspaceRoot(): Path {
      var current: Path? = Paths.get("").toAbsolutePath().normalize()
      while (current != null) {
        val hasSettingsGradle = Files.exists(current.resolve("settings.gradle.kts")) ||
          Files.exists(current.resolve("settings.gradle"))
        val hasAppModule = Files.isDirectory(current.resolve("app"))
        if (hasSettingsGradle && hasAppModule) {
          return current
        }
        current = current.parent
      }
      return Paths.get("").toAbsolutePath().normalize()
    }
  }
}
