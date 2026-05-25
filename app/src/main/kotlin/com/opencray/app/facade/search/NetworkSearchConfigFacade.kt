package com.opencray.app.facade.search

import android.content.Context
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.WebSearchProviderId
import com.opencray.app.WebSearchSettingsStore
import com.opencray.app.WebSearchSlotConfig
import org.opencray.app.R

data class NetworkSearchSlotSnapshot(
  val id: String,
  val providerId: String,
  val label: String,
  val baseUrl: String,
  val model: String,
  val apiKey: String,
  val enabled: Boolean,
)

data class NetworkSearchConfigSnapshot(
  val localeTag: String,
  val title: String,
  val subtitle: String,
  val slots: List<NetworkSearchSlotSnapshot>,
)

data class SaveNetworkSearchSlotRequest(
  val id: String,
  val providerId: String,
  val label: String,
  val baseUrl: String,
  val model: String,
  val apiKey: String,
  val enabled: Boolean,
)

data class SaveNetworkSearchConfigRequest(
  val slots: List<SaveNetworkSearchSlotRequest>,
)

interface NetworkSearchConfigFacade {
  fun load(): NetworkSearchConfigSnapshot

  fun save(request: SaveNetworkSearchConfigRequest): NetworkSearchConfigSnapshot
}

internal class LocalNetworkSearchConfigFacade private constructor(
  private val settingsStore: WebSearchSettingsStore,
  private val strings: NetworkSearchConfigStrings,
) : NetworkSearchConfigFacade {
  override fun load(): NetworkSearchConfigSnapshot = snapshotFor(settingsStore.load())

  override fun save(request: SaveNetworkSearchConfigRequest): NetworkSearchConfigSnapshot {
    settingsStore.save(
      request.slots.map { slot ->
        WebSearchSlotConfig.create(
          id = slot.id,
          providerId = slot.providerId,
          label = slot.label,
          baseUrl = slot.baseUrl,
          model = slot.model,
          apiKey = slot.apiKey,
          enabled = slot.enabled,
        )
      },
    )
    return load()
  }

  private fun snapshotFor(slots: List<WebSearchSlotConfig>): NetworkSearchConfigSnapshot =
    NetworkSearchConfigSnapshot(
      localeTag = strings.localeTag,
      title = strings.title,
      subtitle = strings.subtitle,
      slots = slots.map { slot ->
        NetworkSearchSlotSnapshot(
          id = slot.id,
          providerId = WebSearchProviderId.fromWireValue(slot.providerId)?.wireValue
            ?: WebSearchProviderId.EXA.wireValue,
          label = slot.label,
          baseUrl = slot.baseUrl,
          model = slot.model,
          apiKey = slot.apiKey,
          enabled = slot.enabled,
        )
      },
    )

  companion object {
    fun fromContext(context: Context): NetworkSearchConfigFacade {
      val localizedContext = OpenCrayLocaleManager.wrap(context.applicationContext)
      val localeTag = LocaleSettingsStore.fromContext(context.applicationContext).loadLanguage().tag
      return LocalNetworkSearchConfigFacade(
        settingsStore = WebSearchSettingsStore.fromContext(context.applicationContext),
        strings = NetworkSearchConfigStrings(
          localeTag = localeTag,
          title = localizedContext.getString(R.string.settings_network_search_title),
          subtitle = localizedContext.getString(R.string.settings_network_search_subtitle),
        ),
      )
    }

    internal fun create(
      settingsStore: WebSearchSettingsStore,
    ): NetworkSearchConfigFacade = LocalNetworkSearchConfigFacade(
      settingsStore = settingsStore,
      strings = NetworkSearchConfigStrings(
        localeTag = "en",
        title = "Network & Search",
        subtitle = "Add API keys here. Enabled slots run top to bottom.",
      ),
    )
  }
}

internal data class NetworkSearchConfigStrings(
  val localeTag: String,
  val title: String,
  val subtitle: String,
)

internal object EmptyNetworkSearchConfigFacade : NetworkSearchConfigFacade {
  override fun load(): NetworkSearchConfigSnapshot = NetworkSearchConfigSnapshot(
    localeTag = "en",
    title = "Network & Search",
    subtitle = "Host support is unavailable.",
    slots = emptyList(),
  )

  override fun save(request: SaveNetworkSearchConfigRequest): NetworkSearchConfigSnapshot =
    throw IllegalStateException("Network search settings host support is unavailable.")
}
