package com.opencray.app

import android.content.Context
import com.opencray.runtime.PythonExecRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class DebugPythonScriptRunner(
  context: Context,
  private val pythonRuntime: P4aPythonRuntime = P4aPythonRuntime.fromContext(context.applicationContext),
  private val workspaceRootProvider: () -> Path = {
    AppAgentWorkspace.ensureRootForContext(context.applicationContext)
  },
) {
  fun runScript(
    fileName: String,
    scriptText: String,
  ): Map<String, Any?> {
    val normalizedScript = normalizeScript(scriptText)
    val normalizedFileName = normalizeFileName(fileName)
    val workspaceRoot = workspaceRootProvider()
    val scriptRelativePath = "$DEBUG_SCRIPT_DIRECTORY/$normalizedFileName"
    val scriptPath = workspaceRoot.resolve(".opencray").resolve("debug-python").resolve(normalizedFileName)
    writeAtomicText(scriptPath, normalizedScript)
    val taskId = "debug-python-${UUID.randomUUID()}"
    val result = pythonRuntime.exec(
      PythonExecRequest(
        taskId = taskId,
        workspaceRoot = workspaceRoot,
        scriptPath = scriptPath,
        timeoutMs = DEFAULT_SCRIPT_TIMEOUT_MS,
        requestId = taskId,
        startupTimeoutMs = DEFAULT_STARTUP_TIMEOUT_MS,
      ),
    )
    return DebugPythonRunResult(
      taskId = taskId,
      fileName = normalizedFileName,
      scriptRelativePath = scriptRelativePath,
      status = result.status.name.lowercase(),
      exitCode = result.exitCode,
      stdout = result.stdout,
      stderr = result.stderr,
      errorCode = result.errorCode,
      errorMessage = result.errorMessage,
      startedAtEpochMs = result.startedAtEpochMs,
      finishedAtEpochMs = result.finishedAtEpochMs,
      metadata = result.metadata.toSortedMap(),
    ).toMap()
  }

  private fun normalizeScript(scriptText: String): String {
    require(scriptText.trim().isNotEmpty()) {
      "Script content cannot be empty."
    }
    val normalized = scriptText
      .replace("\r\n", "\n")
      .replace('\r', '\n')
    return if (normalized.endsWith("\n")) normalized else "$normalized\n"
  }

  private fun normalizeFileName(rawValue: String): String {
    val sanitized = rawValue
      .substringAfterLast('/')
      .substringAfterLast('\\')
      .trim()
      .replace(Regex("""[\\/:*?"<>|]+"""), "_")
      .replace(Regex("\\s+"), "_")
      .trim { character -> character == '_' || character == '.' }
    val candidate = sanitized.ifBlank { DEFAULT_FILE_NAME }
    return if (candidate.lowercase().endsWith(".py")) {
      candidate
    } else {
      "$candidate.py"
    }
  }

  private fun writeAtomicText(
    path: Path,
    content: String,
  ) {
    Files.createDirectories(path.parent)
    val tempPath = path.resolveSibling("${path.fileName}.tmp")
    Files.write(tempPath, content.toByteArray(StandardCharsets.UTF_8))
    runCatching {
      Files.move(
        tempPath,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    }.getOrElse {
      Files.move(
        tempPath,
        path,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  private data class DebugPythonRunResult(
    val taskId: String,
    val fileName: String,
    val scriptRelativePath: String,
    val status: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val errorCode: String?,
    val errorMessage: String?,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val metadata: Map<String, String>,
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "taskId" to taskId,
      "fileName" to fileName,
      "scriptRelativePath" to scriptRelativePath,
      "status" to status,
      "exitCode" to exitCode,
      "stdout" to stdout,
      "stderr" to stderr,
      "errorCode" to errorCode,
      "errorMessage" to errorMessage,
      "startedAtEpochMs" to startedAtEpochMs,
      "finishedAtEpochMs" to finishedAtEpochMs,
      "metadata" to metadata,
    )
  }

  companion object {
    private const val DEFAULT_FILE_NAME: String = "debug.py"
    private const val DEBUG_SCRIPT_DIRECTORY: String = ".opencray/debug-python"
    private const val DEFAULT_STARTUP_TIMEOUT_MS: Long = 20_000L
    private const val DEFAULT_SCRIPT_TIMEOUT_MS: Long = 60_000L
  }
}
