package com.opencray.app

import java.nio.file.Files
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLiveLlmTestConfigTest {
  @Test
  fun loadReturnsNullWhenConfiguredFileDoesNotExist() {
    val tempFile = Files.createTempDirectory("opencray-live-llm-config-test")
      .resolve("missing.json")
    withConfigPath(tempFile.toString()) {
      assertNull(LocalLiveLlmTestConfig.load())
    }
  }

  @Test
  fun loadParsesValidJsonConfigFromOverridePath() {
    val tempFile = Files.createTempFile("opencray-live-llm-config-", ".json")
    tempFile.writeText(
      """
      {
        "protocol": "openai",
        "baseUrl": "https://api.openai.com/v1",
        "apiKey": "sk-test",
        "model": "gpt-4.1-mini",
        "reasoningEffort": "high"
      }
      """.trimIndent(),
    )

    withConfigPath(tempFile.toString()) {
      val config = LocalLiveLlmTestConfig.load()
      requireNotNull(config)
      assertEquals("openai", config.protocol)
      assertEquals("https://api.openai.com/v1", config.baseUrl)
      assertEquals("sk-test", config.apiKey)
      assertEquals("gpt-4.1-mini", config.model)
      assertEquals("high", config.reasoningEffort)
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun loadFailsFastWhenRequiredFieldIsMissing() {
    val tempFile = Files.createTempFile("opencray-live-llm-config-", ".json")
    tempFile.writeText(
      """
      {
        "protocol": "openai",
        "baseUrl": "https://api.openai.com/v1",
        "apiKey": "",
        "model": "gpt-4.1-mini"
      }
      """.trimIndent(),
    )

    withConfigPath(tempFile.toString()) {
      LocalLiveLlmTestConfig.load()
    }
  }

  private fun withConfigPath(
    path: String,
    block: () -> Unit,
  ) {
    val previous = System.getProperty(LocalLiveLlmTestConfig.CONFIG_PATH_PROPERTY)
    System.setProperty(LocalLiveLlmTestConfig.CONFIG_PATH_PROPERTY, path)
    try {
      block()
    } finally {
      if (previous == null) {
        System.clearProperty(LocalLiveLlmTestConfig.CONFIG_PATH_PROPERTY)
      } else {
        System.setProperty(LocalLiveLlmTestConfig.CONFIG_PATH_PROPERTY, previous)
      }
    }
  }
}
