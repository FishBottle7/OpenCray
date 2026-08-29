package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBatchApprovalSpecTest {
  @Test
  fun gitStatusGeneratesTwoTokenPrefix() {
    val spec = commandBatchApprovalSpecFromMetadata(
      metadata = approvalMetadata(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
    )

    assertEquals(listOf("git", "status"), requireNotNull(spec).prefixArgs)
    assertEquals("/workspace", spec.workingDirectory)
  }

  @Test
  fun secondTokenOnlyIncludedWhenItMatchesSubcommandShape() {
    assertEquals(
      listOf("git", "commit"),
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "git",
          args = listOf("commit", "-m", "message"),
          workingDirectory = "/workspace",
        ),
      )?.prefixArgs,
    )
    assertEquals(
      listOf("ls"),
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "ls",
          args = listOf("-la"),
          workingDirectory = "/workspace",
        ),
      )?.prefixArgs,
    )
  }

  @Test
  fun siblingCommandTokenNeverCollidesWithApprovedPrefix() {
    val gitSpec = commandBatchApprovalSpecFromMetadata(
      metadata = approvalMetadata(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
    )
    val gitFooSpec = commandBatchApprovalSpecFromMetadata(
      metadata = approvalMetadata(
        command = "gitfoo",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
    )

    assertEquals(listOf("git", "status"), gitSpec?.prefixArgs)
    assertEquals(listOf("gitfoo", "status"), gitFooSpec?.prefixArgs)
    assertTrue(gitSpec?.prefixArgs != gitFooSpec?.prefixArgs)
  }

  @Test
  fun wrapperCommandWithoutShapedSecondTokenDegradesToNull() {
    assertNull(
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "bash",
          args = listOf("-c", "echo hi"),
          workingDirectory = "/workspace",
        ),
      ),
    )
    assertNull(
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "python",
          args = listOf("script.py"),
          workingDirectory = "/workspace",
        ),
      ),
    )
  }

  @Test
  fun broadSingleTokenPrefixesDegradeToNull() {
    listOf("git", "rm", "npm", "docker").forEach { command ->
      assertNull(
        commandBatchApprovalSpecFromMetadata(
          metadata = approvalMetadata(
            command = command,
            args = emptyList(),
            workingDirectory = "/workspace",
          ),
        ),
      )
    }
  }

  @Test
  fun npmRunWithShapedSecondTokenDegradesToNull() {
    assertNull(
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "npm",
          args = listOf("run", "build"),
          workingDirectory = "/workspace",
        ),
      ),
    )
  }

  @Test
  fun tokenWithWhitespaceDegradesToNull() {
    assertNull(
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "echo hi",
          args = listOf("status"),
          workingDirectory = "/workspace",
        ),
      ),
    )
  }

  @Test
  fun missingCommandOrWorkingDirectoryDegradeToNull() {
    assertNull(
      commandBatchApprovalSpecFromMetadata(
        metadata = approvalMetadata(
          command = "git",
          args = listOf("status"),
          workingDirectory = null,
        ),
      ),
    )
    assertNull(
      commandBatchApprovalSpecFromMetadata(
        metadata = mapOf(
          "args" to listOf("status").joinToString("\u0000"),
          "workingDirectory" to "/workspace",
        ),
      ),
    )
  }

  private fun approvalMetadata(
    command: String,
    args: List<String>,
    workingDirectory: String?,
  ): Map<String, String> = buildMap {
    put("command", command)
    put("args", args.joinToString("\u0000"))
    if (workingDirectory != null) {
      put("workingDirectory", workingDirectory)
    }
  }
}
