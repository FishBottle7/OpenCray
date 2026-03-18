package com.opencray.runtime

import java.nio.file.Path

data class PythonExecRequest(
  val taskId: String,
  val workspaceRoot: Path,
  val scriptPath: Path,
  val args: List<String> = emptyList(),
  val timeoutMs: Long = 30_000,
  /** Python executable used to invoke the runner module (defaults to 'python'). */
  val pythonExecutable: String = "python",
  /** Optional stable request id used by runtimes which expose out-of-band cancellation. */
  val requestId: String? = null,
  /**
   * Optional extra budget reserved for runtime startup before the script timeout begins counting.
   *
   * Runtimes without a separate startup phase may ignore this.
   */
  val startupTimeoutMs: Long? = null,
) {
  constructor(
    taskId: String,
    workspaceRoot: Path,
    scriptPath: Path,
    args: List<String>,
    timeoutMs: Long,
    pythonExecutable: String,
  ) : this(
    taskId = taskId,
    workspaceRoot = workspaceRoot,
    scriptPath = scriptPath,
    args = args,
    timeoutMs = timeoutMs,
    pythonExecutable = pythonExecutable,
    requestId = null,
    startupTimeoutMs = null,
  )

  constructor(
    taskId: String,
    workspaceRoot: Path,
    scriptPath: Path,
    args: List<String>,
    timeoutMs: Long,
    pythonExecutable: String,
    requestId: String?,
  ) : this(
    taskId = taskId,
    workspaceRoot = workspaceRoot,
    scriptPath = scriptPath,
    args = args,
    timeoutMs = timeoutMs,
    pythonExecutable = pythonExecutable,
    requestId = requestId,
    startupTimeoutMs = null,
  )
}
