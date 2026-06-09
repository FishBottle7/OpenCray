package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal const val DEFAULT_RUNTIME_SERVICE_OWNER_LEASE_DURATION_MS: Long = 30_000L
internal const val DEFAULT_RUNTIME_SERVICE_OWNER_LEASE_HEARTBEAT_INTERVAL_MS: Long = 10_000L

internal data class RuntimeServiceOwnerLease(
  val target: RuntimeServiceTarget,
  val phase: String = PHASE_HELD,
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val controllerInstanceId: String? = null,
  val durableControllerId: String? = null,
  val runtimeOwnerId: String,
  val runtimeControllerId: String? = null,
  val durableRuntimeControllerId: String? = null,
  val serviceInstanceId: String? = null,
  val serviceProcessName: String? = null,
  val acquiredAtEpochMs: Long,
  val heartbeatAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val releasedAtEpochMs: Long? = null,
  val lastAcquireFailure: RuntimeServiceOwnerLeaseAcquireFailure? = null,
) {
  init {
    require(processStartId.isNotBlank()) {
      "RuntimeServiceOwnerLease processStartId must not be blank."
    }
    require(runtimeOwnerId.isNotBlank()) {
      "RuntimeServiceOwnerLease runtimeOwnerId must not be blank."
    }
    require(phase == PHASE_HELD || phase == PHASE_RELEASED) {
      "RuntimeServiceOwnerLease phase must be '$PHASE_HELD' or '$PHASE_RELEASED'."
    }
  }

  val isHeld: Boolean
    get() = phase == PHASE_HELD

  fun isExpiredAt(epochMs: Long): Boolean = epochMs >= expiresAtEpochMs

  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("target", target.wireValue)
    put("phase", phase)
    put("isHeld", isHeld)
    put("processStartId", processStartId)
    put("processStartedAtEpochMs", processStartedAtEpochMs)
    put("controllerInstanceId", controllerInstanceId)
    put("durableControllerId", durableControllerId)
    put("runtimeOwnerId", runtimeOwnerId)
    put("runtimeControllerId", runtimeControllerId)
    put("durableRuntimeControllerId", durableRuntimeControllerId)
    put("serviceInstanceId", serviceInstanceId)
    put("serviceProcessName", serviceProcessName)
    put("acquiredAtEpochMs", acquiredAtEpochMs)
    put("heartbeatAtEpochMs", heartbeatAtEpochMs)
    put("expiresAtEpochMs", expiresAtEpochMs)
    put("releasedAtEpochMs", releasedAtEpochMs)
    lastAcquireFailure?.let { failure ->
      put("lastAcquireFailure", failure.snapshotMap())
    }
  }

  companion object {
    const val PHASE_HELD: String = "held"
    const val PHASE_RELEASED: String = "released"
  }
}

internal data class RuntimeServiceOwnerLeaseAcquireFailure(
  val target: RuntimeServiceTarget,
  val reason: String = REASON_OWNER_LEASE_HELD,
  val attemptedAtEpochMs: Long,
  val attemptedProcessStartId: String,
  val attemptedControllerInstanceId: String? = null,
  val attemptedDurableControllerId: String? = null,
  val attemptedRuntimeOwnerId: String,
  val attemptedRuntimeControllerId: String? = null,
  val attemptedDurableRuntimeControllerId: String? = null,
  val attemptedServiceInstanceId: String? = null,
  val attemptedServiceProcessName: String? = null,
  val holderRuntimeOwnerId: String,
  val holderControllerInstanceId: String? = null,
  val holderDurableControllerId: String? = null,
  val holderServiceInstanceId: String? = null,
  val holderHeartbeatAtEpochMs: Long,
  val holderExpiresAtEpochMs: Long,
) {
  init {
    require(reason.isNotBlank()) {
      "RuntimeServiceOwnerLeaseAcquireFailure reason must not be blank."
    }
    require(attemptedProcessStartId.isNotBlank()) {
      "RuntimeServiceOwnerLeaseAcquireFailure attemptedProcessStartId must not be blank."
    }
    require(attemptedRuntimeOwnerId.isNotBlank()) {
      "RuntimeServiceOwnerLeaseAcquireFailure attemptedRuntimeOwnerId must not be blank."
    }
    require(holderRuntimeOwnerId.isNotBlank()) {
      "RuntimeServiceOwnerLeaseAcquireFailure holderRuntimeOwnerId must not be blank."
    }
  }

  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("target", target.wireValue)
    put("reason", reason)
    put("attemptedAtEpochMs", attemptedAtEpochMs)
    put("attemptedProcessStartId", attemptedProcessStartId)
    put("attemptedControllerInstanceId", attemptedControllerInstanceId)
    put("attemptedDurableControllerId", attemptedDurableControllerId)
    put("attemptedRuntimeOwnerId", attemptedRuntimeOwnerId)
    put("attemptedRuntimeControllerId", attemptedRuntimeControllerId)
    put("attemptedDurableRuntimeControllerId", attemptedDurableRuntimeControllerId)
    put("attemptedServiceInstanceId", attemptedServiceInstanceId)
    put("attemptedServiceProcessName", attemptedServiceProcessName)
    put("holderRuntimeOwnerId", holderRuntimeOwnerId)
    put("holderControllerInstanceId", holderControllerInstanceId)
    put("holderDurableControllerId", holderDurableControllerId)
    put("holderServiceInstanceId", holderServiceInstanceId)
    put("holderHeartbeatAtEpochMs", holderHeartbeatAtEpochMs)
    put("holderExpiresAtEpochMs", holderExpiresAtEpochMs)
  }

  companion object {
    const val REASON_OWNER_LEASE_HELD: String = "owner_lease_held"
  }
}

internal fun runtimeServiceOwnerLease(
  target: RuntimeServiceTarget,
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor?,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  serviceLifecycle: RuntimeServiceLifecycleDescriptor?,
  acquiredAtEpochMs: Long,
  heartbeatAtEpochMs: Long,
  leaseDurationMs: Long = DEFAULT_RUNTIME_SERVICE_OWNER_LEASE_DURATION_MS,
): RuntimeServiceOwnerLease =
  RuntimeServiceOwnerLease(
    target = target,
    processStartId = runtimeOwnerLifecycle.processStartId,
    processStartedAtEpochMs = runtimeOwnerLifecycle.processStartedAtEpochMs,
    controllerInstanceId = runtimeControllerLifecycle?.controllerInstanceId
      ?: runtimeOwnerLifecycle.runtimeControllerId,
    durableControllerId = runtimeControllerLifecycle?.durableControllerId
      ?: runtimeOwnerLifecycle.durableRuntimeControllerId,
    runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
    runtimeControllerId = runtimeOwnerLifecycle.runtimeControllerId,
    durableRuntimeControllerId = runtimeOwnerLifecycle.durableRuntimeControllerId,
    serviceInstanceId = serviceLifecycle?.serviceInstanceId,
    serviceProcessName = serviceLifecycle?.serviceProcess?.processName,
    acquiredAtEpochMs = acquiredAtEpochMs,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = heartbeatAtEpochMs + leaseDurationMs.coerceAtLeast(0L),
  )

internal fun RuntimeServiceOwnerLease.released(
  releasedAtEpochMs: Long,
): RuntimeServiceOwnerLease = copy(
  phase = RuntimeServiceOwnerLease.PHASE_RELEASED,
  heartbeatAtEpochMs = releasedAtEpochMs,
  expiresAtEpochMs = releasedAtEpochMs,
  releasedAtEpochMs = releasedAtEpochMs,
)

internal fun RuntimeServiceOwnerLease.sameRuntimeServiceOwnerAs(
  other: RuntimeServiceOwnerLease,
): Boolean =
  target == other.target &&
    processStartId == other.processStartId &&
    runtimeOwnerId == other.runtimeOwnerId &&
    controllerInstanceId == other.controllerInstanceId &&
    durableControllerId == other.durableControllerId

private fun RuntimeServiceOwnerLease.leaseForAcceptedReplacement(
  next: RuntimeServiceOwnerLease,
): RuntimeServiceOwnerLease =
  if (sameRuntimeServiceOwnerAs(next)) {
    next.copy(lastAcquireFailure = lastAcquireFailure)
  } else {
    next.copy(lastAcquireFailure = null)
  }

private fun RuntimeServiceOwnerLease.withAcquireFailure(
  attemptedLease: RuntimeServiceOwnerLease,
): RuntimeServiceOwnerLease = copy(
  lastAcquireFailure = runtimeServiceOwnerLeaseAcquireFailure(
    attemptedLease = attemptedLease,
    holderLease = this,
  ),
)

internal interface RuntimeServiceOwnerLeaseStore {
  fun load(target: RuntimeServiceTarget): RuntimeServiceOwnerLease?

  fun save(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease

  fun release(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease

  fun clear(target: RuntimeServiceTarget)
}

internal fun inMemoryRuntimeServiceOwnerLeaseStore(): RuntimeServiceOwnerLeaseStore =
  InMemoryRuntimeServiceOwnerLeaseStore()

internal class FileBackedRuntimeServiceOwnerLeaseStore(
  private val storage: DurableTextStorage,
) : RuntimeServiceOwnerLeaseStore {
  override fun load(target: RuntimeServiceTarget): RuntimeServiceOwnerLease? =
    storage.readText(fileNameForTarget(target))
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::decodeLeaseOrNull)
      ?.takeIf { lease -> lease.target == target }

  override fun save(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease =
    updateLease(lease.target) { current, currentText ->
      if (current == null || current.canBeReplacedBy(lease)) {
        val nextLease = current?.leaseForAcceptedReplacement(lease)
          ?: lease.copy(lastAcquireFailure = null)
        DurableTextUpdate(
          text = encodeLease(nextLease),
          result = nextLease,
        )
      } else {
        val nextLease = current.withAcquireFailure(lease)
        DurableTextUpdate(
          text = encodeLease(nextLease),
          result = nextLease,
        )
      }
    }

  override fun release(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease =
    updateLease(lease.target) { current, currentText ->
      if (current == null || current.sameRuntimeServiceOwnerAs(lease)) {
        val currentLease = current ?: lease
        val released = currentLease
          .takeUnless { it.phase == RuntimeServiceOwnerLease.PHASE_RELEASED }
          ?.released(lease.heartbeatAtEpochMs)
          ?: currentLease
        DurableTextUpdate(
          text = encodeLease(released),
          result = released,
        )
      } else {
        DurableTextUpdate(
          text = currentText,
          result = current,
          write = false,
        )
      }
    }

  override fun clear(target: RuntimeServiceTarget) {
    storage.delete(fileNameForTarget(target))
  }

  private fun updateLease(
    target: RuntimeServiceTarget,
    update: (
      RuntimeServiceOwnerLease?,
      String?,
    ) -> DurableTextUpdate<RuntimeServiceOwnerLease>,
  ): RuntimeServiceOwnerLease = storage.updateText(fileNameForTarget(target)) { currentText ->
    val current = currentText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::decodeLeaseOrNull)
      ?.takeIf { lease -> lease.target == target }
    update(current, currentText)
  }

  private fun RuntimeServiceOwnerLease.canBeReplacedBy(
    next: RuntimeServiceOwnerLease,
  ): Boolean =
    sameRuntimeServiceOwnerAs(next) ||
      (
        acquiredAtEpochMs <= next.acquiredAtEpochMs &&
          (
            phase == RuntimeServiceOwnerLease.PHASE_RELEASED ||
              isExpiredAt(next.heartbeatAtEpochMs)
            )
        )

  private fun decodeLeaseOrNull(encoded: String): RuntimeServiceOwnerLease? = runCatching {
    PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRuntimeServiceOwnerLeaseRecord.serializer(),
      string = encoded,
    ).toLease()
  }.getOrNull()

  private fun encodeLease(lease: RuntimeServiceOwnerLease): String =
    PersistenceJson.instance.encodeToString(
      serializer = PersistedRuntimeServiceOwnerLeaseRecord.serializer(),
      value = lease.toPersistedRecord(),
    )

  private fun fileNameForTarget(target: RuntimeServiceTarget): String =
    "runtime-service-owner-lease-${target.wireValue}.json"

  companion object {
    fun fromRootDirectory(runtimeRootDirectory: File): RuntimeServiceOwnerLeaseStore {
      if (!runtimeRootDirectory.exists()) {
        runtimeRootDirectory.mkdirs()
      }
      return FileBackedRuntimeServiceOwnerLeaseStore(
        storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      )
    }

    fun fromContext(context: Context): RuntimeServiceOwnerLeaseStore =
      fromRootDirectory(
        File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

private class InMemoryRuntimeServiceOwnerLeaseStore : RuntimeServiceOwnerLeaseStore {
  private val lock = Any()
  private val leases = linkedMapOf<RuntimeServiceTarget, RuntimeServiceOwnerLease>()

  override fun load(target: RuntimeServiceTarget): RuntimeServiceOwnerLease? = synchronized(lock) {
    leases[target]
  }

  override fun save(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease = synchronized(lock) {
    val current = leases[lease.target]
    if (current == null || current.canBeReplacedBy(lease)) {
      val nextLease = current?.leaseForAcceptedReplacement(lease)
        ?: lease.copy(lastAcquireFailure = null)
      leases[lease.target] = nextLease
      nextLease
    } else {
      val nextLease = current.withAcquireFailure(lease)
      leases[lease.target] = nextLease
      nextLease
    }
  }

  override fun release(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease = synchronized(lock) {
    val current = leases[lease.target]
    if (current == null || current.sameRuntimeServiceOwnerAs(lease)) {
      val currentLease = current ?: lease
      val released = currentLease
        .takeUnless { it.phase == RuntimeServiceOwnerLease.PHASE_RELEASED }
        ?.released(lease.heartbeatAtEpochMs)
        ?: currentLease
      leases[lease.target] = released
      released
    } else {
      current
    }
  }

  override fun clear(target: RuntimeServiceTarget) {
    synchronized(lock) {
      leases.remove(target)
    }
  }

  private fun RuntimeServiceOwnerLease.canBeReplacedBy(
    next: RuntimeServiceOwnerLease,
  ): Boolean =
    sameRuntimeServiceOwnerAs(next) ||
      (
        acquiredAtEpochMs <= next.acquiredAtEpochMs &&
          (
            phase == RuntimeServiceOwnerLease.PHASE_RELEASED ||
              isExpiredAt(next.heartbeatAtEpochMs)
            )
        )
}

@Serializable
private data class PersistedRuntimeServiceOwnerLeaseRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val target: String,
  val phase: String,
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val controllerInstanceId: String? = null,
  val durableControllerId: String? = null,
  val runtimeOwnerId: String,
  val runtimeControllerId: String? = null,
  val durableRuntimeControllerId: String? = null,
  val serviceInstanceId: String? = null,
  val serviceProcessName: String? = null,
  val acquiredAtEpochMs: Long,
  val heartbeatAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val releasedAtEpochMs: Long? = null,
  val lastAcquireFailure: PersistedRuntimeServiceOwnerLeaseAcquireFailure? = null,
)

@Serializable
private data class PersistedRuntimeServiceOwnerLeaseAcquireFailure(
  val target: String,
  val reason: String,
  val attemptedAtEpochMs: Long,
  val attemptedProcessStartId: String,
  val attemptedControllerInstanceId: String? = null,
  val attemptedDurableControllerId: String? = null,
  val attemptedRuntimeOwnerId: String,
  val attemptedRuntimeControllerId: String? = null,
  val attemptedDurableRuntimeControllerId: String? = null,
  val attemptedServiceInstanceId: String? = null,
  val attemptedServiceProcessName: String? = null,
  val holderRuntimeOwnerId: String,
  val holderControllerInstanceId: String? = null,
  val holderDurableControllerId: String? = null,
  val holderServiceInstanceId: String? = null,
  val holderHeartbeatAtEpochMs: Long,
  val holderExpiresAtEpochMs: Long,
)

private fun RuntimeServiceOwnerLease.toPersistedRecord(): PersistedRuntimeServiceOwnerLeaseRecord =
  PersistedRuntimeServiceOwnerLeaseRecord(
    target = target.wireValue,
    phase = phase,
    processStartId = processStartId,
    processStartedAtEpochMs = processStartedAtEpochMs,
    controllerInstanceId = controllerInstanceId,
    durableControllerId = durableControllerId,
    runtimeOwnerId = runtimeOwnerId,
    runtimeControllerId = runtimeControllerId,
    durableRuntimeControllerId = durableRuntimeControllerId,
    serviceInstanceId = serviceInstanceId,
    serviceProcessName = serviceProcessName,
    acquiredAtEpochMs = acquiredAtEpochMs,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    releasedAtEpochMs = releasedAtEpochMs,
    lastAcquireFailure = lastAcquireFailure?.toPersistedRecord(),
  )

private fun RuntimeServiceOwnerLeaseAcquireFailure.toPersistedRecord():
  PersistedRuntimeServiceOwnerLeaseAcquireFailure =
  PersistedRuntimeServiceOwnerLeaseAcquireFailure(
    target = target.wireValue,
    reason = reason,
    attemptedAtEpochMs = attemptedAtEpochMs,
    attemptedProcessStartId = attemptedProcessStartId,
    attemptedControllerInstanceId = attemptedControllerInstanceId,
    attemptedDurableControllerId = attemptedDurableControllerId,
    attemptedRuntimeOwnerId = attemptedRuntimeOwnerId,
    attemptedRuntimeControllerId = attemptedRuntimeControllerId,
    attemptedDurableRuntimeControllerId = attemptedDurableRuntimeControllerId,
    attemptedServiceInstanceId = attemptedServiceInstanceId,
    attemptedServiceProcessName = attemptedServiceProcessName,
    holderRuntimeOwnerId = holderRuntimeOwnerId,
    holderControllerInstanceId = holderControllerInstanceId,
    holderDurableControllerId = holderDurableControllerId,
    holderServiceInstanceId = holderServiceInstanceId,
    holderHeartbeatAtEpochMs = holderHeartbeatAtEpochMs,
    holderExpiresAtEpochMs = holderExpiresAtEpochMs,
  )

private fun PersistedRuntimeServiceOwnerLeaseRecord.toLease(): RuntimeServiceOwnerLease? {
  val resolvedTarget = RuntimeServiceTarget.fromWireValue(target) ?: return null
  return RuntimeServiceOwnerLease(
    target = resolvedTarget,
    phase = phase,
    processStartId = processStartId,
    processStartedAtEpochMs = processStartedAtEpochMs,
    controllerInstanceId = controllerInstanceId,
    durableControllerId = durableControllerId,
    runtimeOwnerId = runtimeOwnerId,
    runtimeControllerId = runtimeControllerId,
    durableRuntimeControllerId = durableRuntimeControllerId,
    serviceInstanceId = serviceInstanceId,
    serviceProcessName = serviceProcessName,
    acquiredAtEpochMs = acquiredAtEpochMs,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    releasedAtEpochMs = releasedAtEpochMs,
    lastAcquireFailure = lastAcquireFailure?.toLeaseAcquireFailure(),
  )
}

private fun PersistedRuntimeServiceOwnerLeaseAcquireFailure.toLeaseAcquireFailure():
  RuntimeServiceOwnerLeaseAcquireFailure? {
  val resolvedTarget = RuntimeServiceTarget.fromWireValue(target) ?: return null
  return runCatching {
    RuntimeServiceOwnerLeaseAcquireFailure(
      target = resolvedTarget,
      reason = reason,
      attemptedAtEpochMs = attemptedAtEpochMs,
      attemptedProcessStartId = attemptedProcessStartId,
      attemptedControllerInstanceId = attemptedControllerInstanceId,
      attemptedDurableControllerId = attemptedDurableControllerId,
      attemptedRuntimeOwnerId = attemptedRuntimeOwnerId,
      attemptedRuntimeControllerId = attemptedRuntimeControllerId,
      attemptedDurableRuntimeControllerId = attemptedDurableRuntimeControllerId,
      attemptedServiceInstanceId = attemptedServiceInstanceId,
      attemptedServiceProcessName = attemptedServiceProcessName,
      holderRuntimeOwnerId = holderRuntimeOwnerId,
      holderControllerInstanceId = holderControllerInstanceId,
      holderDurableControllerId = holderDurableControllerId,
      holderServiceInstanceId = holderServiceInstanceId,
      holderHeartbeatAtEpochMs = holderHeartbeatAtEpochMs,
      holderExpiresAtEpochMs = holderExpiresAtEpochMs,
    )
  }.getOrNull()
}

private fun runtimeServiceOwnerLeaseAcquireFailure(
  attemptedLease: RuntimeServiceOwnerLease,
  holderLease: RuntimeServiceOwnerLease,
): RuntimeServiceOwnerLeaseAcquireFailure = RuntimeServiceOwnerLeaseAcquireFailure(
  target = attemptedLease.target,
  attemptedAtEpochMs = attemptedLease.heartbeatAtEpochMs,
  attemptedProcessStartId = attemptedLease.processStartId,
  attemptedControllerInstanceId = attemptedLease.controllerInstanceId,
  attemptedDurableControllerId = attemptedLease.durableControllerId,
  attemptedRuntimeOwnerId = attemptedLease.runtimeOwnerId,
  attemptedRuntimeControllerId = attemptedLease.runtimeControllerId,
  attemptedDurableRuntimeControllerId = attemptedLease.durableRuntimeControllerId,
  attemptedServiceInstanceId = attemptedLease.serviceInstanceId,
  attemptedServiceProcessName = attemptedLease.serviceProcessName,
  holderRuntimeOwnerId = holderLease.runtimeOwnerId,
  holderControllerInstanceId = holderLease.controllerInstanceId,
  holderDurableControllerId = holderLease.durableControllerId,
  holderServiceInstanceId = holderLease.serviceInstanceId,
  holderHeartbeatAtEpochMs = holderLease.heartbeatAtEpochMs,
  holderExpiresAtEpochMs = holderLease.expiresAtEpochMs,
)
