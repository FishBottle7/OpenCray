package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillsManagementUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val targetContext = instrumentation.targetContext
  private var launchedActivity: SkillsManagementActivity? = null

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
  fun wrapperActivityShowsRealInstalledSkillsAndInstallTab() {
    clearSkillRoots()
    seedSkill(
      root = AppSkillsStorage.managedSkillsRootForContext(targetContext),
      folderName = "skill-installer",
      name = "skill-installer",
      description = "Install curated or local skills",
    )
    seedSkill(
      root = AppSkillsStorage.catalogSkillsRootForContext(targetContext),
      folderName = "find-skills",
      name = "find-skills",
      description = "Suggests skills for a task",
    )

    launchSkillsManagementActivity()

    assertTextVisible("Skills")
    assertTextVisible("skill-installer")
    assertTextVisible("Manage")
    assertTextVisible("Install")

    performClickOnText("Install")
    assertTextVisible("Suggested")
    assertTextVisible("find-skills")
    assertTextVisible("Install from")
  }

  private fun launchSkillsManagementActivity() {
    val intent = Intent(targetContext, SkillsManagementActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    launchedActivity = instrumentation.startActivitySync(intent) as SkillsManagementActivity
    instrumentation.waitForIdleSync()
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
    AppSkillsStorage.managedSkillsRootForContext(targetContext).deleteRecursively()
    AppSkillsStorage.catalogSkillsRootForContext(targetContext).deleteRecursively()
  }

  private fun assertTextVisible(text: String) {
    runOnActivity {
      val textView = requireTextView("text '$text'") { candidate -> candidate == text }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView("text '$text'") { candidate -> candidate == text }
      hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected text '$text' to be visible on screen.", isVisible)
  }

  private fun performClickOnText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView("clickable text '$text'") { candidate -> candidate == text && isClickable }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      textView.performClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a main-thread click on '$text' to succeed.", clicked)
  }

  private fun requireTextView(
    queryDescription: String,
    predicate: TextView.(String) -> Boolean,
  ): TextView {
    val matches = mutableListOf<TextView>()
    collectTextViews(requireActivity().window.decorView.rootView, matches)
    return matches.firstOrNull { view -> view.isShown && view.predicate(view.text.toString()) }
      ?: throw AssertionError("Expected to find $queryDescription in the launched activity.")
  }

  private fun collectTextViews(
    root: View,
    matches: MutableList<TextView>,
  ) {
    if (root is TextView) {
      matches += root
    }
    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectTextViews(root.getChildAt(index), matches)
      }
    }
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

  private fun hasGlobalVisibleBounds(view: View): Boolean {
    val rect = Rect()
    return view.getGlobalVisibleRect(rect) && !rect.isEmpty
  }
}
