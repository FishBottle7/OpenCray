package com.opencray.app

import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentHandleState

internal data class MergedSubAgentHandleState(
  val handle: SubAgentHandleState,
  val closed: Boolean,
)

internal fun mergeSubAgentHandlesByLatestState(
  liveHandles: Iterable<SubAgentHandleState>,
  closedHandles: Iterable<SubAgentHandleState>,
): List<MergedSubAgentHandleState> {
  val merged = linkedMapOf<SubAgentExecutionKey, MergedSubAgentHandleState>()
  (
    liveHandles.map { handle ->
      MergedSubAgentHandleState(handle = normalizeSubAgentHandle(handle), closed = false)
    } +
      closedHandles.map { handle ->
        MergedSubAgentHandleState(handle = normalizeSubAgentHandle(handle), closed = true)
      }
    )
    .sortedWith(
      compareByDescending<MergedSubAgentHandleState> { it.handle.updatedAtEpochMs }
        .thenByDescending { it.handle.createdAtEpochMs }
        .thenByDescending { if (it.closed) 1 else 0 },
    )
    .forEach { entry ->
      val key = SubAgentExecutionKey.from(entry.handle)
      if (key !in merged) {
        merged[key] = entry
      }
    }
  return merged.values.toList()
}

private fun normalizeSubAgentHandle(
  handle: SubAgentHandleState,
): SubAgentHandleState = handle.copy(
  description = handle.description.trim(),
  prompt = handle.prompt.trim(),
  subagentType = handle.subagentType.trim(),
  contextMode = handle.contextMode.trim(),
  parentRunId = handle.parentRunId.trim(),
  parentTaskId = handle.parentTaskId.trim(),
).withNormalizedMailbox()
