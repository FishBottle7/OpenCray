package com.opencray.app

import com.opencray.runtime.web.SequentialWebSearchProvider
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfiguredWebSearchProviderFactoryTest {
  @Test
  fun returnsUnconfiguredProviderWhenNoEnabledKeyExists() {
    val provider = AppConfiguredWebSearchProviderFactory.create(
      slots = listOf(
        WebSearchSlotConfig.create(
          providerId = "exa",
          apiKey = "",
          enabled = true,
        ),
        WebSearchSlotConfig.create(
          providerId = "brave",
          apiKey = "ignored",
          enabled = false,
        ),
      ),
      userAgent = "OpenCray-Test/1.0",
    )

    assertTrue(provider === UnconfiguredWebSearchProvider)
  }

  @Test
  fun returnsSequentialProviderWhenEnabledKeyExists() {
    val provider = AppConfiguredWebSearchProviderFactory.create(
      slots = listOf(
        WebSearchSlotConfig.create(
          providerId = "tavily",
          apiKey = "tavily-secret",
          enabled = true,
        ),
      ),
      userAgent = "OpenCray-Test/1.0",
    )

    assertTrue(provider is SequentialWebSearchProvider)
    assertEquals("configured-web-search", provider.providerName)
  }
}
