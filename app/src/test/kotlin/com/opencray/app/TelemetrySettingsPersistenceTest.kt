package com.opencray.app
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySettingsPersistenceTest {
  @Test
  fun loadFallsBackToLocalizedDefaultsWhenNothingIsPersisted() {
    val defaults = defaultTelemetryState()
    val stateStore = TelemetrySettingsStore(InMemoryTelemetrySettingsKeyValueStore())

    val restored = stateStore.load(defaults)

    assertFalse(restored.telemetry.isChecked)
    assertTrue(restored.privacyGuard.isChecked)
    assertEquals(defaults.telemetry.defaultValue, restored.telemetry.defaultValue)
    assertEquals(defaults.privacyGuard.defaultValue, restored.privacyGuard.defaultValue)
    assertEquals(defaults.defaultsDisclosure, restored.defaultsDisclosure)
    assertEquals(defaults.localRetentionDisclosure, restored.localRetentionDisclosure)
  }

  @Test
  fun persistsChangedToggleStateAcrossReloadWithoutChangingDefaults() {
    val defaults = defaultTelemetryState()
    val keyValueStore = InMemoryTelemetrySettingsKeyValueStore()
    val stateStore = TelemetrySettingsStore(keyValueStore)

    stateStore.save(
      defaults.copy(
        telemetry = defaults.telemetry.copy(isChecked = true),
        privacyGuard = defaults.privacyGuard.copy(isChecked = false),
      ),
    )

    val restored = TelemetrySettingsStore(keyValueStore).load(defaults)

    assertTrue(restored.telemetry.isChecked)
    assertFalse(restored.privacyGuard.isChecked)
    assertFalse(restored.telemetry.defaultValue)
    assertTrue(restored.privacyGuard.defaultValue)
    assertEquals(defaults.telemetry.title, restored.telemetry.title)
    assertEquals(defaults.privacyGuard.title, restored.privacyGuard.title)
  }

  private fun defaultTelemetryState(): TelemetryTogglesState = TelemetryTogglesState(
    title = "Privacy & Telemetry",
    subtitle = "Review what changes and what stays local.",
    telemetry = TelemetryToggleState(
      title = "Telemetry",
      switchLabel = "Enable telemetry",
      enabledSummary = "Anonymous product telemetry is allowed.",
      disabledSummary = "No telemetry leaves the device.",
      disclosureText = "Telemetry may improve product quality.",
      isChecked = false,
      defaultValue = false,
    ),
    privacyGuard = TelemetryToggleState(
      title = "Privacy guard",
      switchLabel = "Enable privacy guard",
      enabledSummary = "Local redaction stays enabled.",
      disabledSummary = "Full local detail can be retained.",
      disclosureText = "Privacy guard applies only to eligible local records.",
      isChecked = true,
      defaultValue = true,
    ),
    defaultsDisclosure = "Defaults: Enable telemetry = Off. Enable privacy guard = On.",
    localRetentionDisclosure = "Even with telemetry off, OpenCray still keeps local settings and audit history required for core app function on this device.",
  )
}
