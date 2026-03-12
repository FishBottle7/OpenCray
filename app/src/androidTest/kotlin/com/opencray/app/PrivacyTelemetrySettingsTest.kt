package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import com.opencray.ui.settings.TelemetryTogglesState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PrivacyTelemetrySettingsTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val targetContext = instrumentation.targetContext
  private var launchedActivity: AppShellActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    AppShellStateStore.fromContext(targetContext).clear()
    TelemetrySettingsStore.fromContext(targetContext).clear()
    launchedActivity = null
  }

  @Test
  fun privacySubpageShowsDefaultsAndPersistsChangesAcrossRelaunch() {
    val defaults = TelemetryTogglesState.localized(targetContext)

    launchSettingsPrivacy()

    assertTextVisible(targetContext.getString(R.string.settings_card_privacy_telemetry))
    assertTextVisible(defaults.defaultsDisclosure)
    assertTextVisible(defaults.localRetentionDisclosure)
    assertSwitchChecked(defaults.telemetry.switchLabel, expectedChecked = false)
    assertSwitchChecked(defaults.privacyGuard.switchLabel, expectedChecked = true)

    setSwitchChecked(defaults.telemetry.switchLabel, checked = true)
    setSwitchChecked(defaults.privacyGuard.switchLabel, checked = false)

    pressBackOnActivity()
    assertActivityTitle(targetContext.getString(R.string.shell_tab_settings))
    assertTextVisible(homeSummary(defaults, telemetryEnabled = true, privacyGuardEnabled = false))

    relaunchSettingsHome()
    assertTextVisible(homeSummary(defaults, telemetryEnabled = true, privacyGuardEnabled = false))

    performCardClickForText(targetContext.getString(R.string.settings_card_privacy_telemetry))
    assertSwitchChecked(defaults.telemetry.switchLabel, expectedChecked = true)
    assertSwitchChecked(defaults.privacyGuard.switchLabel, expectedChecked = false)
    assertTextVisible(defaults.localRetentionDisclosure)
  }

  @Test
  fun localRetentionDisclosureRemainsVisibleWhenTelemetryIsOff() {
    val defaults = TelemetryTogglesState.localized(targetContext)

    launchSettingsPrivacy()

    setSwitchChecked(defaults.telemetry.switchLabel, checked = true)
    setSwitchChecked(defaults.telemetry.switchLabel, checked = false)

    assertSwitchChecked(defaults.telemetry.switchLabel, expectedChecked = false)
    assertTextVisible(defaults.localRetentionDisclosure)

    pressBackOnActivity()
    assertTextVisible(homeSummary(defaults, telemetryEnabled = false, privacyGuardEnabled = true))
  }

  private fun launchSettingsPrivacy(): AppShellActivity = launchShell(
    Intent(targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SETTINGS.name)
      putExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE, SettingsSubpage.PRIVACY.name)
    },
  )

  private fun relaunchSettingsHome(): AppShellActivity {
    closeLaunchedActivity()
    return launchShell(
      Intent(targetContext, AppShellActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SETTINGS.name)
      },
    )
  }

  private fun launchShell(intent: Intent): AppShellActivity {
    closeLaunchedActivity()

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun closeLaunchedActivity() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
      launchedActivity = null
    }
  }

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertTextVisible(text: String) {
    runOnActivity {
      val textView = requireTextView(text) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView(text) { it.isShown }
      textView.isShown && hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected '$text' to be visible on screen.", isVisible)
  }

  private fun assertSwitchChecked(
    label: String,
    expectedChecked: Boolean,
  ) {
    runOnActivity {
      val switchView = requireSwitch(label) { it.isShown }
      switchView.requestRectangleOnScreen(viewBounds(switchView), true)
    }
    instrumentation.waitForIdleSync()

    val switchSnapshot = runOnActivity {
      val switchView = requireSwitch(label) { it.isShown }
      SwitchSnapshot(
        isVisible = switchView.isShown && hasGlobalVisibleBounds(switchView),
        isChecked = switchView.isChecked,
      )
    }

    assertTrue("Expected switch '$label' to be visible on screen.", switchSnapshot.isVisible)
    if (expectedChecked) {
      assertTrue("Expected switch '$label' to be checked.", switchSnapshot.isChecked)
    } else {
      assertFalse("Expected switch '$label' to be unchecked.", switchSnapshot.isChecked)
    }
  }

  private fun setSwitchChecked(
    label: String,
    checked: Boolean,
  ) {
    val changed = runOnActivity {
      val switchView = requireSwitch(label) { it.isShown }
      switchView.requestRectangleOnScreen(viewBounds(switchView), true)
      if (switchView.isChecked == checked) {
        false
      } else {
        switchView.performClick() || switchView.callOnClick()
      }
    }
    instrumentation.waitForIdleSync()

    if (changed) {
      assertSwitchChecked(label, expectedChecked = checked)
    }
  }

  private fun performCardClickForText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView(text) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      val clickableAncestor = requireClickableAncestor(textView)
      clickableAncestor.performClick() || clickableAncestor.callOnClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a card-wide click for '$text' to succeed.", clicked)
  }

  private fun pressBackOnActivity() {
    runOnActivity { onBackPressed() }
    instrumentation.waitForIdleSync()
  }

  private fun requireTextView(
    text: String,
    predicate: (TextView) -> Boolean,
  ): TextView {
    val matches = findTextViews(requireActivity().window.decorView.rootView, text)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find visible text '$text' in the launched shell activity.")
  }

  private fun findTextViews(root: View, text: String): List<TextView> {
    val matches = mutableListOf<TextView>()
    collectTextViews(root, text, matches)
    return matches
  }

  private fun collectTextViews(root: View, text: String, matches: MutableList<TextView>) {
    if (root is TextView && root.text.toString() == text) {
      matches += root
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectTextViews(root.getChildAt(index), text, matches)
      }
    }
  }

  private fun requireSwitch(
    label: String,
    predicate: (Switch) -> Boolean,
  ): Switch {
    val matches = mutableListOf<Switch>()
    collectSwitches(requireActivity().window.decorView.rootView, label, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find switch '$label' in the launched shell activity.")
  }

  private fun collectSwitches(
    root: View,
    label: String,
    matches: MutableList<Switch>,
  ) {
    if (root is Switch) {
      val viewLabel = root.text?.toString().orEmpty()
      val contentDescription = root.contentDescription?.toString().orEmpty()
      if (viewLabel == label || contentDescription == label) {
        matches += root
      }
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectSwitches(root.getChildAt(index), label, matches)
      }
    }
  }

  private fun requireClickableAncestor(view: View): View {
    var current: View? = view
    while (current != null) {
      if (current.isClickable && current.isEnabled && current.isShown) {
        return current
      }
      current = current.parent as? View
    }
    throw AssertionError("Expected a clickable ancestor for the requested text view.")
  }

  private fun homeSummary(
    defaults: TelemetryTogglesState,
    telemetryEnabled: Boolean,
    privacyGuardEnabled: Boolean,
  ): String = buildString {
    append(defaults.telemetry.switchLabel)
    append(": ")
    append(onOffLabel(telemetryEnabled))
    append(" • ")
    append(defaults.privacyGuard.switchLabel)
    append(": ")
    append(onOffLabel(privacyGuardEnabled))
  }

  private fun onOffLabel(value: Boolean): String = if (Locale.getDefault().language.startsWith("zh")) {
    if (value) "开启" else "关闭"
  } else {
    if (value) "On" else "Off"
  }

  private fun viewBounds(view: View): Rect = Rect(
    0,
    0,
    view.width.coerceAtLeast(1),
    view.height.coerceAtLeast(1),
  )

  private fun hasGlobalVisibleBounds(view: View): Boolean {
    val rect = Rect()
    return view.getGlobalVisibleRect(rect) && !rect.isEmpty
  }

  private fun requireActivity(): AppShellActivity =
    checkNotNull(launchedActivity) { "Expected a launched AppShellActivity." }

  private fun <T> runOnActivity(block: AppShellActivity.() -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync {
      result = runCatching { requireActivity().block() }
    }
    return checkNotNull(result).getOrThrow()
  }

  private data class SwitchSnapshot(
    val isVisible: Boolean,
    val isChecked: Boolean,
  )
}
