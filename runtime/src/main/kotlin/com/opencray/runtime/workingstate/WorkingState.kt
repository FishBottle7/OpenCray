package com.opencray.runtime.workingstate

import kotlinx.serialization.Serializable

@Serializable
data class WorkingStateObjective(
  val taskId: String? = null,
  val runId: String? = null,
  val primaryGoal: String? = null,
  val currentSubgoal: String? = null,
  val status: String? = null,
) {
  init {
    require(taskId == null || taskId.isNotBlank()) {
      "WorkingStateObjective taskId must not be blank."
    }
    require(runId == null || runId.isNotBlank()) {
      "WorkingStateObjective runId must not be blank."
    }
    require(primaryGoal == null || primaryGoal.isNotBlank()) {
      "WorkingStateObjective primaryGoal must not be blank."
    }
    require(currentSubgoal == null || currentSubgoal.isNotBlank()) {
      "WorkingStateObjective currentSubgoal must not be blank."
    }
    require(status == null || status.isNotBlank()) {
      "WorkingStateObjective status must not be blank."
    }
  }

  val isEmpty: Boolean
    get() = taskId.isNullOrBlank() &&
      runId.isNullOrBlank() &&
      primaryGoal.isNullOrBlank() &&
      currentSubgoal.isNullOrBlank() &&
      status.isNullOrBlank()
}

@Serializable
data class WorkingStateEntry(
  val text: String,
  val sourceType: String? = null,
  val rationale: String? = null,
) {
  init {
    require(text.isNotBlank()) { "WorkingStateEntry text must not be blank." }
    require(sourceType == null || sourceType.isNotBlank()) {
      "WorkingStateEntry sourceType must not be blank."
    }
    require(rationale == null || rationale.isNotBlank()) {
      "WorkingStateEntry rationale must not be blank."
    }
  }
}

@Serializable
data class WorkingState(
  val objective: WorkingStateObjective? = null,
  val findings: List<WorkingStateEntry> = emptyList(),
  val recentActions: List<WorkingStateEntry> = emptyList(),
  val decisions: List<WorkingStateEntry> = emptyList(),
  val blockers: List<WorkingStateEntry> = emptyList(),
  val nextActions: List<WorkingStateEntry> = emptyList(),
  val updatedAtEpochMs: Long? = null,
) {
  init {
    require(updatedAtEpochMs == null || updatedAtEpochMs >= 0L) {
      "WorkingState updatedAtEpochMs must be >= 0 when present."
    }
  }

  val isEmpty: Boolean
    get() = (objective == null || objective.isEmpty) &&
      findings.isEmpty() &&
      recentActions.isEmpty() &&
      decisions.isEmpty() &&
      blockers.isEmpty() &&
      nextActions.isEmpty()
}

data class WorkingStateTrace(
  val included: Boolean = false,
  val objectivePresent: Boolean = false,
  val findingCount: Int = 0,
  val recentActionCount: Int = 0,
  val decisionCount: Int = 0,
  val blockerCount: Int = 0,
  val nextActionCount: Int = 0,
  val synthesizedFromTaskInput: Boolean = false,
  val synthesizedFromRecentObservations: Boolean = false,
  val synthesizedFromResumeContext: Boolean = false,
  val synthesizedFromTodoSnapshot: Boolean = false,
) {
  init {
    require(findingCount >= 0) { "WorkingStateTrace findingCount must be >= 0." }
    require(recentActionCount >= 0) { "WorkingStateTrace recentActionCount must be >= 0." }
    require(decisionCount >= 0) { "WorkingStateTrace decisionCount must be >= 0." }
    require(blockerCount >= 0) { "WorkingStateTrace blockerCount must be >= 0." }
    require(nextActionCount >= 0) { "WorkingStateTrace nextActionCount must be >= 0." }
  }

  val isEmpty: Boolean
    get() = !included &&
      !objectivePresent &&
      findingCount == 0 &&
      recentActionCount == 0 &&
      decisionCount == 0 &&
      blockerCount == 0 &&
      nextActionCount == 0 &&
      !synthesizedFromTaskInput &&
      !synthesizedFromRecentObservations &&
      !synthesizedFromResumeContext &&
      !synthesizedFromTodoSnapshot
}
