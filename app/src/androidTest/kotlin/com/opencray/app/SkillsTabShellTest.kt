package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import com.opencray.ui.skills.SkillEditorViewModel
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillsTabShellTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val targetContext = instrumentation.targetContext
  private var launchedActivity: AppShellActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    clearSkillRoots()
    launchedActivity = null
  }

  @Test
  fun skillsTabShowsPrototypeLayoutFromRealManagedSkillsDirectory() {
    clearSkillRoots()
    seedSkill(
      root = SkillEditorViewModel.managedSkillsRootForContext(targetContext),
      folderName = "ui-ux-pro-max",
      name = "ui-ux-pro-max",
      description = "Design guidance and layout polish",
    )
    seedSkill(
      root = SkillEditorViewModel.managedSkillsRootForContext(targetContext),
      folderName = "ios-hig-design",
      name = "ios-hig-design",
      description = "Apple-style interface rules",
    )

    launchAppShellActivity()
    performClickOnText("Skills")

    assertTextVisibleWithoutScrolling("Workspace set")
    assertTextVisibleWithoutScrolling("Manage")
    assertTextVisibleWithoutScrolling("Install")
    assertTextVisible("ui-ux-pro-max")
    assertTextVisible("ios-hig-design")

    performClickOnText("ui-ux-pro-max")
    assertTextVisible("Upgrade")
    assertTextVisible("Delete")

    performClickOnText("Install")
    assertTextVisible("Install from")
    assertTextVisible("Curated library")
    assertTextVisible("Local folder")
  }

  private fun launchAppShellActivity(): AppShellActivity {
    val intent = Intent(targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.CHAT.name)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun seedSkill(
    root: File,
    folderName: String,
    name: String,
    description: String,
  ) {
    val skillDirectory = File(root, folderName)
    check(skillDirectory.mkdirs() || skillDirectory.isDirectory)
    File(skillDirectory, "SKILL.md").writeText(
      """
      ---
      name: $name
      description: $description
      invocation-control: explicit-and-implicit
      user-invocable: true
      context: inline
      ---
      # $name
      """.trimIndent(),
    )
  }

  private fun clearSkillRoots() {
    SkillEditorViewModel.managedSkillsRootForContext(targetContext).deleteRecursively()
    SkillEditorViewModel.catalogSkillsRootForContext(targetContext).deleteRecursively()
  }

  private fun assertTextVisible(text: String) {
    assertTextVisibleMatching(
      queryDescription = "text '$text'",
      textMatcher = { candidate -> candidate == text },
    )
  }

  private fun assertTextVisibleWithoutScrolling(text: String) {
    val isVisible = runOnActivity {
      val textView = requireTextView("text '$text'", { candidate -> candidate == text }) { it.isShown }
      hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected text '$text' to be visible without scrolling.", isVisible)
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
