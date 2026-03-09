package com.opencray.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.opencray.ui.help.SafetyAndLimitsScreen
import com.opencray.ui.help.SafetyAndLimitsScreenState
import com.opencray.ui.settings.TelemetryToggles
import com.opencray.ui.settings.TelemetryTogglesState

class SafetyAndLimitsActivity : Activity() {
  private lateinit var safetyAndLimitsScreen: SafetyAndLimitsScreen
  private lateinit var telemetryToggles: TelemetryToggles

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    title = "OpenCray Safety and limits"

    val telemetryState = seededTelemetryState()
    val safetyState = seededSafetyState(telemetryState)

    safetyAndLimitsScreen = SafetyAndLimitsScreen(this).apply {
      submitState(safetyState)
    }
    telemetryToggles = TelemetryToggles(this).apply {
      submitState(telemetryState)
    }

    val contentContainer = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(24))
      addView(safetyAndLimitsScreen, sectionParams())
      addView(telemetryToggles, sectionParams(topDp = 16))
    }

    setContentView(
      ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Color.parseColor("#F4F7FB"))
        addView(
          contentContainer,
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ),
        )
      },
    )
  }

  private fun seededTelemetryState(): TelemetryTogglesState {
    val defaults = TelemetryTogglesState()

    return defaults.copy(
      telemetry = defaults.telemetry.copy(
        isChecked = false,
        defaultValue = false,
      ),
      privacyGuard = defaults.privacyGuard.copy(
        isChecked = true,
        defaultValue = true,
      ),
    )
  }

  private fun seededSafetyState(telemetryState: TelemetryTogglesState): SafetyAndLimitsScreenState =
    SafetyAndLimitsScreenState(
      telemetryPrivacyIntro =
        "This screen summarizes the same defaults shown below in Telemetry and privacy.",
      telemetryPrivacyFooter = telemetryState.defaultsDisclosure,
    )

  private fun sectionParams(topDp: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    topMargin = dp(topDp)
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
