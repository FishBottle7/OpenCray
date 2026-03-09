package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import java.nio.file.Path

/**
 * Normalized runtime contract for parity between the current in-app runtime path and a future
 * Termux-backed implementation.
 *
 * The request envelope explicitly covers both script execution and dependency installation. The
 * response reuses [ExecutionResult] as the canonical outcome payload so callers do not need a
 * separate Termux-only success or error shape.
 */
interface TermuxRuntimeAdapter {
  val backend: TermuxRuntimeBackend
  val availability: TermuxRuntimeAvailability

  fun execute(request: TermuxRuntimeRequest): TermuxRuntimeResponse

  fun exec(request: TermuxRuntimeRequest.Exec): TermuxRuntimeResponse = execute(request)

  fun install(request: TermuxRuntimeRequest.InstallDependencies): TermuxRuntimeResponse =
    execute(request)

  companion object {
    fun v1Unavailable(
      clock: () -> Long = { System.currentTimeMillis() },
    ): TermuxRuntimeAdapter = V1UnavailableTermuxRuntimeAdapter(clock)
  }
}

enum class TermuxRuntimeBackend {
  IN_APP,
  TERMUX,
  TERMUX_STUB,
}

enum class TermuxV1Marker {
  AVAILABLE,
  UNAVAILABLE_IN_V1,
}

sealed interface TermuxRuntimeAvailability {
  val isAvailable: Boolean
  val reasonCode: String?
  val detail: String?
  val v1Marker: TermuxV1Marker

  data object Available : TermuxRuntimeAvailability {
    override val isAvailable: Boolean = true
    override val reasonCode: String? = null
    override val detail: String? = null
    override val v1Marker: TermuxV1Marker = TermuxV1Marker.AVAILABLE
  }

  data class Unavailable(
    override val reasonCode: String = TermuxRuntimeContract.ERROR_TERMUX_UNAVAILABLE,
    override val detail: String =
      "Termux execution is unavailable in V1; callers must use the in-app runtime path.",
    override val v1Marker: TermuxV1Marker = TermuxV1Marker.UNAVAILABLE_IN_V1,
  ) : TermuxRuntimeAvailability {
    override val isAvailable: Boolean = false
  }
}

enum class TermuxRuntimeOperation {
  EXEC,
  INSTALL_DEPENDENCIES,
}

sealed interface TermuxRuntimeRequest {
  val taskId: String
  val workspaceRoot: Path
  val timeoutMs: Long
  val metadata: Map<String, String>
  val operation: TermuxRuntimeOperation

  data class Exec(
    override val taskId: String,
    override val workspaceRoot: Path,
    val scriptPath: Path,
    val args: List<String> = emptyList(),
    override val timeoutMs: Long = 30_000,
    val pythonExecutable: String = "python",
    override val metadata: Map<String, String> = emptyMap(),
  ) : TermuxRuntimeRequest {
    override val operation: TermuxRuntimeOperation = TermuxRuntimeOperation.EXEC

    init {
      require(taskId.isNotBlank()) { "TermuxRuntimeRequest.Exec taskId must not be blank." }
      require(timeoutMs > 0) { "TermuxRuntimeRequest.Exec timeoutMs must be > 0." }
    }
  }

  data class InstallDependencies(
    override val taskId: String,
    override val workspaceRoot: Path,
    val requirements: List<String>,
    override val timeoutMs: Long = 120_000,
    val pythonExecutable: String = "python",
    override val metadata: Map<String, String> = emptyMap(),
  ) : TermuxRuntimeRequest {
    override val operation: TermuxRuntimeOperation =
      TermuxRuntimeOperation.INSTALL_DEPENDENCIES

    init {
      require(taskId.isNotBlank()) {
        "TermuxRuntimeRequest.InstallDependencies taskId must not be blank."
      }
      require(timeoutMs > 0) { "TermuxRuntimeRequest.InstallDependencies timeoutMs must be > 0." }
    }
  }
}

/**
 * Normalized response wrapper for both exec and dependency-install requests.
 *
 * [result] remains an [ExecutionResult] so current in-app runtime semantics and a future Termux
 * backend can share the exact same status, timing, error, and metadata envelope.
 */
data class TermuxRuntimeResponse(
  val taskId: String,
  val operation: TermuxRuntimeOperation,
  val backend: TermuxRuntimeBackend,
  val availability: TermuxRuntimeAvailability,
  val result: ExecutionResult,
) {
  init {
    require(taskId.isNotBlank()) { "TermuxRuntimeResponse taskId must not be blank." }
    require(result.taskId == taskId) {
      "TermuxRuntimeResponse result.taskId must match taskId."
    }
  }

  companion object {
    fun from(
      request: TermuxRuntimeRequest,
      backend: TermuxRuntimeBackend,
      availability: TermuxRuntimeAvailability,
      result: ExecutionResult,
    ): TermuxRuntimeResponse {
      val normalizedMetadata = LinkedHashMap(result.metadata)
      normalizedMetadata.putIfAbsent(
        TermuxRuntimeContract.METADATA_RUNTIME_OPERATION,
        request.operation.name.lowercase(),
      )
      normalizedMetadata.putIfAbsent(
        TermuxRuntimeContract.METADATA_RUNTIME_BACKEND,
        backend.name.lowercase(),
      )
      normalizedMetadata.putIfAbsent(
        TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE,
        availability.isAvailable.toString(),
      )
      normalizedMetadata.putIfAbsent(
        TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER,
        availability.v1Marker.name.lowercase(),
      )

      val normalizedResult = if (normalizedMetadata == result.metadata) {
        result
      } else {
        result.copy(metadata = normalizedMetadata)
      }

      return TermuxRuntimeResponse(
        taskId = request.taskId,
        operation = request.operation,
        backend = backend,
        availability = availability,
        result = normalizedResult,
      )
    }
  }
}

object TermuxRuntimeContract {
  const val ERROR_TERMUX_UNAVAILABLE: String = "TERMUX_UNAVAILABLE"
  const val METADATA_RUNTIME_OPERATION: String = "runtimeOperation"
  const val METADATA_RUNTIME_BACKEND: String = "runtimeBackend"
  const val METADATA_TERMUX_AVAILABLE: String = "termuxAvailable"
  const val METADATA_TERMUX_V1_MARKER: String = "termuxV1Marker"
}

class V1UnavailableTermuxRuntimeAdapter(
  private val clock: () -> Long = { System.currentTimeMillis() },
) : TermuxRuntimeAdapter {
  override val backend: TermuxRuntimeBackend = TermuxRuntimeBackend.TERMUX_STUB
  override val availability: TermuxRuntimeAvailability = TermuxRuntimeAvailability.Unavailable()

  override fun execute(request: TermuxRuntimeRequest): TermuxRuntimeResponse {
    val startedAt = clock()
    val finishedAt = maxOf(startedAt, clock())
    val result = ExecutionResult(
      taskId = request.taskId,
      status = ExecutionStatus.DENIED,
      errorCode = availability.reasonCode,
      errorMessage = availability.detail,
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = finishedAt,
      metadata = request.metadata,
    )
    return TermuxRuntimeResponse.from(
      request = request,
      backend = backend,
      availability = availability,
      result = result,
    )
  }
}

fun PythonExecRequest.toTermuxRuntimeRequest(
  metadata: Map<String, String> = emptyMap(),
): TermuxRuntimeRequest.Exec = TermuxRuntimeRequest.Exec(
  taskId = taskId,
  workspaceRoot = workspaceRoot,
  scriptPath = scriptPath,
  args = args,
  timeoutMs = timeoutMs,
  pythonExecutable = pythonExecutable,
  metadata = metadata,
)

fun PipInstallRequest.toTermuxRuntimeRequest(
  metadata: Map<String, String> = emptyMap(),
): TermuxRuntimeRequest.InstallDependencies = TermuxRuntimeRequest.InstallDependencies(
  taskId = taskId,
  workspaceRoot = workspaceRoot,
  requirements = requirements,
  timeoutMs = timeoutMs,
  pythonExecutable = pythonExecutable,
  metadata = metadata,
)
