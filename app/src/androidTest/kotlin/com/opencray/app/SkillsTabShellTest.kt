package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillsTabShellTest {
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
  fun skillsTabKeepsExistingSkillsFlowAndRetainsEditorStateAcrossTabSwitches() {
    launchAppShellActivity()

    performClickOnText("Skills")

    assertTextVisibleWithoutScrolling("Create new draft")
    assertTextVisibleWithoutScrolling("Save draft")
    assertTextVisibleWithoutScrolling("Import package")
    assertTextVisibleWithoutScrolling("Export package")
    assertTextVisibleWithoutScrolling("Import / export feedback")
    assertTextAbsent("OpenCray sections")
    assertClickableTextAbsent("Editing")

    performClickOnText("Export package")
    assertTextVisible("Exported directory-style package snapshot for workspace-audit.")
    assertTextContainingVisibleWithoutScrolling("package-type=directory-style")

    performCardClickForText("forked-review")
    assertTextContainingVisible("Selected: forked-review")

    val retainedDescription = "Shell-hosted edit survives tab switches."
    setEditTextValue(
      hint = "Short summary of what the skill does.",
      value = retainedDescription,
    )

    performClickOnText("Chat")
    performClickOnText("Skills")

    assertTextContainingVisible("Selected: forked-review")
    assertEditTextValue(
      hint = "Short summary of what the skill does.",
      expected = retainedDescription,
    )

    performClickOnText("Save draft")
    assertTextVisible("Updated forked-review.")

    performClickOnText("Enable selected")
    assertTextVisible("forked-review is active in memory.")
    assertTextContainingVisible("Lifecycle: active • Install: not installed")
  }

  @Test
  fun invalidSkillNameStillShowsInlineValidationInsideShellSkillsTab() {
    launchAppShellActivity()

    performClickOnText("Skills")
    assertTextAbsent("OpenCray sections")

    performClickOnText("Create new draft")
    setEditTextValue("valid-skill-name", "Invalid Name")

    performClickOnText("Save draft")

    assertInlineErrorForHint(
      hint = "valid-skill-name",
      expected = "Skill name must use lowercase alphanumeric-hyphen format.",
    )
    assertTextVisible("Validation blocked the save. Fix the inline feedback and try again.")
  }

  private fun launchAppShellActivity(): AppShellActivity {
    val intent = Intent(instrumentation.targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.CHAT.name)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
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

  private fun assertTextContainingVisible(fragment: String) {
    assertTextVisibleMatching(
      queryDescription = "text containing '$fragment'",
      textMatcher = { candidate -> candidate.contains(fragment) },
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
      textView.isShown && hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected $queryDescription to be visible on screen.", isVisible)
  }

  private fun assertTextVisibleWithoutScrolling(text: String) {
    assertTextVisibleWithoutScrollingMatching(
      queryDescription = "text '$text'",
      textMatcher = { candidate -> candidate == text },
    )
  }

  private fun assertTextContainingVisibleWithoutScrolling(fragment: String) {
    assertTextVisibleWithoutScrollingMatching(
      queryDescription = "text containing '$fragment'",
      textMatcher = { candidate -> candidate.contains(fragment) },
    )
  }

  private fun assertTextVisibleWithoutScrollingMatching(
    queryDescription: String,
    textMatcher: (String) -> Boolean,
  ) {
    val isVisible = runOnActivity {
      val textView = requireTextView(queryDescription, textMatcher) { it.isShown }
      hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected $queryDescription to be visible without scrolling.", isVisible)
  }

  private fun assertTextAbsent(text: String) {
    val matches = runOnActivity {
      val collected = mutableListOf<TextView>()
      collectTextViews(requireActivity().window.decorView.rootView, { candidate -> candidate == text }, collected)
      collected.toList()
    }

    assertTrue("Expected text '$text' to be absent.", matches.isEmpty())
  }

  private fun assertClickableTextAbsent(text: String) {
    val hasVisibleClickableMatch = runOnActivity {
      val collected = mutableListOf<TextView>()
      collectTextViews(requireActivity().window.decorView.rootView, { candidate -> candidate == text }, collected)
      collected.any { view ->
        view.isShown &&
          view.isClickable &&
          view.isEnabled &&
          hasGlobalVisibleBounds(view)
      }
    }

    assertFalse("Expected clickable text '$text' to be absent.", hasVisibleClickableMatch)
  }

  private fun performClickOnText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView("clickable text '$text'", { candidate -> candidate == text }) {
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
      val textView = requireTextView("card text '$text'", { candidate -> candidate == text }) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      val clickableAncestor = requireClickableAncestor(textView)
      clickableAncestor.performClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a card-wide click for '$text' to succeed.", clicked)
  }

  private fun setEditTextValue(
    hint: String,
    value: String,
  ) {
    runOnActivity {
      val input = requireEditText(hint) { it.isShown && it.isEnabled }
      input.requestRectangleOnScreen(viewBounds(input), true)
      input.requestFocus()
      input.setText(value)
      input.setSelection(input.text?.length ?: 0)
    }
    instrumentation.waitForIdleSync()

    assertEditTextValue(hint, value)
  }

  private fun assertEditTextValue(
    hint: String,
    expected: String,
  ) {
    val actual = runOnActivity {
      requireEditText(hint) { true }.text?.toString().orEmpty()
    }

    assertEquals("Expected input with hint '$hint' to match.", expected, actual)
  }

  private fun assertInlineErrorForHint(
    hint: String,
    expected: String,
  ) {
    runOnActivity {
      val errorTextView = requireInlineErrorTextView(hint) { it.isShown }
      errorTextView.requestRectangleOnScreen(viewBounds(errorTextView), true)
    }
    instrumentation.waitForIdleSync()

    val result = runOnActivity {
      val errorTextView = requireInlineErrorTextView(hint) { it.isShown }
      hasGlobalVisibleBounds(errorTextView) to errorTextView.text.toString()
    }

    assertTrue("Expected inline error for field hint '$hint' to be visible.", result.first)
    assertEquals("Expected inline error for field hint '$hint'.", expected, result.second)
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

  private fun requireEditText(
    hint: String,
    predicate: (EditText) -> Boolean,
  ): EditText {
    val matches = mutableListOf<EditText>()
    collectEditTexts(requireActivity().window.decorView.rootView, hint, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find an EditText with hint '$hint' in the launched activity.")
  }

  private fun collectEditTexts(
    root: View,
    hint: String,
    matches: MutableList<EditText>,
  ) {
    if (root is EditText && root.hint?.toString() == hint) {
      matches += root
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectEditTexts(root.getChildAt(index), hint, matches)
      }
    }
  }

  private fun requireInlineErrorTextView(
    hint: String,
    predicate: (TextView) -> Boolean,
  ): TextView {
    val matches = mutableListOf<TextView>()
    collectInlineErrorTextViews(requireActivity().window.decorView.rootView, hint, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected inline error text for field hint '$hint' in the launched activity.")
  }

  private fun collectInlineErrorTextViews(
    root: View,
    hint: String,
    matches: MutableList<TextView>,
  ) {
    if (root is TextView) {
      val parent = root.parent as? ViewGroup
      if (parent != null && parent.childCount > 0 && parent.getChildAt(parent.childCount - 1) === root) {
        if (parentContainsHintedEditText(parent, hint)) {
          matches += root
        }
      }
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectInlineErrorTextViews(root.getChildAt(index), hint, matches)
      }
    }
  }

  private fun parentContainsHintedEditText(
    parent: ViewGroup,
    hint: String,
  ): Boolean {
    for (index in 0 until parent.childCount) {
      if (viewContainsHintedEditText(parent.getChildAt(index), hint)) {
        return true
      }
    }
    return false
  }

  private fun viewContainsHintedEditText(
    view: View,
    hint: String,
  ): Boolean {
    if (view is EditText && view.hint?.toString() == hint) {
      return true
    }
    if (view is ViewGroup) {
      for (index in 0 until view.childCount) {
        if (viewContainsHintedEditText(view.getChildAt(index), hint)) {
          return true
        }
      }
    }
    return false
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

  private fun requireActivity(): AppShellActivity =
    checkNotNull(launchedActivity) { "Expected a launched AppShellActivity." }

  private fun <T> runOnActivity(block: AppShellActivity.() -> T): T {
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

  private fun hasGlobalVisibleBounds(view: View): Boolean {
    val rect = Rect()
    return view.getGlobalVisibleRect(rect) && !rect.isEmpty
  }
}
