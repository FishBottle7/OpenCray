package com.opencray.runtime.memory

data class OpenTaskCommitment(
  val id: String,
  val content: String,
) {
  init {
    require(id.isNotBlank()) { "OpenTaskCommitment id must not be blank." }
    require(content.isNotBlank()) { "OpenTaskCommitment content must not be blank." }
  }
}

data class ProposedTaskCommitment(
  val candidateIndex: Int,
  val content: String,
) {
  init {
    require(candidateIndex >= 0) { "ProposedTaskCommitment candidateIndex must be >= 0." }
    require(content.isNotBlank()) { "ProposedTaskCommitment content must not be blank." }
  }
}

data class TaskCommitmentIntentRequest(
  val sessionId: String,
  val commitments: List<OpenTaskCommitment>,
  val proposedCommitments: List<ProposedTaskCommitment> = emptyList(),
  val userInput: String? = null,
  val assistantOutput: String? = null,
  val toolObservations: List<String> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "TaskCommitmentIntentRequest sessionId must not be blank." }
  }
}

enum class TaskCommitmentIntentAction {
  RESOLVE,
  REAFFIRM,
  ABANDON,
  SUPERSEDE_WITH_PROPOSED,
  DROP_PROPOSED,
}

data class TaskCommitmentIntentDecision(
  val commitmentId: String? = null,
  val action: TaskCommitmentIntentAction,
  val proposedCommitmentIndex: Int? = null,
) {
  init {
    require(proposedCommitmentIndex == null || proposedCommitmentIndex >= 0) {
      "TaskCommitmentIntentDecision proposedCommitmentIndex must be >= 0 when provided."
    }
    require(!commitmentId.isNullOrBlank() || proposedCommitmentIndex != null) {
      "TaskCommitmentIntentDecision must target a commitmentId or a proposedCommitmentIndex."
    }
  }
}

sealed interface TaskCommitmentIntentInterpretation {
  data class Success(
    val decisions: List<TaskCommitmentIntentDecision>,
  ) : TaskCommitmentIntentInterpretation

  data class Unavailable(
    val allowHeuristicFallback: Boolean,
    val reason: String? = null,
  ) : TaskCommitmentIntentInterpretation
}

interface TaskCommitmentIntentInterpreter {
  fun interpret(request: TaskCommitmentIntentRequest): TaskCommitmentIntentInterpretation
}

object NoOpTaskCommitmentIntentInterpreter : TaskCommitmentIntentInterpreter {
  override fun interpret(
    request: TaskCommitmentIntentRequest,
  ): TaskCommitmentIntentInterpretation = TaskCommitmentIntentInterpretation.Unavailable(
    allowHeuristicFallback = false,
    reason = "No task commitment intent interpreter configured.",
  )
}
