package com.opencray.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

sealed class FileStateSnapshot {
  object Missing : FileStateSnapshot()

  data class ExistingFile(
    val bytes: ByteArray,
  ) : FileStateSnapshot()
}

data class RollbackRecord(
  val path: Path,
  val state: FileStateSnapshot,
)

data class RollbackCheckpoint(
  val id: String,
  val createdAtEpochMs: Long,
  val records: List<RollbackRecord>,
)

interface RollbackJournal {
  fun checkpoint(paths: List<Path>): RollbackCheckpoint
  fun commit(checkpointId: String)
  fun restore(checkpoint: RollbackCheckpoint)
  fun activeCheckpointIds(): Set<String>
}

/**
 * Local-only rollback journal.
 *
 * Remote rollback is intentionally out-of-scope for this implementation.
 */
class LocalRollbackJournal(
  private val clock: () -> Long = System::currentTimeMillis,
  private val idCounter: AtomicLong = AtomicLong(0L),
) : RollbackJournal {
  private val activeCheckpoints: LinkedHashMap<String, RollbackCheckpoint> = linkedMapOf()

  override fun checkpoint(paths: List<Path>): RollbackCheckpoint {
    val orderedPaths = paths
      .distinct()
      .sortedBy { it.toAbsolutePath().normalize().toString() }

    val records = orderedPaths.map { path ->
      RollbackRecord(
        path = path,
        state = captureState(path),
      )
    }

    val checkpoint = RollbackCheckpoint(
      id = "local-${idCounter.incrementAndGet()}",
      createdAtEpochMs = clock(),
      records = records,
    )
    activeCheckpoints[checkpoint.id] = checkpoint
    return checkpoint
  }

  override fun commit(checkpointId: String) {
    activeCheckpoints.remove(checkpointId)
  }

  override fun restore(checkpoint: RollbackCheckpoint) {
    val records = checkpoint.records.sortedByDescending { it.path.nameCount }
    for (record in records) {
      restoreRecord(record)
    }
    activeCheckpoints.remove(checkpoint.id)
  }

  override fun activeCheckpointIds(): Set<String> = activeCheckpoints.keys.toSet()

  private fun captureState(path: Path): FileStateSnapshot {
    if (!Files.exists(path)) {
      return FileStateSnapshot.Missing
    }
    if (Files.isDirectory(path)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.INVALID_OPERATION,
        message = "Rollback journal supports files only: $path",
      )
    }

    return FileStateSnapshot.ExistingFile(Files.readAllBytes(path))
  }

  private fun restoreRecord(record: RollbackRecord) {
    when (val state = record.state) {
      FileStateSnapshot.Missing -> restoreMissing(record.path)
      is FileStateSnapshot.ExistingFile -> restoreExistingFile(record.path, state.bytes)
    }
  }

  private fun restoreMissing(path: Path) {
    if (!Files.exists(path)) {
      return
    }
    if (Files.isDirectory(path)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.INVALID_OPERATION,
        message = "Rollback restore cannot delete directory path: $path",
      )
    }
    Files.delete(path)
  }

  private fun restoreExistingFile(path: Path, bytes: ByteArray) {
    val parent = path.parent
    if (parent != null) {
      Files.createDirectories(parent)
    }
    Files.write(
      path,
      bytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
  }
}
