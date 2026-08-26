package com.opencray.runtime.process

import java.util.concurrent.TimeUnit

internal object LocalProcessTermination {
  const val GRACE_DESTROY_WINDOW_MS: Long = 250L
  const val COLLECTOR_JOIN_TIMEOUT_MS: Long = 1_000L

  private val treeKillWaitUnit: TimeUnit = TimeUnit.MILLISECONDS

  fun resolvePid(process: Process): Long? =
    invokePublicProcessFunction(process, "pid")?.let { pid ->
      runCatching { (pid as Number).toLong() }.getOrNull()
    }

  fun beginGracefulTermination(process: Process) {
    val descendants = descendantHandles(process)
    descendants.forEach { handle ->
      runCatching { invokeDestroy(handle, force = false) }
    }
    if (descendants.isEmpty()) {
      killDescendantsViaPlatformToolsBestEffort(resolvePid(process), force = false)
    }
    runCatching { process.destroy() }
  }

  fun escalateToForcedTermination(process: Process) {
    val descendants = descendantHandles(process)
    descendants.forEach { handle ->
      runCatching { invokeDestroy(handle, force = true) }
    }
    if (descendants.isEmpty()) {
      killDescendantsViaPlatformToolsBestEffort(resolvePid(process), force = true)
    }
    runCatching { process.destroyForcibly() }
  }

  fun closeInputStreamsAfterCollectorsExit(
    process: Process,
    stdoutCollector: Thread,
    stderrCollector: Thread,
    joinTimeoutMs: Long,
  ) {
    runCatching { process.outputStream.close() }
    stdoutCollector.join(joinTimeoutMs)
    stderrCollector.join(joinTimeoutMs)
    if (!stdoutCollector.isAlive) {
      runCatching { process.inputStream.close() }
    }
    if (!stderrCollector.isAlive) {
      runCatching { process.errorStream.close() }
    }
  }

  private fun descendantHandles(process: Process): List<Any> {
    val handle = invokePublicProcessFunction(process, "toHandle") ?: return emptyList()
    val handleType = findPublicApiType(handle, "java.lang.ProcessHandle") ?: return emptyList()
    return runCatching {
      @Suppress("UNCHECKED_CAST")
      val stream = handleType.getMethod("descendants").invoke(handle)
        as java.util.stream.Stream<Any>
      val descendants = stream.collect(java.util.stream.Collectors.toList())
      descendants ?: emptyList()
    }.getOrDefault(emptyList())
  }

  private fun invokeDestroy(handle: Any, force: Boolean) {
    val methodName = if (force) "destroyForcibly" else "destroy"
    val handleType = findPublicApiType(handle, "java.lang.ProcessHandle")
    if (handleType != null) {
      runCatching { handleType.getMethod(methodName).invoke(handle) }
      return
    }
    runCatching { handle.javaClass.getMethod(methodName).invoke(handle) }
  }

  private fun invokePublicProcessFunction(process: Process, functionName: String): Any? {
    var type: Class<*>? = process.javaClass
    while (type != null) {
      if (type.name == "java.lang.Process") {
        return runCatching { type.getMethod(functionName).invoke(process) }.getOrNull()
      }
      type = type.superclass
    }
    return null
  }

  private fun findPublicApiType(instance: Any, publicTypeName: String): Class<*>? {
    val queue = ArrayDeque<Class<*>>()
    queue.addLast(instance.javaClass)
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      if (current.name == publicTypeName) {
        return current
      }
      current.superclass?.let(queue::addLast)
      queue.addAll(current.interfaces)
    }
    return null
  }

  private fun killDescendantsViaPlatformToolsBestEffort(pid: Long?, force: Boolean) {
    val resolvedPid = pid ?: return
    if (isWindowsPlatform()) {
      runCatching {
        val treeKill = ProcessBuilder(
          listOf("taskkill", "/PID", resolvedPid.toString(), "/T", "/F"),
        ).start()
        treeKill.waitFor(2_000L, treeKillWaitUnit)
      }
    } else {
      val signal = if (force) "-KILL" else "-TERM"
      runCatching {
        ProcessBuilder(listOf("pkill", signal, "-P", resolvedPid.toString())).start()
      }
    }
  }

  private fun isWindowsPlatform(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")
}
