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
)
