package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import com.opencray.ui.help.DisclosureTone
import com.opencray.ui.help.SafetyAndLimitsScreenState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R

@RunWith(AndroidJUnit4::class)
class SafetyLimitsSubpageTest {
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
    launchedActivity = null
  }

  @Test
  fun settingsHomeSafetySummaryUsesHighestRiskHeadline() {
    launchSettingsHome()

    assertTextVisible(targetContext.getString(R.string.settings_card_safety_limits))
    assertTextContaining(highestRiskHeadline())
  }

  @Test
  fun safetySubpageShowsReleaseCriticalWarningsInsideSettings() {
    launchSettingsHome()

    performCardClickForText(targetContext.getString(R.string.settings_card_safety_limits))

    assertActivityTitle(targetContext.getString(R.string.settings_card_safety_limits))
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
  }

  private fun launchSettingsHome(): AppShellActivity {
    if (launchedActivity != null) {
      return requireActivity()
    }

    AppShellStateStore.fromContext(targetContext).clear()

    val intent = Intent(targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SETTINGS.name)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun highestRiskHeadline(): String = SafetyAndLimitsScreenState.localized(targetContext)
    .modeRiskCards
    .first { it.tone == DisclosureTone.DANGER }
    .title

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertTextVisible(text: String) {
    assertTextMatching(
      queryDescription = "'$text'",
      textMatcher = { candidate -> candidate == text },
    )
  }

  private fun assertTextContaining(textFragment: String) {
    assertTextMatching(
      queryDescription = "text containing '$textFragment'",
      textMatcher = { candidate -> candidate.contains(textFragment) },
    )
  }

  private fun assertTextMatching(
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
      textView.isShown && hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected $queryDescription to be visible on screen.", isVisible)
  }

  private fun performCardClickForText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView(text, textMatcher = { candidate -> candidate == text }) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      val clickableAncestor = requireClickableAncestor(textView)
      clickableAncestor.performClick() || clickableAncestor.callOnClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a card-wide click for '$text' to succeed.", clicked)
  }

  private fun requireTextView(
    queryDescription: String,
    textMatcher: (String) -> Boolean,
    predicate: (TextView) -> Boolean,
  ): TextView {
    val matches = mutableListOf<TextView>()
    collectTextViews(requireActivity().window.decorView.rootView, textMatcher, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find visible $queryDescription in the launched shell activity.")
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
}
