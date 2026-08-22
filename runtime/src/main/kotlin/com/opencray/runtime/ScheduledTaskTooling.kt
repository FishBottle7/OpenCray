package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.OpenCrayToolDispatcher.Companion.HOST_SESSION_ID_METADATA_KEY
import com.opencray.runtime.policy.SchedulingIntent
import com.opencray.runtime.policy.SchedulingIntentKind
import com.opencray.runtime.policy.ToolMetadataContextRequest
import com.opencray.runtime.policy.ToolResultEnvelope
import com.opencray.runtime.policy.ToolResultLimitKind
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import java.io.File
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.json.JsonObject

internal fun OpenCrayToolDispatcher.createScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskCreate")
    val prompt = arguments.requiredString("prompt").trim()
    val explicitSessionId = arguments.optionalStringFrom("session_id", "sessionId")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val resolvedSessionId = explicitSessionId
      ?: hostSessionId(task)
      ?: return AgentToolResult(
        toolName = "ScheduledTaskCreate",
        status = AgentToolResultStatus.FAILED,
        content = "ScheduledTaskCreate requires session_id when the current host session id is unavailable.",
        errorCode = "SCHEDULED_TASK_SESSION_UNRESOLVED",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "ScheduledTaskCreate",
          request = ToolMetadataContextRequest(
            workspaceRelation = ToolWorkspaceRelation.NONE,
          ),
        ),
      )
    val title = arguments.optionalStringFrom("title", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val conflictPolicy = requireNotNull(
      parseScheduledTaskConflictPolicy(
        arguments = arguments,
        toolName = "ScheduledTaskCreate",
        defaultValue = ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN,
      ),
    )
    val trigger = requireNotNull(
      parseScheduledTaskTrigger(
        arguments = arguments,
        toolName = "ScheduledTaskCreate",
        required = true,
      ),
    )
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val targetSummary = buildString {
      append(resolvedSessionId)
      append(" -> ")
      append(title ?: inlinePreview(prompt, maxChars = 80))
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskCreate",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = targetSummary,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.CREATE_SCHEDULED_TASK,
        triggerKind = scheduledTaskTriggerKind(trigger),
        sessionMode = if (explicitSessionId != null) "explicit_session" else "current_session",
        targetSessionId = resolvedSessionId,
        title = title,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SESSION_ID to resolvedSessionId,
      ),
    )?.let { return it }

    val request = ScheduledTaskCreateRequest(
      sessionId = resolvedSessionId,
      title = title,
      prompt = prompt,
      trigger = trigger,
      enabled = arguments.optionalBoolean("enabled") ?: true,
      conflictPolicy = conflictPolicy,
      notifyOnQueued = arguments.optionalBooleanFrom("notify_on_queued", "notifyOnQueued") ?: false,
      notifyOnApproval = arguments.optionalBooleanFrom(
        "notify_on_approval",
        "notifyOnApproval",
      ) ?: true,
      notifyOnCompletion = arguments.optionalBooleanFrom(
        "notify_on_completion",
        "notifyOnCompletion",
      ) ?: true,
      notifyOnInterruption = arguments.optionalBooleanFrom(
        "notify_on_interruption",
        "notifyOnInterruption",
      ) ?: true,
    )
    val result = runCatching {
      scheduledTaskManager.create(request)
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to create the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskCreate",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_CREATE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SESSION_ID to resolvedSessionId,
            ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to conflictPolicy.name.lowercase(Locale.US),
          ),
        ),
      )
    }
    return AgentToolResult(
      toolName = "ScheduledTaskCreate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task created.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        appendLine("title=${result.title}")
        appendLine("trigger_kind=${result.triggerKind}")
        appendLine("trigger_summary=${result.triggerSummary}")
        append("enabled=${result.enabled}")
        result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
          appendLine()
          append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
        }
        result.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
          appendLine()
          append("snoozed_until_epoch_ms=$snoozedUntilEpochMs")
        }
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
          ScheduledTaskToolMetadataKeys.TRIGGER_KIND to result.triggerKind,
          ScheduledTaskToolMetadataKeys.TRIGGER_SUMMARY to result.triggerSummary,
          ScheduledTaskToolMetadataKeys.ENABLED to result.enabled.toString(),
          ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to conflictPolicy.name.lowercase(Locale.US),
        ) + listOfNotNull(
          result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
          },
          result.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
            ScheduledTaskToolMetadataKeys.SNOOZED_UNTIL_EPOCH_MS to snoozedUntilEpochMs.toString()
          },
        ).toMap(),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.listScheduledTasks(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskList")
    val explicitSessionId = arguments.optionalStringFrom("session_id", "sessionId")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val resolvedSessionId = explicitSessionId ?: hostSessionId(task)
    val sessionMode = when {
      explicitSessionId != null -> "explicit_session"
      resolvedSessionId != null -> "current_session"
      else -> "all_sessions"
    }
    val enabled = arguments.optionalBoolean("enabled")
    val limit = (arguments.optionalInt("limit") ?: 20).coerceIn(1, config.maxDirectoryEntries)
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val targetSummary = buildString {
      append(resolvedSessionId ?: displayPolicyTargetPath)
      enabled?.let { append(" enabled=$it") }
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskList",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = targetSummary,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.LIST_SCHEDULED_TASKS,
        sessionMode = sessionMode,
        targetSessionId = resolvedSessionId,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPolicyTargetPath)
        put("limit", limit.toString())
        resolvedSessionId?.let { put(ScheduledTaskToolMetadataKeys.SESSION_ID, it) }
        enabled?.let { put(ScheduledTaskToolMetadataKeys.ENABLED, it.toString()) }
      },
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.list(
        ScheduledTaskListRequest(
          sessionId = resolvedSessionId,
          enabled = enabled,
          limit = limit,
        ),
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to list scheduled tasks."
      return AgentToolResult(
        toolName = "ScheduledTaskList",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_LIST_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            resolvedSessionId?.let { put(ScheduledTaskToolMetadataKeys.SESSION_ID, it) }
            enabled?.let { put(ScheduledTaskToolMetadataKeys.ENABLED, it.toString()) }
            put(ScheduledTaskToolMetadataKeys.RETURNED_COUNT, "0")
            put(ScheduledTaskToolMetadataKeys.TOTAL_COUNT, "0")
          },
        ),
      )
    }
    val truncated = result.totalCount > result.tasks.size
    return AgentToolResult(
      toolName = "ScheduledTaskList",
      status = AgentToolResultStatus.SUCCESS,
      content = renderScheduledTaskListResult(
        result = result,
        sessionMode = sessionMode,
      ),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          resolvedSessionId?.let { put(ScheduledTaskToolMetadataKeys.SESSION_ID, it) }
          enabled?.let { put(ScheduledTaskToolMetadataKeys.ENABLED, it.toString()) }
          put(ScheduledTaskToolMetadataKeys.RETURNED_COUNT, result.tasks.size.toString())
          put(ScheduledTaskToolMetadataKeys.TOTAL_COUNT, result.totalCount.toString())
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.getScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskGet")
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId")
      .trim()
    val recentRunLimit = (arguments.optionalInt("recent_run_limit")
      ?: arguments.optionalInt("recentRunLimit")
      ?: 5).coerceIn(1, config.maxDirectoryEntries)
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskGet",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = scheduleId,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.GET_SCHEDULED_TASK,
        targetScheduleId = scheduleId,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.get(
        ScheduledTaskGetRequest(
          scheduleId = scheduleId,
          recentRunLimit = recentRunLimit,
        ),
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to inspect the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskGet",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_GET_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
            ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT to "0",
          ),
        ),
      )
    }
    val details = result.task
    val truncated = result.totalRunCount > result.recentRuns.size
    return AgentToolResult(
      toolName = "ScheduledTaskGet",
      status = AgentToolResultStatus.SUCCESS,
      content = renderScheduledTaskGetResult(result),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = scheduledTaskDetailsMetadata(details) + buildMap {
          put(ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT, result.recentRuns.size.toString())
          put(ScheduledTaskToolMetadataKeys.TOTAL_COUNT, result.totalRunCount.toString())
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.updateScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskUpdate")
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId")
      .trim()
    val title = arguments.optionalStringFrom("title", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val prompt = arguments.optionalString("prompt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val trigger = parseScheduledTaskTrigger(
      arguments = arguments,
      toolName = "ScheduledTaskUpdate",
      required = false,
    )
    val conflictPolicy = parseScheduledTaskConflictPolicy(
      arguments = arguments,
      toolName = "ScheduledTaskUpdate",
      defaultValue = null,
    )
    val enabled = arguments.optionalBoolean("enabled")
    val notifyOnQueued = arguments.optionalBooleanFrom("notify_on_queued", "notifyOnQueued")
    val notifyOnApproval = arguments.optionalBooleanFrom(
      "notify_on_approval",
      "notifyOnApproval",
    )
    val notifyOnCompletion = arguments.optionalBooleanFrom(
      "notify_on_completion",
      "notifyOnCompletion",
    )
    val notifyOnInterruption = arguments.optionalBooleanFrom(
      "notify_on_interruption",
      "notifyOnInterruption",
    )
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val targetSummary = buildString {
      append(scheduleId)
      title?.let { append(" -> $it") }
      if (title == null) {
        prompt?.let { append(" -> ${inlinePreview(it, maxChars = 80)}") }
      }
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskUpdate",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = targetSummary,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.UPDATE_SCHEDULED_TASK,
        triggerKind = trigger?.let(::scheduledTaskTriggerKind),
        targetScheduleId = scheduleId,
        title = title,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val request = runCatching {
      ScheduledTaskUpdateRequest(
        scheduleId = scheduleId,
        title = title,
        prompt = prompt,
        trigger = trigger,
        enabled = enabled,
        conflictPolicy = conflictPolicy,
        notifyOnQueued = notifyOnQueued,
        notifyOnApproval = notifyOnApproval,
        notifyOnCompletion = notifyOnCompletion,
        notifyOnInterruption = notifyOnInterruption,
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "ScheduledTaskUpdate requires at least one mutable field."
      return AgentToolResult(
        toolName = "ScheduledTaskUpdate",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_UPDATE_INVALID",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
          ),
        ),
      )
    }
    val result = runCatching {
      scheduledTaskManager.update(request)
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to update the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskUpdate",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_UPDATE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
          ) + listOfNotNull(
            conflictPolicy?.let {
              ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to it.name.lowercase(Locale.US)
            },
          ).toMap(),
        ),
      )
    }
    return AgentToolResult(
      toolName = "ScheduledTaskUpdate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task updated.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        appendLine("title=${result.title}")
        appendLine("trigger_kind=${result.triggerKind}")
        appendLine("trigger_summary=${result.triggerSummary}")
        append("enabled=${result.enabled}")
        result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
          appendLine()
          append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
        }
        result.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
          appendLine()
          append("snoozed_until_epoch_ms=$snoozedUntilEpochMs")
        }
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
          ScheduledTaskToolMetadataKeys.TRIGGER_KIND to result.triggerKind,
          ScheduledTaskToolMetadataKeys.TRIGGER_SUMMARY to result.triggerSummary,
          ScheduledTaskToolMetadataKeys.ENABLED to result.enabled.toString(),
        ) + listOfNotNull(
          conflictPolicy?.let {
            ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to it.name.lowercase(Locale.US)
          },
          result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
          },
          result.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
            ScheduledTaskToolMetadataKeys.SNOOZED_UNTIL_EPOCH_MS to snoozedUntilEpochMs.toString()
          },
        ).toMap(),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.runScheduledTaskNow(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val toolName = "ScheduledTaskRunNow"
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = toolName)
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId").trim()
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = toolName,
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = scheduleId,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.RUN_SCHEDULED_TASK_NOW,
        targetScheduleId = scheduleId,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.runNow(ScheduledTaskRunNowRequest(scheduleId = scheduleId))
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to request an immediate scheduled run."
      return AgentToolResult(
        toolName = toolName,
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_RUN_NOW_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId),
        ),
      )
    }
    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Immediate scheduled run requested.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        appendLine("title=${result.title}")
        appendLine("schedule_run_id=${result.scheduleRunId}")
        append("requested_at_epoch_ms=${result.requestedAtEpochMs}")
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
          ScheduledTaskToolMetadataKeys.SCHEDULE_RUN_ID to result.scheduleRunId,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.snoozeScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val toolName = "ScheduledTaskSnooze"
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = toolName)
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId").trim()
    val durationMinutes = arguments.optionalInt("duration_minutes")
      ?: arguments.optionalInt("durationMinutes")
      ?: 15
    val request = runCatching {
      ScheduledTaskSnoozeRequest(
        scheduleId = scheduleId,
        durationMinutes = durationMinutes,
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "ScheduledTaskSnooze duration_minutes is invalid."
      return AgentToolResult(
        toolName = toolName,
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_SNOOZE_INVALID",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = toolName,
          request = ToolMetadataContextRequest(workspaceRelation = ToolWorkspaceRelation.NONE),
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
            ScheduledTaskToolMetadataKeys.SNOOZE_DURATION_MINUTES to durationMinutes.toString(),
          ),
        ),
      )
    }
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = toolName,
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = "$scheduleId +${durationMinutes}m",
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.SNOOZE_SCHEDULED_TASK,
        targetScheduleId = scheduleId,
      ),
    )
    val affectedPaths = mapOf(
      "path" to displayPolicyTargetPath,
      ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ScheduledTaskToolMetadataKeys.SNOOZE_DURATION_MINUTES to durationMinutes.toString(),
    )
    toolPolicyPipeline.gateFileMutation(plan = plan, affectedPaths = affectedPaths)
      ?.let { return it }

    val result = runCatching {
      scheduledTaskManager.snooze(request)
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to snooze the scheduled task."
      return AgentToolResult(
        toolName = toolName,
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_SNOOZE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(plan = plan, metadata = affectedPaths),
      )
    }
    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task snoozed.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        appendLine("title=${result.title}")
        append("snoozed_until_epoch_ms=${result.snoozedUntilEpochMs}")
        result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
          appendLine()
          append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
        }
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
          ScheduledTaskToolMetadataKeys.SNOOZE_DURATION_MINUTES to durationMinutes.toString(),
          ScheduledTaskToolMetadataKeys.SNOOZED_UNTIL_EPOCH_MS to result.snoozedUntilEpochMs.toString(),
        ) + listOfNotNull(
          result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
          },
        ).toMap(),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.deleteScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskDelete")
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId")
      .trim()
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskDelete",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = scheduleId,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.DELETE_SCHEDULED_TASK,
        targetScheduleId = scheduleId,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.delete(
        ScheduledTaskDeleteRequest(
          scheduleId = scheduleId,
        ),
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to delete the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskDelete",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_DELETE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
          ),
        ),
      )
    }
    return AgentToolResult(
      toolName = "ScheduledTaskDelete",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task deleted.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        append("title=${result.title}")
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.unavailableScheduledTaskManager(
    toolName: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "$toolName is unavailable in the current execution environment.",
    errorCode = "SCHEDULED_TASK_MANAGER_UNAVAILABLE",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        workspaceRelation = ToolWorkspaceRelation.NONE,
      ),
    ),
  )

internal fun OpenCrayToolDispatcher.displayScheduledTaskPolicyPath(path: Path): String =
    path.toString().replace(File.separatorChar, '/')

internal fun OpenCrayToolDispatcher.hostSessionId(task: AgentTask): String? =
    task.metadata[HOST_SESSION_ID_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)

internal fun OpenCrayToolDispatcher.parseScheduledTaskTrigger(
    arguments: JsonObject,
    toolName: String,
    required: Boolean,
  ): ScheduledTaskTriggerRequest? {
    val trigger = arguments.optionalObjectFrom("trigger")
      ?: return if (required) {
        throw IllegalArgumentException("$toolName requires a trigger object.")
      } else {
        null
      }
    val at = trigger.optionalStringFrom("at")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val after = trigger.optionalStringFrom("after")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val startAt = trigger.optionalStringFrom("start_at", "startAt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val timezone = trigger.optionalStringFrom("timezone", "time_zone", "timeZone")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val rrule = trigger.optionalStringFrom("rrule")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val exdates = trigger.optionalStringArrayFrom("exdates", "ex_dates", "exDates")
      .map(String::trim)
    val rdates = trigger.optionalStringArrayFrom("rdates", "r_dates", "rDates")
      .map(String::trim)
    val hasRecurrenceFields =
      startAt != null ||
        rrule != null ||
        timezone != null ||
        exdates.isNotEmpty() ||
        rdates.isNotEmpty()
    val configuredTriggerKinds = listOfNotNull(
      at?.let { "trigger.at" },
      after?.let { "trigger.after" },
      hasRecurrenceFields.takeIf { it }?.let { "trigger.rrule" },
    )
    require(configuredTriggerKinds.size == 1) {
      "$toolName trigger must use exactly one of trigger.at, trigger.after, or trigger.start_at plus trigger.rrule."
    }
    return when {
      at != null -> ScheduledTaskTriggerRequest.At(
        at = at,
      )

      after != null -> ScheduledTaskTriggerRequest.After(after = after)

      hasRecurrenceFields -> ScheduledTaskTriggerRequest.Recurrence(
        startAt = startAt
          ?: throw IllegalArgumentException("$toolName trigger.start_at is required for recurrence."),
        timezone = timezone,
        rrule = rrule
          ?: throw IllegalArgumentException("$toolName trigger.rrule is required for recurrence."),
        exdates = exdates,
        rdates = rdates,
      )

      else -> error("$toolName trigger configuration unexpectedly resolved empty.")
    }
  }

internal fun OpenCrayToolDispatcher.scheduledTaskTriggerKind(trigger: ScheduledTaskTriggerRequest): String = when (trigger) {
    is ScheduledTaskTriggerRequest.At -> "at"
    is ScheduledTaskTriggerRequest.After -> "after"
    is ScheduledTaskTriggerRequest.Recurrence -> "rrule"
  }

internal fun OpenCrayToolDispatcher.parseScheduledTaskConflictPolicy(
    arguments: JsonObject,
    toolName: String,
    defaultValue: ScheduledTaskConflictPolicy?,
  ): ScheduledTaskConflictPolicy? {
    val raw = arguments.optionalStringFrom("conflict_policy", "conflictPolicy")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return defaultValue
    return when (raw.lowercase(Locale.US)) {
      "enqueue_new_run", "enqueue-new-run", "enqueuenewrun" ->
        ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN

      "skip_if_session_busy", "skip-if-session-busy", "skipifsessionbusy" ->
        ScheduledTaskConflictPolicy.SKIP_IF_SESSION_BUSY

      "cancel_older_waiting_trigger",
      "cancel-older-waiting-trigger",
      "cancelolderwaitingtrigger",
      -> ScheduledTaskConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER

      else -> throw IllegalArgumentException(
        "$toolName conflict_policy must be enqueue_new_run, skip_if_session_busy, or cancel_older_waiting_trigger.",
      )
    }
  }

internal fun OpenCrayToolDispatcher.renderScheduledTaskListResult(
    result: ScheduledTaskListResult,
    sessionMode: String,
  ): String = if (result.tasks.isEmpty()) {
    "No scheduled tasks matched the current filter."
  } else {
    buildString {
      appendLine("Listed ${result.tasks.size} scheduled task(s) (session_mode=$sessionMode total=${result.totalCount}).")
      result.tasks.forEachIndexed { index, summary ->
        if (index > 0) {
          appendLine("--")
        }
        append(renderScheduledTaskSummary(summary))
        if (index < result.tasks.lastIndex) {
          appendLine()
        }
      }
    }.trim()
  }

internal fun OpenCrayToolDispatcher.renderScheduledTaskGetResult(
    result: ScheduledTaskGetResult,
  ): String = buildString {
    appendLine("Scheduled task details.")
    appendLine(renderScheduledTaskDetails(result.task))
    appendLine("recent_runs_returned=${result.recentRuns.size}")
    appendLine("recent_runs_total=${result.totalRunCount}")
    result.recentRuns.forEachIndexed { index, run ->
      appendLine("run[${index + 1}]=${renderScheduledTaskRunRecord(run)}")
    }
  }.trim()

internal fun OpenCrayToolDispatcher.renderScheduledTaskSummary(summary: ScheduledTaskSummary): String = buildString {
    appendLine("schedule_id=${summary.scheduleId}")
    appendLine("session_id=${summary.sessionId}")
    appendLine("title=${summary.title}")
    appendLine("trigger_kind=${summary.triggerKind}")
    appendLine("trigger_summary=${summary.triggerSummary}")
    append("enabled=${summary.enabled}")
    summary.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
      appendLine()
      append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
    }
    summary.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
      appendLine()
      append("snoozed_until_epoch_ms=$snoozedUntilEpochMs")
    }
  }

internal fun OpenCrayToolDispatcher.renderScheduledTaskDetails(details: ScheduledTaskDetails): String = buildString {
    appendLine("schedule_id=${details.scheduleId}")
    appendLine("session_id=${details.sessionId}")
    appendLine("title=${details.title}")
    appendLine("prompt=${details.prompt.replace("\n", "\\n")}")
    appendLine("enabled=${details.enabled}")
    appendLine("trigger_kind=${details.triggerKind}")
    appendLine("trigger_summary=${details.triggerSummary}")
    append(renderScheduledTaskTriggerSnapshot(details.trigger))
    appendLine()
    details.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
      appendLine("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
    }
    details.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
      appendLine("snoozed_until_epoch_ms=$snoozedUntilEpochMs")
    }
    appendLine("conflict_policy=${details.conflictPolicy}")
    appendLine("foreground_notification_required=${details.foregroundNotificationRequired}")
    appendLine("notify_on_queued=${details.notifyOnQueued}")
    appendLine("notify_on_approval=${details.notifyOnApproval}")
    appendLine("notify_on_completion=${details.notifyOnCompletion}")
    appendLine("notify_on_interruption=${details.notifyOnInterruption}")
    appendLine("created_at_epoch_ms=${details.createdAtEpochMs}")
    append("updated_at_epoch_ms=${details.updatedAtEpochMs}")
  }

internal fun OpenCrayToolDispatcher.renderScheduledTaskTriggerSnapshot(
    trigger: ScheduledTaskTriggerSnapshot,
  ): String = when (trigger) {
    is ScheduledTaskTriggerSnapshot.At ->
      "trigger.at=${trigger.at}"

    is ScheduledTaskTriggerSnapshot.After ->
      "trigger.after=${trigger.after}"

    is ScheduledTaskTriggerSnapshot.Recurrence -> buildString {
      appendLine("trigger.start_at=${trigger.startAt}")
      appendLine("trigger.timezone=${trigger.timezone}")
      appendLine("trigger.rrule=${trigger.rrule}")
      if (trigger.exdates.isNotEmpty()) {
        appendLine("trigger.exdates=${trigger.exdates.joinToString(separator = ",")}")
      }
      append("trigger.rdates=${trigger.rdates.joinToString(separator = ",")}")
    }
  }

internal fun OpenCrayToolDispatcher.renderScheduledTaskRunRecord(
    run: ScheduledTaskRunRecordSummary,
  ): String = buildString {
    append("schedule_run_id=${run.scheduleRunId}")
    append(" result=${run.result}")
    append(" trigger_reason=${run.triggerReason}")
    append(" triggered_at_epoch_ms=${run.triggeredAtEpochMs}")
    run.acceptedAtEpochMs?.let { append(" accepted_at_epoch_ms=$it") }
    run.createdRunId?.let { append(" created_run_id=$it") }
    run.createdTaskId?.let { append(" created_task_id=$it") }
    run.failureReason?.let { append(" failure_reason=${it.replace("\n", "\\n")}") }
    run.recoverySource?.let { append(" recovery_source=$it") }
    append(" updated_at_epoch_ms=${run.updatedAtEpochMs}")
  }

internal fun OpenCrayToolDispatcher.scheduledTaskDetailsMetadata(
    details: ScheduledTaskDetails,
  ): Map<String, String> = mapOf(
    ScheduledTaskToolMetadataKeys.SCHEDULE_ID to details.scheduleId,
    ScheduledTaskToolMetadataKeys.SESSION_ID to details.sessionId,
    ScheduledTaskToolMetadataKeys.TITLE to details.title,
    ScheduledTaskToolMetadataKeys.TRIGGER_KIND to details.triggerKind,
    ScheduledTaskToolMetadataKeys.TRIGGER_SUMMARY to details.triggerSummary,
    ScheduledTaskToolMetadataKeys.ENABLED to details.enabled.toString(),
    ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to details.conflictPolicy,
  ) + listOfNotNull(
    details.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
      ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
    },
    details.snoozedUntilEpochMs?.let { snoozedUntilEpochMs ->
      ScheduledTaskToolMetadataKeys.SNOOZED_UNTIL_EPOCH_MS to snoozedUntilEpochMs.toString()
    },
  ).toMap()
