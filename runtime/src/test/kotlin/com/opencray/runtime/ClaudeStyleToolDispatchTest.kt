package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClaudeStyleToolDispatchTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun readSupportsOffsetAndLimit() {
    val workspaceRoot = temporaryFolder.newFolder("claude-read").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "one\ntwo\nthree\nfour".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Read",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
          put("offset", 2)
          put("limit", 2)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("two\nthree", result.content)
    assertEquals("2", result.metadata["offset"])
    assertEquals("2", result.metadata["limit"])
    assertEquals("2", result.metadata["returnedLineCount"])
  }

  @Test
  fun writeAllowsEmptyContent() {
    val workspaceRoot = temporaryFolder.newFolder("claude-write-empty").toPath()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Write",
        arguments = buildJsonObject {
          put("file_path", "empty.txt")
          put("content", "")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("", String(Files.readAllBytes(workspaceRoot.resolve("empty.txt")), StandardCharsets.UTF_8))
  }

  @Test
  fun globMatchesWorkspaceRelativePathsRecursively() {
    val workspaceRoot = temporaryFolder.newFolder("claude-glob").toPath()
    Files.createDirectories(workspaceRoot.resolve("src").resolve("main"))
    Files.write(workspaceRoot.resolve("src").resolve("main").resolve("App.kt"), "class App".toByteArray(StandardCharsets.UTF_8))
    Files.write(workspaceRoot.resolve("README.md"), "readme".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Glob",
        arguments = buildJsonObject {
          put("pattern", "**/*.kt")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("src/main/App.kt"))
    assertTrue(!result.content.contains("README.md"))
  }

  @Test
  fun editFailsWhenTargetIsAmbiguousAndLeavesFileUntouched() {
    val workspaceRoot = temporaryFolder.newFolder("claude-edit-ambiguous").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "dup\ndup\n".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Edit",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
          put("old_string", "dup")
          put("new_string", "single")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertTrue(result.content.contains("ambiguous"))
    assertEquals("dup\ndup\n", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun multiEditFailureDoesNotPartiallyRewriteFile() {
    val workspaceRoot = temporaryFolder.newFolder("claude-multiedit-fail").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "alpha\nbeta\n".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "MultiEdit",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
          put(
            "edits",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("old_string", "alpha")
                  put("new_string", "ALPHA")
                },
              )
              add(
                buildJsonObject {
                  put("old_string", "missing")
                  put("new_string", "MISSING")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertTrue(result.content.contains("was not found"))
    assertEquals("alpha\nbeta\n", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun todoWriteReadsSharedSessionStateAcrossDispatchers() {
    val workspaceRoot = temporaryFolder.newFolder("claude-todo").toPath()
    val todoStore = InMemoryAgentTodoStore()
    val firstDispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)
    val secondDispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val writeResult = firstDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put(
            "todos",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("content", "Implement Grep")
                  put("status", "completed")
                },
              )
              add(
                buildJsonObject {
                  put("content", "Implement TodoWrite")
                  put("status", "in_progress")
                  put("activeForm", "Implementing TodoWrite")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )
    val readResult = secondDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, writeResult.status)
    assertEquals(AgentToolResultStatus.SUCCESS, readResult.status)
    assertEquals("2", readResult.metadata["todoCount"])
    assertTrue(readResult.content.contains("Implement Grep"))
    assertTrue(readResult.content.contains("Implementing TodoWrite"))
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path = temporaryFolder.newFolder("claude-tool-workspace").toPath(),
    todoStore: AgentTodoStore = InMemoryAgentTodoStore(),
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      todoStore = todoStore,
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
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in ClaudeStyleToolDispatchTest.") },
  )
}
