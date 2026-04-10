package com.opencray.app

import com.opencray.runtime.ScheduledTaskConflictPolicy
import com.opencray.runtime.ScheduledTaskCreateRequest
import com.opencray.runtime.ScheduledTaskCreateResult
import com.opencray.runtime.ScheduledTaskDeleteRequest
import com.opencray.runtime.ScheduledTaskDeleteResult
import com.opencray.runtime.ScheduledTaskDetails
import com.opencray.runtime.ScheduledTaskGetRequest
import com.opencray.runtime.ScheduledTaskGetResult
import com.opencray.runtime.ScheduledTaskListRequest
import com.opencray.runtime.ScheduledTaskListResult
import com.opencray.runtime.ScheduledTaskManager
import com.opencray.runtime.ScheduledTaskRunRecordSummary
import com.opencray.runtime.ScheduledTaskSummary
import com.opencray.runtime.ScheduledTaskTriggerRequest
import com.opencray.runtime.ScheduledTaskTriggerSnapshot
import com.opencray.runtime.ScheduledTaskUpdateRequest
import com.opencray.runtime.ScheduledTaskUpdateResult
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

internal class AppScheduledTaskManager(
  private val storageRootPath: Path,
  private val chatSessionStore: ChatSessionLocalStore,
  private val specStore: ScheduledTaskSpecStore,
  private val runRecordStore: ScheduledTaskRunRecordStore,
  private val triggerRegistrar: ScheduledTriggerRegistrar,
  private val triggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  private val clock: () -> Long = System::currentTimeMillis,
) : ScheduledTaskManager {
  override fun policyTargetPath(): Path = storageRootPath.toAbsolutePath().normalize()

  override fun create(request: ScheduledTaskCreateRequest): ScheduledTaskCreateResult {
    val sessionId = request.sessionId.trim()
    requireSessionExists(
      sessionId = sessionId,
      actionName = "ScheduledTaskCreate",
    )
    val nowEpochMs = clock()
    val spec = ScheduledTaskSpec(
      scheduleId = generatedScheduleId(nowEpochMs),
      sessionId = sessionId,
      title = normalizedTitle(request.title, request.prompt),
      enabled = request.enabled,
      trigger = request.trigger.toAppTrigger(createdAtEpochMs = nowEpochMs),
      payload = ScheduledTaskPayload(
        prompt = request.prompt.trim(),
      ),
      policy = ScheduledTaskPolicy(
        conflictPolicy = request.conflictPolicy.toAppConflictPolicy(),
        requiresForegroundNotification = request.requiresForegroundNotification,
        notifyOnQueued = request.notifyOnQueued,
        notifyOnApproval = request.notifyOnApproval,
        notifyOnCompletion = request.notifyOnCompletion,
        notifyOnInterruption = request.notifyOnInterruption,
      ),
      createdAtEpochMs = nowEpochMs,
      updatedAtEpochMs = nowEpochMs,
    )
    specStore.upsert(spec)
    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = triggerRegistrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )
    return spec.toCreateResult(nowEpochMs)
  }

  override fun list(request: ScheduledTaskListRequest): ScheduledTaskListResult {
    val nowEpochMs = clock()
    val requestedSessionId = request.sessionId?.trim()
      ?.takeIf(String::isNotBlank)
    val filtered = specStore.list()
      .asSequence()
      .filter { spec ->
        requestedSessionId == null || spec.sessionId == requestedSessionId
      }
      .filter { spec ->
        request.enabled == null || spec.enabled == request.enabled
      }
      .toList()
    return ScheduledTaskListResult(
      tasks = filtered
        .take(request.limit)
        .map { spec -> spec.toRuntimeSummary(nowEpochMs) },
      totalCount = filtered.size,
    )
  }

  override fun get(request: ScheduledTaskGetRequest): ScheduledTaskGetResult {
    val spec = requireExistingSpec(
      scheduleId = request.scheduleId.trim(),
      actionName = "ScheduledTaskGet",
    )
    val nowEpochMs = clock()
    val runRecords = runRecordStore.listForSchedule(spec.scheduleId)
    return ScheduledTaskGetResult(
      task = spec.toRuntimeDetails(nowEpochMs),
      recentRuns = runRecords
        .take(request.recentRunLimit)
        .map { record -> record.toRuntimeSummary() },
      totalRunCount = runRecords.size,
    )
  }

  override fun update(request: ScheduledTaskUpdateRequest): ScheduledTaskUpdateResult {
    val existing = requireExistingSpec(
      scheduleId = request.scheduleId.trim(),
      actionName = "ScheduledTaskUpdate",
    )
    val nowEpochMs = clock()
    val updated = existing.copy(
      title = request.title?.trim()?.takeIf(String::isNotBlank) ?: existing.title,
      trigger = request.trigger?.toAppTrigger(createdAtEpochMs = nowEpochMs) ?: existing.trigger,
      payload = existing.payload.copy(
        prompt = request.prompt?.trim()?.takeIf(String::isNotBlank) ?: existing.payload.prompt,
      ),
      policy = existing.policy.copy(
        conflictPolicy = request.conflictPolicy?.toAppConflictPolicy() ?: existing.policy.conflictPolicy,
        requiresForegroundNotification = request.requiresForegroundNotification
          ?: existing.policy.requiresForegroundNotification,
        notifyOnQueued = request.notifyOnQueued ?: existing.policy.notifyOnQueued,
        notifyOnApproval = request.notifyOnApproval ?: existing.policy.notifyOnApproval,
        notifyOnCompletion = request.notifyOnCompletion ?: existing.policy.notifyOnCompletion,
        notifyOnInterruption = request.notifyOnInterruption ?: existing.policy.notifyOnInterruption,
      ),
      updatedAtEpochMs = nowEpochMs,
    )
    specStore.upsert(updated)
    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = triggerRegistrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )
    return updated.toUpdateResult(nowEpochMs)
  }

  override fun delete(request: ScheduledTaskDeleteRequest): ScheduledTaskDeleteResult {
    val existing = requireExistingSpec(
      scheduleId = request.scheduleId.trim(),
      actionName = "ScheduledTaskDelete",
    )
    specStore.remove(existing.scheduleId)
    runRecordStore.removeForSchedule(existing.scheduleId)
    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = triggerRegistrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )
    return ScheduledTaskDeleteResult(
      scheduleId = existing.scheduleId,
      sessionId = existing.sessionId,
      title = existing.title,
    )
  }

  private fun requireSessionExists(
    sessionId: String,
    actionName: String,
  ) {
    require(sessionId.isNotEmpty()) { "Scheduled task sessionId must not be blank." }
    require(chatSessionStore.loadSession(sessionId) != null) {
      "$actionName target session '$sessionId' was not found."
    }
  }

  private fun requireExistingSpec(
    scheduleId: String,
    actionName: String,
  ): ScheduledTaskSpec = specStore.get(scheduleId)
    ?: throw IllegalArgumentException("$actionName schedule '$scheduleId' was not found.")

  private fun ScheduledTaskTriggerRequest.toAppTrigger(
    createdAtEpochMs: Long,
  ): ScheduledTrigger = when (this) {
    is ScheduledTaskTriggerRequest.At -> ScheduledTrigger.At(
      atEpochMs = parseScheduledTaskAbsoluteEpochMs(
        value = at,
        fieldName = "trigger.at",
      ),
    )

    is ScheduledTaskTriggerRequest.After -> ScheduledTrigger.After(
      delayMs = parseScheduledTaskDelayMs(
        value = after,
        fieldName = "trigger.after",
      ),
      createdAtEpochMs = createdAtEpochMs,
    )

    is ScheduledTaskTriggerRequest.Recurrence -> parseScheduledTaskRecurrenceTrigger(
      startAt = startAt,
      timezone = timezone,
      rrule = rrule,
      exdates = exdates,
      rdates = rdates,
    )
  }

  private fun ScheduledTaskConflictPolicy.toAppConflictPolicy(): ScheduledConflictPolicy =
    when (this) {
      ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN ->
        ScheduledConflictPolicy.ENQUEUE_NEW_RUN

      ScheduledTaskConflictPolicy.SKIP_IF_SESSION_BUSY ->
        ScheduledConflictPolicy.SKIP_IF_SESSION_BUSY

      ScheduledTaskConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER ->
        ScheduledConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER
    }

  private fun ScheduledConflictPolicy.toRuntimeConflictPolicy(): ScheduledTaskConflictPolicy =
    when (this) {
      ScheduledConflictPolicy.ENQUEUE_NEW_RUN ->
        ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN

      ScheduledConflictPolicy.SKIP_IF_SESSION_BUSY ->
        ScheduledTaskConflictPolicy.SKIP_IF_SESSION_BUSY

      ScheduledConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER ->
        ScheduledTaskConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER
    }

  private fun ScheduledTrigger.kindWireValue(): String = when (this) {
    is ScheduledTrigger.At -> "at"
    is ScheduledTrigger.After -> "after"
    is ScheduledTrigger.Recurrence -> "rrule"
  }

  private fun ScheduledTaskSpec.toCreateResult(
    nowEpochMs: Long,
  ): ScheduledTaskCreateResult = ScheduledTaskCreateResult(
    scheduleId = scheduleId,
    sessionId = sessionId,
    title = title,
    enabled = enabled,
    triggerKind = trigger.kindWireValue(),
    triggerSummary = scheduledTriggerSummary(trigger),
    nextTriggerAtEpochMs = nextScheduledTriggerAtEpochMs(this, nowEpochMs),
  )

  private fun ScheduledTaskSpec.toUpdateResult(
    nowEpochMs: Long,
  ): ScheduledTaskUpdateResult = ScheduledTaskUpdateResult(
    scheduleId = scheduleId,
    sessionId = sessionId,
    title = title,
    enabled = enabled,
    triggerKind = trigger.kindWireValue(),
    triggerSummary = scheduledTriggerSummary(trigger),
    nextTriggerAtEpochMs = nextScheduledTriggerAtEpochMs(this, nowEpochMs),
  )

  private fun ScheduledTaskSpec.toRuntimeSummary(
    nowEpochMs: Long,
  ): ScheduledTaskSummary = ScheduledTaskSummary(
    scheduleId = scheduleId,
    sessionId = sessionId,
    title = title,
    enabled = enabled,
    triggerKind = trigger.kindWireValue(),
    triggerSummary = scheduledTriggerSummary(trigger),
    nextTriggerAtEpochMs = nextScheduledTriggerAtEpochMs(this, nowEpochMs),
  )

  private fun ScheduledTaskSpec.toRuntimeDetails(
    nowEpochMs: Long,
  ): ScheduledTaskDetails = ScheduledTaskDetails(
    scheduleId = scheduleId,
    sessionId = sessionId,
    title = title,
    prompt = payload.prompt,
    enabled = enabled,
    triggerKind = trigger.kindWireValue(),
    triggerSummary = scheduledTriggerSummary(trigger),
    trigger = trigger.toRuntimeSnapshot(),
    nextTriggerAtEpochMs = nextScheduledTriggerAtEpochMs(this, nowEpochMs),
    conflictPolicy = policy.conflictPolicy.toRuntimeConflictPolicy().name.lowercase(Locale.US),
    requiresForegroundNotification = policy.requiresForegroundNotification,
    notifyOnQueued = policy.notifyOnQueued,
    notifyOnApproval = policy.notifyOnApproval,
    notifyOnCompletion = policy.notifyOnCompletion,
    notifyOnInterruption = policy.notifyOnInterruption,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private fun ScheduledTaskRunRecord.toRuntimeSummary(): ScheduledTaskRunRecordSummary =
    ScheduledTaskRunRecordSummary(
      scheduleRunId = scheduleRunId,
      triggerReason = triggerReason,
      result = result.name.lowercase(Locale.US),
      triggeredAtEpochMs = triggeredAtEpochMs,
      acceptedAtEpochMs = acceptedAtEpochMs,
      createdRunId = createdRunId,
      createdTaskId = createdTaskId,
      failureReason = failureReason,
      recoverySource = recoverySource,
      updatedAtEpochMs = updatedAtEpochMs,
    )

  private fun ScheduledTrigger.toRuntimeSnapshot(): ScheduledTaskTriggerSnapshot = when (this) {
    is ScheduledTrigger.At -> ScheduledTaskTriggerSnapshot.At(
      at = Instant.ofEpochMilli(atEpochMs).toString(),
    )

    is ScheduledTrigger.After -> ScheduledTaskTriggerSnapshot.After(
      after = Duration.ofMillis(delayMs).toString(),
    )

    is ScheduledTrigger.Recurrence -> {
      val zoneId = ZoneId.of(timezoneId)
      ScheduledTaskTriggerSnapshot.Recurrence(
        startAt = formatEpochMsForZone(startAtEpochMs, zoneId),
        timezone = timezoneId,
        rrule = rrule,
        exdates = exdatesEpochMs.map { epochMs -> formatEpochMsForZone(epochMs, zoneId) },
        rdates = rdatesEpochMs.map { epochMs -> formatEpochMsForZone(epochMs, zoneId) },
      )
    }
  }

  private fun scheduledTriggerSummary(trigger: ScheduledTrigger): String = when (trigger) {
    is ScheduledTrigger.At -> "at:${Instant.ofEpochMilli(trigger.atEpochMs)}"
    is ScheduledTrigger.After -> "after:${Duration.ofMillis(trigger.delayMs)}"
    is ScheduledTrigger.Recurrence -> scheduledRecurrenceTriggerSummary(trigger)
  }

  private fun normalizedTitle(
    title: String?,
    prompt: String,
  ): String {
    title?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    val collapsedPrompt = prompt
      .lineSequence()
      .map(String::trim)
      .firstOrNull(String::isNotBlank)
      ?.replace(Regex("\\s+"), " ")
      .orEmpty()
    if (collapsedPrompt.isEmpty()) {
      return "Scheduled task"
    }
    return if (collapsedPrompt.length <= DEFAULT_TITLE_MAX_CHARS) {
      collapsedPrompt
    } else {
      collapsedPrompt.take(DEFAULT_TITLE_MAX_CHARS - 3).trimEnd() + "..."
    }
  }

  private fun generatedScheduleId(nowEpochMs: Long): String =
    "schedule-$nowEpochMs-${UUID.randomUUID().toString().take(8)}"

  private fun formatEpochMsForZone(
    epochMs: Long,
    zoneId: ZoneId,
  ): String = Instant.ofEpochMilli(epochMs)
    .atZone(zoneId)
    .toOffsetDateTime()
    .toString()

  companion object {
    private const val DEFAULT_TITLE_MAX_CHARS: Int = 48
  }
}
