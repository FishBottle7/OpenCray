package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal const val DEFAULT_RUNTIME_SESSION_OWNER_LEASE_DURATION_MS: Long =
  DEFAULT_RUNTIME_SERVICE_OWNER_LEASE_DURATION_MS

internal data class RuntimeSessionOwnerLease(
  val sessionId: String,
  val target: RuntimeServiceTarget,
  val phase: String = PHASE_HELD,
  val processStartId: String,
  val runtimeOwnerId: String,
  val runtimeControllerId: String,
  val durableRuntimeControllerId: String,
  val acquiredAtEpochMs: Long,
  val heartbeatAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val releasedAtEpochMs: Long? = null,
) {
  init {
    require(sessionId.isNotBlank()) { "RuntimeSessionOwnerLease sessionId must not be blank." }
    require(processStartId.isNotBlank()) {
      "RuntimeSessionOwnerLease processStartId must not be blank."
    }
    require(runtimeOwnerId.isNotBlank()) {
      "RuntimeSessionOwnerLease runtimeOwnerId must not be blank."
    }
    require(runtimeControllerId.isNotBlank()) {
      "RuntimeSessionOwnerLease runtimeControllerId must not be blank."
    }
    require(durableRuntimeControllerId.isNotBlank()) {
      "RuntimeSessionOwnerLease durableRuntimeControllerId must not be blank."
    }
    require(phase == PHASE_HELD || phase == PHASE_RELEASED) {
      "RuntimeSessionOwnerLease phase must be '$PHASE_HELD' or '$PHASE_RELEASED'."
    }
  }

  val isHeld: Boolean
    get() = phase == PHASE_HELD

  fun isExpiredAt(epochMs: Long): Boolean = epochMs >= expiresAtEpochMs

  companion object {
    const val PHASE_HELD: String = "held"
    const val PHASE_RELEASED: String = "released"
  }
}

internal fun runtimeSessionOwnerLease(
  sessionId: String,
  target: RuntimeServiceTarget,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  acquiredAtEpochMs: Long,
  heartbeatAtEpochMs: Long,
  leaseDurationMs: Long = DEFAULT_RUNTIME_SESSION_OWNER_LEASE_DURATION_MS,
): RuntimeSessionOwnerLease = RuntimeSessionOwnerLease(
  sessionId = sessionId.trim(),
  target = target,
  processStartId = runtimeOwnerLifecycle.processStartId,
  runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
  runtimeControllerId = runtimeOwnerLifecycle.runtimeControllerId,
  durableRuntimeControllerId = runtimeOwnerLifecycle.durableRuntimeControllerId,
  acquiredAtEpochMs = acquiredAtEpochMs,
  heartbeatAtEpochMs = heartbeatAtEpochMs,
  expiresAtEpochMs = heartbeatAtEpochMs + leaseDurationMs.coerceAtLeast(0L),
)

internal fun RuntimeSessionOwnerLease.sameRuntimeSessionOwnerAs(
  other: RuntimeSessionOwnerLease,
): Boolean =
  sessionId == other.sessionId &&
    target == other.target &&
    processStartId == other.processStartId &&
    runtimeControllerId == other.runtimeControllerId &&
    durableRuntimeControllerId == other.durableRuntimeControllerId

internal fun RuntimeSessionOwnerLease.released(
  releasedAtEpochMs: Long,
): RuntimeSessionOwnerLease = copy(
  phase = RuntimeSessionOwnerLease.PHASE_RELEASED,
  heartbeatAtEpochMs = releasedAtEpochMs,
  expiresAtEpochMs = releasedAtEpochMs,
  releasedAtEpochMs = releasedAtEpochMs,
)

internal interface RuntimeSessionOwnerLeaseStore {
  fun load(sessionId: String): RuntimeSessionOwnerLease?

  fun loadLiveOwner(
    sessionId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): RuntimeSessionOwnerLease?

  fun acquire(lease: RuntimeSessionOwnerLease): RuntimeSessionOwnerLease

  fun release(lease: RuntimeSessionOwnerLease): RuntimeSessionOwnerLease
}

internal fun inMemoryRuntimeSessionOwnerLeaseStore(
  runtimeServiceOwnerLeaseStore: RuntimeServiceOwnerLeaseStore =
    inMemoryRuntimeServiceOwnerLeaseStore(),
): RuntimeSessionOwnerLeaseStore = InMemoryRuntimeSessionOwnerLeaseStore(
  runtimeServiceOwnerLeaseStore = runtimeServiceOwnerLeaseStore,
)

internal class FileBackedRuntimeSessionOwnerLeaseStore(
  private val storage: DurableTextStorage,
  private val runtimeServiceOwnerLeaseStore: RuntimeServiceOwnerLeaseStore,
) : RuntimeSessionOwnerLeaseStore {
  override fun load(sessionId: String): RuntimeSessionOwnerLease? =
    storage.readText(fileNameForSession(sessionId))
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::decodeLeaseOrNull)
      ?.takeIf { lease -> lease.sessionId == sessionId.trim() }

  override fun loadLiveOwner(
    sessionId: String,
    nowEpochMs: Long,
  ): RuntimeSessionOwnerLease? = load(sessionId)
    ?.takeIf { lease -> lease.isLiveAt(nowEpochMs) }

  override fun acquire(lease: RuntimeSessionOwnerLease): RuntimeSessionOwnerLease =
    updateLease(lease.sessionId) { current, currentText ->
      if (
        current == null ||
        current.sameRuntimeSessionOwnerAs(lease) ||
        !current.isLiveAt(lease.heartbeatAtEpochMs)
      ) {
        val next = if (current?.sameRuntimeSessionOwnerAs(lease) == true) {
          lease.copy(acquiredAtEpochMs = current.acquiredAtEpochMs)
        } else {
          lease
        }
        DurableTextUpdate(text = encodeLease(next), result = next)
      } else {
        DurableTextUpdate(text = currentText, result = current, write = false)
      }
    }

  override fun release(lease: RuntimeSessionOwnerLease): RuntimeSessionOwnerLease =
    updateLease(lease.sessionId) { current, currentText ->
      if (current == null || current.sameRuntimeSessionOwnerAs(lease)) {
        val released = (current ?: lease).released(lease.heartbeatAtEpochMs)
        DurableTextUpdate(text = encodeLease(released), result = released)
      } else {
        DurableTextUpdate(text = currentText, result = current, write = false)
      }
    }

  private fun updateLease(
    sessionId: String,
    update: (RuntimeSessionOwnerLease?, String?) -> DurableTextUpdate<RuntimeSessionOwnerLease>,
  ): RuntimeSessionOwnerLease = storage.updateText(fileNameForSession(sessionId)) { currentText ->
    val current = currentText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::decodeLeaseOrNull)
      ?.takeIf { lease -> lease.sessionId == sessionId.trim() }
    update(current, currentText)
  }

  private fun RuntimeSessionOwnerLease.isLiveAt(nowEpochMs: Long): Boolean {
    if (!isHeld) {
      return false
    }
    if (!isExpiredAt(nowEpochMs)) {
      return true
    }
    val serviceLease = runtimeServiceOwnerLeaseStore.load(target) ?: return false
    return serviceLease.isHeld &&
      !serviceLease.isExpiredAt(nowEpochMs) &&
      serviceLease.processStartId == processStartId &&
      serviceLease.controllerInstanceId == runtimeControllerId &&
      serviceLease.durableControllerId == durableRuntimeControllerId
  }

  private fun decodeLeaseOrNull(encoded: String): RuntimeSessionOwnerLease? = runCatching {
    PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRuntimeSessionOwnerLeaseRecord.serializer(),
      string = encoded,
    ).toLease()
  }.getOrNull()

  private fun encodeLease(lease: RuntimeSessionOwnerLease): String =
    PersistenceJson.instance.encodeToString(
      serializer = PersistedRuntimeSessionOwnerLeaseRecord.serializer(),
      value = lease.toPersistedRecord(),
    )

  private fun fileNameForSession(sessionId: String): String =
    "runtime-session-owner-lease-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId)}.json"

  companion object {
    fun fromRootDirectory(
      runtimeRootDirectory: File,
      runtimeServiceOwnerLeaseStore: RuntimeServiceOwnerLeaseStore =
        FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(runtimeRootDirectory),
    ): RuntimeSessionOwnerLeaseStore {
      if (!runtimeRootDirectory.exists()) {
        runtimeRootDirectory.mkdirs()
      }
      return FileBackedRuntimeSessionOwnerLeaseStore(
        storage = DirectoryDurableTextStorage(runtimeRootDirectory),
        runtimeServiceOwnerLeaseStore = runtimeServiceOwnerLeaseStore,
      )
    }

    fun fromContext(context: Context): RuntimeSessionOwnerLeaseStore {
      val runtimeRootDirectory = File(
        context.filesDir,
        FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
      )
      return fromRootDirectory(runtimeRootDirectory)
    }
  }
}

private class InMemoryRuntimeSessionOwnerLeaseStore(
  private val runtimeServiceOwnerLeaseStore: RuntimeServiceOwnerLeaseStore,
) : RuntimeSessionOwnerLeaseStore {
  private val lock = Any()
  private val leases = linkedMapOf<String, RuntimeSessionOwnerLease>()

  override fun load(sessionId: String): RuntimeSessionOwnerLease? = synchronized(lock) {
    leases[sessionId.trim()]
  }

  override fun loadLiveOwner(
    sessionId: String,
    nowEpochMs: Long,
  ): RuntimeSessionOwnerLease? = synchronized(lock) {
    leases[sessionId.trim()]?.takeIf { lease -> lease.isLiveAt(nowEpochMs) }
  }

  override fun acquire(lease: RuntimeSessionOwnerLease): RuntimeSessionOwnerLease = synchronized(lock) {
    val current = leases[lease.sessionId]
    if (
      current == null ||
      current.sameRuntimeSessionOwnerAs(lease) ||
      !current.isLiveAt(lease.heartbeatAtEpochMs)
    ) {
      val next = if (current?.sameRuntimeSessionOwnerAs(lease) == true) {
        lease.copy(acquiredAtEpochMs = current.acquiredAtEpochMs)
      } else {
        lease
      }
      leases[lease.sessionId] = next
      next
    } else {
      current
    }
  }

  override fun release(lease: RuntimeSessionOwnerLease): RuntimeSessionOwnerLease = synchronized(lock) {
    val current = leases[lease.sessionId]
    if (current == null || current.sameRuntimeSessionOwnerAs(lease)) {
      (current ?: lease).released(lease.heartbeatAtEpochMs).also { released ->
        leases[lease.sessionId] = released
      }
    } else {
      current
    }
  }

  private fun RuntimeSessionOwnerLease.isLiveAt(nowEpochMs: Long): Boolean {
    if (!isHeld) {
      return false
    }
    if (!isExpiredAt(nowEpochMs)) {
      return true
    }
    val serviceLease = runtimeServiceOwnerLeaseStore.load(target) ?: return false
    return serviceLease.isHeld &&
      !serviceLease.isExpiredAt(nowEpochMs) &&
      serviceLease.processStartId == processStartId &&
      serviceLease.controllerInstanceId == runtimeControllerId &&
      serviceLease.durableControllerId == durableRuntimeControllerId
  }
}

@Serializable
private data class PersistedRuntimeSessionOwnerLeaseRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val sessionId: String,
  val target: String,
  val phase: String,
  val processStartId: String,
  val runtimeOwnerId: String,
  val runtimeControllerId: String,
  val durableRuntimeControllerId: String,
  val acquiredAtEpochMs: Long,
  val heartbeatAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val releasedAtEpochMs: Long? = null,
)

private fun RuntimeSessionOwnerLease.toPersistedRecord(): PersistedRuntimeSessionOwnerLeaseRecord =
  PersistedRuntimeSessionOwnerLeaseRecord(
    sessionId = sessionId,
    target = target.wireValue,
    phase = phase,
    processStartId = processStartId,
    runtimeOwnerId = runtimeOwnerId,
    runtimeControllerId = runtimeControllerId,
    durableRuntimeControllerId = durableRuntimeControllerId,
    acquiredAtEpochMs = acquiredAtEpochMs,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    releasedAtEpochMs = releasedAtEpochMs,
  )

private fun PersistedRuntimeSessionOwnerLeaseRecord.toLease(): RuntimeSessionOwnerLease? {
  val resolvedTarget = RuntimeServiceTarget.fromWireValue(target) ?: return null
  return runCatching {
    RuntimeSessionOwnerLease(
      sessionId = sessionId,
      target = resolvedTarget,
      phase = phase,
      processStartId = processStartId,
      runtimeOwnerId = runtimeOwnerId,
      runtimeControllerId = runtimeControllerId,
      durableRuntimeControllerId = durableRuntimeControllerId,
      acquiredAtEpochMs = acquiredAtEpochMs,
      heartbeatAtEpochMs = heartbeatAtEpochMs,
      expiresAtEpochMs = expiresAtEpochMs,
      releasedAtEpochMs = releasedAtEpochMs,
    )
  }.getOrNull()
}
