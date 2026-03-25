package com.opencray.app

import com.opencray.runtime.web.ConfiguredWebSearchSlot
import com.opencray.runtime.web.SequentialWebSearchProvider
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import com.opencray.runtime.web.WebSearchProvider

internal object AppConfiguredWebSearchProviderFactory {
  fun create(
    slots: List<WebSearchSlotConfig>,
    userAgent: String,
  ): WebSearchProvider {
    val configuredSlots = slots
      .map(WebSearchSlotConfig::sanitized)
      .filter { slot ->
        slot.enabled && slot.apiKey.isNotBlank()
      }
      .map { slot ->
        ConfiguredWebSearchSlot(
          providerId = slot.providerId,
          baseUrl = slot.baseUrl,
          model = slot.model,
          apiKey = slot.apiKey,
          label = slot.label,
          enabled = slot.enabled,
        )
      }
    if (configuredSlots.isEmpty()) {
      return UnconfiguredWebSearchProvider
    }
    return SequentialWebSearchProvider(
      slots = configuredSlots,
      transport = com.opencray.runtime.web.HttpUrlWebSearchTransport(userAgent = userAgent),
    )
  }
}
