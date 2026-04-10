package com.opencray.app.facade.settings

import android.content.pm.PackageManager
import android.os.Build
import com.opencray.app.AppLanguage
import com.opencray.app.AppAgentWorkspace
import com.opencray.app.LlmProviderCatalog
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.app.LiteRtOnDeviceModelInstallStore
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.OnDeviceLlmCatalog
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.isOperationallyConfigured
import android.content.Context
import com.opencray.app.TelemetrySettingsStore
import com.opencray.app.TelemetryTogglesState
import com.opencray.app.WebSearchSlotConfig
import com.opencray.app.WebSearchSettingsStore
import com.opencray.app.WorkspaceSoulProfile
import com.opencray.app.WorkspaceSoulProfileStore
import java.net.URI
import java.nio.file.Path
import org.opencray.app.R

enum class SettingsRouteId(
  val wireValue: String,
) {
  NOTIFICATIONS_BACKGROUND("notifications_background"),
  NOTIFICATION_CHANNELS("notification_channels"),
  WORKSPACE_ACCESS("workspace_access"),
  LLM("llm"),
  MCP("mcp"),
  API_INTEGRATIONS("api_integrations"),
  NETWORK_SEARCH("network_search"),
  MEDIA_SPEECH("media_speech"),
  PRIVACY_TELEMETRY("privacy_telemetry"),
  SAFETY_LIMITS("safety_limits"),
  PERSONALIZATION("personalization"),
  AGENTS("agents"),
  ABOUT_VERSION("about_version"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): SettingsRouteId? =
      entries.firstOrNull { routeId -> routeId.wireValue == rawValue.trim() }
  }
}

enum class SettingsSectionBackgroundTone(
  val wireValue: String,
) {
  SURFACE("surface"),
  DANGER("danger"),
}

enum class SettingsRowTrailingKind(
  val wireValue: String,
) {
  CHEVRON("chevron"),
  TOGGLE("toggle"),
  VALUE("value"),
}

data class SettingsHomeEntrySnapshot(
  val routeId: SettingsRouteId,
  val title: String,
)

data class SettingsOverviewSnapshot(
  val eyebrow: String,
  val title: String,
  val subtitle: String,
  val deviceTitle: String,
  val deviceSummary: String,
  val entries: List<SettingsHomeEntrySnapshot>,
)

data class SettingsDetailSnapshot(
  val routeId: SettingsRouteId,
  val title: String,
  val subtitle: String,
  val sections: List<SettingsSectionSnapshot>,
)

data class SettingsSectionSnapshot(
  val title: String,
  val helperText: String? = null,
  val rows: List<SettingsRowSnapshot> = emptyList(),
  val segmentedOptions: List<String>? = null,
  val segmentedIndex: Int? = null,
  val inlinePanelText: String? = null,
  val backgroundTone: SettingsSectionBackgroundTone = SettingsSectionBackgroundTone.SURFACE,
)

data class SettingsRowSnapshot(
  val title: String,
  val subtitle: String?,
  val trailingKind: SettingsRowTrailingKind,
  val toggleValue: Boolean? = null,
  val valueLabel: String? = null,
) {
  companion object {
    fun chevron(
      title: String,
      subtitle: String? = null,
    ): SettingsRowSnapshot = SettingsRowSnapshot(
      title = title,
      subtitle = subtitle,
      trailingKind = SettingsRowTrailingKind.CHEVRON,
    )

    fun toggle(
      title: String,
      subtitle: String? = null,
      toggleValue: Boolean,
    ): SettingsRowSnapshot = SettingsRowSnapshot(
      title = title,
      subtitle = subtitle,
      trailingKind = SettingsRowTrailingKind.TOGGLE,
      toggleValue = toggleValue,
    )

    fun value(
      title: String,
      valueLabel: String,
    ): SettingsRowSnapshot = SettingsRowSnapshot(
      title = title,
      subtitle = null,
      trailingKind = SettingsRowTrailingKind.VALUE,
      valueLabel = valueLabel,
    )
  }
}

interface SettingsFacade {
  fun loadOverview(): SettingsOverviewSnapshot

  fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot
}

internal class LocalSettingsFacade(
  private val context: Context,
  private val llmSettingsStore: LlmSettingsStore,
  private val onDeviceModelInstallStore: LiteRtOnDeviceModelInstallStore,
  private val localeSettingsStore: LocaleSettingsStore,
  private val telemetrySettingsStore: TelemetrySettingsStore,
  private val soulProfileStore: WorkspaceSoulProfileStore,
  private val workspaceRootProvider: () -> Path,
  private val webSearchSettingsStore: WebSearchSettingsStore,
) : SettingsFacade {
  override fun loadOverview(): SettingsOverviewSnapshot {
    return SettingsOverviewSnapshot(
      eyebrow = "APP SHELL",
      title = context.getString(R.string.shell_tab_settings),
      subtitle = context.getString(R.string.settings_home_intro),
      deviceTitle = context.getString(R.string.settings_home_profile_title),
      deviceSummary = context.getString(R.string.settings_home_profile_meta_api_routes),
      entries = listOf(
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.NOTIFICATIONS_BACKGROUND,
          title = context.getString(R.string.settings_card_notifications_background),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.WORKSPACE_ACCESS,
          title = context.getString(R.string.settings_card_workspace_access),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.LLM,
          title = context.getString(R.string.settings_card_llm),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.MCP,
          title = context.getString(R.string.settings_card_mcp),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.API_INTEGRATIONS,
          title = context.getString(R.string.settings_card_api_integrations),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.SAFETY_LIMITS,
          title = context.getString(R.string.settings_card_safety_limits),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.PERSONALIZATION,
          title = context.getString(R.string.settings_card_personalization),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.AGENTS,
          title = context.getString(R.string.settings_card_agents),
        ),
        SettingsHomeEntrySnapshot(
          routeId = SettingsRouteId.ABOUT_VERSION,
          title = context.getString(R.string.settings_card_about_version),
        ),
      ),
    )
  }

  override fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot = when (routeId) {
    SettingsRouteId.NOTIFICATIONS_BACKGROUND -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_notifications_background),
      subtitle = context.getString(R.string.settings_notifications_background_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Notifications",
          helperText = "Notification and background controls are rendered by the Flutter settings page.",
        ),
      ),
    )
    SettingsRouteId.NOTIFICATION_CHANNELS -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_notification_channels_title),
      subtitle = context.getString(R.string.settings_notification_channels_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Notification channels",
          helperText = "Notification channel controls are rendered by the Flutter settings page.",
        ),
      ),
    )
    SettingsRouteId.WORKSPACE_ACCESS -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_workspace_access),
      subtitle = context.getString(R.string.settings_workspace_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Workspace status",
          helperText = context.getString(R.string.workspace_settings_detail_no_grant),
        ),
        SettingsSectionSnapshot(
          title = "Current behavior",
          helperText = "Live workspace grant state is not wired into Settings yet.",
        ),
      ),
    )
    SettingsRouteId.LLM -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_llm),
      subtitle = context.getString(R.string.settings_llm_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Provider status",
          rows = listOf(
            SettingsRowSnapshot.value(
              title = context.getString(R.string.llm_settings_section_status),
              valueLabel = llmStatusLabel(llmState()),
            ),
            SettingsRowSnapshot.value(title = "Endpoint", valueLabel = providerLabel(llmState())),
          ),
        ),
        SettingsSectionSnapshot(
          title = "Model defaults",
          rows = listOf(
            SettingsRowSnapshot.value(title = "Model", valueLabel = llmState().model.ifBlank { "Not set" }),
            SettingsRowSnapshot.value(
              title = "API key",
              valueLabel = if (llmState().apiKey.isBlank()) "Optional" else "Configured",
            ),
            SettingsRowSnapshot.value(
              title = "System prompt",
              valueLabel = if (llmState().systemPrompt.isBlank()) "Default" else "Custom",
            ),
          ),
        ),
      ),
    )
    SettingsRouteId.MCP -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_mcp),
      subtitle = context.getString(R.string.settings_mcp_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Registry state",
          helperText = context.getString(R.string.mcp_settings_guidance_body),
        ),
        SettingsSectionSnapshot(
          title = "Current host bridge",
          rows = listOf(
            SettingsRowSnapshot.chevron(
              title = context.getString(R.string.mcp_settings_guidance_title),
              subtitle = "A live MCP registry snapshot is not wired into Settings yet.",
            ),
          ),
        ),
      ),
    )
    SettingsRouteId.API_INTEGRATIONS -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_api_integrations),
      subtitle = context.getString(R.string.settings_api_integrations_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Routing rules",
          helperText = "Search keeps ordered slots. Media uses external APIs, while STT can switch between a hosted API and an on-device model package.",
        ),
      ),
    )
    SettingsRouteId.NETWORK_SEARCH,
    SettingsRouteId.PRIVACY_TELEMETRY,
    -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_network_search_title),
      subtitle = context.getString(R.string.settings_network_search_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Search slots",
          helperText = "Configured search keys run from top to bottom when fallback is needed.",
          rows = listOf(
            SettingsRowSnapshot.value(
              title = "Active slots",
              valueLabel = searchProfileLabel(webSearchSettingsStore.load()),
            ),
          ),
        ),
      ),
    )
    SettingsRouteId.MEDIA_SPEECH -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_media_speech_title),
      subtitle = context.getString(R.string.settings_media_speech_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Generation services",
          helperText = "Image and voice generation both use external API routes.",
        ),
        SettingsSectionSnapshot(
          title = "Speech-to-text",
          helperText = "Switch between a hosted API route and an on-device model package.",
        ),
      ),
    )
    SettingsRouteId.SAFETY_LIMITS -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_safety_limits),
      subtitle = context.getString(R.string.settings_safety_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Current policy surface",
          helperText = "Safety mode settings are not yet persisted as a host snapshot.",
        ),
        SettingsSectionSnapshot(
          title = "Guardrails",
          rows = listOf(
            SettingsRowSnapshot.chevron(
              title = "Approval and protected-path rules",
              subtitle = "Mutating actions can still pause for review and protected paths stay blocked.",
            ),
          ),
        ),
      ),
    )
    SettingsRouteId.PERSONALIZATION -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_personalization),
      subtitle = context.getString(R.string.settings_personalization_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Tone preset",
          segmentedOptions = listOf("QUIET", "FOCUS", "WARM"),
          segmentedIndex = personalizationToneIndex(personalizationProfile()),
          helperText = "Tone presets shape reply style and pacing.",
        ),
        SettingsSectionSnapshot(
          title = "Free editing",
          helperText = "Write your own lasting guidance.",
          inlinePanelText = personalizationGuidance(personalizationProfile()),
        ),
        SettingsSectionSnapshot(
          title = "Behavior defaults",
          rows = listOf(
            SettingsRowSnapshot.value(
              title = "Profile label",
              valueLabel = personalizationLabel(personalizationProfile()),
            ),
            SettingsRowSnapshot.value(
              title = "App language",
              valueLabel = appLanguageLabel(localeSettingsStore.loadLanguage()),
            ),
          ),
        ),
        SettingsSectionSnapshot(
          title = "Danger zone",
          helperText = "Typed confirmation is required before either reset runs.",
          backgroundTone = SettingsSectionBackgroundTone.DANGER,
        ),
      ),
    )
    SettingsRouteId.AGENTS -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_agents),
      subtitle = "Browse saved agents and create a new one.",
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Dedicated editor",
          helperText = "Agent configuration is rendered by the Flutter prototype page.",
        ),
      ),
    )
    SettingsRouteId.ABOUT_VERSION -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_about_version),
      subtitle = context.getString(R.string.settings_about_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = "Build details",
          rows = listOf(
            SettingsRowSnapshot.value(
              title = context.getString(R.string.about_installed_version_label),
              valueLabel = installedVersionName(),
            ),
            SettingsRowSnapshot.value(
              title = context.getString(R.string.about_build_number_label),
              valueLabel = installedVersionCode().toString(),
            ),
            SettingsRowSnapshot.value(
              title = context.getString(R.string.about_min_android_label),
              valueLabel = minimumAndroidLabel(),
            ),
          ),
        ),
        SettingsSectionSnapshot(
          title = "Device runtime",
          rows = listOf(
            SettingsRowSnapshot.value(title = "Android release", valueLabel = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" }),
            SettingsRowSnapshot.value(title = "API level", valueLabel = Build.VERSION.SDK_INT.toString()),
          ),
        ),
      ),
    )
  }

  private fun llmState(): LlmSettingsState = llmSettingsStore.load()

  private fun personalizationProfile(): WorkspaceSoulProfile? =
    soulProfileStore.loadSoulProfile(workspaceRootProvider())

  private fun llmStatusLabel(state: LlmSettingsState): String = when {
    state.isOperationallyConfigured(onDeviceModelInstallStore) ->
      context.getString(R.string.llm_settings_status_configured)
    else -> context.getString(R.string.llm_settings_status_incomplete)
  }

  private fun providerLabel(state: LlmSettingsState): String {
    if (state.isOnDeviceProviderMode()) {
      return OnDeviceLlmCatalog.titleFor(state.selectedOnDeviceModelId)
    }
    if (state.baseUrl.isBlank()) {
      return "Not set"
    }
    return LlmProviderCatalog.displayNameFor(
      providerId = state.providerId,
      baseUrl = state.baseUrl,
    )
  }

  private fun searchProfileLabel(slots: List<WebSearchSlotConfig>): String {
    if (slots.isEmpty()) {
      return "No slots"
    }
    val enabledCount = slots.count(WebSearchSlotConfig::enabled)
    val configuredCount = slots.count { slot -> slot.apiKey.isNotBlank() }
    return "$enabledCount enabled · $configuredCount configured"
  }

  private fun personalizationToneIndex(
    profile: WorkspaceSoulProfile?,
  ): Int = when (profile?.presetName?.trim()?.uppercase()) {
    "BUILDER" -> 1
    "WARM" -> 2
    else -> 0
  }

  private fun personalizationGuidance(
    profile: WorkspaceSoulProfile?,
  ): String = profile?.customGuidance?.trim().orEmpty().ifBlank {
    "No custom guidance has been saved on this device."
  }

  private fun appLanguageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ENGLISH -> context.getString(R.string.settings_language_english)
    AppLanguage.SIMPLIFIED_CHINESE -> context.getString(R.string.settings_language_simplified_chinese)
  }

  private fun installedVersionName(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return packageInfo.versionName?.trim().orEmpty().ifBlank { "0" }
  }

  private fun installedVersionCode(): Long {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      packageInfo.versionCode.toLong()
    }
  }

  private fun minimumAndroidLabel(): String {
    val minSdk = context.applicationInfo.minSdkVersion
    val releaseLabel = when (minSdk) {
      26 -> context.getString(R.string.shell_android_release_26)
      27 -> context.getString(R.string.shell_android_release_27)
      28 -> context.getString(R.string.shell_android_release_28)
      29 -> context.getString(R.string.shell_android_release_29)
      30 -> context.getString(R.string.shell_android_release_30)
      31 -> context.getString(R.string.shell_android_release_31)
      32 -> context.getString(R.string.shell_android_release_32)
      33 -> context.getString(R.string.shell_android_release_33)
      34 -> context.getString(R.string.shell_android_release_34)
      else -> context.getString(R.string.shell_android_release_generic)
    }
    return context.getString(R.string.shell_android_api_label, releaseLabel, minSdk)
  }

  private fun personalizationLabel(
    profile: WorkspaceSoulProfile?,
  ): String {
    val customLabel = profile?.customLabel?.trim().orEmpty()
    if (customLabel.isNotEmpty()) {
      return customLabel
    }
    return when (profile?.presetName?.trim()?.uppercase()) {
      "BUILDER" -> context.getString(R.string.settings_tone_focus)
      "WARM" -> context.getString(R.string.settings_tone_warm)
      else -> context.getString(R.string.settings_tone_quiet)
    }
  }

  companion object {
    fun fromContext(context: Context): SettingsFacade = LocalSettingsFacade(
      context = OpenCrayLocaleManager.wrap(context.applicationContext),
      llmSettingsStore = LlmSettingsStore.fromContext(context),
      onDeviceModelInstallStore = LiteRtOnDeviceModelInstallStore.fromContext(context),
      localeSettingsStore = LocaleSettingsStore.fromContext(context),
      telemetrySettingsStore = TelemetrySettingsStore.fromContext(context),
      soulProfileStore = WorkspaceSoulProfileStore(),
      workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(context.applicationContext) },
      webSearchSettingsStore = WebSearchSettingsStore.fromContext(context),
    )
  }
}
