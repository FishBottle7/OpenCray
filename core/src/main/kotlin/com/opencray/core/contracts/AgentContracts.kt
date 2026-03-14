package com.opencray.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ContractSchemaVersion {
  const val CURRENT: Int = 1
}

@Serializable
enum class AgentTaskType {
  @SerialName("prompt") PROMPT,
  @SerialName("tool_call") TOOL_CALL,
  @SerialName("skill_call") SKILL_CALL,
  @SerialName("system") SYSTEM,
}

@Serializable
enum class AgentTaskState {
  @SerialName("queued") QUEUED,
  @SerialName("running") RUNNING,
  @SerialName("completed") COMPLETED,
  @SerialName("failed") FAILED,
  @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class PolicyDecisionOutcome {
  @SerialName("allow") ALLOW,
  @SerialName("ask") ASK,
  @SerialName("deny") DENY,
}

@Serializable
enum class PolicyApprovalRisk {
  @SerialName("standard") STANDARD,
  @SerialName("high_risk") HIGH_RISK,
}

@Serializable
data class PolicyDecision(
  val outcome: PolicyDecisionOutcome,
  val reasonCode: String,
  val detail: String? = null,
  val approvalRisk: PolicyApprovalRisk = PolicyApprovalRisk.STANDARD,
) {
  init {
    require(reasonCode.isNotBlank()) { "Policy reasonCode must not be blank." }
  }
}

@Serializable
data class AgentTask(
  val id: String,
  val type: AgentTaskType,
  val input: String,
  val state: AgentTaskState = AgentTaskState.QUEUED,
  val policyDecision: PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ASK,
    reasonCode = "PENDING_REVIEW",
  ),
  val skillName: String? = null,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long = createdAtEpochMs,
  val metadata: Map<String, String> = emptyMap(),
  val schemaVersion: Int = ContractSchemaVersion.CURRENT,
) {
  init {
    require(id.isNotBlank()) { "AgentTask id must not be blank." }
    require(input.isNotBlank()) { "AgentTask input must not be blank." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "AgentTask updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}

@Serializable
enum class ExecutionStatus {
  @SerialName("success") SUCCESS,
  @SerialName("failed") FAILED,
  @SerialName("denied") DENIED,
  @SerialName("cancelled") CANCELLED,
  @SerialName("timeout") TIMEOUT,
}

@Serializable
data class ExecutionResult(
  val taskId: String,
  val status: ExecutionStatus,
  val exitCode: Int? = null,
  val stdout: String = "",
  val stderr: String = "",
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val policyDecision: PolicyDecision? = null,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
  val metadata: Map<String, String> = emptyMap(),
  val schemaVersion: Int = ContractSchemaVersion.CURRENT,
) {
  init {
    require(taskId.isNotBlank()) { "ExecutionResult taskId must not be blank." }
    require(finishedAtEpochMs >= startedAtEpochMs) {
      "ExecutionResult finishedAtEpochMs must be >= startedAtEpochMs."
    }
  }
}
