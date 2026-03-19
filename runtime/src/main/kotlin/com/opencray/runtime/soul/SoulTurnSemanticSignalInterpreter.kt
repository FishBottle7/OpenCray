package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeConversationMessage

data class SoulTurnSemanticSignalRequest(
  val sessionId: String,
  val taskId: String,
  val userInput: String,
  val conversation: List<RuntimeConversationMessage> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "SoulTurnSemanticSignalRequest sessionId must not be blank." }
    require(taskId.isNotBlank()) { "SoulTurnSemanticSignalRequest taskId must not be blank." }
    require(userInput.isNotBlank()) { "SoulTurnSemanticSignalRequest userInput must not be blank." }
  }
}

sealed interface SoulTurnSemanticSignalInterpretation {
  data class Success(
    val signal: SoulTurnSemanticSignal,
  ) : SoulTurnSemanticSignalInterpretation

  data class Unavailable(
    val reason: String? = null,
  ) : SoulTurnSemanticSignalInterpretation
}

interface SoulTurnSemanticSignalInterpreter {
  fun interpret(request: SoulTurnSemanticSignalRequest): SoulTurnSemanticSignalInterpretation
}

object NoOpSoulTurnSemanticSignalInterpreter : SoulTurnSemanticSignalInterpreter {
  override fun interpret(
    request: SoulTurnSemanticSignalRequest,
  ): SoulTurnSemanticSignalInterpretation = SoulTurnSemanticSignalInterpretation.Unavailable(
    reason = "No soul turn semantic signal interpreter configured.",
  )
}
