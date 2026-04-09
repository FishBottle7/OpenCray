package com.opencray.app

import java.util.Locale

internal fun approvalSupportComposeBody(
  body: String,
  toolReason: String?,
  metadata: Map<String, String>,
  isChinese: Boolean,
): String {
  val details = mutableListOf<String>()
  approvalSupportPrimaryDetailLine(metadata, isChinese)?.let(details::add)
  approvalSupportPathDetailLines(metadata, isChinese).forEach(details::add)
  approvalSupportWorkingDirectoryLine(metadata, isChinese)?.let(details::add)
  approvalSupportReasonLine(toolReason, isChinese)?.let(details::add)
  if (details.isEmpty()) {
    return body
  }
  return buildString {
    details.forEach { line -> appendLine(line) }
    appendLine()
    append(body)
  }.trim()
}

internal fun approvalSupportRequestSummary(
  metadata: Map<String, String>,
): String? = metadata["targetSummary"]?.trim()?.takeIf(String::isNotBlank)
  ?: approvalSupportPrimaryDetailValue(metadata)

internal fun approvalSupportPrimaryDetailValue(
  metadata: Map<String, String>,
): String? {
  metadata["scriptPath"]?.takeIf(String::isNotBlank)?.let { return it }
  approvalSupportShellCommandSummary(metadata)?.let { return it }
  metadata["query"]?.takeIf(String::isNotBlank)?.let { return it }
  metadata["requestedUrl"]?.takeIf(String::isNotBlank)?.let { return it }
  metadata["finalUrl"]?.takeIf(String::isNotBlank)?.let { return it }
  metadata["processId"]?.takeIf(String::isNotBlank)?.let { processId ->
    if (metadata["targetKind"] == "process") {
      return processId
    }
  }
  metadata["delegationDescription"]?.takeIf(String::isNotBlank)?.let { return it }
  metadata["targetSummary"]?.takeIf(String::isNotBlank)?.let { summary ->
    val primaryTargetPath = metadata["primaryTargetPath"]?.trim().orEmpty()
    val secondaryTargetPath = metadata["secondaryTargetPath"]?.trim().orEmpty()
    val duplicateSummaries = buildSet {
      if (primaryTargetPath.isNotEmpty()) {
        add(primaryTargetPath)
      }
      if (secondaryTargetPath.isNotEmpty()) {
        add(secondaryTargetPath)
      }
      if (primaryTargetPath.isNotEmpty() && secondaryTargetPath.isNotEmpty()) {
        add("$primaryTargetPath -> $secondaryTargetPath")
      }
    }
    if (summary !in duplicateSummaries) {
      return summary
    }
  }
  return null
}

internal fun approvalSupportPrimaryDetailLine(
  metadata: Map<String, String>,
  isChinese: Boolean,
): String? = when {
  metadata["scriptPath"]?.isNotBlank() == true ->
    approvalSupportPrimaryDetailValue(metadata)?.let { detail ->
      "${approvalSupportLabel("script", isChinese)}: $detail"
    }
  approvalSupportShellCommandSummary(metadata) != null ->
    approvalSupportPrimaryDetailValue(metadata)?.let { detail ->
      "${approvalSupportLabel("command", isChinese)}: $detail"
    }
  metadata["query"]?.isNotBlank() == true ->
    approvalSupportPrimaryDetailValue(metadata)?.let { detail ->
      "${approvalSupportLabel("query", isChinese)}: $detail"
    }
  metadata["requestedUrl"]?.isNotBlank() == true || metadata["finalUrl"]?.isNotBlank() == true ->
    approvalSupportPrimaryDetailValue(metadata)?.let { detail ->
      "${approvalSupportLabel("url", isChinese)}: $detail"
    }
  metadata["processId"]?.isNotBlank() == true && metadata["targetKind"] == "process" ->
    approvalSupportPrimaryDetailValue(metadata)?.let { detail ->
      "${approvalSupportLabel("process", isChinese)}: $detail"
    }
  else ->
    approvalSupportPrimaryDetailValue(metadata)?.let { detail ->
      "${approvalSupportLabel("request", isChinese)}: $detail"
    }
}

internal fun approvalSupportPathDetailLines(
  metadata: Map<String, String>,
  isChinese: Boolean,
): List<String> {
  val sourcePath = metadata["sourcePath"]?.trim().orEmpty()
  val destinationPath = metadata["destinationPath"]?.trim().orEmpty()
  val delegationPromptPreview = metadata["delegationPromptPreview"]?.trim().orEmpty()
  val delegationAllowedTools = metadata["delegationAllowedTools"]?.trim().orEmpty()
  if (sourcePath.isNotEmpty() || destinationPath.isNotEmpty()) {
    return buildList {
      if (sourcePath.isNotEmpty()) {
        add("${approvalSupportLabel("from", isChinese)}: $sourcePath")
      }
      if (destinationPath.isNotEmpty()) {
        add("${approvalSupportLabel("to", isChinese)}: $destinationPath")
      }
      if (delegationPromptPreview.isNotEmpty()) {
        add("${approvalSupportLabel("prompt", isChinese)}: $delegationPromptPreview")
      }
      if (delegationAllowedTools.isNotEmpty()) {
        add("${approvalSupportLabel("allowed_tools", isChinese)}: $delegationAllowedTools")
      }
    }
  }
  val primaryTargetPath = metadata["primaryTargetPath"]?.trim().orEmpty()
  val secondaryTargetPath = metadata["secondaryTargetPath"]?.trim().orEmpty()
  val scriptPath = metadata["scriptPath"]?.trim().orEmpty()
  val workingDirectory = metadata["workingDirectory"]?.trim().orEmpty()
  return buildList {
    if (
      primaryTargetPath.isNotEmpty() &&
      primaryTargetPath != scriptPath &&
      primaryTargetPath != workingDirectory
    ) {
      add("${approvalSupportLabel("target", isChinese)}: $primaryTargetPath")
    }
    if (secondaryTargetPath.isNotEmpty()) {
      add("${approvalSupportLabel("to", isChinese)}: $secondaryTargetPath")
    }
    if (delegationPromptPreview.isNotEmpty()) {
      add("${approvalSupportLabel("prompt", isChinese)}: $delegationPromptPreview")
    }
    if (delegationAllowedTools.isNotEmpty()) {
      add("${approvalSupportLabel("allowed_tools", isChinese)}: $delegationAllowedTools")
    }
  }
}

internal fun approvalSupportWorkingDirectoryValue(
  metadata: Map<String, String>,
): String? = metadata["workingDirectory"]?.trim()?.takeIf(String::isNotBlank)

internal fun approvalSupportWorkingDirectoryLine(
  metadata: Map<String, String>,
  isChinese: Boolean,
): String? = approvalSupportWorkingDirectoryValue(metadata)
  ?.let { workingDirectory ->
    "${approvalSupportLabel("working_directory", isChinese)}: $workingDirectory"
  }

internal fun approvalSupportReasonValue(
  toolReason: String?,
): String? = approvalSupportSanitizePotentialInternalAgentText(
  text = toolReason?.trim().orEmpty(),
  fallback = "",
).trim().takeIf(String::isNotBlank)

internal fun approvalSupportReasonLine(
  toolReason: String?,
  isChinese: Boolean,
): String? = approvalSupportReasonValue(toolReason)
  ?.let { reason ->
    "${approvalSupportLabel("reason", isChinese)}: $reason"
  }

internal fun approvalSupportShellCommandSummary(
  metadata: Map<String, String>,
): String? {
  metadata["shellCommand"]?.takeIf(String::isNotBlank)?.let { return it }
  val command = metadata["command"]?.trim().orEmpty()
  if (command.isEmpty()) {
    return null
  }
  val args = metadata["args"]
    ?.split('\u0000')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    .orEmpty()
  return buildString {
    append(command)
    if (args.isNotEmpty()) {
      append(' ')
      append(args.joinToString(separator = " "))
    }
  }.trim()
}

internal fun approvalSupportLabel(
  kind: String,
  isChinese: Boolean,
): String = when (kind) {
  "command" -> if (isChinese) "命令" else "Command"
  "script" -> if (isChinese) "脚本" else "Script"
  "query" -> if (isChinese) "查询" else "Query"
  "url" -> if (isChinese) "地址" else "URL"
  "process" -> if (isChinese) "进程" else "Process"
  "request" -> if (isChinese) "操作" else "Request"
  "prompt" -> if (isChinese) "委派内容" else "Prompt"
  "allowed_tools" -> if (isChinese) "可用工具" else "Allowed tools"
  "from" -> if (isChinese) "来源" else "From"
  "to" -> if (isChinese) "目标" else "To"
  "target" -> if (isChinese) "目标" else "Target"
  "working_directory" -> if (isChinese) "工作目录" else "Working directory"
  "reason" -> if (isChinese) "理由" else "Agent reason"
  else -> if (isChinese) "详情" else "Details"
}

internal fun approvalSupportSanitizePotentialInternalAgentText(
  text: String,
  fallback: String,
): String {
  val trimmed = text.trim()
  if (trimmed.isBlank()) {
    return fallback
  }
  return if (approvalSupportLooksLikeInternalToolPayload(trimmed)) fallback else text
}

internal fun approvalSupportLooksLikeInternalToolPayload(
  text: String,
): Boolean {
  val jsonCandidate = approvalSupportExtractEmbeddedJsonObject(text) ?: return false
  val normalized = jsonCandidate.lowercase(Locale.US)
  val explicitToolAction =
    "\"type\"" in normalized &&
      ("\"tool_call\"" in normalized || "\"tool\"" in normalized)
  val toolArgumentShape = "\"tool_name\"" in normalized && "\"arguments\"" in normalized
  return explicitToolAction || toolArgumentShape
}

private fun approvalSupportExtractEmbeddedJsonObject(raw: String): String? {
  val trimmed = raw.trim()
  if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
    return trimmed
  }
  var depth = 0
  var startIndex = -1
  var inString = false
  var escaped = false
  for ((index, character) in raw.withIndex()) {
    when {
      inString && escaped -> escaped = false
      inString && character == '\\' -> escaped = true
      character == '"' -> inString = !inString
      !inString && character == '{' -> {
        if (depth == 0) {
          startIndex = index
        }
        depth += 1
      }

      !inString && character == '}' -> {
        depth -= 1
        if (depth == 0 && startIndex >= 0) {
          return raw.substring(startIndex, index + 1)
        }
      }
    }
  }
  return null
}
