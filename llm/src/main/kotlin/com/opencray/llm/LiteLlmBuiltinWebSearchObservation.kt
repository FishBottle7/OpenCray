package com.opencray.llm

import kotlinx.serialization.Serializable

@Serializable
data class LiteLlmBuiltinWebSearchObservation(
  val actionType: String,
  val status: String? = null,
  val queries: List<String> = emptyList(),
  val domains: List<String> = emptyList(),
  val url: String? = null,
  val findText: String? = null,
  val sources: List<LiteLlmBuiltinWebSearchSource> = emptyList(),
) {
  init {
    require(actionType.isNotBlank()) { "LiteLlmBuiltinWebSearchObservation actionType must not be blank." }
  }
}

@Serializable
data class LiteLlmBuiltinWebSearchSource(
  val title: String? = null,
  val url: String,
) {
  init {
    require(url.isNotBlank()) { "LiteLlmBuiltinWebSearchSource url must not be blank." }
  }
}
