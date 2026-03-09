package com.opencray.filesystem

import com.opencray.policy.PolicyReasonCode
import java.nio.file.Path

sealed class FileMutationOperation {
  data class Create(
    val path: Path,
    val content: String = "",
  ) : FileMutationOperation()

  data class Write(
    val path: Path,
    val content: String,
  ) : FileMutationOperation()

  data class Delete(
    val path: Path,
  ) : FileMutationOperation()

  data class Move(
    val sourcePath: Path,
    val destinationPath: Path,
  ) : FileMutationOperation()
}

data class FileBatchResult(
  val checkpointId: String,
  val checkpointEntryCount: Int,
  val operationCount: Int,
  val committedPaths: List<Path>,
)

object FileOpsReasonCode {
  const val DENY_PATH_TRAVERSAL: String = PolicyReasonCode.DENY_PATH_TRAVERSAL
  const val DENY_PATH_ESCAPE: String = PolicyReasonCode.DENY_PATH_ESCAPE
  const val DENY_PROTECTED_FILE: String = PolicyReasonCode.DENY_PROTECTED_FILE

  const val INVALID_OPERATION: String = "INVALID_OPERATION"
  const val ALREADY_EXISTS: String = "ALREADY_EXISTS"
  const val FILE_NOT_FOUND: String = "FILE_NOT_FOUND"
  const val IO_ERROR: String = "IO_ERROR"
  const val ROLLBACK_FAILED: String = "ROLLBACK_FAILED"
}

class FileOpsException(
  val reasonCode: String,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause) {
  var rollbackRestored: Boolean = false
    private set

  fun markRollbackRestored(): FileOpsException {
    rollbackRestored = true
    return this
  }
}
