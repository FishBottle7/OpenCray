package com.opencray.app

import android.content.ClipboardManager
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

abstract class FilesWorkbenchTestSupport {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private var launchedActivity: AppShellActivity? = null

  @After
  fun tearDown() {
    finishLaunchedActivity()
  }

  protected fun assertFilesWorkbenchHappyStateFlow() {
    launchFilesWorkbench(WorkspaceSettingsActivity.SCENARIO_ACTIVE_GRANT)

    assertTextVisible("Workspace access")
    assertTextVisible("GRANT ACTIVE")
    assertTextVisible("Workspace grant active")
    assertTextContainingVisible("Workspace root: projects/demo")
    assertTextVisible("Granted-root workbench")
    assertTextVisible("Current folder: docs")
    assertTextVisible("Refresh list")
    assertTextVisible("Create file")
    assertTextVisible("Create folder")
    assertTextVisible("Rename selected")
    assertTextVisible("Delete selected")
    assertTextVisible("Copy relative path")
    assertTextContainingVisible("Quarterly demo report stays inside the granted root")

    setEditTextValue("New item name", "session-note.txt")
    performClickOnText("Create file")
    assertTextVisible("session-note.txt")
    assertTextVisible("Created file: docs/session-note.txt")

    setEditTextValue("Rename selected to", "session-note-renamed.txt")
    performClickOnText("Rename selected")
    assertTextVisible("session-note-renamed.txt")
    assertTextVisible("Renamed to: docs/session-note-renamed.txt")

    performClickOnText("Copy relative path")
    assertTextVisible("Copied relative path: docs/session-note-renamed.txt")
    assertClipboardText("docs/session-note-renamed.txt")

    setEditTextValue("New item name", "draft-copies")
    performClickOnText("Create folder")
    assertTextVisible("draft-copies/")
    assertTextVisible("Created folder: docs/draft-copies")

    performClickOnText("draft-copies/")
    assertTextVisible("Current folder: docs/draft-copies")
    assertTextVisible("This folder is empty.")

    setEditTextValue("Rename selected to", "draft-copies-renamed")
    performClickOnText("Rename selected")
    assertTextVisible("Renamed to: docs/draft-copies-renamed")
    assertTextVisible("Current folder: docs/draft-copies-renamed")

    performClickOnText("Up one folder")
    assertTextVisible("Current folder: docs")
    assertTextVisible("draft-copies-renamed/")

    performClickOnText("draft-copies-renamed/")
    performClickOnText("Delete selected")
    assertTextVisible("Deleted: docs/draft-copies-renamed")
    assertTextNotVisible("draft-copies-renamed/")

    performClickOnText("session-note-renamed.txt")
    performClickOnText("Delete selected")
    assertTextVisible("Deleted: docs/session-note-renamed.txt")
    assertTextNotVisible("session-note-renamed.txt")

    performClickOnText("Refresh list")
    assertTextVisible("Refreshed current folder.")

    performClickOnText("Open workspace root")
    assertTextVisible("Current folder: workspace root")
    assertTextVisible("workspace-notes.txt")

    performClickOnText("Clear grant")

    assertTextVisible("NO GRANT")
    assertTextVisible("No workspace grant yet")
    assertTextVisible("Granted-root workbench")
    assertTextVisible("Unavailable until a workspace grant is active.")
    assertTextVisible("Pick workspace")

    performClickOnText("Pick workspace")

    assertTextVisible("GRANT ACTIVE")
    assertTextVisible("Workspace grant active")
    assertTextVisible("Granted-root workbench")
  }

  protected fun assertFilesWorkbenchDeniedFamiliesRemainRecoverable() {
    launchFilesWorkbench(WorkspaceSettingsActivity.SCENARIO_REVOKED_GRANT)

    assertTextVisible("RECOVERY NEEDED")
    assertTextVisible("Workspace permission was revoked")
    assertTextVisible("Re-authorize workspace")
    assertTextVisible("Granted-root workbench")
    assertTextVisible("Re-authorize the saved root before browsing or editing continues.")

    performClickOnText("Re-authorize workspace")

    assertTextVisible("GRANT ACTIVE")
    assertTextVisible("Current folder: docs")

    launchFilesWorkbench(WorkspaceSettingsActivity.SCENARIO_OUTSIDE_ROOT_DENIAL)

    assertTextVisible("OUTSIDE ROOT")
    assertTextVisible("Request blocked by the granted root")
    assertTextVisible("Requested location is outside the granted root")
    assertTextContainingVisible("this request falls outside projects/demo")
    assertTextVisible("Granted-root workbench")
    assertTextVisible("Current folder: workspace root")
    assertTextVisible("workspace-notes.txt")
    assertTextVisible("Refresh list")

    performClickOnText("workspace-notes.txt")
    assertTextContainingVisible("This lightweight workbench stays inside projects/demo only")

    performClickOnText("Refresh list")
    assertTextVisible("Refreshed current folder.")

    performClickOnText("Pick workspace")

    assertTextVisible("GRANT ACTIVE")
  }

  protected fun assertCurrentFolderSearchFiltersByName() {
    launchFilesWorkbench(WorkspaceSettingsActivity.SCENARIO_ACTIVE_GRANT)

    assertTextVisible("report.md")
    assertTextVisible("checklist.txt")
    assertTextVisible("Search current folder")

    setEditTextValue("Search current folder", "report")
    performClickOnText("Search current folder")

    assertTextVisible("Filtering current folder by name: report")
    assertTextVisible("report.md")
    assertTextNotVisible("checklist.txt")

    performClickOnText("Clear search")

    assertTextVisible("Cleared the current-folder search filter.")
    assertTextVisible("checklist.txt")
  }

  protected fun assertPreviewContractForSmallLargeAndBinaryFiles() {
    launchFilesWorkbench(WorkspaceSettingsActivity.SCENARIO_ACTIVE_GRANT)

    performClickOnText("report.md")
    assertTextContainingVisible("Quarterly demo report stays inside the granted root")

    performClickOnText("oversized-preview.txt")
    assertTextContainingVisible("Metadata only fallback")
    assertTextContainingVisible("larger than the 128 KB UTF-8 preview limit")

    performClickOnText("binary-preview.bin")
    assertTextContainingVisible("Metadata only fallback")
    assertTextContainingVisible("binary or not valid UTF-8 text")
  }

  private fun launchFilesWorkbench(filesScenario: String): AppShellActivity {
    finishLaunchedActivity()

    val intent = Intent(instrumentation.targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.FILES.routeKey)
      putExtra(AppShellNavigationExtras.EXTRA_FILES_SCENARIO, filesScenario)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun finishLaunchedActivity() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    launchedActivity = null
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

  private fun assertTextNotVisible(text: String) {
    val isVisible = runOnActivity {
      val matches = mutableListOf<TextView>()
      collectTextViews(requireActivity().window.decorView.rootView, { candidate -> candidate == text }, matches)
      matches.any { it.isShown && it.hasGlobalVisibleBounds() }
    }
    assertFalse("Expected text '$text' to be absent or off-screen.", isVisible)
  }

  private fun performClickOnText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView("clickable text '$text'", { candidate -> candidate == text }) {
        it.isShown && it.isEnabled && it.isClickable
      }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      textView.performClick() || textView.callOnClick()
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
    assertEquals("Expected EditText '$hint' to match.", value, actual)
  }

  private fun assertClipboardText(expected: String) {
    val actual = runOnActivity {
      val clipboard = getSystemService(ClipboardManager::class.java)
      clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    }
    assertEquals("Expected clipboard text to match the copied relative path.", expected, actual)
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

  private fun View.hasGlobalVisibleBounds(): Boolean {
    val rect = Rect()
    return getGlobalVisibleRect(rect) && !rect.isEmpty
  }
}

@RunWith(AndroidJUnit4::class)
class FilesWorkbenchTest : FilesWorkbenchTestSupport() {

  @Test
  fun filesWorkbenchHappyStateFlow() {
    assertFilesWorkbenchHappyStateFlow()
  }

  @Test
  fun filesWorkbenchDeniedFamiliesRemainRecoverable() {
    assertFilesWorkbenchDeniedFamiliesRemainRecoverable()
  }

  @Test
  fun currentFolderSearchFiltersByName() {
    assertCurrentFolderSearchFiltersByName()
  }

  @Test
  fun previewContractUsesUtf8PreviewAndMetadataFallbacks() {
    assertPreviewContractForSmallLargeAndBinaryFiles()
  }
}

// Learning: Stable button labels and input hints make it practical to verify real workbench actions in androidTest without adding test-only view IDs.
// Issue: The required Gradle verification only compiles androidTest sources here, so device-side execution still depends on whether an emulator or attached test device is available.
