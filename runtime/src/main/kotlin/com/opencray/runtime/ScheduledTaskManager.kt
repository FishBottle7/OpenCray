package com.opencray.runtime

import java.nio.file.Path

data class ScheduledTaskCreateRequest(
  val sessionId: String,
  val title: String? = null,
  val prompt: String,
  val trigger: ScheduledTaskTriggerRequest,
  val enabled: Boolean = true,
  val conflictPolicy: ScheduledTaskConflictPolicy = ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN,
  val requiresForegroundNotification: Boolean = true,
  val notifyOnQueued: Boolean = false,
  val notifyOnApproval: Boolean = true,
  val notifyOnCompletion: Boolean = true,
  val notifyOnInterruption: Boolean = true,
) {
  init {
    require(sessionId.isNotBlank()) { "ScheduledTaskCreateRequest sessionId must not be blank." }
    require(prompt.isNotBlank()) { "ScheduledTaskCreateRequest prompt must not be blank." }
  }
}

data class ScheduledTaskListRequest(
  val sessionId: String? = null,
  val enabled: Boolean? = null,
  val limit: Int = DEFAULT_SCHEDULED_TASK_LIST_LIMIT,
) {
  init {
    require(sessionId == null || sessionId.isNotBlank()) {
      "ScheduledTaskListRequest sessionId must not be blank when provided."
    }
    require(limit >= 1) { "ScheduledTaskListRequest limit must be >= 1." }
  }
}

data class ScheduledTaskGetRequest(
  val scheduleId: String,
  val recentRunLimit: Int = DEFAULT_SCHEDULED_TASK_RECENT_RUN_LIMIT,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskGetRequest scheduleId must not be blank." }
    require(recentRunLimit >= 1) { "ScheduledTaskGetRequest recentRunLimit must be >= 1." }
  }
}

data class ScheduledTaskUpdateRequest(
  val scheduleId: String,
  val title: String? = null,
  val prompt: String? = null,
  val trigger: ScheduledTaskTriggerRequest? = null,
  val conflictPolicy: ScheduledTaskConflictPolicy? = null,
  val requiresForegroundNotification: Boolean? = null,
  val notifyOnQueued: Boolean? = null,
  val notifyOnApproval: Boolean? = null,
  val notifyOnCompletion: Boolean? = null,
  val notifyOnInterruption: Boolean? = null,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskUpdateRequest scheduleId must not be blank." }
    require(title == null || title.isNotBlank()) {
      "ScheduledTaskUpdateRequest title must not be blank when provided."
    }
    require(prompt == null || prompt.isNotBlank()) {
      "ScheduledTaskUpdateRequest prompt must not be blank when provided."
    }
    require(
      title != null ||
        prompt != null ||
        trigger != null ||
        conflictPolicy != null ||
        requiresForegroundNotification != null ||
        notifyOnQueued != null ||
        notifyOnApproval != null ||
        notifyOnCompletion != null ||
        notifyOnInterruption != null,
    ) {
      "ScheduledTaskUpdateRequest must include at least one mutable field."
    }
  }
}

data class ScheduledTaskDeleteRequest(
  val scheduleId: String,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskDeleteRequest scheduleId must not be blank." }
  }
}

sealed class ScheduledTaskTriggerRequest {
  data class At(
    val at: String,
  ) : ScheduledTaskTriggerRequest() {
    init {
      require(at.isNotBlank()) { "ScheduledTaskTriggerRequest.At at must not be blank." }
    }
  }

  data class After(
    val after: String,
  ) : ScheduledTaskTriggerRequest() {
    init {
      require(after.isNotBlank()) { "ScheduledTaskTriggerRequest.After after must not be blank." }
    }
  }

  data class Recurrence(
    val startAt: String,
    val timezone: String? = null,
    val rrule: String,
    val exdates: List<String> = emptyList(),
    val rdates: List<String> = emptyList(),
  ) : ScheduledTaskTriggerRequest() {
    init {
      require(startAt.isNotBlank()) {
        "ScheduledTaskTriggerRequest.Recurrence startAt must not be blank."
      }
      require(rrule.isNotBlank()) { "ScheduledTaskTriggerRequest.Recurrence rrule must not be blank." }
      require(timezone == null || timezone.isNotBlank()) {
        "ScheduledTaskTriggerRequest.Recurrence timezone must not be blank when provided."
      }
      require(exdates.none { value -> value.isBlank() }) {
        "ScheduledTaskTriggerRequest.Recurrence exdates must not contain blank values."
      }
      require(rdates.none { value -> value.isBlank() }) {
        "ScheduledTaskTriggerRequest.Recurrence rdates must not contain blank values."
      }
    }
  }
}

sealed class ScheduledTaskTriggerSnapshot {
  data class At(
    val at: String,
  ) : ScheduledTaskTriggerSnapshot() {
    init {
      require(at.isNotBlank()) { "ScheduledTaskTriggerSnapshot.At at must not be blank." }
    }
  }

  data class After(
    val after: String,
  ) : ScheduledTaskTriggerSnapshot() {
    init {
      require(after.isNotBlank()) { "ScheduledTaskTriggerSnapshot.After after must not be blank." }
    }
  }

  data class Recurrence(
    val startAt: String,
    val timezone: String,
    val rrule: String,
    val exdates: List<String> = emptyList(),
    val rdates: List<String> = emptyList(),
  ) : ScheduledTaskTriggerSnapshot() {
    init {
      require(startAt.isNotBlank()) {
        "ScheduledTaskTriggerSnapshot.Recurrence startAt must not be blank."
      }
      require(timezone.isNotBlank()) {
        "ScheduledTaskTriggerSnapshot.Recurrence timezone must not be blank."
      }
      require(rrule.isNotBlank()) { "ScheduledTaskTriggerSnapshot.Recurrence rrule must not be blank." }
      require(exdates.none { value -> value.isBlank() }) {
        "ScheduledTaskTriggerSnapshot.Recurrence exdates must not contain blank values."
      }
      require(rdates.none { value -> value.isBlank() }) {
        "ScheduledTaskTriggerSnapshot.Recurrence rdates must not contain blank values."
      }
    }
  }
}

enum class ScheduledTaskConflictPolicy {
  ENQUEUE_NEW_RUN,
  SKIP_IF_SESSION_BUSY,
  CANCEL_OLDER_WAITING_TRIGGER,
}

data class ScheduledTaskSummary(
  val scheduleId: String,
  val sessionId: String,
  val title: String,
  val enabled: Boolean,
  val triggerKind: String,
  val triggerSummary: String,
  val nextTriggerAtEpochMs: Long? = null,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskSummary scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskSummary sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskSummary title must not be blank." }
    require(triggerKind.isNotBlank()) { "ScheduledTaskSummary triggerKind must not be blank." }
    require(triggerSummary.isNotBlank()) { "ScheduledTaskSummary triggerSummary must not be blank." }
  }
}

data class ScheduledTaskCreateResult(
  val scheduleId: String,
  val sessionId: String,
  val title: String,
  val enabled: Boolean,
  val triggerKind: String,
  val triggerSummary: String,
  val nextTriggerAtEpochMs: Long? = null,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskCreateResult scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskCreateResult sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskCreateResult title must not be blank." }
    require(triggerKind.isNotBlank()) { "ScheduledTaskCreateResult triggerKind must not be blank." }
    require(triggerSummary.isNotBlank()) {
      "ScheduledTaskCreateResult triggerSummary must not be blank."
    }
  }
}

data class ScheduledTaskListResult(
  val tasks: List<ScheduledTaskSummary>,
  val totalCount: Int,
) {
  init {
    require(totalCount >= 0) { "ScheduledTaskListResult totalCount must be >= 0." }
  }
}

data class ScheduledTaskDetails(
  val scheduleId: String,
  val sessionId: String,
  val title: String,
  val prompt: String,
  val enabled: Boolean,
  val triggerKind: String,
  val triggerSummary: String,
  val trigger: ScheduledTaskTriggerSnapshot,
  val nextTriggerAtEpochMs: Long? = null,
  val conflictPolicy: String,
  val requiresForegroundNotification: Boolean,
  val notifyOnQueued: Boolean,
  val notifyOnApproval: Boolean,
  val notifyOnCompletion: Boolean,
  val notifyOnInterruption: Boolean,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskDetails scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskDetails sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskDetails title must not be blank." }
    require(prompt.isNotBlank()) { "ScheduledTaskDetails prompt must not be blank." }
    require(triggerKind.isNotBlank()) { "ScheduledTaskDetails triggerKind must not be blank." }
    require(triggerSummary.isNotBlank()) { "ScheduledTaskDetails triggerSummary must not be blank." }
    require(conflictPolicy.isNotBlank()) {
      "ScheduledTaskDetails conflictPolicy must not be blank."
    }
    require(createdAtEpochMs >= 0L) { "ScheduledTaskDetails createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "ScheduledTaskDetails updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}

data class ScheduledTaskRunRecordSummary(
  val scheduleRunId: String,
  val triggerReason: String,
  val result: String,
  val triggeredAtEpochMs: Long,
  val acceptedAtEpochMs: Long? = null,
  val createdRunId: String? = null,
  val createdTaskId: String? = null,
  val failureReason: String? = null,
  val recoverySource: String? = null,
  val updatedAtEpochMs: Long,
) {
  init {
    require(scheduleRunId.isNotBlank()) {
      "ScheduledTaskRunRecordSummary scheduleRunId must not be blank."
    }
    require(triggerReason.isNotBlank()) {
      "ScheduledTaskRunRecordSummary triggerReason must not be blank."
    }
    require(result.isNotBlank()) { "ScheduledTaskRunRecordSummary result must not be blank." }
    require(triggeredAtEpochMs >= 0L) {
      "ScheduledTaskRunRecordSummary triggeredAtEpochMs must be >= 0."
    }
    require(updatedAtEpochMs >= triggeredAtEpochMs) {
      "ScheduledTaskRunRecordSummary updatedAtEpochMs must be >= triggeredAtEpochMs."
    }
  }
}

data class ScheduledTaskGetResult(
  val task: ScheduledTaskDetails,
  val recentRuns: List<ScheduledTaskRunRecordSummary>,
  val totalRunCount: Int,
) {
  init {
    require(totalRunCount >= 0) { "ScheduledTaskGetResult totalRunCount must be >= 0." }
  }
}

data class ScheduledTaskUpdateResult(
  val scheduleId: String,
  val sessionId: String,
  val title: String,
  val enabled: Boolean,
  val triggerKind: String,
  val triggerSummary: String,
  val nextTriggerAtEpochMs: Long? = null,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskUpdateResult scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskUpdateResult sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskUpdateResult title must not be blank." }
    require(triggerKind.isNotBlank()) { "ScheduledTaskUpdateResult triggerKind must not be blank." }
    require(triggerSummary.isNotBlank()) {
      "ScheduledTaskUpdateResult triggerSummary must not be blank."
    }
  }
}

data class ScheduledTaskDeleteResult(
  val scheduleId: String,
  val sessionId: String,
  val title: String,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskDeleteResult scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskDeleteResult sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskDeleteResult title must not be blank." }
  }
}

interface ScheduledTaskManager {
  fun policyTargetPath(): Path

  fun create(request: ScheduledTaskCreateRequest): ScheduledTaskCreateResult

  fun list(request: ScheduledTaskListRequest): ScheduledTaskListResult

  fun get(request: ScheduledTaskGetRequest): ScheduledTaskGetResult

  fun update(request: ScheduledTaskUpdateRequest): ScheduledTaskUpdateResult

  fun delete(request: ScheduledTaskDeleteRequest): ScheduledTaskDeleteResult
}

internal object ScheduledTaskToolMetadataKeys {
  const val SCHEDULE_ID: String = "scheduleId"
  const val SESSION_ID: String = "sessionId"
  const val TITLE: String = "title"
  const val TRIGGER_KIND: String = "triggerKind"
  const val TRIGGER_SUMMARY: String = "triggerSummary"
  const val NEXT_TRIGGER_AT_EPOCH_MS: String = "nextTriggerAtEpochMs"
  const val ENABLED: String = "enabled"
  const val CONFLICT_POLICY: String = "conflictPolicy"
  const val RETURNED_COUNT: String = "returnedCount"
  const val TOTAL_COUNT: String = "totalCount"
  const val RECENT_RUN_COUNT: String = "recentRunCount"
}

private const val DEFAULT_SCHEDULED_TASK_LIST_LIMIT: Int = 20
private const val DEFAULT_SCHEDULED_TASK_RECENT_RUN_LIMIT: Int = 5
