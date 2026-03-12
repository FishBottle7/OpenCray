package com.opencray.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R

@RunWith(AndroidJUnit4::class)
class AboutVersionScreenTest {
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
  fun aboutScreenShowsAppMetadataAndGuardrailSummary() {
    launchAboutScreen()

    assertActivityTitle(aboutTitle())
    assertTextVisible("OpenCray")
    assertTextContainingVisible("Chat, Skills, Files, and Settings inside one shell")
    assertTextVisible("Release guardrails verified")
    assertTextVisible("Version ${installedVersionName()}")
    assertTextVisible("Build ${installedVersionCode()}")
    assertTextVisible(minimumAndroidLabel())
    assertTextContainingVisible("Protected paths and other blocked actions stay unavailable")
    assertTextContainingVisible("Rollback only covers local file checkpoints")
    assertTextContainingVisible("V1 stays focused")
  }

  @Test
  fun aboutScreenStaysConsumerFacingInsteadOfDiagnosticHeavy() {
    launchAboutScreen()

    assertTextVisible("Build details")
    assertTextVisible("Release guardrails verified")
    assertNoVisibleTextContains("Exception")
    assertNoVisibleTextContains("Stack trace")
    assertNoVisibleTextContains("NullPointerException")
    assertNoVisibleTextContains("DEBUG")
    assertNoVisibleTextContains("thread dump")
  }

  private fun launchAboutScreen(): AppShellActivity {
    AppShellStateStore.fromContext(targetContext).clear()

    val intent = Intent(targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SETTINGS.name)
      putExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE, SettingsSubpage.ABOUT.name)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun aboutTitle(): String = targetContext.getString(R.string.settings_card_about_version)

  private fun installedVersionName(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      targetContext.packageManager.getPackageInfo(
        targetContext.packageName,
        PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
    }

    return packageInfo.versionName?.trim().orEmpty().ifBlank { "0" }
  }

  private fun installedVersionCode(): Long {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      targetContext.packageManager.getPackageInfo(
        targetContext.packageName,
        PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      packageInfo.versionCode.toLong()
    }
  }

  private fun minimumAndroidLabel(): String {
    val minSdk = targetContext.applicationInfo.minSdkVersion
    val releaseLabel = when (minSdk) {
      26 -> "Android 8.0+"
      27 -> "Android 8.1+"
      28 -> "Android 9+"
      29 -> "Android 10+"
      30 -> "Android 11+"
      31 -> "Android 12+"
      32 -> "Android 12L+"
      33 -> "Android 13+"
      34 -> "Android 14+"
      else -> "Android"
    }

    return "$releaseLabel, API $minSdk"
  }

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertTextVisible(text: String) {
    runOnActivity {
      val textView = requireTextView("text '$text'", { candidate -> candidate == text }) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView("text '$text'", { candidate -> candidate == text }) { it.isShown }
      textView.isShown && hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected '$text' to be visible on screen.", isVisible)
  }

  private fun assertTextContainingVisible(fragment: String) {
    runOnActivity {
      val textView = requireTextView(
        "text containing '$fragment'",
        { candidate -> candidate.contains(fragment) },
      ) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView(
        "text containing '$fragment'",
        { candidate -> candidate.contains(fragment) },
      ) { it.isShown }
      textView.isShown && hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected text containing '$fragment' to be visible on screen.", isVisible)
  }

  private fun assertNoVisibleTextContains(fragment: String) {
    val hasVisibleMatch = runOnActivity {
      collectVisibleTextValues().any { it.contains(fragment, ignoreCase = true) }
    }

    assertFalse("Expected no visible text containing '$fragment'.", hasVisibleMatch)
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

  private fun collectVisibleTextValues(): List<String> = buildList {
    collectVisibleTextValues(requireActivity().window.decorView.rootView, this)
  }

  private fun collectVisibleTextValues(root: View, values: MutableList<String>) {
    if (root is TextView && root.isShown) {
      values += root.text.toString()
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectVisibleTextValues(root.getChildAt(index), values)
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
