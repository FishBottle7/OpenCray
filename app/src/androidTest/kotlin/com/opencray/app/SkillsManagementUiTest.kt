package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

abstract class SkillsManagementUiTestSupport {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private var launchedActivity: SkillsManagementActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    launchedActivity = null
  }

  protected fun assertWorkspaceAuditSkillLifecycleCrudFlow() {
    launchSkillsManagementActivity()

    val draftName = "lifecycle-export-skill"
    val exportSummary = """
      package-type=directory-style
      name=$draftName
      invocation-control=explicit-and-implicit
      context=inline
      tool-count=0
      metadata-count=0
    """.trimIndent()

    assertTextVisible("Skills Management")
    assertTextContainingVisible("Selected: workspace-audit")
    assertTextContainingVisible("Lifecycle: active • Install: installed")

    performClickOnText("Create new draft")
    setEditTextValue("valid-skill-name", draftName)
    setEditTextValue("Short summary of what the skill does.", "Covers create and export lifecycle coverage.")

    performClickOnText("Save draft")

    assertTextVisible("Created $draftName. Persistence remains deferred.")
    assertTextContainingVisible("Selected: $draftName")
    assertTextContainingVisible("Lifecycle: active • Install: not installed")

    setEditTextValue("Short summary of what the skill does.", "Covers create, edit, disable, and export lifecycle coverage.")
    performClickOnText("Save draft")

    assertTextVisible("Updated $draftName.")
    assertTextContainingVisible("Selected: $draftName")

    performClickOnText("Disable selected")

    assertTextVisible("$draftName is disabled in memory.")
    assertTextVisible("Enable selected")
    assertTextContainingVisible("Selected: $draftName")
    assertTextContainingVisible("Lifecycle: disabled • Install: not installed")

    performClickOnText("Export placeholder")

    assertTextVisible("Exported directory-style package snapshot for $draftName.")
    assertTextVisible(exportSummary)
  }

  protected fun assertDraftSkillValidationInlineErrors() {
    launchSkillsManagementActivity()

    performClickOnText("Create new draft")

    setEditTextValue("valid-skill-name", "Invalid Name")

    performClickOnText("Save draft")

    assertInlineErrorForHint(
      hint = "valid-skill-name",
      expected = "Skill name must use lowercase alphanumeric-hyphen format.",
    )
    assertTextVisible("Validation blocked the save. Fix the inline feedback and try again.")
  }

  private fun launchSkillsManagementActivity(): SkillsManagementActivity {
    val intent = Intent(instrumentation.targetContext, SkillsManagementActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return (instrumentation.startActivitySync(intent) as SkillsManagementActivity).also { activity ->
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
      textView.isShown && textView.hasGlobalVisibleBounds()
    }

    assertTrue("Expected $queryDescription to be visible on screen.", isVisible)
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

    val actual = runOnActivity {
      requireEditText(hint) { true }.text?.toString().orEmpty()
    }

    assertEquals("Expected input with hint '$hint' to update on the main thread.", value, actual)
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
      errorTextView.hasGlobalVisibleBounds() to errorTextView.text.toString()
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

  private fun requireActivity(): SkillsManagementActivity =
    checkNotNull(launchedActivity) { "Expected a launched SkillsManagementActivity." }

  private fun <T> runOnActivity(block: SkillsManagementActivity.() -> T): T {
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
}

@RunWith(AndroidJUnit4::class)
class SkillsManagementLifecycleTest : SkillsManagementUiTestSupport() {

  @Test
  fun workspaceAuditSkillLifecycleCrudFlow() {
    assertWorkspaceAuditSkillLifecycleCrudFlow()
  }
}

@RunWith(AndroidJUnit4::class)
class SkillsEditorValidationTest : SkillsManagementUiTestSupport() {

  @Test
  fun draftSkillValidationInlineErrors() {
    assertDraftSkillValidationInlineErrors()
  }
}

// Learning: The seeded skill copy and input hints make stable Espresso coverage possible without adding test-only IDs.
// Issue: The inline validation text is duplicated in the editor summary, so the test needs a field-scoped matcher to assert the true inline error.
