package com.opencray.app

import android.content.Context

internal fun scheduleRuntimeOwnerLeaseExpiryRepair(
  context: Context,
  target: RuntimeServiceTarget,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  val appContext = context.applicationContext
  return scheduleRuntimeOwnerLeaseExpiryRepair(
    target = target,
    nowEpochMs = nowEpochMs,
    ownerLeaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromContext(appContext),
    workScheduler = WorkManagerScheduledWorkScheduler.fromContext(appContext),
  )
}

internal fun scheduleRuntimeOwnerLeaseExpiryRepair(
  target: RuntimeServiceTarget,
  nowEpochMs: Long,
  ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
  workScheduler: ScheduledWorkScheduler,
): Boolean {
  val delayMs = runtimeOwnerLeaseExpiryRepairDelayMs(
    lease = ownerLeaseStore.load(target),
    nowEpochMs = nowEpochMs,
  ) ?: return false
  workScheduler.enqueueRepair(
    reason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
    initialDelayMs = delayMs,
  )
  return true
}

internal fun scheduleNextRuntimeOwnerLeaseExpiryRepair(
  nowEpochMs: Long,
  ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
  workScheduler: ScheduledWorkScheduler,
  targets: Iterable<RuntimeServiceTarget> = RuntimeServiceTarget.entries,
): Boolean {
  val delayMs = nextRuntimeOwnerLeaseExpiryRepairDelayMs(
    targets = targets,
    ownerLeaseStore = ownerLeaseStore,
    nowEpochMs = nowEpochMs,
  ) ?: return false
  workScheduler.enqueueRepair(
    reason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
    initialDelayMs = delayMs,
  )
  return true
}

internal fun nextRuntimeOwnerLeaseExpiryRepairDelayMs(
  targets: Iterable<RuntimeServiceTarget>,
  ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
  nowEpochMs: Long,
): Long? = targets
  .mapNotNull { target ->
    runtimeOwnerLeaseFutureExpiryRepairDelayMs(
      lease = ownerLeaseStore.load(target),
      nowEpochMs = nowEpochMs,
    )
  }
  .minOrNull()

internal fun runtimeOwnerLeaseExpiryRepairDelayMs(
  lease: RuntimeServiceOwnerLease?,
  nowEpochMs: Long,
): Long? {
  if (lease == null || lease.phase != RuntimeServiceOwnerLease.PHASE_HELD) {
    return null
  }
  return (lease.expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L)
}

private fun runtimeOwnerLeaseFutureExpiryRepairDelayMs(
  lease: RuntimeServiceOwnerLease?,
  nowEpochMs: Long,
): Long? = runtimeOwnerLeaseExpiryRepairDelayMs(
  lease = lease,
  nowEpochMs = nowEpochMs,
)?.takeIf { delayMs -> delayMs > 0L }
