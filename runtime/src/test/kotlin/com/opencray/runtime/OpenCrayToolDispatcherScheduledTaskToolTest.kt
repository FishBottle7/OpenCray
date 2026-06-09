package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherScheduledTaskToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun scheduledTaskCreateAliasUsesCurrentSessionMetadataAndEmitsSchedulingMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
          "_host.sessionId" to "session-current",
        ),
      ),
      call = AgentToolCall(
        toolName = "scheduledtaskcreate",
        arguments = buildJsonObject {
          put("prompt", "Summarize the workspace status")
          put(
            "trigger",
            buildJsonObject {
              put("after", "PT1M")
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    val request = requireNotNull(manager.lastRequest)
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("scheduledtaskcreate", result.toolName)
    assertEquals("ScheduledTaskCreate", result.metadata["canonicalToolName"])
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("scheduling", result.metadata["intentCategory"])
    assertEquals("create_scheduled_task", result.metadata["schedulingIntentKind"])
    assertEquals("after", result.metadata["scheduleTriggerKind"])
    assertEquals("current_session", result.metadata["scheduleSessionMode"])
    assertEquals(modelPath(storageRoot), result.metadata["primaryTargetPath"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
    assertEquals("session-current", request.sessionId)
    assertEquals("Summarize the workspace status", request.prompt)
    assertTrue(request.trigger is ScheduledTaskTriggerRequest.After)
    assertEquals("PT1M", (request.trigger as ScheduledTaskTriggerRequest.After).after)
    assertTrue(result.content.contains("schedule_id=schedule-test"))
    assertTrue(result.content.contains("session_id=session-current"))
  }

  @Test
  fun scheduledTaskCreateRequiresApprovalInSafeModeBeforeMutation() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-safe-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-safe-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "SAFE",
          "_host.sessionId" to "session-safe",
        ),
      ),
      call = AgentToolCall(
        toolName = "ScheduledTaskCreate",
        arguments = buildJsonObject {
          put("prompt", "Run the nightly summary")
          put(
            "trigger",
            buildJsonObject {
              put("at", "2026-04-11T21:00:00+08:00")
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_WRITE", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("directory", result.metadata["targetKind"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
    assertEquals(modelPath(storageRoot), result.metadata["primaryTargetPath"])
    assertEquals("scheduling", result.metadata["intentCategory"])
    assertEquals("create_scheduled_task", result.metadata["schedulingIntentKind"])
    assertEquals("at", result.metadata["scheduleTriggerKind"])
    assertEquals("current_session", result.metadata["scheduleSessionMode"])
    assertNull(manager.lastRequest)
  }

  @Test
  fun scheduledTaskCreateParsesRecurrenceTriggerObject() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-recurrence-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-recurrence-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
          "_host.sessionId" to "session-recurrence",
        ),
      ),
      call = AgentToolCall(
        toolName = "ScheduledTaskCreate",
        arguments = buildJsonObject {
          put("prompt", "Review the repo every Monday and Tuesday morning")
          put(
            "trigger",
            buildJsonObject {
              put("timezone", "Asia/Shanghai")
              put("start_at", "2026-04-13T09:00:00+08:00")
              put("rrule", "FREQ=WEEKLY;BYDAY=MO,TU")
              put(
                "exdates",
                kotlinx.serialization.json.buildJsonArray {
                  add(kotlinx.serialization.json.JsonPrimitive("2026-04-20T09:00:00+08:00"))
                },
              )
              put(
                "rdates",
                kotlinx.serialization.json.buildJsonArray {
                  add(kotlinx.serialization.json.JsonPrimitive("2026-04-22T09:00:00+08:00"))
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    val request = requireNotNull(manager.lastRequest)
    val trigger = request.trigger as ScheduledTaskTriggerRequest.Recurrence
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("rrule", result.metadata["scheduleTriggerKind"])
    assertEquals("Asia/Shanghai", trigger.timezone)
    assertEquals("2026-04-13T09:00:00+08:00", trigger.startAt)
    assertEquals("FREQ=WEEKLY;BYDAY=MO,TU", trigger.rrule)
    assertEquals(listOf("2026-04-20T09:00:00+08:00"), trigger.exdates)
    assertEquals(listOf("2026-04-22T09:00:00+08:00"), trigger.rdates)
  }

  @Test
  fun scheduledTaskListUsesCurrentSessionFilterAndEmitsSchedulingMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-list-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-list-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
          "_host.sessionId" to "session-list",
        ),
      ),
      call = AgentToolCall(
        toolName = "ScheduledTaskList",
        arguments = buildJsonObject {
          put("limit", 5)
        },
      ),
      hooks = runtimeHooks(),
    )

    val request = requireNotNull(manager.lastListRequest)
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("scheduling", result.metadata["intentCategory"])
    assertEquals("list_scheduled_tasks", result.metadata["schedulingIntentKind"])
    assertEquals("current_session", result.metadata["scheduleSessionMode"])
    assertEquals("session-list", request.sessionId)
    assertEquals(5, request.limit)
    assertEquals("1", result.metadata[ScheduledTaskToolMetadataKeys.RETURNED_COUNT])
    assertEquals("1", result.metadata[ScheduledTaskToolMetadataKeys.TOTAL_COUNT])
    assertTrue(result.content.contains("Listed 1 scheduled task"))
    assertTrue(result.content.contains("schedule_id=schedule-test"))
  }

  @Test
  fun scheduledTaskGetReturnsDetailedScheduleMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-get-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-get-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
        ),
      ),
      call = AgentToolCall(
        toolName = "ScheduledTaskGet",
        arguments = buildJsonObject {
          put("schedule_id", "schedule-test")
          put("recent_run_limit", 3)
        },
      ),
      hooks = runtimeHooks(),
    )

    val request = requireNotNull(manager.lastGetRequest)
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("get_scheduled_task", result.metadata["schedulingIntentKind"])
    assertEquals("schedule-test", request.scheduleId)
    assertEquals(3, request.recentRunLimit)
    assertEquals("schedule-test", result.metadata[ScheduledTaskToolMetadataKeys.SCHEDULE_ID])
    assertEquals("session-get", result.metadata[ScheduledTaskToolMetadataKeys.SESSION_ID])
    assertEquals("rrule", result.metadata[ScheduledTaskToolMetadataKeys.TRIGGER_KIND])
    assertEquals("1", result.metadata[ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT])
    assertTrue(result.content.contains("Scheduled task details."))
    assertTrue(result.content.contains("trigger.rrule=FREQ=WEEKLY;BYDAY=MO"))
  }

  @Test
  fun scheduledTaskUpdateRequiresApprovalInSafeModeBeforeMutation() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-update-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-update-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "SAFE",
        ),
      ),
      call = AgentToolCall(
        toolName = "ScheduledTaskUpdate",
        arguments = buildJsonObject {
          put("schedule_id", "schedule-test")
          put("title", "Updated title")
          put(
            "trigger",
            buildJsonObject {
              put("after", "PT30M")
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("update_scheduled_task", result.metadata["schedulingIntentKind"])
    assertEquals("after", result.metadata["scheduleTriggerKind"])
    assertNull(manager.lastUpdateRequest)
  }

  @Test
  fun scheduledTaskDeleteRequiresApprovalInSafeModeBeforeDeletion() {
    val workspaceRoot = temporaryFolder.newFolder("scheduled-task-delete-workspace").toPath()
    val storageRoot = temporaryFolder.newFolder("scheduled-task-delete-storage").toPath()
    val manager = RecordingScheduledTaskManager(storageRoot)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      scheduledTaskManager = manager,
    )

    val result = dispatcher.dispatch(
      task = task(
        metadata = mapOf(
          "chatMode" to "SAFE",
        ),
      ),
      call = AgentToolCall(
        toolName = "ScheduledTaskDelete",
        arguments = buildJsonObject {
          put("schedule_id", "schedule-test")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("delete_scheduled_task", result.metadata["schedulingIntentKind"])
    assertNull(manager.lastDeleteRequest)
  }

  private fun dispatcher(
    workspaceRoot: Path,
    scheduledTaskManager: ScheduledTaskManager,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      extraPolicyReadRoots = setOf(scheduledTaskManager.policyTargetPath()),
      extraPolicyWriteRoots = setOf(scheduledTaskManager.policyTargetPath()),
      scheduledTaskManager = scheduledTaskManager,
    ),
  )

  private fun task(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-scheduled-tool",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in scheduled task tool test.") },
  )

  private fun modelPath(path: Path): String = path.toString().replace(File.separatorChar, '/')

  private class RecordingScheduledTaskManager(
    private val targetPath: Path,
  ) : ScheduledTaskManager {
    var lastRequest: ScheduledTaskCreateRequest? = null
      private set
    var lastListRequest: ScheduledTaskListRequest? = null
      private set
    var lastGetRequest: ScheduledTaskGetRequest? = null
      private set
    var lastUpdateRequest: ScheduledTaskUpdateRequest? = null
      private set
    var lastDeleteRequest: ScheduledTaskDeleteRequest? = null
      private set

    override fun policyTargetPath(): Path = targetPath

    override fun create(request: ScheduledTaskCreateRequest): ScheduledTaskCreateResult {
      lastRequest = request
      return ScheduledTaskCreateResult(
        scheduleId = "schedule-test",
        sessionId = request.sessionId,
        title = request.title ?: "Derived title",
        enabled = request.enabled,
        triggerKind = triggerKind(request.trigger),
        triggerSummary = triggerSummary(request.trigger),
        nextTriggerAtEpochMs = nextTriggerAtEpochMs(request.trigger),
      )
    }

    override fun list(request: ScheduledTaskListRequest): ScheduledTaskListResult {
      lastListRequest = request
      return ScheduledTaskListResult(
        tasks = listOf(
          ScheduledTaskSummary(
            scheduleId = "schedule-test",
            sessionId = request.sessionId ?: "session-list",
            title = "Nightly summary",
            enabled = true,
            triggerKind = "after",
            triggerSummary = "after:PT1M",
            nextTriggerAtEpochMs = 61_000L,
          ),
        ),
        totalCount = 1,
      )
    }

    override fun get(request: ScheduledTaskGetRequest): ScheduledTaskGetResult {
      lastGetRequest = request
      return ScheduledTaskGetResult(
        task = ScheduledTaskDetails(
          scheduleId = request.scheduleId,
          sessionId = "session-get",
          title = "Weekly review",
          prompt = "Review the repository state",
          enabled = true,
          triggerKind = "rrule",
          triggerSummary = "rrule:FREQ=WEEKLY;BYDAY=MO",
          trigger = ScheduledTaskTriggerSnapshot.Recurrence(
            startAt = "2026-04-13T09:00:00+08:00",
            timezone = "Asia/Shanghai",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
          ),
          nextTriggerAtEpochMs = 120_000L,
          conflictPolicy = "enqueue_new_run",
          requiresForegroundNotification = true,
          notifyOnQueued = false,
          notifyOnApproval = true,
          notifyOnCompletion = true,
          notifyOnInterruption = true,
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 2_000L,
        ),
        recentRuns = listOf(
          ScheduledTaskRunRecordSummary(
            scheduleRunId = "schedule-run-1",
            triggerReason = "alarm",
            result = "accepted",
            triggeredAtEpochMs = 10_000L,
            createdRunId = "run-1",
            createdTaskId = "task-1",
            updatedAtEpochMs = 11_000L,
          ),
        ),
        totalRunCount = 1,
      )
    }

    override fun update(request: ScheduledTaskUpdateRequest): ScheduledTaskUpdateResult {
      lastUpdateRequest = request
      return ScheduledTaskUpdateResult(
        scheduleId = request.scheduleId,
        sessionId = "session-update",
        title = request.title ?: "Updated title",
        enabled = true,
        triggerKind = request.trigger?.let(::triggerKind) ?: "after",
        triggerSummary = request.trigger?.let(::triggerSummary) ?: "after:PT1M",
        nextTriggerAtEpochMs = request.trigger?.let(::nextTriggerAtEpochMs) ?: 61_000L,
      )
    }

    override fun delete(request: ScheduledTaskDeleteRequest): ScheduledTaskDeleteResult {
      lastDeleteRequest = request
      return ScheduledTaskDeleteResult(
        scheduleId = request.scheduleId,
        sessionId = "session-delete",
        title = "Delete me",
      )
    }

    private fun triggerKind(trigger: ScheduledTaskTriggerRequest): String = when (trigger) {
      is ScheduledTaskTriggerRequest.At -> "at"
      is ScheduledTaskTriggerRequest.After -> "after"
      is ScheduledTaskTriggerRequest.Recurrence -> "rrule"
    }

    private fun triggerSummary(trigger: ScheduledTaskTriggerRequest): String = when (trigger) {
      is ScheduledTaskTriggerRequest.At -> "at:${trigger.at}"
      is ScheduledTaskTriggerRequest.After -> "after:${trigger.after}"
      is ScheduledTaskTriggerRequest.Recurrence -> "rrule:${trigger.rrule}"
    }

    private fun nextTriggerAtEpochMs(trigger: ScheduledTaskTriggerRequest): Long = when (trigger) {
      is ScheduledTaskTriggerRequest.At -> 2_000L
      is ScheduledTaskTriggerRequest.After -> 61_000L
      is ScheduledTaskTriggerRequest.Recurrence -> 120_000L
    }
  }
}
