package com.opencray.app

import android.content.Context
import org.opencray.app.R

data class TelemetryToggleState(
  val title: String,
  val switchLabel: String,
  val enabledSummary: String,
  val disabledSummary: String,
  val disclosureText: String,
  val isChecked: Boolean,
  val defaultValue: Boolean,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(switchLabel.isNotBlank()) { "switchLabel must not be blank." }
    require(enabledSummary.isNotBlank()) { "enabledSummary must not be blank." }
    require(disabledSummary.isNotBlank()) { "disabledSummary must not be blank." }
    require(disclosureText.isNotBlank()) { "disclosureText must not be blank." }
  }
}

data class TelemetryTogglesState(
  val title: String,
  val subtitle: String,
  val telemetry: TelemetryToggleState,
  val privacyGuard: TelemetryToggleState,
  val defaultsDisclosure: String,
  val localRetentionDisclosure: String,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
    require(defaultsDisclosure.isNotBlank()) { "defaultsDisclosure must not be blank." }
    require(localRetentionDisclosure.isNotBlank()) {
      "localRetentionDisclosure must not be blank."
    }
  }

  companion object {
    fun localized(context: Context): TelemetryTogglesState = TelemetryTogglesState(
      title = context.getString(R.string.telemetry_title),
      subtitle = context.getString(R.string.telemetry_subtitle),
      telemetry = TelemetryToggleState(
        title = context.getString(R.string.telemetry_toggle_title),
        switchLabel = context.getString(R.string.telemetry_switch_label),
        enabledSummary = context.getString(R.string.telemetry_toggle_enabled_summary),
        disabledSummary = context.getString(R.string.telemetry_toggle_disabled_summary),
        disclosureText = context.getString(R.string.telemetry_toggle_disclosure),
        isChecked = false,
        defaultValue = false,
      ),
      privacyGuard = TelemetryToggleState(
        title = context.getString(R.string.privacy_guard_title),
        switchLabel = context.getString(R.string.privacy_guard_switch_label),
        enabledSummary = context.getString(R.string.privacy_guard_enabled_summary),
        disabledSummary = context.getString(R.string.privacy_guard_disabled_summary),
        disclosureText = context.getString(R.string.privacy_guard_disclosure),
        isChecked = true,
        defaultValue = true,
      ),
      defaultsDisclosure = context.getString(R.string.telemetry_defaults_disclosure),
      localRetentionDisclosure = context.getString(R.string.telemetry_local_retention_disclosure),
    )
  }
}
