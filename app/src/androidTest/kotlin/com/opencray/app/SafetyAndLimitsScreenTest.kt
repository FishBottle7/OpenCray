package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyAndLimitsScreenTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private var launchedActivity: SafetyAndLimitsActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    launchedActivity = null
  }

  @Test
  fun safetyWarningsAndTelemetryDefaultsAreVisible() {
    launchSafetyAndLimitsActivity()

    assertTextVisible("Safety and limits")
    assertTextVisible(
      "Developer mode does not override hard denials. Protected-file, path, and other hard policy denials still stop the action.",
    )
    assertTextVisible(
      "Rollback is local-only, not guaranteed for remote/external side effects such as shell commands, MCP actions, network requests, cloud changes, or any other external system change.",
    )
    assertTextVisible("V1 does not ship real Termux execution.")
    assertTextVisible(
      "Multi-agent parallel execution, iOS client support, cloud collaboration sync, and a public marketplace review system are out of scope for V1.",
    )
    assertTextVisible("Defaults: Enable telemetry = Off. Enable privacy guard = On.")
    assertTextVisible(
      "Even with telemetry off, OpenCray still keeps local settings, workspace access state, consent choices, and recent audit history required for core app function on this device until a later clear-data flow removes them.",
    )
    assertTextVisible("Current: Off • Default: Off • No telemetry leaves the device.")
    assertTextVisible(
      "Current: On • Default: On • Local redaction stays enabled for eligible analytics and audit details.",
    )
    assertSwitchChecked(label = "Enable telemetry", expectedChecked = false)
    assertSwitchChecked(label = "Enable privacy guard", expectedChecked = true)
  }

  private fun launchSafetyAndLimitsActivity(): SafetyAndLimitsActivity {
    if (launchedActivity != null) {
      return requireActivity()
    }

    val intent = Intent(instrumentation.targetContext, SafetyAndLimitsActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return (instrumentation.startActivitySync(intent) as SafetyAndLimitsActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun assertTextVisible(text: String) {
    assertTextVisibleMatching(
      queryDescription = "text '$text'",
      textMatcher = { candidate -> candidate == text },
    )
  }

  private fun assertTextVisibleMatching(
    queryDescription: String,
    textMatcher: (String) -> Boolean,
  ) {
    runOnActivity {
      val textView = requireTextView(queryDescription, textMatcher) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView(queryDescription, textMatcher) { it.isShown }
      textView.isShown && textView.hasGlobalVisibleBounds()
    }

    assertTrue("Expected $queryDescription to be visible on screen.", isVisible)
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
        isVisible = switchView.isShown && switchView.hasGlobalVisibleBounds(),
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

  private fun requireTextView(
    queryDescription: String,
    textMatcher: (String) -> Boolean,
    predicate: (TextView) -> Boolean,
  ): TextView {
    val matches = mutableListOf<TextView>()
    collectTextViews(requireActivity().window.decorView.rootView, textMatcher, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find $queryDescription in the launched activity.")
  }

  private fun collectTextViews(
    root: View,
    textMatcher: (String) -> Boolean,
    matches: MutableList<TextView>,
  ) {
    if (root is TextView && textMatcher(root.text.toString())) {
      matches += root
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectTextViews(root.getChildAt(index), textMatcher, matches)
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
      ?: throw AssertionError("Expected to find switch '$label' in the launched activity.")
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

  private fun requireActivity(): SafetyAndLimitsActivity =
    checkNotNull(launchedActivity) { "Expected a launched SafetyAndLimitsActivity." }

  private fun <T> runOnActivity(block: SafetyAndLimitsActivity.() -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync {
      result = runCatching { requireActivity().block() }
    }
    return checkNotNull(result).getOrThrow()
  }

  private fun viewBounds(view: View): Rect = Rect(
    0,
    0,
    view.width.coerceAtLeast(1),
    view.height.coerceAtLeast(1),
  )

  private fun View.hasGlobalVisibleBounds(): Boolean {
    val rect = Rect()
    return getGlobalVisibleRect(rect) && !rect.isEmpty
  }

  private data class SwitchSnapshot(
    val isVisible: Boolean,
    val isChecked: Boolean,
  )
}
