package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.ui.chat.ApprovalPromptState
import com.opencray.ui.chat.ApprovalPromptStatus
import com.opencray.ui.chat.ChatMode
import com.opencray.ui.chat.ChatScreen
import com.opencray.ui.chat.ChatScreenState
import com.opencray.ui.chat.ConversationHeaderState
import com.opencray.ui.chat.ModeState
import com.opencray.ui.timeline.ActionApprovalState
import com.opencray.ui.timeline.ActionPolicyDecision
import com.opencray.ui.timeline.ActionResultStatus
import com.opencray.ui.timeline.ActionTimelineItem
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import kotlin.Function0
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityPolicyE2ETest {
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

  @Test
  fun protectedFileInvariantDenialIsVisibleEndToEnd() {
    val outcome = protectedFileDenialOutcome()

    showOutcome(outcome)

    assertDeniedUiVisible(outcome)
  }

  @Test
  fun pathEscapeDenialIsVisibleEndToEnd() {
    val outcome = pathEscapeDenialOutcome()

    showOutcome(outcome)

    assertDeniedUiVisible(outcome)
  }

  private fun protectedFileDenialOutcome(): SecurityOutcome {
    val workspaceRoot = freshWorkspace("security-policy-e2e-protected")
    val protectedFile = workspaceRoot.resolve("agent.md")
    Files.write(protectedFile, "protected".toByteArray(StandardCharsets.UTF_8))

    val policyDecision = decidePolicy(
      modeName = "DEVELOPER",
      toolClassName = "DELETE_FILE",
      workspaceRoot = workspaceRoot,
      targetPath = Paths.get("agent.md"),
    )
    assertEquals(PolicyDecisionOutcome.DENY, policyDecision.outcome)
    assertEquals("DENY_PROTECTED_FILE", policyDecision.reasonCode)

    val runtimeFailure = executeBatchExpectingFailure(
      workspaceRoot = workspaceRoot,
      operation = deleteOperation(Paths.get("agent.md")),
    )
    assertEquals("DENY_PROTECTED_FILE", runtimeFailure.reasonCode)
    assertTrue(Files.exists(protectedFile))
    assertEquals(
      "protected",
      String(Files.readAllBytes(protectedFile), StandardCharsets.UTF_8),
    )

    return SecurityOutcome(
      operationLabel = "Delete protected workspace file",
      reasonCode = policyDecision.reasonCode,
      promptMessage = "Protected-file invariant denial blocked the destructive request before any workspace mutation ran.",
      reasonText = buildReasonText(policyDecision, runtimeFailure),
      detailFragment = "agent.md",
    )
  }

  private fun pathEscapeDenialOutcome(): SecurityOutcome {
    val workspaceRoot = freshWorkspace("security-policy-e2e-path-escape")
    val outsidePath = workspaceRoot.resolveSibling("security-policy-e2e-outside.txt")
    Files.deleteIfExists(outsidePath)

    val policyDecision = decidePolicy(
      modeName = "DEVELOPER",
      toolClassName = "WRITE_FILE",
      workspaceRoot = workspaceRoot,
      targetPath = outsidePath,
    )
    assertEquals(PolicyDecisionOutcome.DENY, policyDecision.outcome)
    assertEquals("DENY_PATH_ESCAPE", policyDecision.reasonCode)

    val runtimeFailure = executeBatchExpectingFailure(
      workspaceRoot = workspaceRoot,
      operation = writeOperation(outsidePath, "blocked"),
    )
    assertEquals("DENY_PATH_ESCAPE", runtimeFailure.reasonCode)
    assertTrue(Files.notExists(outsidePath))

    return SecurityOutcome(
      operationLabel = "Write outside approved workspace",
      reasonCode = policyDecision.reasonCode,
      promptMessage = "Path-escape denial blocked the write before any file could leave the approved workspace root.",
      reasonText = buildReasonText(policyDecision, runtimeFailure),
      detailFragment = "escapes",
    )
  }

  private fun buildReasonText(
    policyDecision: PolicyDecision,
    runtimeFailure: ReflectedFailure,
  ): String = buildString {
    append(policyDecision.reasonCode)
    append(" | policy=")
    append(policyDecision.detail ?: "No policy detail was returned.")
    append(" | runtime=")
    append(runtimeFailure.message)
  }

  private fun showOutcome(outcome: SecurityOutcome) {
    launchHostActivity()
    runOnActivity {
      val hostActivity = this
      val screen = ChatScreen(hostActivity).apply {
        setListener(hostActivity)
        submitState(outcome.toChatScreenState())
      }
      setContentView(screen)
    }
    instrumentation.waitForIdleSync()
  }

  private fun assertDeniedUiVisible(outcome: SecurityOutcome) {
    assertTextVisible("Blocked by policy")
    assertTextVisible(outcome.promptMessage)
    assertTextVisible(outcome.operationLabel)
    assertTextVisible("POLICY DENY")
    assertTextVisible("RESULT FAILED")
    assertTextVisible("NO APPROVAL")
    assertTextContainingVisible("Status: DENIED | Reason: ${outcome.reasonCode}")
    assertTextContainingVisible(outcome.reasonCode)
    assertTextContainingVisible(outcome.detailFragment)
  }

  private fun decidePolicy(
    modeName: String,
    toolClassName: String,
    workspaceRoot: Path,
    targetPath: Path,
    destinationPath: Path? = null,
  ): PolicyDecision {
    val requestClass = loadClass("com.opencray.policy.PolicyRequest")
    val request = requestClass.getConstructor(
      loadClass("com.opencray.policy.ExecutionMode"),
      loadClass("com.opencray.policy.PolicyToolClass"),
      Path::class.java,
      Path::class.java,
      Path::class.java,
    ).newInstance(
      enumConstant("com.opencray.policy.ExecutionMode", modeName),
      enumConstant("com.opencray.policy.PolicyToolClass", toolClassName),
      workspaceRoot,
      targetPath,
      destinationPath,
    )

    val policyClass = loadClass("com.opencray.policy.ModePolicy")
    val policy = policyClass.getConstructor(loadClass("com.opencray.policy.ProtectedRegistry"))
      .newInstance(newProtectedRegistry())

    return policyClass.getMethod("decide", requestClass).invoke(policy, request) as PolicyDecision
  }

  private fun executeBatchExpectingFailure(
    workspaceRoot: Path,
    operation: Any,
  ): ReflectedFailure {
    val protectedRegistryClass = loadClass("com.opencray.policy.ProtectedRegistry")
    val rollbackJournalClass = loadClass("com.opencray.filesystem.RollbackJournal")
    val serviceClass = loadClass("com.opencray.filesystem.FileOpsService")
    val service = serviceClass.getConstructor(
      Set::class.java,
      protectedRegistryClass,
      rollbackJournalClass,
    ).newInstance(
      setOf(workspaceRoot),
      newProtectedRegistry(),
      newRollbackJournal(),
    )

    val failure = captureFailure {
      serviceClass.getMethod("executeBatch", List::class.java).invoke(service, listOf(operation))
    }

    val reasonCode = failure.javaClass.getMethod("getReasonCode").invoke(failure) as String
    return ReflectedFailure(
      reasonCode = reasonCode,
      message = failure.message ?: failure.javaClass.simpleName,
    )
  }

  private fun deleteOperation(targetPath: Path): Any = loadClass(
    "com.opencray.filesystem.FileMutationOperation\$Delete",
  ).getConstructor(Path::class.java).newInstance(targetPath)

  private fun writeOperation(
    targetPath: Path,
    content: String,
  ): Any = loadClass(
    "com.opencray.filesystem.FileMutationOperation\$Write",
  ).getConstructor(Path::class.java, String::class.java).newInstance(targetPath, content)

  private fun newProtectedRegistry(): Any = loadClass(
    "com.opencray.policy.ProtectedRegistry",
  ).getConstructor(Set::class.java).newInstance(emptySet<String>())

  private fun newRollbackJournal(): Any {
    val clock = object : Function0<Long> {
      override fun invoke(): Long = System.currentTimeMillis()
    }

    return loadClass("com.opencray.filesystem.LocalRollbackJournal").getConstructor(
      Function0::class.java,
      AtomicLong::class.java,
    ).newInstance(clock, AtomicLong(0L))
  }

  private fun captureFailure(block: () -> Any?): Throwable = try {
    block()
    throw AssertionError("Expected the reflected security operation to fail.")
  } catch (failure: InvocationTargetException) {
    failure.targetException ?: failure
  }

  private fun enumConstant(className: String, constantName: String): Any {
    val values = loadClass(className).enumConstants.orEmpty()
    return values.firstOrNull { candidate ->
      (candidate as Enum<*>).name == constantName
    } ?: throw AssertionError("Expected enum constant $constantName on $className.")
  }

  private fun freshWorkspace(directoryName: String): Path {
    val workspace = File(instrumentation.targetContext.cacheDir, directoryName)
    if (workspace.exists()) {
      workspace.deleteRecursively()
    }
    assertTrue("Expected $directoryName workspace directory to be created.", workspace.mkdirs())
    return workspace.toPath()
  }

  private fun launchHostActivity(): MainInteractionActivity {
    if (launchedActivity != null) {
      return requireActivity()
    }

    val intent = Intent(instrumentation.targetContext, MainInteractionActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(
        MainInteractionActivity.EXTRA_SCENARIO,
        MainInteractionActivity.SCENARIO_DEFAULT_APPROVAL,
      )
    }

    return (instrumentation.startActivitySync(intent) as MainInteractionActivity).also { activity ->
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

  private fun requireActivity(): MainInteractionActivity =
    checkNotNull(launchedActivity) { "Expected a launched MainInteractionActivity." }

  private fun <T> runOnActivity(block: MainInteractionActivity.() -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync {
      result = runCatching { requireActivity().block() }
    }
    return checkNotNull(result).getOrThrow()
  }

  private fun loadClass(className: String): Class<*> = Class.forName(className)

  private data class ReflectedFailure(
    val reasonCode: String,
    val message: String,
  )

  private data class SecurityOutcome(
    val operationLabel: String,
    val reasonCode: String,
    val promptMessage: String,
    val reasonText: String,
    val detailFragment: String,
  ) {
    fun toChatScreenState(): ChatScreenState = ChatScreenState(
      headerState = ConversationHeaderState(
        title = "OpenCray Chat",
        subtitle = "Developer mode still exposes hard denials when security invariants reject a workspace mutation.",
        queuedActionCount = 0,
        isQueueVisible = true,
      ),
      modeState = ModeState(
        selectedMode = ChatMode.DEVELOPER,
      ),
      approvalPromptState = ApprovalPromptState(
        status = ApprovalPromptStatus.DENIED,
        title = "Blocked by policy",
        message = promptMessage,
        decisionNote = "Status: DENIED | Reason: $reasonCode",
        approveLabel = "Approve write",
        denyLabel = "Keep blocked",
      ),
      conversationLines = listOf(
        "User: Apply the prepared workspace mutation.",
        "Agent: The request stayed denied end-to-end and the reason remains visible for transparent follow-up.",
      ),
      timelineItems = listOf(
        ActionTimelineItem(
          sequenceNumber = 1,
          operationLabel = operationLabel,
          policyDecision = ActionPolicyDecision.DENY,
          resultStatus = ActionResultStatus.FAILED,
          reasonText = reasonText,
          approvalState = ActionApprovalState.NOT_REQUIRED,
        ),
      ),
    )
  }
}
