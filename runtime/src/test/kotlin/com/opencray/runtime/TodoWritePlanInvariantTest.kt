package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoWritePlanInvariantTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun todoWriteRejectsMultipleInProgressEntriesWithoutMutatingExistingPlan() {
    val workspaceRoot = temporaryFolder.newFolder("todo-invariants-multi").toPath()
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Inspect runtime continuation",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Inspecting runtime continuation",
        ),
        AgentTodoEntry(
          content = "Write follow-up tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val invalidWrite = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put(
            "todos",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("content", "Inspect runtime continuation")
                  put("status", "in_progress")
                  put("activeForm", "Inspecting runtime continuation")
                },
              )
              add(
                buildJsonObject {
                  put("content", "Write follow-up tests")
                  put("status", "in_progress")
                  put("activeForm", "Writing follow-up tests")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )
    val readBack = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, invalidWrite.status)
    assertTrue(invalidWrite.content.contains("at most one in_progress"))
    assertEquals(AgentToolResultStatus.SUCCESS, readBack.status)
    assertEquals("1", readBack.metadata["pendingTodoCount"])
    assertEquals("1", readBack.metadata["inProgressTodoCount"])
    assertEquals("0", readBack.metadata["completedTodoCount"])
    assertEquals("Inspect runtime continuation", readBack.metadata["activeTodoContent"])
    assertTrue(readBack.content.contains("Inspecting runtime continuation"))
    assertTrue(readBack.content.contains("Write follow-up tests"))
    assertFalse(readBack.content.contains("Writing follow-up tests"))
  }

  @Test
  fun todoWriteRejectsActiveFormOnNonActiveEntryWithoutMutatingExistingPlan() {
    val workspaceRoot = temporaryFolder.newFolder("todo-invariants-active-form").toPath()
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Keep current plan",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Keeping current plan",
        ),
      ),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val invalidWrite = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put(
            "todos",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("content", "Keep current plan")
                  put("status", "completed")
                  put("activeForm", "Still keeping current plan")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )
    val readBack = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, invalidWrite.status)
    assertTrue(invalidWrite.content.contains("can only set activeForm when status is in_progress"))
    assertEquals(AgentToolResultStatus.SUCCESS, readBack.status)
    assertEquals("0", readBack.metadata["pendingTodoCount"])
    assertEquals("1", readBack.metadata["inProgressTodoCount"])
    assertEquals("0", readBack.metadata["completedTodoCount"])
    assertEquals("Keep current plan", readBack.metadata["activeTodoContent"])
    assertTrue(readBack.content.contains("Keeping current plan"))
    assertFalse(readBack.content.contains("Still keeping current plan"))
  }

  @Test
  fun todoWriteRejectsDuplicateTodoContentWithoutMutatingExistingPlan() {
    val workspaceRoot = temporaryFolder.newFolder("todo-invariants-duplicate-content").toPath()
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Keep current plan",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Keeping current plan",
        ),
        AgentTodoEntry(
          content = "Write follow-up tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val invalidWrite = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put(
            "todos",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("content", "Keep current plan")
                  put("status", "in_progress")
                  put("activeForm", "Keeping current plan")
                },
              )
              add(
                buildJsonObject {
                  put("content", "Keep current plan")
                  put("status", "pending")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )
    val readBack = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, invalidWrite.status)
    assertTrue(invalidWrite.content.contains("duplicates todo 1 content"))
    assertEquals(AgentToolResultStatus.SUCCESS, readBack.status)
    assertEquals("Keep current plan", readBack.metadata["activeTodoContent"])
    assertTrue(readBack.content.contains("Keeping current plan"))
    assertTrue(readBack.content.contains("Write follow-up tests"))
  }

  @Test
  fun todoWriteReportsPlanDeltaMetadataForSuccessfulReplacement() {
    val workspaceRoot = temporaryFolder.newFolder("todo-plan-delta").toPath()
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Inspect runtime continuation",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Inspecting runtime continuation",
        ),
        AgentTodoEntry(
          content = "Write follow-up tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val writeResult = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put(
            "todos",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("content", "Inspect runtime continuation")
                  put("status", "completed")
                },
              )
              add(
                buildJsonObject {
                  put("content", "Prepare final answer")
                  put("status", "in_progress")
                  put("activeForm", "Preparing final answer")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, writeResult.status)
    assertEquals("true", writeResult.metadata[TodoWriteMetadataKeys.PLAN_CHANGED])
    assertEquals("1", writeResult.metadata[TodoWriteMetadataKeys.ADDED_TODO_COUNT])
    assertEquals("1", writeResult.metadata[TodoWriteMetadataKeys.REMOVED_TODO_COUNT])
    assertEquals("1", writeResult.metadata[TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT])
    assertEquals("1", writeResult.metadata[TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT])
    assertEquals("true", writeResult.metadata[TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED])
    assertEquals("Prepare final answer", writeResult.metadata[TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT])
    assertEquals("1", writeResult.metadata[TodoWriteMetadataKeys.COMPLETED_TODO_COUNT])
    assertEquals("1", writeResult.metadata[TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT])
    assertTrue(writeResult.content.contains("Preparing final answer"))
  }

  @Test
  fun todoWriteOmitsMutationWhenTodosFieldIsMissingAndClearsPlanWhenTodosIsEmpty() {
    val workspaceRoot = temporaryFolder.newFolder("todo-read-vs-clear").toPath()
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Inspect runtime continuation",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Inspecting runtime continuation",
        ),
        AgentTodoEntry(
          content = "Write follow-up tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val readCurrent = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )
    val clearCurrent = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put("todos", buildJsonArray {})
        },
      ),
      hooks = runtimeHooks(),
    )
    val readBack = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, readCurrent.status)
    assertEquals("false", readCurrent.metadata[TodoWriteMetadataKeys.MUTATED])
    assertEquals("2", readCurrent.metadata[TodoWriteMetadataKeys.TODO_COUNT])
    assertEquals(
      "Inspect runtime continuation",
      readCurrent.metadata[TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT],
    )

    assertEquals(AgentToolResultStatus.SUCCESS, clearCurrent.status)
    assertEquals("true", clearCurrent.metadata[TodoWriteMetadataKeys.MUTATED])
    assertEquals("true", clearCurrent.metadata[TodoWriteMetadataKeys.PLAN_CHANGED])
    assertEquals("0", clearCurrent.metadata[TodoWriteMetadataKeys.TODO_COUNT])
    assertEquals("0", clearCurrent.metadata[TodoWriteMetadataKeys.PENDING_TODO_COUNT])
    assertEquals("0", clearCurrent.metadata[TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT])
    assertEquals("0", clearCurrent.metadata[TodoWriteMetadataKeys.COMPLETED_TODO_COUNT])
    assertEquals("Todo list is empty.", clearCurrent.content)

    assertEquals(AgentToolResultStatus.SUCCESS, readBack.status)
    assertEquals("false", readBack.metadata[TodoWriteMetadataKeys.MUTATED])
    assertEquals("0", readBack.metadata[TodoWriteMetadataKeys.TODO_COUNT])
    assertEquals("Todo list is empty.", readBack.content)
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path,
    todoStore: AgentTodoStore,
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
    requestRetry = { _: RetryRequest -> error("Retry not expected in TodoWritePlanInvariantTest.") },
  )
}
