package com.opencray.llm

object LiteLlmMetadataKeys {
  const val NATIVE_TOOL_CALL_REQUESTED: String = "nativeToolCallRequested"
  const val PROVIDER_RESPONSE_SHAPE: String = "providerResponseShape"
  const val NATIVE_TOOL_CALL_OBSERVED: String = "nativeToolCallObserved"
  const val PARSED_TOOL_CALL_OBSERVED: String = "parsedToolCallObserved"
  const val FALLBACK_PARSER_ATTEMPTED: String = "fallbackParserAttempted"
  const val FALLBACK_PARSER_SUCCEEDED: String = "fallbackParserSucceeded"
  const val TOOL_CALL_EVENT_EMITTED: String = "toolCallEventEmitted"
  const val TOOL_RESULT_EVENT_EMITTED: String = "toolResultEventEmitted"
  const val LAST_SUCCESSFUL_TOOL_NAME: String = "lastSuccessfulToolName"
}
