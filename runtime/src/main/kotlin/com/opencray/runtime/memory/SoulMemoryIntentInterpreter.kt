package com.opencray.runtime.memory

data class SoulMemoryIntentRequest(
  val sessionId: String,
  val workspaceId: String? = null,
  val userInput: String,
) {
  init {
    require(sessionId.isNotBlank()) { "SoulMemoryIntentRequest sessionId must not be blank." }
    require(userInput.isNotBlank()) { "SoulMemoryIntentRequest userInput must not be blank." }
  }
}

data class SoulMemoryIntent(
  val preferenceKey: String,
  val preferenceValue: String,
  val scope: MemoryScope,
  val soulExtensions: Map<String, String> = emptyMap(),
  val preferenceExtensions: Map<String, String> = emptyMap(),
) {
  init {
    require(preferenceKey.isNotBlank()) { "SoulMemoryIntent preferenceKey must not be blank." }
    require(preferenceValue.isNotBlank()) { "SoulMemoryIntent preferenceValue must not be blank." }
  }
}

sealed interface SoulMemoryIntentInterpretation {
  data class Success(
    val intents: List<SoulMemoryIntent>,
  ) : SoulMemoryIntentInterpretation

  data class Unavailable(
    val allowHeuristicFallback: Boolean,
    val reason: String? = null,
  ) : SoulMemoryIntentInterpretation
}

interface SoulMemoryIntentInterpreter {
  fun interpret(request: SoulMemoryIntentRequest): SoulMemoryIntentInterpretation
}

object NoOpSoulMemoryIntentInterpreter : SoulMemoryIntentInterpreter {
  override fun interpret(
    request: SoulMemoryIntentRequest,
  ): SoulMemoryIntentInterpretation = SoulMemoryIntentInterpretation.Unavailable(
    allowHeuristicFallback = false,
    reason = "No soul memory intent interpreter configured.",
  )
}
