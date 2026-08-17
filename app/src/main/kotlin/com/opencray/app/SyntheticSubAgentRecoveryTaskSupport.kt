package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.subagent.SubAgentExecutionKey
import java.security.MessageDigest

internal const val METADATA_SYNTHETIC_SUBAGENT_TASK_KIND: String = "_host.detachedControlKind"
internal const val SYNTHETIC_SUBAGENT_TASK_KIND_ACTOR: String =
  "subagent_actor"
internal const val SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT: String =
  "subagent_recovery_wait"
internal const val METADATA_SUBAGENT_RECOVERY_AGENT_ID: String =
  "_host.subagentRecoveryAgentId"
internal const val METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID: String =
  "_host.subagentRecoveryParentRunId"
internal const val SUBAGENT_RECOVERY_ALLOW_REASON_CODE: String =
  "RUNTIME_SERVICE_SUBAGENT_RECOVERY_ALLOW"

internal sealed interface SyntheticSubAgentTaskSpec {
  val kind: String

  val agentId: String

  val parentRunId: String
}

internal data class SyntheticSubAgentActorTaskSpec(
  override val agentId: String,
  override val parentRunId: String,
) : SyntheticSubAgentTaskSpec {
  override val kind: String = SYNTHETIC_SUBAGENT_TASK_KIND_ACTOR
}

internal data class SyntheticSubAgentRecoveryWaitTaskSpec(
  override val agentId: String,
  override val parentRunId: String,
) : SyntheticSubAgentTaskSpec {
  override val kind: String = SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT
}

internal fun syntheticSubAgentRecoveryTaskId(
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

internal fun syntheticSubAgentRecoveryRunId(taskId: String): String = "run-$taskId"

internal fun syntheticSubAgentActorTaskId(
  sessionId: String,
  agentId: String,
  parentRunId: String,
): String {
  val digest = MessageDigest.getInstance("SHA-256")
    .digest("$sessionId|$parentRunId|$agentId|actor".toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
    .take(24)
  return "subagent-actor-$digest"
}

internal fun syntheticSubAgentActorRunId(taskId: String): String = "run-$taskId"

internal fun syntheticSubAgentActorTask(
  sessionId: String,
  agentId: String,
  parentRunId: String,
  createdAtEpochMs: Long,
  metadata: Map<String, String>,
): AgentTask {
  val taskId = syntheticSubAgentActorTaskId(
    sessionId = sessionId,
    agentId = agentId,
    parentRunId = parentRunId,
  )
  return AgentTask(
    id = taskId,
    type = com.opencray.core.contracts.AgentTaskType.SYSTEM,
    input = "internal:subagent_actor:$agentId",
    state = AgentTaskState.QUEUED,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = SUBAGENT_RECOVERY_ALLOW_REASON_CODE,
    ),
    createdAtEpochMs = createdAtEpochMs,
    metadata = metadata + mapOf(
      AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to syntheticSubAgentActorRunId(taskId),
      AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
      METADATA_SYNTHETIC_SUBAGENT_TASK_KIND to SYNTHETIC_SUBAGENT_TASK_KIND_ACTOR,
      METADATA_SUBAGENT_RECOVERY_AGENT_ID to agentId,
      METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID to parentRunId,
    ),
  )
}

internal fun syntheticSubAgentRecoveryWaitTask(
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
    AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to syntheticSubAgentRecoveryRunId(taskId),
    AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
    METADATA_SYNTHETIC_SUBAGENT_TASK_KIND to SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT,
    METADATA_SUBAGENT_RECOVERY_AGENT_ID to agentId,
    METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID to parentRunId,
  ),
)

internal fun syntheticSubAgentRecoveryExecutionKey(task: AgentTask): SubAgentExecutionKey? {
  val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  return SubAgentExecutionKey(
    parentRunId = parentRunId,
    agentId = agentId,
  )
}

internal fun syntheticSubAgentTaskSpec(task: AgentTask): SyntheticSubAgentTaskSpec? {
  return when (
    task.metadata[METADATA_SYNTHETIC_SUBAGENT_TASK_KIND]
      ?.trim()
      ?.takeIf(String::isNotBlank)
  ) {
    SYNTHETIC_SUBAGENT_TASK_KIND_ACTOR -> {
      val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      SyntheticSubAgentActorTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      )
    }

    SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT -> {
      val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      SyntheticSubAgentRecoveryWaitTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      )
    }

    else -> null
  }
}
