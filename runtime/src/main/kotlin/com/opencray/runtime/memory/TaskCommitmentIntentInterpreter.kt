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

data class TaskCommitmentIntentRequest(
  val sessionId: String,
  val commitments: List<OpenTaskCommitment>,
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
}

data class TaskCommitmentIntentDecision(
  val commitmentId: String,
  val action: TaskCommitmentIntentAction,
) {
  init {
    require(commitmentId.isNotBlank()) { "TaskCommitmentIntentDecision commitmentId must not be blank." }
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
