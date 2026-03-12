package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTabShellTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private var launchedActivity: AppShellActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    launchedActivity = null
  }

  @Test
  fun chatTabKeepsApprovalFlowAndRetainsStateAcrossTabSwitches() {
    launchAppShellActivity()

    assertTextVisible("Approval required")
    assertTextVisible("Allow the queued workspace write so the prepared summary update can be applied.")
    assertTextVisible("Waiting for a decision before the queued action can continue.")
    assertTextVisible("Queue visible • 1 waiting")

    performClickOnText("Hide queue")
    assertTextVisible("Queue hidden • 1 waiting")

    performClickOnText("Auto")
    assertTextVisible("This seeded Auto-mode request stays paused on purpose so the approve path remains observable in tests and demos.")

    performClickOnText("Skills")
    assertActivityTitle("Skills")
    performClickOnText("Chat")
    assertActivityTitle("Chat")

    assertTextVisible("Queue hidden • 1 waiting")
    assertTextVisible("This seeded Auto-mode request stays paused on purpose so the approve path remains observable in tests and demos.")

    performClickOnText("Approve write")

    assertTextVisible("Write approved")
    assertTextVisible("The queued workspace write was marked successful immediately after approval so the progression stays deterministic.")
    assertTextVisible("Approved explicitly, then advanced to a visible success state with no hidden async work.")
  }

  @Test
  fun denyAndResetFlowsRemainVisibleInsideShellChat() {
    launchAppShellActivity(chatScenario = MainInteractionActivity.SCENARIO_DEFAULT_APPROVAL)

    performClickOnText("Keep blocked")

    assertTextVisible("Write denied")
    assertTextVisible("The workspace write remains blocked and the denial reason stays visible so the next request inherits the same safety context.")
    assertTextVisible("Denied explicitly to keep the workspace unchanged. Reason: user chose to keep the queued write blocked.")
    assertTextVisible("POLICY DENY")
    assertTextVisible("RESULT CANCELLED")
    assertTextVisible("APPROVAL REQUIRED")
    assertTextVisible("Reason: Denied explicitly to keep the workspace unchanged. Transparency note: the user chose to keep the queued write blocked.")

    performClickOnText("Reset agent identity")
    assertTextVisible("Confirm agent identity reset")
    assertTextVisible("Confirm the reset before the active identity is cleared.")

    performClickOnText("Files")
    assertActivityTitle("Files")
    performClickOnText("Chat")
    assertActivityTitle("Chat")

    assertTextVisible("Confirm agent identity reset")

    performClickOnText("Cancel")
    assertTextNotVisible("Confirm agent identity reset")

    performClickOnText("Reset agent identity")
    performClickOnText("Confirm reset")

    assertTextVisible("Approval required")
    assertTextVisible("Allow the queued workspace write so the prepared summary update can be applied.")
    assertTextVisible("Queue visible • 1 waiting")
  }

  @Test
  fun deniedPolicyScenarioFromShellExtraRemainsVisible() {
    launchAppShellActivity(chatScenario = MainInteractionActivity.SCENARIO_DENIED_POLICY)

    assertTextVisible("Blocked by policy")
    assertTextVisible("This alternate scenario seeds a protected-file denial before approval can open, which makes the denial path easy to cover in androidTest later.")
    assertTextVisible("Denied transparently because protected-policy rules block the write before execution.")
    assertTextVisible("Apply protected workspace write")
    assertTextVisible("POLICY DENY")
    assertTextVisible("RESULT FAILED")
    assertTextVisible("NO APPROVAL")
    assertTextVisible("Reason: Protected-policy rules denied the write before approval because this seeded scenario targets a blocked path for transparent coverage.")
  }

  @Test
  fun invalidChatScenarioFallsBackToDefaultApproval() {
    launchAppShellActivity(chatScenario = "not-a-real-scenario")

    assertTextVisible("Approval required")
    assertTextVisible("Allow the queued workspace write so the prepared summary update can be applied.")
    assertTextVisible("Waiting for a decision before the queued action can continue.")
  }

  private fun launchAppShellActivity(chatScenario: String? = null): AppShellActivity {
    val intent = Intent(instrumentation.targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.CHAT.name)
      if (chatScenario != null) {
        putExtra(AppShellNavigationExtras.EXTRA_CHAT_SCENARIO, chatScenario)
      }
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
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

  private fun assertTextNotVisible(text: String) {
    val hasVisibleMatch = runOnActivity {
      findTextViews(requireActivity().window.decorView.rootView, text).any { textView ->
        textView.isShown && hasGlobalVisibleBounds(textView)
      }
    }

    assertTrue("Expected '$text' to be hidden from the current shell view.", !hasVisibleMatch)
  }

  private fun performClickOnText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView(text) { it.isShown && it.isEnabled && it.isClickable }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      if (textView is RadioButton) {
        if (!textView.isChecked) {
          textView.isChecked = true
        }
        true
      } else {
        textView.performClick() || textView.callOnClick()
      }
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a main-thread click on '$text' to succeed.", clicked)
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
