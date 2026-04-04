package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import java.security.MessageDigest

internal const val METADATA_DETACHED_CONTROL_KIND: String = "_host.detachedControlKind"
internal const val DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT: String =
  "subagent_recovery_wait"
internal const val METADATA_SUBAGENT_RECOVERY_AGENT_ID: String =
  "_host.subagentRecoveryAgentId"
internal const val METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID: String =
  "_host.subagentRecoveryParentRunId"
internal const val SUBAGENT_RECOVERY_ALLOW_REASON_CODE: String =
  "RUNTIME_SERVICE_SUBAGENT_RECOVERY_ALLOW"

internal sealed interface DetachedControlTaskSpec {
  val kind: String
}

internal data class DetachedSubAgentRecoveryWaitTaskSpec(
  val agentId: String,
  val parentRunId: String,
) : DetachedControlTaskSpec {
  override val kind: String = DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT
}

internal fun detachedSubAgentRecoveryTaskId(
  sessionId: String,
  agentId: String,
  parentRunId: String,
): String {
  val digest = MessageDigest.getInstance("SHA-256")
    .digest("$sessionId|$parentRunId|$agentId".toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
    .take(24)
  return "subagent-recovery-$digest"
}

internal fun detachedSubAgentRecoveryRunId(taskId: String): String = "run-$taskId"

internal fun detachedSubAgentRecoveryWaitTask(
  sessionId: String,
  agentId: String,
  parentRunId: String,
  taskId: String,
  createdAtEpochMs: Long,
  metadata: Map<String, String>,
): AgentTask = AgentTask(
  id = taskId,
  type = com.opencray.core.contracts.AgentTaskType.SYSTEM,
  input = "internal:subagent_recovery_wait:$agentId",
  state = AgentTaskState.QUEUED,
  policyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = SUBAGENT_RECOVERY_ALLOW_REASON_CODE,
  ),
  createdAtEpochMs = createdAtEpochMs,
  metadata = metadata + mapOf(
    AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to detachedSubAgentRecoveryRunId(taskId),
    AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
    METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
    METADATA_SUBAGENT_RECOVERY_AGENT_ID to agentId,
    METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID to parentRunId,
  ),
)

internal fun detachedControlTaskSpec(task: AgentTask): DetachedControlTaskSpec? {
  return when (
    task.metadata[METADATA_DETACHED_CONTROL_KIND]
      ?.trim()
      ?.takeIf(String::isNotBlank)
  ) {
    DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT -> {
      val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      DetachedSubAgentRecoveryWaitTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      )
    }

    else -> null
  }
}
