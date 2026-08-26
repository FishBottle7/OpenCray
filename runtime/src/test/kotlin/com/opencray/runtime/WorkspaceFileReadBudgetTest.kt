package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileReadBudgetTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun workspaceReadFileReportsNoTruncationWhenMultibyteContentFitsCharBudget() {
    val workspaceRoot = newWorkspaceRoot("budget-read-multibyte-fits")
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "😀😀".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 5)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "workspace_read_file",
        arguments = buildJsonObject { put("path", "notes.txt") },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("😀😀", result.content)
    assertEquals("8", result.metadata["byteCount"])
    assertEquals("false", result.metadata["truncated"])
    assertEquals("false", result.metadata["resultTruncated"])
  }

  @Test
  fun readTruncatesMultibyteContentAtExactCharBudgetConsistently() {
    val workspaceRoot = newWorkspaceRoot("budget-read-multibyte-cut")
    Files.write(
      workspaceRoot.resolve("emoji.txt"),
      "😀😀😀".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 2)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Read",
        arguments = buildJsonObject { put("file_path", "emoji.txt") },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("😀", result.content)
    assertEquals(2, result.content.length)
    assertEquals("12", result.metadata["byteCount"])
    assertEquals("true", result.metadata["truncated"])
    assertEquals("true", result.metadata["resultTruncated"])
  }

  @Test
  fun readStreamsOnlyHeadWindowWhenFileExceedsGuardBudget() {
    val workspaceRoot = newWorkspaceRoot("budget-read-head-window")
    Files.write(
      workspaceRoot.resolve("large.txt"),
      "a".repeat(500).toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 100)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Read",
        arguments = buildJsonObject { put("file_path", "large.txt") },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(100, result.content.length)
    assertEquals("500", result.metadata["byteCount"])
    assertEquals("1", result.metadata["returnedLineCount"])
    assertEquals("true", result.metadata["truncated"])
  }

  @Test
  fun editRejectsOversizedTargetWithoutLoadingOrModifyingIt() {
    val workspaceRoot = newWorkspaceRoot("budget-edit-oversized")
    val target = workspaceRoot.resolve("big.txt")
    Files.write(target, "x".repeat(500).toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 100)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Edit",
        arguments = buildJsonObject {
          put("file_path", "big.txt")
          put("old_string", "x")
          put("new_string", "y")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertTrue(result.content.contains("exceeding the editable file limit of 400 bytes"))
    assertEquals("TOOL_EXECUTION_FAILED", result.errorCode)
    assertEquals("x".repeat(500), String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun multiEditRejectsOversizedTargetWithoutLoadingOrModifyingIt() {
    val workspaceRoot = newWorkspaceRoot("budget-multiedit-oversized")
    val target = workspaceRoot.resolve("big.txt")
    Files.write(target, "x".repeat(500).toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 100)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "MultiEdit",
        arguments = buildJsonObject {
          put("file_path", "big.txt")
          put("edits", buildJsonArrayOfEdits())
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertTrue(result.content.contains("MultiEdit rejected"))
    assertTrue(result.content.contains("exceeding the editable file limit of 400 bytes"))
    assertEquals("x".repeat(500), String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun editAcceptsFileAtExactEditableSizeLimit() {
    val workspaceRoot = newWorkspaceRoot("budget-edit-at-limit")
    val target = workspaceRoot.resolve("edge.txt")
    Files.write(target, ("mark" + "y".repeat(396)).toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 100)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Edit",
        arguments = buildJsonObject {
          put("file_path", "edge.txt")
          put("old_string", "mark")
          put("new_string", "mart")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(String(Files.readAllBytes(target), StandardCharsets.UTF_8).startsWith("mart"))
  }

  @Test
  fun readHeadDecoderFallsBackAtCharacterBoundaryWhenCutSplitsMultibyteSequence() {
    val workspaceRoot = newWorkspaceRoot("budget-decode-boundary")
    val file = workspaceRoot.resolve("euro.txt")
    Files.write(file, "aaaa€€€".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 32_000)

    val head = dispatcher.readFileHeadWithinCharBudget(file = file, maxChars = 2)

    assertEquals("aa", head.text)
    assertTrue(head.truncated)
    assertEquals(13L, head.byteCount)
    assertFalse(head.text.contains('\uFFFD'))
  }

  @Test
  fun readHeadHonorsInjectedSizeProbeForOversizeGuard() {
    val workspaceRoot = newWorkspaceRoot("budget-size-probe")
    val file = workspaceRoot.resolve("small.txt")
    Files.write(file, "hi".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot, maxReadBytes = 32_000)

    val head = dispatcher.readFileHeadWithinCharBudget(
      file = file,
      maxChars = 3,
      sizeProbe = { 10_000L },
    )

    assertEquals("hi", head.text)
    assertTrue(head.truncated)
    assertEquals(10_000L, head.byteCount)
  }

  private fun buildJsonArrayOfEdits() = buildJsonArray {
    add(
      buildJsonObject {
        put("old_string", "x")
        put("new_string", "y")
      },
    )
  }

  private fun newWorkspaceRoot(name: String): Path = temporaryFolder.newFolder(name).toPath()

  private fun dispatcher(
    workspaceRoot: Path,
    maxReadBytes: Int,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      maxReadBytes = maxReadBytes,
    ),
  )

  private fun agentTask(): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = emptyMap(),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in WorkspaceFileReadBudgetTest.") },
  )
}
