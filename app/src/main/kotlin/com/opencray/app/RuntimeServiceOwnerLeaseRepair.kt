package com.opencray.app

import android.content.Context
import android.util.Log

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
    workScheduler = ProcessSafeScheduledWorkSchedulerFactory.fromContext(appContext),
  )
}

internal fun scheduleRuntimeOwnerLeaseExpiryRepair(
  target: RuntimeServiceTarget,
  nowEpochMs: Long,
  ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
  workScheduler: ScheduledWorkScheduler,
): Boolean {
  val delayMs = runtimeOwnerLeaseExpiryRepairDelayMs(
    lease = loadOwnerLeaseForRepairOrNull(ownerLeaseStore, target),
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
      lease = loadOwnerLeaseForRepairOrNull(ownerLeaseStore, target),
      nowEpochMs = nowEpochMs,
    )
  }
  .minOrNull()

internal fun dueRuntimeOwnerLeaseExpiryRepairTargets(
  targets: Iterable<RuntimeServiceTarget>,
  ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
  nowEpochMs: Long,
): Set<RuntimeServiceTarget> = targets
  .filter { target ->
    val lease = loadOwnerLeaseForRepairOrNull(ownerLeaseStore, target)
      ?.takeIf { candidate -> candidate.phase == RuntimeServiceOwnerLease.PHASE_HELD }
    lease != null && lease.isExpiredAt(nowEpochMs)
  }
  .toCollection(linkedSetOf())

private fun loadOwnerLeaseForRepairOrNull(
  ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
  target: RuntimeServiceTarget,
): RuntimeServiceOwnerLease? =
  try {
    ownerLeaseStore.load(target)
  } catch (failure: RuntimeLeaseStoreCorruptedException) {
    runCatching {
      Log.e(
        OWNER_LEASE_REPAIR_LOG_TAG,
        "runtime.ownerLeaseStoreCorrupted errorCode=OWNER_LEASE_STORE_CORRUPTED " +
          "target=${target.wireValue} file=${failure.fileName} " +
          "quarantined=${failure.quarantined}",
      )
    }
    null
  }

private const val OWNER_LEASE_REPAIR_LOG_TAG: String = "OpenCrayRuntimeLease"

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
