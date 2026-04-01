package com.opencray.app.agent

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class AgentIdFactory(
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
  private val randomToken: () -> String = { UUID.randomUUID().toString() },
) {
  private val sequence = AtomicLong(0L)

  fun newAgentId(): String = newId(prefix = AGENT_PREFIX)

  fun newSessionId(): String = newId(prefix = SESSION_PREFIX)

  fun newRunId(): String = newId(prefix = RUN_PREFIX)

  fun newTaskId(): String = newId(prefix = TASK_PREFIX)

  private fun newId(prefix: String): String {
    val epochMs = nowEpochMs().coerceAtLeast(0L)
    val ordinal = sequence.incrementAndGet()
    return "$prefix-$epochMs-$ordinal-${normalizedToken(randomToken())}"
  }

  private fun normalizedToken(raw: String): String {
    val normalized = raw
      .trim()
      .lowercase()
      .filter { character -> character.isLetterOrDigit() }
      .take(24)
    if (normalized.isNotBlank()) {
      return normalized
    }
    return UUID.randomUUID()
      .toString()
      .replace("-", "")
      .lowercase()
      .take(24)
  }

  companion object {
    private const val AGENT_PREFIX = "agent"
    private const val SESSION_PREFIX = "session"
    private const val RUN_PREFIX = "run"
    private const val TASK_PREFIX = "task"
  }
}
