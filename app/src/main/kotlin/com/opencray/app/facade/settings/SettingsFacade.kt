package com.opencray.app.facade.settings

import android.content.pm.PackageManager
import android.os.Build
import com.opencray.app.AppLanguage
import com.opencray.app.LlmProviderCatalog
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.OpenCrayLocaleManager
import android.content.Context
import com.opencray.app.PersonalizationLocalStore
import com.opencray.app.TelemetrySettingsStore
import com.opencray.app.TelemetryTogglesState
import java.net.URI
import org.opencray.app.R

enum class SettingsRouteId(
  val wireValue: String,
) {
  WORKSPACE_ACCESS("workspace_access"),
  LLM("llm"),
  MCP("mcp"),
  PRIVACY_TELEMETRY("privacy_telemetry"),
  SAFETY_LIMITS("safety_limits"),
  PERSONALIZATION("personalization"),
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
  private val localeSettingsStore: LocaleSettingsStore,
  private val telemetrySettingsStore: TelemetrySettingsStore,
  private val personalizationLocalStore: PersonalizationLocalStore,
) : SettingsFacade {
  override fun loadOverview(): SettingsOverviewSnapshot {
    val telemetryState = telemetrySettingsStore.load(TelemetryTogglesState.localized(context))
    val soulProfile = personalizationLocalStore.loadSoulProfile()
    return SettingsOverviewSnapshot(
      eyebrow = "APP SHELL",
      title = context.getString(R.string.shell_tab_settings),
      subtitle = context.getString(R.string.settings_home_intro),
      deviceTitle = context.getString(R.string.settings_home_profile_title),
      deviceSummary = context.getString(
        R.string.settings_home_profile_meta,
        personalizationLabel(soulProfile),
        telemetryProfileLabel(telemetryState),
      ),
      entries = listOf(
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
          routeId = SettingsRouteId.PRIVACY_TELEMETRY,
          title = context.getString(R.string.settings_card_privacy_telemetry),
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
          routeId = SettingsRouteId.ABOUT_VERSION,
          title = context.getString(R.string.settings_card_about_version),
        ),
      ),
    )
  }

  override fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot = when (routeId) {
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
    SettingsRouteId.PRIVACY_TELEMETRY -> SettingsDetailSnapshot(
      routeId = routeId,
      title = context.getString(R.string.settings_card_privacy_telemetry),
      subtitle = context.getString(R.string.settings_privacy_subtitle),
      sections = listOf(
        SettingsSectionSnapshot(
          title = telemetryState().telemetry.title,
          helperText = telemetryState().defaultsDisclosure,
          rows = listOf(
            SettingsRowSnapshot.toggle(
              title = telemetryState().telemetry.switchLabel,
              subtitle = if (telemetryState().telemetry.isChecked) {
                telemetryState().telemetry.enabledSummary
              } else {
                telemetryState().telemetry.disabledSummary
              },
              toggleValue = telemetryState().telemetry.isChecked,
            ),
          ),
        ),
        SettingsSectionSnapshot(
          title = telemetryState().privacyGuard.title,
          helperText = telemetryState().localRetentionDisclosure,
          rows = listOf(
            SettingsRowSnapshot.toggle(
              title = telemetryState().privacyGuard.switchLabel,
              subtitle = if (telemetryState().privacyGuard.isChecked) {
                telemetryState().privacyGuard.enabledSummary
              } else {
                telemetryState().privacyGuard.disabledSummary
              },
              toggleValue = telemetryState().privacyGuard.isChecked,
            ),
          ),
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

  private fun telemetryState(): TelemetryTogglesState =
    telemetrySettingsStore.load(TelemetryTogglesState.localized(context))

  private fun personalizationProfile(): PersonalizationLocalStore.SoulProfile? =
    personalizationLocalStore.loadSoulProfile()

  private fun llmStatusLabel(state: LlmSettingsState): String = when {
    state.isConfigured() -> context.getString(R.string.llm_settings_status_configured)
    else -> context.getString(R.string.llm_settings_status_incomplete)
  }

  private fun providerLabel(state: LlmSettingsState): String {
    if (state.baseUrl.isBlank()) {
      return "Not set"
    }
    return LlmProviderCatalog.displayNameFor(
      providerId = state.providerId,
      baseUrl = state.baseUrl,
    )
  }

  private fun personalizationToneIndex(
    profile: PersonalizationLocalStore.SoulProfile?,
  ): Int = when (profile?.presetName?.trim()?.uppercase()) {
    "BUILDER" -> 1
    "WARM" -> 2
    else -> 0
  }

  private fun personalizationGuidance(
    profile: PersonalizationLocalStore.SoulProfile?,
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

  private fun telemetryProfileLabel(state: TelemetryTogglesState): String =
    if (state.telemetry.isChecked) {
      context.getString(R.string.settings_telemetry_profile_active)
    } else {
      context.getString(R.string.settings_telemetry_profile_minimal)
    }

  private fun personalizationLabel(
    profile: PersonalizationLocalStore.SoulProfile?,
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
      localeSettingsStore = LocaleSettingsStore.fromContext(context),
      telemetrySettingsStore = TelemetrySettingsStore.fromContext(context),
      personalizationLocalStore = PersonalizationLocalStore.fromContext(context),
    )
  }
}
