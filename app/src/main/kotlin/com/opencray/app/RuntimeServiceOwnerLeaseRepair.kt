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

internal fun runtimeOwnerLeaseExpiryRepairDelayMs(
  lease: RuntimeServiceOwnerLease?,
  nowEpochMs: Long,
): Long? {
  if (lease == null || lease.phase != RuntimeServiceOwnerLease.PHASE_HELD) {
    return null
  }
  return (lease.expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L)
}
