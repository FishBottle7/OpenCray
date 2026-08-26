package com.opencray.runtime.process

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ManagedProcessExecutionConfig(
  val outputByteLimit: Int = 64_000,
) {
  init {
    require(outputByteLimit > 0) { "ManagedProcessExecutionConfig outputByteLimit must be > 0." }
  }
}

class LocalManagedProcessControllerFactory(
  private val config: ManagedProcessExecutionConfig = ManagedProcessExecutionConfig(),
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val cancellationCheckFor: ((taskId: String) -> ManagedProcessCancellationCheck?)? =
    { taskId -> ManagedProcessCancellationRegistry.checkFor(taskId) },
) : ManagedProcessControllerFactory {
  override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
    LocalManagedProcessController(
      request = request,
      config = config,
      clock = clock,
      cancellationCheckFor = cancellationCheckFor,
    )
}

private class LocalManagedProcessController(
  private val request: ManagedProcessStartRequest,
  private val config: ManagedProcessExecutionConfig,
  private val clock: () -> Long,
  private val cancellationCheckFor: ((String) -> ManagedProcessCancellationCheck?)?,
) : ManagedProcessController {
  private val lock = Any()
  private val completion = CountDownLatch(1)
  private val stopReason = AtomicReference<StopReason?>(null)
  private val destroyRequested = AtomicBoolean(false)
  private val escalationDeadlineEpochMs = AtomicLong(Long.MAX_VALUE)
  private val stdoutBuffer = ByteArrayOutputStream()
  private val stderrBuffer = ByteArrayOutputStream()

  private var process: Process? = null
  private var status: ManagedProcessStatus = ManagedProcessStatus.RUNNING
  private var processStarted: Boolean = false
  private var exitCode: Int? = null
  private var errorCode: String? = null
  private var errorMessage: String? = null
  private var startedAtEpochMs: Long = clock()
  private var updatedAtEpochMs: Long = startedAtEpochMs
  private var finishedAtEpochMs: Long? = null
  private var timedOut: Boolean = false
  private var cancelled: Boolean = false
  private var outputLimitExceeded: Boolean = false
  private var totalOutputBytes: Int = 0
  private var resultMetadata: Map<String, String> = emptyMap()

  init {
    startProcess()
  }

  override fun snapshot(): ManagedProcessSnapshot = synchronized(lock) {
    snapshotLocked()
  }

  override fun await(timeoutMs: Long): ManagedProcessSnapshot {
    completion.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    return snapshot()
  }

  override fun terminate(): ManagedProcessSnapshot {
    val shouldTerminate = synchronized(lock) { !status.isTerminal }
    if (shouldTerminate) {
      requestStop(StopReason.CANCELLED)
      completion.await(TERMINATE_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    return snapshot()
  }

  private fun startProcess() {
    val resolvedStartedAt = clock()
    synchronized(lock) {
      startedAtEpochMs = resolvedStartedAt
      updatedAtEpochMs = resolvedStartedAt
    }
    val startedProcess = try {
      ProcessBuilder(buildList {
        add(request.command)
        addAll(request.args)
      }).apply {
        request.workingDirectory?.let { workingDirectory ->
          directory(File(workingDirectory))
        }
        redirectInput(ProcessBuilder.Redirect.PIPE)
      }.start()
    } catch (exception: Exception) {
      val finishedAt = clock()
      synchronized(lock) {
        status = ManagedProcessStatus.SPAWN_ERROR
        errorCode = ERROR_SPAWN_ERROR
        errorMessage = exception.message ?: exception::class.java.simpleName
        updatedAtEpochMs = finishedAt
        finishedAtEpochMs = finishedAt
      }
      completion.countDown()
      return
    }

    synchronized(lock) {
      process = startedProcess
      processStarted = true
      status = ManagedProcessStatus.RUNNING
      updatedAtEpochMs = resolvedStartedAt
    }

    val stdoutThread = startCollectorThread(
      name = "managed-process-stdout-${request.processId}",
      input = startedProcess.inputStream,
      sink = stdoutBuffer,
    )
    val stderrThread = startCollectorThread(
      name = "managed-process-stderr-${request.processId}",
      input = startedProcess.errorStream,
      sink = stderrBuffer,
    )

    Thread(
      { monitorProcess(startedProcess, stdoutThread, stderrThread) },
      "managed-process-watch-${request.processId}",
    ).apply {
      isDaemon = true
      start()
    }
  }

  private fun startCollectorThread(
    name: String,
    input: InputStream,
    sink: ByteArrayOutputStream,
  ): Thread = Thread(
    {
      val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = runCatching { input.read(chunk) }.getOrDefault(-1)
        if (read <= 0) {
          break
        }
        var bytesToWrite = 0
        var outputLimitReached = false
        synchronized(lock) {
          for (index in 0 until read) {
            val nextTotal = totalOutputBytes + 1
            if (nextTotal > config.outputByteLimit) {
              outputLimitReached = true
              break
            }
            totalOutputBytes = nextTotal
            bytesToWrite += 1
          }
          if (bytesToWrite > 0) {
            sink.write(chunk, 0, bytesToWrite)
            updatedAtEpochMs = maxOf(updatedAtEpochMs, clock())
          }
        }
        if (outputLimitReached || bytesToWrite < read) {
          requestStop(StopReason.OUTPUT_LIMIT_EXCEEDED)
          break
        }
      }
    },
    name,
  ).apply {
    isDaemon = true
    start()
  }

  private fun monitorProcess(
    process: Process,
    stdoutThread: Thread,
    stderrThread: Thread,
  ) {
    val deadlineEpochMs = startedAtEpochMs + request.timeoutMs
    var escalated = false
    while (process.isAlive) {
      if (cancellationRequested()) {
        requestStop(StopReason.CANCELLED)
      } else if (clock() >= deadlineEpochMs) {
        requestStop(StopReason.TIMEOUT)
      }
      if (
        !escalated &&
        destroyRequested.get() &&
        clock() >= escalationDeadlineEpochMs.get()
      ) {
        escalated = true
        LocalProcessTermination.escalateToForcedTermination(process)
      }
      if (!process.waitFor(25L, TimeUnit.MILLISECONDS)) {
        continue
      }
    }

    if (destroyRequested.get()) {
      LocalProcessTermination.closeInputStreamsAfterCollectorsExit(
        process = process,
        stdoutCollector = stdoutThread,
        stderrCollector = stderrThread,
        joinTimeoutMs = LocalProcessTermination.COLLECTOR_JOIN_TIMEOUT_MS,
      )
    } else {
      stdoutThread.join(LocalProcessTermination.COLLECTOR_JOIN_TIMEOUT_MS)
      stderrThread.join(LocalProcessTermination.COLLECTOR_JOIN_TIMEOUT_MS)
    }

    val resolvedFinishedAt = clock()
    val orphanSuspected = stdoutThread.isAlive || stderrThread.isAlive
    val terminationUnconfirmed = process.isAlive
    synchronized(lock) {
      exitCode = runCatching { process.exitValue() }.getOrNull()
      updatedAtEpochMs = resolvedFinishedAt
      finishedAtEpochMs = resolvedFinishedAt
      when (stopReason.get()) {
        StopReason.TIMEOUT -> {
          status = ManagedProcessStatus.TIMEOUT
          errorCode = ERROR_TIMEOUT
          errorMessage = "Managed process exceeded timeout."
          timedOut = true
        }

        StopReason.CANCELLED -> {
          status = ManagedProcessStatus.CANCELLED
          errorCode = ERROR_CANCELLED
          errorMessage = "Managed process terminated."
          cancelled = true
        }

        StopReason.OUTPUT_LIMIT_EXCEEDED -> {
          status = ManagedProcessStatus.FAILED
          errorCode = ERROR_OUTPUT_LIMIT_EXCEEDED
          errorMessage = "Managed process output exceeded configured byte limit."
          outputLimitExceeded = true
        }

        null -> if (exitCode == 0) {
          status = ManagedProcessStatus.SUCCESS
        } else {
          status = ManagedProcessStatus.FAILED
          errorCode = ERROR_EXEC_ERROR
          errorMessage = "Process exited with code ${exitCode ?: -1}."
        }
      }
      resultMetadata = buildMap {
        if (orphanSuspected) {
          put(METADATA_SUSPECTED_ORPHAN_DESCENDANTS, "true")
        }
        if (terminationUnconfirmed) {
          put(METADATA_TERMINATION_UNCONFIRMED, "true")
        }
      }
    }
    completion.countDown()
  }

  private fun cancellationRequested(): Boolean {
    val resolver = cancellationCheckFor ?: return false
    val check = resolver(request.taskId) ?: return false
    return runCatching { check.isCancellationRequested() }.getOrDefault(false)
  }

  private fun requestStop(reason: StopReason) {
    if (!stopReason.compareAndSet(null, reason)) {
      return
    }
    val runningProcess = synchronized(lock) { process } ?: return
    if (destroyRequested.compareAndSet(false, true)) {
      escalationDeadlineEpochMs.set(clock() + LocalProcessTermination.GRACE_DESTROY_WINDOW_MS)
      LocalProcessTermination.beginGracefulTermination(runningProcess)
    }
  }

  private fun snapshotLocked(): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = request.processId,
    taskId = request.taskId,
    command = request.command,
    args = request.args,
    workingDirectory = request.workingDirectory,
    status = status,
    processStarted = processStarted,
    timeoutMs = request.timeoutMs,
    stdout = stdoutBuffer.toString(StandardCharsets.UTF_8.name()),
    stderr = stderrBuffer.toString(StandardCharsets.UTF_8.name()),
    exitCode = exitCode,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = startedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    timedOut = timedOut,
    cancelled = cancelled,
    outputLimitExceeded = outputLimitExceeded,
    ownerIdentity = request.ownerIdentity,
    metadata = request.metadata + resultMetadata,
  )

  private enum class StopReason {
    TIMEOUT,
    CANCELLED,
    OUTPUT_LIMIT_EXCEEDED,
  }

  private companion object {
    const val TERMINATE_COMPLETION_TIMEOUT_MS: Long = 5_000L
    const val METADATA_SUSPECTED_ORPHAN_DESCENDANTS: String = "suspectedOrphanDescendants"
    const val METADATA_TERMINATION_UNCONFIRMED: String = "terminationUnconfirmed"
    const val ERROR_TIMEOUT: String = "TIMEOUT"
    const val ERROR_CANCELLED: String = "CANCELLED"
    const val ERROR_OUTPUT_LIMIT_EXCEEDED: String = "OUTPUT_LIMIT_EXCEEDED"
    const val ERROR_EXEC_ERROR: String = "EXEC_ERROR"
    const val ERROR_SPAWN_ERROR: String = "SPAWN_ERROR"
  }
}
