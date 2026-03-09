package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.ui.files.WorkspacePickerScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

abstract class SafIntegrationTestSupport {
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

  protected fun assertGrantAllowsInRootOpsUiState() {
    val state = workspacePickerState(
      snapshot = grantedSnapshot(),
      request = relativePathRequest("projects/demo/docs/report.md"),
    )

    showWorkspacePicker(state)

    assertTextVisible("GRANT ACTIVE")
    assertTextVisible("Workspace grant active")
    assertTextVisible("Granted root summary")
    assertTextContainingVisible("Workspace root: projects/demo")
    assertTextVisible("Pick workspace")
    assertTextVisible("Clear grant")
  }

  protected fun assertRevocationRecoveryUiState() {
    val state = workspacePickerState(
      snapshot = revokedSnapshot(grantedSnapshot(), revokedAtEpochMillis = 2_000L),
      request = relativePathRequest("projects/demo/docs/report.md"),
    )

    showWorkspacePicker(state)

    assertTextVisible("RECOVERY NEEDED")
    assertTextVisible("Workspace permission was revoked")
    assertTextContainingVisible("Re-authorize workspace to recover access to projects/demo.")
    assertTextContainingVisible("Saved grant: revoked • revoked-at=2000")
    assertTextVisible("Re-authorize workspace")
  }

  private fun workspacePickerState(
    snapshot: Any,
    request: Any,
  ): Any {
    val bridge = defaultSafWorkspaceBridge(
      inMemorySafWorkspaceGrantStore(initialGrants = listOf(snapshot)),
    )
    val stateClass = loadClass("com.opencray.ui.files.WorkspacePickerScreenState")
    val bridgeClass = loadClass("com.opencray.filesystem.SafWorkspaceBridge")
    val requestClass = loadClass("com.opencray.filesystem.SafAccessRequest")

    return invokeCompanion(
      ownerClass = stateClass,
      methodName = "fromBridge",
      parameterTypes = arrayOf(
        bridgeClass,
        String::class.java,
        requestClass,
        String::class.java,
        String::class.java,
      ),
      args = arrayOf(
        bridge,
        WORKSPACE_ID,
        request,
        "Workspace access",
        "Manual instrumentation-safe host rendering keeps SAF state visible.",
      ),
    )
  }

  private fun showWorkspacePicker(state: Any) {
    launchHostActivity()
    runOnActivity {
      val screen = WorkspacePickerScreen(this)
      screen.submitStateReflectively(state)
      setContentView(screen)
    }
    instrumentation.waitForIdleSync()
  }

  private fun grantedSnapshot(): Any = invokeCompanion(
    ownerClass = loadClass("com.opencray.filesystem.PersistedSafGrantSnapshot"),
    methodName = "fromTreeUri",
    parameterTypes = arrayOf(
      String::class.java,
      String::class.java,
      Long::class.javaPrimitiveType!!,
      String::class.java,
    ),
    args = arrayOf(
      WORKSPACE_ID,
      "content://com.android.externalstorage.documents/tree/primary%3AOpenCray%2Fprojects%2Fdemo",
      1_000L,
      "projects/demo",
    ),
  )

  private fun revokedSnapshot(snapshot: Any, revokedAtEpochMillis: Long): Any =
    checkNotNull(
      snapshot.javaClass
        .getMethod("asRevoked", Long::class.javaPrimitiveType!!)
        .invoke(snapshot, revokedAtEpochMillis),
    ) { "Expected PersistedSafGrantSnapshot.asRevoked() to return a snapshot." }

  private fun relativePathRequest(rawValue: String): Any = loadClass(
    "com.opencray.filesystem.SafAccessRequest\$RelativePath",
  ).getConstructor(String::class.java).newInstance(rawValue)

  private fun inMemorySafWorkspaceGrantStore(initialGrants: List<Any>): Any = loadClass(
    "com.opencray.filesystem.InMemorySafWorkspaceGrantStore",
  ).getConstructor(Iterable::class.java).newInstance(initialGrants)

  private fun defaultSafWorkspaceBridge(store: Any): Any = loadClass(
    "com.opencray.filesystem.DefaultSafWorkspaceBridge",
  ).getConstructor(loadClass("com.opencray.filesystem.SafWorkspaceGrantStore")).newInstance(store)

  private fun invokeCompanion(
    ownerClass: Class<*>,
    methodName: String,
    parameterTypes: Array<Class<*>>,
    args: Array<Any>,
  ): Any {
    val companion = ownerClass.getDeclaredField("Companion").get(null)
    return checkNotNull(
      companion.javaClass.getMethod(methodName, *parameterTypes).invoke(companion, *args),
    ) { "Expected $methodName on ${ownerClass.name} companion to return a value." }
  }

  private fun loadClass(className: String): Class<*> = Class.forName(className)

  private fun launchHostActivity(): SkillsManagementActivity {
    if (launchedActivity != null) {
      return requireActivity()
    }

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

  private fun WorkspacePickerScreen.submitStateReflectively(state: Any) {
    val stateClass = loadClass("com.opencray.ui.files.WorkspacePickerScreenState")
    javaClass.getMethod("submitState", stateClass).invoke(this, state)
  }

  private companion object {
    private const val WORKSPACE_ID = "workspace-saf-ui"
  }
}

@RunWith(AndroidJUnit4::class)
class SafGrantAllowsInRootOps : SafIntegrationTestSupport() {

  @Test
  fun safGrantAllowsInRootOps() {
    assertGrantAllowsInRootOpsUiState()
  }
}

@RunWith(AndroidJUnit4::class)
class SafRevocationRecoveryTest : SafIntegrationTestSupport() {

  @Test
  fun safRevocationRecoveryUiState() {
    assertRevocationRecoveryUiState()
  }
}

// Issue: app androidTest does not compile against :filesystem directly, so this slice reflects into the real SAF bridge/state types instead of changing production Gradle wiring.
