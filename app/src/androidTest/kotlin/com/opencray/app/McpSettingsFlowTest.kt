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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R

@RunWith(AndroidJUnit4::class)
class McpSettingsFlowTest {
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
  fun homeSummaryAndManualEnableFlowStayVisibleWithoutSecrets() {
    launchSettingsHome()

    assertTextVisible("Enabled 2 • Blocked 1 • Attention 2")
    assertTextVisible("Enable MCP integrations")

    performCardClickForText(settingsCardMcp())
    assertActivityTitle(settingsCardMcp())
    assertTextVisible("Community Bridge")
    assertTextVisible("Enable server")
    assertNoVisibleTextContaining("secret://")
    assertNoVisibleTextContaining("Authorization")
    assertNoVisibleTextContaining("OPENCRAY_PROFILE")

    performClickOnText("Enable server")

    assertTextVisible("Trust: Enabled • Manual consent saved")
    assertTextVisible("Tools: Active")
    assertNoVisibleTextContaining("secret://")
    assertNoVisibleTextContaining("Authorization")

    pressBackOnActivity()

    assertActivityTitle(settingsTitle())
    assertTextVisible("Enabled 3 • Blocked 0 • Attention 1")
    assertTextVisible("Enable MCP integrations")
  }

  @Test
  fun unknownServerStaysBlockedUntilManualConsent() {
    launchSettingsHome()

    performCardClickForText(settingsCardMcp())

    assertActivityTitle(settingsCardMcp())
    assertActivityNotFinishing()
    assertTextVisible("Community Bridge")
    assertTextVisible("Trust: Requires manual enable")
    assertTextVisible("Tools: Blocked")
    assertTextVisible("Auth: Credential configured")
    assertTextVisible("Readiness: Ready")
    assertTextVisible("Blocked until you enable this server manually. Tools stay hidden until you consent here.")
    assertTextVisible("Enable server")
    assertNoVisibleTextContaining("secret://")
    assertNoVisibleTextContaining("Authorization")
  }

  private fun launchSettingsHome(): AppShellActivity {
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

  private fun settingsTitle(): String = targetContext.getString(R.string.shell_tab_settings)

  private fun settingsCardMcp(): String = targetContext.getString(R.string.settings_card_mcp)

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertActivityNotFinishing() {
    val isFinishing = runOnActivity { isFinishing }
    assertFalse("Expected the shell activity to remain open.", isFinishing)
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

  private fun assertNoVisibleTextContaining(fragment: String) {
    val hasVisibleMatch = runOnActivity {
      val visibleTexts = mutableListOf<String>()
      collectVisibleTexts(requireActivity().window.decorView.rootView, visibleTexts)
      visibleTexts.any { value -> value.contains(fragment) }
    }

    assertFalse("Expected no visible text containing '$fragment'.", hasVisibleMatch)
  }

  private fun performClickOnText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView(text) {
        it.isShown && it.isEnabled && it.isClickable
      }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      textView.performClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a main-thread click on '$text' to succeed.", clicked)
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

  private fun collectVisibleTexts(root: View, values: MutableList<String>) {
    if (root is TextView && root.isShown) {
      values += root.text.toString()
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectVisibleTexts(root.getChildAt(index), values)
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
