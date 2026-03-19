package com.opencray.runtime.memory

data class UserMemoryIntentRequest(
  val sessionId: String,
  val workspaceId: String? = null,
  val userInput: String,
) {
  init {
    require(sessionId.isNotBlank()) { "UserMemoryIntentRequest sessionId must not be blank." }
    require(userInput.isNotBlank()) { "UserMemoryIntentRequest userInput must not be blank." }
  }
}

data class UserMemoryIntent(
  val kind: MemoryKind,
  val scope: MemoryScope,
  val content: String? = null,
  val preferenceKey: String? = null,
  val preferenceValue: String? = null,
  val soulExtensions: Map<String, String> = emptyMap(),
  val preferenceExtensions: Map<String, String> = emptyMap(),
) {
  init {
    require(
      !content.isNullOrBlank() ||
        (!preferenceKey.isNullOrBlank() && !preferenceValue.isNullOrBlank()),
    ) {
      "UserMemoryIntent must define content or a structured preference."
    }
  }
}

sealed interface UserMemoryIntentInterpretation {
  data class Success(
    val intents: List<UserMemoryIntent>,
  ) : UserMemoryIntentInterpretation

  data class Unavailable(
    val allowHeuristicFallback: Boolean,
    val reason: String? = null,
  ) : UserMemoryIntentInterpretation
}

interface UserMemoryIntentInterpreter {
  fun interpret(request: UserMemoryIntentRequest): UserMemoryIntentInterpretation
}

object NoOpUserMemoryIntentInterpreter : UserMemoryIntentInterpreter {
  override fun interpret(
    request: UserMemoryIntentRequest,
  ): UserMemoryIntentInterpretation = UserMemoryIntentInterpretation.Unavailable(
    allowHeuristicFallback = false,
    reason = "No user memory intent interpreter configured.",
  )
}
