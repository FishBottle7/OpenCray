package com.opencray.runtime.subagent

import kotlinx.serialization.Serializable

@Serializable
enum class SubAgentPendingApprovalDecisionState {
  APPROVED,
  REJECTED,
}

@Serializable
data class SubAgentPendingApprovalDecision(
  val state: SubAgentPendingApprovalDecisionState,
  val resume: SubAgentApprovalResume,
  val recordedAtEpochMs: Long,
) {
  init {
    require(recordedAtEpochMs >= 0L) {
      "SubAgentPendingApprovalDecision recordedAtEpochMs must be >= 0."
    }
  }

  val approved: Boolean
    get() = state == SubAgentPendingApprovalDecisionState.APPROVED
}
