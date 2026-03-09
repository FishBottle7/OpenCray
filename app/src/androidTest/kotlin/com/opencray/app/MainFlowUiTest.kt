package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

abstract class MainFlowUiTestSupport {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private var launchedActivity: MainInteractionActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    launchedActivity = null
  }

  protected fun assertApprovalFlowExecutesAfterConsent() {
    launchMainInteractionActivity(MainInteractionActivity.SCENARIO_DEFAULT_APPROVAL)

    assertTextVisible("Approval required")
    assertTextVisible(
      "Allow the queued workspace write so the prepared summary update can be applied.",
    )
    assertTextVisible("Waiting for a decision before the queued action can continue.")

    performClickOnText("Approve write")

    assertTextAbsent("Waiting for a decision before the queued action can continue.")
    assertTextVisible("Write approved")
    assertTextVisible(
      "The queued workspace write was marked successful immediately after approval so the progression stays deterministic.",
    )
    assertTextVisible(
      "Approved explicitly, then advanced to a visible success state with no hidden async work.",
    )
  }

  protected fun assertDeniedOperationVisibleInTimeline() {
    launchMainInteractionActivity(MainInteractionActivity.SCENARIO_DENIED_POLICY)

    assertTextVisible("Blocked by policy")
    assertTextVisible(
      "This alternate scenario seeds a protected-file denial before approval can open, which makes the denial path easy to cover in androidTest later.",
    )
    assertTextVisible(
      "Denied transparently because protected-policy rules block the write before execution.",
    )

    assertTextVisible("Apply protected workspace write")
    assertTextVisible("POLICY DENY")
    assertTextVisible("RESULT FAILED")
    assertTextVisible("NO APPROVAL")
    assertTextVisible(
      "Reason: Protected-policy rules denied the write before approval because this seeded scenario targets a blocked path for transparent coverage.",
    )
  }

  private fun assertTextVisible(text: String) {
    runOnActivity {
      val textView = requireTextView(text) { it.isShown }
      textView.requestRectangleOnScreen(textViewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView(text) { it.isShown }
      textView.isShown && textView.hasGlobalVisibleBounds()
    }

    assertTrue("Expected '$text' to be visible on screen.", isVisible)
  }

  private fun assertTextAbsent(text: String) {
    val exists = runOnActivity {
      findTextViews(window.decorView.rootView, text).isNotEmpty()
    }

    assertFalse("Expected '$text' to be absent from the view tree.", exists)
  }

  private fun performClickOnText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView(text) { it.isShown && it.isEnabled && it.isClickable }
      textView.requestRectangleOnScreen(textViewBounds(textView), true)
      textView.performClick()
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
      ?: throw AssertionError("Expected to find visible text '$text' in the launched activity.")
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

  private fun textViewBounds(textView: TextView): Rect = Rect(
    0,
    0,
    textView.width.coerceAtLeast(1),
    textView.height.coerceAtLeast(1),
  )

  private fun TextView.hasGlobalVisibleBounds(): Boolean {
    val rect = Rect()
    return getGlobalVisibleRect(rect) && !rect.isEmpty
  }

  private fun requireActivity(): MainInteractionActivity =
    checkNotNull(launchedActivity) { "Expected a launched MainInteractionActivity." }

  private fun <T> runOnActivity(block: MainInteractionActivity.() -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync {
      result = runCatching { requireActivity().block() }
    }
    return checkNotNull(result).getOrThrow()
  }

  private fun launchMainInteractionActivity(scenario: String): MainInteractionActivity {
    val intent = Intent(instrumentation.targetContext, MainInteractionActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(MainInteractionActivity.EXTRA_SCENARIO, scenario)
    }

    return (instrumentation.startActivitySync(intent) as MainInteractionActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }
}

@RunWith(AndroidJUnit4::class)
class ApprovalFlowExecutesAfterConsent : MainFlowUiTestSupport() {

  @Test
  fun approvalFlowExecutesAfterConsent() {
    assertApprovalFlowExecutesAfterConsent()
  }
}

@RunWith(AndroidJUnit4::class)
class DeniedOperationVisibleInTimeline : MainFlowUiTestSupport() {

  @Test
  fun deniedOperationVisibleInTimeline() {
    assertDeniedOperationVisibleInTimeline()
  }
}

// Learning: Text-based Espresso coverage is stable here because the seeded prompt and timeline copy is intentionally deterministic.
// Issue: These assertions are copy-sensitive, so any future wording changes in the seeded scenarios must update this test in lockstep.
