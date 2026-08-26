package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmVisibleTextObserver

internal fun OpenCrayAgentRuntime.assistantDraftObserver(
  task: AgentTask,
): LiteLlmVisibleTextObserver = object : LiteLlmVisibleTextObserver {
    private var hasVisibleDraft: Boolean = false
    private var lastVisibleDraftText: String? = null

    override fun onVisibleTextSnapshot(text: String) {
      val normalized = visibleAssistantDraftText(text) ?: return
      if (normalized == lastVisibleDraftText) {
        return
      }
      hasVisibleDraft = true
      lastVisibleDraftText = normalized
      eventSink.onAssistantDraftUpdated(
        task = task,
        text = normalized,
        emittedAtEpochMs = clock(),
      )
    }

    override fun onVisibleTextReset() {
      if (!hasVisibleDraft) {
        return
      }
      clearAssistantDraft(task)
      hasVisibleDraft = false
      lastVisibleDraftText = null
    }
  }

internal fun visibleAssistantDraftText(rawText: String): String? {
  val normalized = rawText.trim().takeIf(String::isNotBlank) ?: return null
  val startsLikeJson = normalized.startsWith('{') || normalized.startsWith('[')
  val lowercase = normalized.lowercase()
  val looksLikeStructuredProtocol =
    startsLikeJson && (
      "\"type\"" in lowercase ||
        "\"decision\"" in lowercase ||
        "\"actions\"" in lowercase ||
        "\"tool_name\"" in lowercase ||
        "\"tool_calls\"" in lowercase ||
        "\"arguments\"" in lowercase
      )
  val looksLikeInternalSignal =
    startsLikeJson && (
      "\"is_task_bearing_request\"" in lowercase ||
        "\"user_affect\"" in lowercase ||
        "\"user_invites_playfulness\"" in lowercase ||
        "\"user_requests_relational_support\"" in lowercase ||
        "\"clarification_needed\"" in lowercase
      )
  if (!startsLikeJson) {
    return normalized
  }
  if (normalized == "{" || normalized == "[") {
    return null
  }
  extractStructuredAssistantDraftText(normalized)?.let { return it }
  if (looksLikeStructuredProtocol || looksLikeInternalSignal) {
    return null
  }
  return normalized
}

internal fun structuredCompletionCommentaryTexts(
  completion: LiteLlmStructuredCompletion,
): List<String> = completion.commentaryTexts
  .map(String::trim)
  .filter(String::isNotBlank)
  .ifEmpty {
    completion.commentaryText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::listOf)
      ?: emptyList()
  }

internal fun extractStructuredAssistantDraftText(rawText: String): String? {
  val lowercase = rawText.lowercase()
  val hasExplicitTypeField = "\"type\"" in lowercase || "\"decision\"" in lowercase
  if ("\"actions\"" in lowercase) {
    extractStructuredActionsDraftText(rawText)?.let { return it }
  }
  if (containsStructuredAssistantExecutionSignal(lowercase)) {
    return null
  }
  val actionType = structuredAssistantDraftActionType(rawText)
  return when (actionType) {
    "final",
    "answer",
    -> firstNonBlankAssistantDraftField(
      partialJsonStringFieldValue(rawText, "answer"),
      partialJsonStringFieldValue(rawText, "text"),
      partialJsonStringFieldValue(rawText, "message"),
      partialJsonStringFieldValue(rawText, "summary"),
    )?.trim()?.takeIf(String::isNotBlank)

    null,
    "",
    -> if (hasExplicitTypeField) {
      partialJsonStringFieldValue(rawText, "answer")
        ?.trim()
        ?.takeIf(String::isNotBlank)
    } else {
      null
    }

    else -> null
  }
}

internal fun extractStructuredActionsDraftText(rawText: String): String? {
  val actions = partialJsonObjectFieldArrayElements(rawText, "actions")
  if (actions.isEmpty()) {
    return null
  }
  val hasExecutionAction = actions.any(::structuredAssistantActionSuppressesFinalDraft)
  return actions
    .mapNotNull { rawAction ->
      val visibleText = extractStructuredAssistantDraftTextFromAction(rawAction) ?: return@mapNotNull null
      if (hasExecutionAction && isStructuredAssistantFinalAction(rawAction)) {
        return@mapNotNull null
      }
      visibleText
    }
    .lastOrNull()
}

internal fun extractStructuredAssistantDraftTextFromAction(rawAction: String): String? {
  val actionType = structuredAssistantDraftActionType(rawAction)
  return when (actionType) {
    "final",
    "answer",
    -> firstNonBlankAssistantDraftField(
      partialJsonStringFieldValue(rawAction, "answer"),
      partialJsonStringFieldValue(rawAction, "text"),
      partialJsonStringFieldValue(rawAction, "message"),
      partialJsonStringFieldValue(rawAction, "summary"),
    )?.trim()?.takeIf(String::isNotBlank)

    else -> null
  }
}

internal fun structuredAssistantDraftActionType(rawText: String): String? =
  firstNonBlankAssistantDraftField(
    partialJsonStringFieldValue(rawText, "type")?.trim()?.lowercase()?.takeIf(String::isNotBlank),
    partialJsonStringFieldValue(rawText, "decision")?.trim()?.lowercase()?.takeIf(String::isNotBlank),
  )

internal fun isStructuredAssistantFinalAction(rawText: String): Boolean =
  structuredAssistantDraftActionType(rawText) in setOf("final", "answer")

internal fun structuredAssistantActionSuppressesFinalDraft(rawAction: String): Boolean {
  val lowercase = rawAction.lowercase()
  if (containsStructuredAssistantExecutionSignal(lowercase)) {
    return true
  }
  return when (structuredAssistantDraftActionType(rawAction)) {
    null,
    "",
    "final",
    "answer",
    "progress",
    "commentary",
    "status",
    -> false

    else -> true
  }
}

internal fun containsStructuredAssistantExecutionSignal(lowercase: String): Boolean =
  containsStructuredAssistantToolSignal(lowercase) ||
    "\"is_task_bearing_request\"" in lowercase ||
    "\"user_affect\"" in lowercase ||
    "\"user_invites_playfulness\"" in lowercase ||
    "\"user_requests_relational_support\"" in lowercase ||
    "\"clarification_needed\"" in lowercase

internal fun containsStructuredAssistantToolSignal(lowercase: String): Boolean =
  "\"tool_name\"" in lowercase ||
    "\"tool_calls\"" in lowercase ||
    "\"arguments\"" in lowercase

internal fun firstNonBlankAssistantDraftField(vararg values: String?): String? =
  values.firstOrNull { value -> !value.isNullOrBlank() }

internal fun partialJsonObjectFieldArrayElements(
  rawText: String,
  fieldName: String,
): List<String> {
  val fieldPattern = "\"$fieldName\""
  var searchFrom = 0
  var keyIndex = -1
  while (searchFrom < rawText.length) {
    val candidateIndex = rawText.indexOf(fieldPattern, searchFrom)
    if (candidateIndex < 0) {
      return emptyList()
    }
    if (isTopLevelPartialJsonObjectKey(rawText = rawText, keyIndex = candidateIndex)) {
      keyIndex = candidateIndex
      break
    }
    searchFrom = candidateIndex + fieldPattern.length
  }
  var index = keyIndex + fieldPattern.length
  while (index < rawText.length && rawText[index].isWhitespace()) {
    index += 1
  }
  if (index >= rawText.length || rawText[index] != ':') {
    return emptyList()
  }
  index += 1
  while (index < rawText.length && rawText[index].isWhitespace()) {
    index += 1
  }
  if (index >= rawText.length || rawText[index] != '[') {
    return emptyList()
  }
  index += 1
  val elements = mutableListOf<String>()
  var objectStart = -1
  var objectDepth = 0
  var inString = false
  var escaped = false
  while (index < rawText.length) {
    val character = rawText[index]
    if (inString) {
      if (escaped) {
        escaped = false
      } else {
        when (character) {
          '\\' -> escaped = true
          '"' -> inString = false
        }
      }
      index += 1
      continue
    }
    when (character) {
      '"' -> inString = true
      '{' -> {
        if (objectDepth == 0) {
          objectStart = index
        }
        objectDepth += 1
      }
      '}' -> {
        if (objectDepth > 0) {
          objectDepth -= 1
          if (objectDepth == 0 && objectStart >= 0) {
            elements += rawText.substring(objectStart, index + 1)
            objectStart = -1
          }
        }
      }
      ']' -> {
        if (objectDepth == 0) {
          return elements
        }
      }
    }
    index += 1
  }
  if (objectStart >= 0) {
    elements += rawText.substring(objectStart)
  }
  return elements
}

internal fun isTopLevelPartialJsonObjectKey(
  rawText: String,
  keyIndex: Int,
): Boolean {
  var objectDepth = 0
  var arrayDepth = 0
  var inString = false
  var escaped = false
  for (index in 0 until keyIndex) {
    val character = rawText[index]
    if (inString) {
      if (escaped) {
        escaped = false
      } else {
        when (character) {
          '\\' -> escaped = true
          '"' -> inString = false
        }
      }
      continue
    }
    when (character) {
      '"' -> inString = true
      '{' -> objectDepth += 1
      '}' -> if (objectDepth > 0) {
        objectDepth -= 1
      }
      '[' -> arrayDepth += 1
      ']' -> if (arrayDepth > 0) {
        arrayDepth -= 1
      }
    }
  }
  return objectDepth == 1 && arrayDepth == 0 && !inString
}

internal fun partialJsonStringFieldValue(
  rawText: String,
  fieldName: String,
): String? {
  val fieldPattern = "\"$fieldName\""
  var searchStart = 0
  while (true) {
    val keyIndex = rawText.indexOf(fieldPattern, startIndex = searchStart)
    if (keyIndex < 0) {
      return null
    }
    var index = keyIndex + fieldPattern.length
    while (index < rawText.length && rawText[index].isWhitespace()) {
      index += 1
    }
    if (index >= rawText.length || rawText[index] != ':') {
      searchStart = keyIndex + fieldPattern.length
      continue
    }
    index += 1
    while (index < rawText.length && rawText[index].isWhitespace()) {
      index += 1
    }
    if (index >= rawText.length || rawText[index] != '"') {
      return null
    }
    index += 1
    val builder = StringBuilder()
    var escaped = false
    while (index < rawText.length) {
      val character = rawText[index]
      if (escaped) {
        escaped = false
        when (character) {
          'n' -> {
            builder.append('\n')
            index += 1
          }
          'r' -> {
            builder.append('\r')
            index += 1
          }
          't' -> {
            builder.append('\t')
            index += 1
          }
          '\\',
          '"',
          '/',
          -> {
            builder.append(character)
            index += 1
          }
          'u' -> {
            val hexStart = index + 1
            val hexEnd = hexStart + 4
            if (hexEnd <= rawText.length) {
              val code = rawText.substring(hexStart, hexEnd).toIntOrNull(16)
              if (code != null) {
                builder.append(code.toChar())
              } else {
                builder.append('\\').append('u')
                builder.append(rawText, hexStart, hexEnd)
              }
              index = hexEnd
            } else {
              builder.append('\\').append('u')
              builder.append(rawText, hexStart, rawText.length)
              index = rawText.length
            }
          }
          else -> {
            builder.append('\\').append(character)
            index += 1
          }
        }
        continue
      }
      when (character) {
        '\\' -> {
          escaped = true
          index += 1
        }

        '"' -> return builder.toString()
        else -> {
          builder.append(character)
          index += 1
        }
      }
    }
    return builder.toString()
  }
}

internal fun OpenCrayAgentRuntime.clearAssistantDraft(task: AgentTask) {
  eventSink.onAssistantDraftCleared(
    task = task,
    emittedAtEpochMs = clock(),
  )
}

internal fun combineVisibleTextObservers(
  primary: LiteLlmVisibleTextObserver,
  secondary: LiteLlmVisibleTextObserver,
): LiteLlmVisibleTextObserver = object : LiteLlmVisibleTextObserver {
  override fun onVisibleTextSnapshot(text: String) {
    primary.onVisibleTextSnapshot(text)
    secondary.onVisibleTextSnapshot(text)
  }

  override fun onVisibleTextReset() {
    primary.onVisibleTextReset()
    secondary.onVisibleTextReset()
  }
}
