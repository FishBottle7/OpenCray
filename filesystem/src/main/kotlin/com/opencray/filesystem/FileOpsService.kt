package com.opencray.filesystem

import com.opencray.policy.ProtectedRegistry
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock

/**
 * Executes local file mutations under canonical boundary checks.
 *
 * Rollback is implemented for local filesystem operations only.
 */
class FileOpsService(
  approvedRoots: Set<Path>,
  private val protectedRegistry: ProtectedRegistry = ProtectedRegistry(),
  private val rollbackJournal: RollbackJournal = LocalRollbackJournal(),
  mutationLockDirectory: Path? = null,
) {
  private val canonicalApprovedRoots: List<Path>
  private val defaultRoot: Path
  private val mutationLock = ReentrantLock(true)
  private val mutationLockCoordinator: FileMutationLockCoordinator?

  init {
    require(approvedRoots.isNotEmpty()) { "At least one approved root is required." }

    canonicalApprovedRoots = approvedRoots
      .map(::canonicalize)
      .distinct()
      .sortedBy { it.toString() }

    defaultRoot = canonicalApprovedRoots.first()
    mutationLockCoordinator = mutationLockDirectory?.let(::FileMutationLockCoordinator)
  }

  fun executeBatch(operations: List<FileMutationOperation>): FileBatchResult {
    if (operations.isEmpty()) {
      return FileBatchResult(
        checkpointId = "",
        checkpointEntryCount = 0,
        operationCount = 0,
        committedPaths = emptyList(),
      )
    }

    return withMutationLock {
      val resolvedOperations = operations.map(::resolveOperation)
      val checkpointPaths = linkedSetOf<Path>()
      for (operation in resolvedOperations) {
        checkpointPaths += operation.checkpointPaths
      }

      val checkpoint = rollbackJournal.checkpoint(checkpointPaths.toList())

      try {
        for (operation in resolvedOperations) {
          applyResolvedOperation(operation)
        }

        rollbackJournal.commit(checkpoint.id)

        FileBatchResult(
          checkpointId = checkpoint.id,
          checkpointEntryCount = checkpoint.records.size,
          operationCount = resolvedOperations.size,
          committedPaths = resolvedOperations
            .flatMap { it.committedPaths }
            .distinct(),
        )
      } catch (failure: Throwable) {
        handleFailure(checkpoint, failure)
      }
    }
  }

  fun <T> withMutationLock(action: () -> T): T {
    mutationLock.lock()
    try {
      return mutationLockCoordinator?.withScopes(canonicalApprovedRoots, action) ?: action()
    } finally {
      mutationLock.unlock()
    }
  }

  fun activeCheckpointIds(): Set<String> = rollbackJournal.activeCheckpointIds()

  private fun handleFailure(checkpoint: RollbackCheckpoint, failure: Throwable): Nothing {
    try {
      rollbackJournal.restore(checkpoint)
    } catch (rollbackFailure: Throwable) {
      val rollbackException = FileOpsException(
        reasonCode = FileOpsReasonCode.ROLLBACK_FAILED,
        message = "Rollback failed for checkpoint ${checkpoint.id}: ${rollbackFailure.message}",
        cause = rollbackFailure,
      )
      rollbackException.addSuppressed(failure)
      throw rollbackException
    }

    when (failure) {
      is FileOpsException -> throw failure.markRollbackRestored()
      else -> throw FileOpsException(
        reasonCode = FileOpsReasonCode.IO_ERROR,
        message = "File batch failed and rollback completed: ${failure.message}",
        cause = failure,
      ).markRollbackRestored()
    }
  }

  private fun resolveOperation(operation: FileMutationOperation): ResolvedOperation = when (operation) {
    is FileMutationOperation.Create -> {
      val target = resolvePath(operation.path, "create target")
      ResolvedOperation.Create(
        path = target,
        content = operation.content.toByteArray(StandardCharsets.UTF_8),
      )
    }

    is FileMutationOperation.Write -> {
      val target = resolvePath(operation.path, "write target")
      ResolvedOperation.Write(
        path = target,
        content = operation.content.toByteArray(StandardCharsets.UTF_8),
      )
    }

    is FileMutationOperation.Delete -> {
      val target = resolvePath(operation.path, "delete target")
      enforceProtectedFileInvariant(target)
      ResolvedOperation.Delete(path = target)
    }

    is FileMutationOperation.Move -> {
      val source = resolvePath(operation.sourcePath, "move source")
      val destination = resolvePath(operation.destinationPath, "move destination")
      enforceProtectedFileInvariant(source)
      enforceProtectedFileInvariant(destination)
      ResolvedOperation.Move(sourcePath = source, destinationPath = destination)
    }
  }

  private fun applyResolvedOperation(operation: ResolvedOperation) {
    when (operation) {
      is ResolvedOperation.Create -> createFile(operation.path, operation.content)
      is ResolvedOperation.Write -> writeFile(operation.path, operation.content)
      is ResolvedOperation.Delete -> deleteFile(operation.path)
      is ResolvedOperation.Move -> moveFile(operation.sourcePath, operation.destinationPath)
    }
  }

  private fun createFile(path: Path, content: ByteArray) {
    if (Files.exists(path)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.ALREADY_EXISTS,
        message = "Create failed because file already exists: $path",
      )
    }

    ensureParentDirectory(path)
    writeBytesAtomically(path = path, content = content, replaceExisting = false)
  }

  private fun writeFile(path: Path, content: ByteArray) {
    if (Files.exists(path) && Files.isDirectory(path)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.INVALID_OPERATION,
        message = "Write target is a directory: $path",
      )
    }

    ensureParentDirectory(path)
    writeBytesAtomically(path = path, content = content, replaceExisting = true)
  }

  private fun deleteFile(path: Path) {
    if (!Files.exists(path)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.FILE_NOT_FOUND,
        message = "Delete target does not exist: $path",
      )
    }
    if (Files.isDirectory(path)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.INVALID_OPERATION,
        message = "Delete target is a directory: $path",
      )
    }

    Files.delete(path)
  }

  private fun moveFile(sourcePath: Path, destinationPath: Path) {
    if (!Files.exists(sourcePath)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.FILE_NOT_FOUND,
        message = "Move source does not exist: $sourcePath",
      )
    }
    if (Files.isDirectory(sourcePath)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.INVALID_OPERATION,
        message = "Move source is a directory: $sourcePath",
      )
    }
    if (Files.exists(destinationPath) && Files.isDirectory(destinationPath)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.INVALID_OPERATION,
        message = "Move destination is a directory: $destinationPath",
      )
    }

    ensureParentDirectory(destinationPath)
    if (sourcePath == destinationPath) {
      return
    }
    Files.move(sourcePath, destinationPath)
  }

  private fun ensureParentDirectory(path: Path) {
    val parent = path.parent ?: return
    Files.createDirectories(parent)
  }

  private fun enforceProtectedFileInvariant(path: Path) {
    if (!protectedRegistry.isProtected(path)) {
      return
    }

    throw FileOpsException(
      reasonCode = FileOpsReasonCode.DENY_PROTECTED_FILE,
      message = "Operation targets protected file: ${path.fileName}",
    )
  }

  private fun resolvePath(candidatePath: Path, label: String): Path {
    if (containsTraversalSegment(candidatePath)) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.DENY_PATH_TRAVERSAL,
        message = "$label path contains traversal segment '..'.",
      )
    }

    val resolved = if (candidatePath.isAbsolute) {
      candidatePath
    } else {
      defaultRoot.resolve(candidatePath)
    }

    val canonicalCandidate = canonicalize(resolved)
    val insideApprovedRoot = canonicalApprovedRoots.any { root -> canonicalCandidate.startsWith(root) }
    if (!insideApprovedRoot) {
      throw FileOpsException(
        reasonCode = FileOpsReasonCode.DENY_PATH_ESCAPE,
        message = "$label path escapes approved roots.",
      )
    }

    return canonicalCandidate
  }

  private fun containsTraversalSegment(path: Path): Boolean {
    for (segment in path) {
      if (segment.toString() == "..") {
        return true
      }
    }
    return false
  }

  private fun canonicalize(path: Path): Path {
    val absoluteNormalized = path.toAbsolutePath().normalize()
    val existingAncestor = findNearestExistingAncestor(absoluteNormalized) ?: return absoluteNormalized
    val relativeSuffix = existingAncestor.relativize(absoluteNormalized)

    return runCatching {
      val canonicalAncestor = existingAncestor.toRealPath()
      canonicalAncestor.resolve(relativeSuffix).normalize()
    }.getOrDefault(absoluteNormalized)
  }

  private fun findNearestExistingAncestor(path: Path): Path? {
    var current: Path? = path
    while (current != null) {
      if (Files.exists(current)) {
        return current
      }
      current = current.parent
    }
    return null
  }

  private sealed class ResolvedOperation {
    abstract val checkpointPaths: List<Path>
    abstract val committedPaths: List<Path>

    data class Create(
      val path: Path,
      val content: ByteArray,
    ) : ResolvedOperation() {
      override val checkpointPaths: List<Path> = listOf(path)
      override val committedPaths: List<Path> = listOf(path)
    }

    data class Write(
      val path: Path,
      val content: ByteArray,
    ) : ResolvedOperation() {
      override val checkpointPaths: List<Path> = listOf(path)
      override val committedPaths: List<Path> = listOf(path)
    }

    data class Delete(
      val path: Path,
    ) : ResolvedOperation() {
      override val checkpointPaths: List<Path> = listOf(path)
      override val committedPaths: List<Path> = listOf(path)
    }

    data class Move(
      val sourcePath: Path,
      val destinationPath: Path,
    ) : ResolvedOperation() {
      override val checkpointPaths: List<Path> = listOf(sourcePath, destinationPath)
      override val committedPaths: List<Path> = listOf(sourcePath, destinationPath)
    }
  }
}

internal fun writeBytesAtomically(
  path: Path,
  content: ByteArray,
  replaceExisting: Boolean,
) {
  val parent = path.parent
  if (parent != null) {
    Files.createDirectories(parent)
  }
  val temporaryDirectory = parent ?: path.toAbsolutePath().normalize().parent
  requireNotNull(temporaryDirectory) { "Atomic file write requires a parent directory: $path" }
  val temporaryPath = Files.createTempFile(temporaryDirectory, ".opencray-write-", ".tmp")
  try {
    Files.write(
      temporaryPath,
      content,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
    if (replaceExisting && Files.exists(path)) {
      runCatching { Files.getPosixFilePermissions(path) }
        .getOrNull()
        ?.let { permissions ->
          runCatching { Files.setPosixFilePermissions(temporaryPath, permissions) }
        }
    }
    if (replaceExisting) {
      try {
        Files.move(
          temporaryPath,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
      }
    } else {
      Files.move(temporaryPath, path)
    }
  } finally {
    Files.deleteIfExists(temporaryPath)
  }
}
