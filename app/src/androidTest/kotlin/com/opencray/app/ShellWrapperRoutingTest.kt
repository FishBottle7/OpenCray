package com.opencray.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R

@RunWith(AndroidJUnit4::class)
class ShellWrapperRoutingTest {
  private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
  private val targetContext: Context = instrumentation.targetContext
  private var launchedShellActivity: AppShellActivity? = null

  @After
  fun tearDown() {
    finishLaunchedShell()
    AppShellStateStore.fromContext(targetContext).clear()
  }

  @Test
  fun manifestKeepsAppShellAsOnlyLauncherAndLegacyWrappersExported() {
    val launcherActivities =
      targetContext.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_LAUNCHER)
          setPackage(targetContext.packageName)
        },
        0,
      ).map { resolveInfo ->
        resolveInfo.activityInfo.name
      }.sorted()

    assertEquals(
      "Expected AppShellActivity to remain the only launcher.",
      listOf(AppShellActivity::class.java.name),
      launcherActivities,
    )

    assertActivityExported(SkillsManagementActivity::class.java)
    assertActivityExported(MainInteractionActivity::class.java)
    assertActivityExported(WorkspaceSettingsActivity::class.java)
    assertActivityExported(SafetyAndLimitsActivity::class.java)
  }

  @Test
  fun skillsManagementWrapperRoutesIntoSkillsTab() {
    launchWrapperIntoShell(SkillsManagementActivity::class.java)

    assertShellChromeVisible()
    assertActivityTitle(targetContext.getString(R.string.shell_tab_skills))
    assertTextVisible("Create new draft")
  }

  @Test
  fun mainInteractionWrapperRoutesIntoChatTabAndPreservesScenario() {
    launchWrapperIntoShell(MainInteractionActivity::class.java) {
      putExtra(MainInteractionActivity.EXTRA_SCENARIO, MainInteractionActivity.SCENARIO_DENIED_POLICY)
    }

    assertShellChromeVisible()
    assertActivityTitle(targetContext.getString(R.string.shell_tab_chat))
    assertTextVisible("Blocked by policy")
    assertTextVisible("Apply protected workspace write")
  }

  @Test
  fun workspaceSettingsWrapperRoutesIntoFilesTabAndPreservesScenario() {
    launchWrapperIntoShell(WorkspaceSettingsActivity::class.java) {
      putExtra(WorkspaceSettingsActivity.EXTRA_SCENARIO, WorkspaceSettingsActivity.SCENARIO_ACTIVE_GRANT)
    }

    assertShellChromeVisible()
    assertActivityTitle(targetContext.getString(R.string.shell_tab_files))
    assertTextVisible("GRANT ACTIVE")
    assertTextVisible("Workspace grant active")
  }

  @Test
  fun safetyAndLimitsWrapperRoutesIntoSettingsSafetySubpage() {
    launchWrapperIntoShell(SafetyAndLimitsActivity::class.java)

    assertShellChromeVisible()
    assertActivityTitle(targetContext.getString(R.string.settings_card_safety_limits))
    assertTextVisible("← Settings")
    assertTextVisible(
      "Developer mode does not override hard denials. Protected-file, path, and other hard policy denials still stop the action.",
    )
  }

  private fun launchWrapperIntoShell(
    wrapperClass: Class<out Activity>,
    configureIntent: Intent.() -> Unit = {},
  ): AppShellActivity {
    finishLaunchedShell()
    AppShellStateStore.fromContext(targetContext).clear()

    val monitor = instrumentation.addMonitor(AppShellActivity::class.java.name, null, false)
    try {
      targetContext.startActivity(
        Intent(targetContext, wrapperClass).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          configureIntent()
        },
      )

      val launchedActivity =
        instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? AppShellActivity
          ?: throw AssertionError(
            "Expected ${wrapperClass.simpleName} to forward into AppShellActivity.",
          )
      launchedShellActivity = launchedActivity
      instrumentation.waitForIdleSync()
      return launchedActivity
    } finally {
      instrumentation.removeMonitor(monitor)
    }
  }

  private fun finishLaunchedShell() {
    launchedShellActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    launchedShellActivity = null
  }

  private fun assertShellChromeVisible() {
    assertTextVisible(targetContext.getString(R.string.shell_tab_chat))
    assertTextVisible(targetContext.getString(R.string.shell_tab_skills))
    assertTextVisible(targetContext.getString(R.string.shell_tab_files))
    assertTextVisible(targetContext.getString(R.string.shell_tab_settings))
  }

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertActivityExported(activityClass: Class<out Activity>) {
    val activityInfo =
      targetContext.packageManager.getActivityInfo(
        ComponentName(targetContext, activityClass),
        0,
      )
    assertTrue(
      "Expected ${activityClass.simpleName} to remain exported for compatibility.",
      activityInfo.exported,
    )
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
    checkNotNull(launchedShellActivity) { "Expected a launched AppShellActivity." }

  private fun <T> runOnActivity(block: AppShellActivity.() -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync {
      result = runCatching { requireActivity().block() }
    }
    return checkNotNull(result).getOrThrow()
  }
}
