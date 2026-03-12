package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R

@RunWith(AndroidJUnit4::class)
class BottomNavShellTest {
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
  fun bottomNavigationShowsFourTabsInRequiredOrderAndRoutesAcrossShell() {
    launchAppShellActivity()

    val requiredTabs = requiredTabLabels()

    assertActivityTitle(requiredTabs.first())
    assertBottomNavOrder(requiredTabs)
    requiredTabs.forEach(::assertBottomNavButtonVisible)

    requiredTabs.drop(1).forEach { label ->
      performClickOnBottomNavButton(label)
      assertActivityTitle(label)
      assertBottomNavOrder(requiredTabs)
    }

    performClickOnBottomNavButton(requiredTabs.first())
    assertActivityTitle(requiredTabs.first())
  }

  private fun launchAppShellActivity(): AppShellActivity {
    AppShellStateStore.fromContext(targetContext).clear()

    val intent = Intent(targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.CHAT.name)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun requiredTabLabels(): List<String> = listOf(
    targetContext.getString(R.string.shell_tab_chat),
    targetContext.getString(R.string.shell_tab_skills),
    targetContext.getString(R.string.shell_tab_files),
    targetContext.getString(R.string.shell_tab_settings),
  )

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertBottomNavOrder(expected: List<String>) {
    val actual = runOnActivity {
      requireBottomNavigationButtons(expected.toSet()).map { it.text.toString() }
    }

    assertEquals("Expected the bottom navigation tabs in the required order.", expected, actual)
  }

  private fun assertBottomNavButtonVisible(label: String) {
    val isVisible = runOnActivity {
      val button = requireBottomNavButton(label)
      button.requestRectangleOnScreen(viewBounds(button), true)
      button.isShown && hasGlobalVisibleBounds(button)
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected bottom navigation tab '$label' to be visible.", isVisible)
  }

  private fun performClickOnBottomNavButton(label: String) {
    val clicked = runOnActivity {
      val button = requireBottomNavButton(label)
      button.requestRectangleOnScreen(viewBounds(button), true)
      button.performClick() || button.callOnClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a bottom navigation click on '$label' to succeed.", clicked)
  }

  private fun requireBottomNavButton(label: String): Button =
    requireBottomNavigationButtons(requiredTabLabels().toSet()).firstOrNull { it.text.toString() == label }
      ?: throw AssertionError("Expected bottom navigation tab '$label' to exist.")

  private fun requireBottomNavigationButtons(expectedLabels: Set<String>): List<Button> =
    findBottomNavigationButtons(requireActivity().window.decorView.rootView, expectedLabels)
      ?: throw AssertionError("Expected a visible bottom navigation bar with four tab buttons.")

  private fun findBottomNavigationButtons(root: View, expectedLabels: Set<String>): List<Button>? {
    if (root is ViewGroup) {
      val directButtons = (0 until root.childCount)
        .map(root::getChildAt)
        .filterIsInstance<Button>()
        .filter { it.isShown && hasGlobalVisibleBounds(it) }

      if (
        directButtons.size == expectedLabels.size &&
          directButtons.map { it.text.toString() }.toSet() == expectedLabels
      ) {
        return directButtons
      }

      for (index in 0 until root.childCount) {
        findBottomNavigationButtons(root.getChildAt(index), expectedLabels)?.let { return it }
      }
    }

    return null
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
