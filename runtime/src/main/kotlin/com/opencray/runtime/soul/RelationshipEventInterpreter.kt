package com.opencray.runtime.soul

data class RelationshipEventRequest(
  val sessionId: String,
  val workspaceId: String? = null,
  val userInput: String,
  val assistantOutput: String? = null,
  val toolObservations: List<String> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "RelationshipEventRequest sessionId must not be blank." }
    require(userInput.isNotBlank()) { "RelationshipEventRequest userInput must not be blank." }
  }
}

sealed interface RelationshipEventInterpretation {
  data class Success(
    val events: List<RelationshipEvent>,
  ) : RelationshipEventInterpretation

  data object Unavailable : RelationshipEventInterpretation
}

fun interface RelationshipEventInterpreter {
  fun interpret(request: RelationshipEventRequest): RelationshipEventInterpretation
}

object NoOpRelationshipEventInterpreter : RelationshipEventInterpreter {
  override fun interpret(request: RelationshipEventRequest): RelationshipEventInterpretation =
    RelationshipEventInterpretation.Unavailable
}
