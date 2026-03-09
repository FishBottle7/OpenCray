package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class CommandExecutionAuditRecord(
  val taskId: String,
  val command: String,
  val args: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val gateStatus: CommandGateStatus,
  val gateReasonCode: String,
  val policyOutcome: PolicyDecisionOutcome,
  val policyReasonCode: String,
  val approvalTokenId: String? = null,
  val approvalProvided: Boolean = false,
  val approvedBy: String? = null,
  val executionStatus: ExecutionStatus,
  val spawned: Boolean = false,
  val exitCode: Int? = null,
  val errorCode: String? = null,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
  val timedOut: Boolean = false,
  val cancelled: Boolean = false,
  val outputLimitExceeded: Boolean = false,
  val detail: String? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(taskId.isNotBlank()) { "CommandExecutionAuditRecord taskId must not be blank." }
    require(command.isNotBlank()) { "CommandExecutionAuditRecord command must not be blank." }
    require(gateReasonCode.isNotBlank()) { "CommandExecutionAuditRecord gateReasonCode must not be blank." }
    require(policyReasonCode.isNotBlank()) { "CommandExecutionAuditRecord policyReasonCode must not be blank." }
    require(finishedAtEpochMs >= startedAtEpochMs) {
      "CommandExecutionAuditRecord finishedAtEpochMs must be >= startedAtEpochMs."
    }
  }
}

fun interface CommandAuditSink {
  fun record(record: CommandExecutionAuditRecord)
}

data class CommandExecutionConfig(
  val timeoutMs: Long = 30_000,
  val outputByteLimit: Int = 64_000,
) {
  init {
    require(timeoutMs > 0) { "CommandExecutionConfig timeoutMs must be > 0." }
    require(outputByteLimit > 0) { "CommandExecutionConfig outputByteLimit must be > 0." }
  }
}

data class CommandSpawnResult(
  val exitCode: Int?,
  val stdout: String,
  val stderr: String,
  val spawnErrorMessage: String? = null,
  val processStarted: Boolean = false,
  val timedOut: Boolean = false,
  val cancelled: Boolean = false,
  val outputLimitExceeded: Boolean = false,
)

fun interface CommandProcessRunner {
  fun run(
    commandLine: List<String>,
    workingDirectory: String?,
    config: CommandExecutionConfig,
    hooks: RuntimeExecutionHooks,
  ): CommandSpawnResult
}

class LocalCommandProcessRunner : CommandProcessRunner {
  override fun run(
    commandLine: List<String>,
    workingDirectory: String?,
    config: CommandExecutionConfig,
    hooks: RuntimeExecutionHooks,
  ): CommandSpawnResult {
    require(commandLine.isNotEmpty()) { "CommandProcessRunner commandLine must not be empty." }

    if (hooks.isCancellationRequested()) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = "",
        stderr = "",
        processStarted = false,
        cancelled = true,
      )
    }

    val process = try {
      ProcessBuilder(commandLine)
        .apply {
          if (workingDirectory != null) {
            directory(File(workingDirectory))
          }
          redirectInput(ProcessBuilder.Redirect.PIPE)
        }
        .start()
    } catch (exception: Exception) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = "",
        stderr = "",
        spawnErrorMessage = exception.message ?: exception::class.java.simpleName,
        processStarted = false,
      )
    }

    val stdoutBuffer = ByteArrayOutputStream()
    val stderrBuffer = ByteArrayOutputStream()
    val totalBytes = AtomicInteger(0)
    val stopReason = AtomicReference<StopReason?>(null)
    val destroyRequested = AtomicBoolean(false)

    fun requestStop(reason: StopReason) {
      if (!stopReason.compareAndSet(null, reason)) {
        return
      }
      if (destroyRequested.compareAndSet(false, true)) {
        process.destroyForcibly()
      }
    }

    val stdoutThread = startCollectorThread(
      name = "command-runner-stdout",
      input = process.inputStream,
      sink = stdoutBuffer,
      totalBytes = totalBytes,
      outputByteLimit = config.outputByteLimit,
      onOutputLimitExceeded = { requestStop(StopReason.OUTPUT_LIMIT_EXCEEDED) },
    )
    val stderrThread = startCollectorThread(
      name = "command-runner-stderr",
      input = process.errorStream,
      sink = stderrBuffer,
      totalBytes = totalBytes,
      outputByteLimit = config.outputByteLimit,
      onOutputLimitExceeded = { requestStop(StopReason.OUTPUT_LIMIT_EXCEEDED) },
    )

    val deadlineEpochMs = System.currentTimeMillis() + config.timeoutMs
    while (process.isAlive) {
      if (hooks.isCancellationRequested()) {
        requestStop(StopReason.CANCELLED)
      } else if (System.currentTimeMillis() >= deadlineEpochMs) {
        requestStop(StopReason.TIMEOUT)
      }

      if (!process.waitFor(25, TimeUnit.MILLISECONDS)) {
        continue
      }
    }

    if (destroyRequested.get()) {
      process.waitFor(250, TimeUnit.MILLISECONDS)
    }
    stdoutThread.join(500)
    stderrThread.join(500)

    val stdout = stdoutBuffer.toString(StandardCharsets.UTF_8.name())
    val stderr = stderrBuffer.toString(StandardCharsets.UTF_8.name())
    val exitCode = runCatching { process.exitValue() }.getOrNull()

    return when (stopReason.get()) {
      StopReason.TIMEOUT -> CommandSpawnResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        processStarted = true,
        timedOut = true,
      )

      StopReason.CANCELLED -> CommandSpawnResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        processStarted = true,
        cancelled = true,
      )

      StopReason.OUTPUT_LIMIT_EXCEEDED -> CommandSpawnResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        processStarted = true,
        outputLimitExceeded = true,
      )

      null -> CommandSpawnResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        processStarted = true,
      )
    }
  }

  private fun startCollectorThread(
    name: String,
    input: InputStream,
    sink: ByteArrayOutputStream,
    totalBytes: AtomicInteger,
    outputByteLimit: Int,
    onOutputLimitExceeded: () -> Unit,
  ): Thread {
    return Thread {
      val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(chunk)
        if (read <= 0) {
          break
        }

        var bytesToWrite = 0
        for (index in 0 until read) {
          val nextTotal = totalBytes.incrementAndGet()
          if (nextTotal > outputByteLimit) {
            onOutputLimitExceeded()
            break
          }
          bytesToWrite += 1
        }

        if (bytesToWrite > 0) {
          sink.write(chunk, 0, bytesToWrite)
        }

        if (bytesToWrite < read) {
          break
        }
      }
    }.apply {
      isDaemon = true
      this.name = name
      start()
    }
  }

  private enum class StopReason {
    TIMEOUT,
    CANCELLED,
    OUTPUT_LIMIT_EXCEEDED,
  }
}

class CommandExecutor(
  private val runner: CommandProcessRunner = LocalCommandProcessRunner(),
  private val config: CommandExecutionConfig = CommandExecutionConfig(),
  private val auditSink: CommandAuditSink = CommandAuditSink { _ -> },
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  fun execute(
    request: CommandExecutionRequest,
    policyDecision: com.opencray.core.contracts.PolicyDecision,
    approvalToken: CommandApprovalToken? = null,
    hooks: RuntimeExecutionHooks,
  ): ExecutionResult {
    val startedAt = clock()
    val gateDecision = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = policyDecision,
      approvalToken = approvalToken,
      decidedAtEpochMs = startedAt,
    )

    if (!gateDecision.shouldExecute) {
      val result = ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.DENIED,
        errorCode = deniedErrorCodeFor(gateDecision.status),
        errorMessage = gateDecision.detail,
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = maxOf(startedAt, clock()),
        metadata = executionMetadata(request, gateDecision, spawned = false),
      )
      auditSink.record(auditRecordFor(request, gateDecision, result, spawned = false))
      return result
    }

    if (hooks.isCancellationRequested()) {
      val result = ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.CANCELLED,
        errorCode = ERROR_CANCELLED_BY_HOOK,
        errorMessage = "Command execution cancelled before spawn.",
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = maxOf(startedAt, clock()),
        metadata = executionMetadata(request, gateDecision, spawned = false),
      )
      auditSink.record(auditRecordFor(request, gateDecision, result, spawned = false))
      return result
    }

    val commandLine = buildList {
      add(request.command)
      addAll(request.args)
    }

    val spawnResult = try {
      runner.run(
        commandLine = commandLine,
        workingDirectory = request.workingDirectory,
        config = config,
        hooks = hooks,
      )
    } catch (throwable: Throwable) {
      val result = ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        errorCode = ERROR_EXEC_ERROR,
        errorMessage = throwable.message ?: throwable::class.java.simpleName,
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = maxOf(startedAt, clock()),
        metadata = executionMetadata(request, gateDecision, spawned = true),
      )
      auditSink.record(auditRecordFor(request, gateDecision, result, spawned = true))
      return result
    }

    val finishedAt = maxOf(startedAt, clock())
    val processStarted = spawnResult.processStarted
    val result = when {
      spawnResult.spawnErrorMessage != null -> ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        stdout = spawnResult.stdout,
        stderr = spawnResult.stderr,
        errorCode = ERROR_SPAWN_ERROR,
        errorMessage = spawnResult.spawnErrorMessage,
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = executionMetadata(request, gateDecision, spawned = false),
      )

      spawnResult.timedOut -> ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.TIMEOUT,
        exitCode = spawnResult.exitCode,
        stdout = spawnResult.stdout,
        stderr = spawnResult.stderr,
        errorCode = ERROR_TIMEOUT,
        errorMessage = "Command execution exceeded timeout.",
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = executionMetadata(request, gateDecision, spawned = processStarted),
      )

      spawnResult.cancelled -> ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.CANCELLED,
        exitCode = spawnResult.exitCode,
        stdout = spawnResult.stdout,
        stderr = spawnResult.stderr,
        errorCode = ERROR_CANCELLED_BY_HOOK,
        errorMessage = "Command execution cancelled by hook.",
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = executionMetadata(request, gateDecision, spawned = processStarted),
      )

      spawnResult.outputLimitExceeded -> ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        exitCode = spawnResult.exitCode,
        stdout = spawnResult.stdout,
        stderr = spawnResult.stderr,
        errorCode = ERROR_OUTPUT_LIMIT_EXCEEDED,
        errorMessage = "Command output exceeded configured byte limit.",
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = executionMetadata(request, gateDecision, spawned = processStarted),
      )

      spawnResult.exitCode == 0 -> ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = spawnResult.exitCode,
        stdout = spawnResult.stdout,
        stderr = spawnResult.stderr,
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = executionMetadata(request, gateDecision, spawned = processStarted),
      )

      else -> ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        exitCode = spawnResult.exitCode,
        stdout = spawnResult.stdout,
        stderr = spawnResult.stderr,
        errorCode = ERROR_EXEC_ERROR,
        errorMessage = "Command exited with code ${spawnResult.exitCode ?: -1}.",
        policyDecision = policyDecision,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = executionMetadata(request, gateDecision, spawned = processStarted),
      )
    }

    auditSink.record(
      auditRecordFor(
        request = request,
        gateDecision = gateDecision,
        result = result,
        spawned = processStarted,
        spawnResult = spawnResult,
      )
    )
    return result
  }

  private fun deniedErrorCodeFor(status: CommandGateStatus): String = when (status) {
    CommandGateStatus.DENIED -> ERROR_DENY_POLICY
    CommandGateStatus.BLOCKED -> ERROR_APPROVAL_REQUIRED
    CommandGateStatus.ALLOWED -> ERROR_DENY_POLICY
  }

  private fun executionMetadata(
    request: CommandExecutionRequest,
    gateDecision: CommandGateDecision,
    spawned: Boolean,
  ): Map<String, String> {
    val metadata = LinkedHashMap<String, String>()
    metadata.putAll(request.metadata)
    metadata["command"] = request.command
    metadata["args"] = request.args.joinToString("\u0000")
    metadata["gateStatus"] = gateDecision.status.name
    metadata["gateReasonCode"] = gateDecision.reasonCode
    metadata["policyOutcome"] = gateDecision.policyDecision.outcome.name
    metadata["policyReasonCode"] = gateDecision.policyDecision.reasonCode
    metadata["spawned"] = spawned.toString()
    gateDecision.approvalToken?.tokenId?.let { metadata["approvalTokenId"] = it }
    request.workingDirectory?.let { metadata["workingDirectory"] = it }
    return metadata
  }

  private fun auditRecordFor(
    request: CommandExecutionRequest,
    gateDecision: CommandGateDecision,
    result: ExecutionResult,
    spawned: Boolean,
    spawnResult: CommandSpawnResult? = null,
  ): CommandExecutionAuditRecord = CommandExecutionAuditRecord(
    taskId = request.taskId,
    command = request.command,
    args = request.args,
    workingDirectory = request.workingDirectory,
    gateStatus = gateDecision.status,
    gateReasonCode = gateDecision.reasonCode,
    policyOutcome = gateDecision.policyDecision.outcome,
    policyReasonCode = gateDecision.policyDecision.reasonCode,
    approvalTokenId = gateDecision.approvalToken?.tokenId,
    approvalProvided = gateDecision.approvalToken != null,
    approvedBy = gateDecision.approvalToken?.approvedBy,
    executionStatus = result.status,
    spawned = spawned,
    exitCode = result.exitCode,
    errorCode = result.errorCode,
    startedAtEpochMs = result.startedAtEpochMs,
    finishedAtEpochMs = result.finishedAtEpochMs,
    timedOut = spawnResult?.timedOut ?: false,
    cancelled = spawnResult?.cancelled ?: (result.status == ExecutionStatus.CANCELLED),
    outputLimitExceeded = spawnResult?.outputLimitExceeded ?: false,
    detail = gateDecision.detail ?: result.errorMessage,
    metadata = request.metadata,
  )

  private companion object {
    const val ERROR_DENY_POLICY = "DENY_POLICY"
    const val ERROR_APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
    const val ERROR_TIMEOUT = "TIMEOUT"
    const val ERROR_OUTPUT_LIMIT_EXCEEDED = "OUTPUT_LIMIT_EXCEEDED"
    const val ERROR_CANCELLED_BY_HOOK = "CANCELLED_BY_HOOK"
    const val ERROR_SPAWN_ERROR = "SPAWN_ERROR"
    const val ERROR_EXEC_ERROR = "EXEC_ERROR"
  }
}

// Learnings: Gate evaluation before runner invocation makes deny and approval-required paths trivially non-spawning.
// Issues: This wrapper is intentionally local-process only and does not stream partial output events yet.
