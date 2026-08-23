package com.opencray.runtime.context

import com.opencray.runtime.ScheduledTaskToolMetadataKeys
import com.opencray.runtime.TodoWriteMetadataKeys
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import com.opencray.runtime.workingstate.WorkingStateEntry

internal fun RecentToolObservationSupport.renderWorkingStateAction(parsed: ParsedToolResult): RenderedWorkingStateAction? {
    val canonicalToolName = canonicalToolName(parsed.toolName)
    if (frontContextObservationCategoryForToolName(canonicalToolName) != null) {
      return renderObservationBackedWorkingStateAction(parsed, canonicalToolName)
    }
    return when (canonicalToolName) {
      "Write",
      "workspace_write_file",
      "workspace_move_file",
      "workspace_delete_file",
      "Edit",
      "MultiEdit",
      -> renderWorkspaceMutationAction(parsed, canonicalToolName)

      "Bash",
      "command_exec",
      -> renderCommandAction(parsed, canonicalToolName)

      "python_exec" -> renderPythonAction(parsed)

      "ProcessStart",
      "ProcessRead",
      "ProcessWait",
      "ProcessTerminate",
      -> renderManagedProcessAction(parsed, canonicalToolName)

      "TodoWrite" -> renderTodoWriteAction(parsed)

      "ImportFile",
      "import_chat_attachment",
      -> renderTargetSummaryAction(
        parsed = parsed,
        canonicalToolName = canonicalToolName,
        sourceType = "workspace_import",
      )

      "extract_workspace_package" -> renderTargetSummaryAction(
        parsed = parsed,
        canonicalToolName = canonicalToolName,
        sourceType = "workspace_extract",
      )

      "view_workspace_document",
      "view_workspace_image",
      "view_workspace_pdf",
      -> renderTargetSummaryAction(
        parsed = parsed,
        canonicalToolName = canonicalToolName,
        sourceType = "workspace_view",
      )

      "GenerateImage" -> renderTargetSummaryAction(
        parsed = parsed,
        canonicalToolName = canonicalToolName,
        sourceType = "media_generation",
      )

      "SynthesizeSpeech" -> renderTargetSummaryAction(
        parsed = parsed,
        canonicalToolName = canonicalToolName,
        sourceType = "speech_generation",
      )

      "SkillsAdd",
      "SkillsAddBatch",
      "SkillsUpdate",
      "SkillsRemove",
      -> renderTargetSummaryAction(
        parsed = parsed,
        canonicalToolName = canonicalToolName,
        sourceType = "skills_mutation",
      )

      "ScheduledTaskCreate",
      "ScheduledTaskUpdate",
      "ScheduledTaskDelete",
      -> renderObservationBackedWorkingStateAction(parsed, canonicalToolName)

      else -> null
    }
  }

internal fun RecentToolObservationSupport.renderObservationBackedWorkingStateAction(
    parsed: ParsedToolResult,
    canonicalToolName: String,
  ): RenderedWorkingStateAction? = renderObservation(parsed)?.let { observation ->
    RenderedWorkingStateAction(
      signature = "observation|${observation.signature}",
      entry = WorkingStateEntry(
        text = observation.summaryLine.removePrefix("- ").trim(),
        sourceType = workingStateSourceTypeFor(canonicalToolName),
      ),
    )
  }

internal fun RecentToolObservationSupport.renderWorkspaceMutationAction(
    parsed: ParsedToolResult,
    canonicalToolName: String,
  ): RenderedWorkingStateAction? {
    val filePath = parsed.metadata["filePath"]?.trim().takeUnless { it.isNullOrBlank() }
    val targetSummary = parsed.metadata["targetSummary"]?.trim().takeUnless { it.isNullOrBlank() }
    val text = when {
      filePath != null -> "$canonicalToolName file_path=$filePath"
      targetSummary != null -> "$canonicalToolName target=$targetSummary"
      else -> null
    } ?: return null
    return renderedWorkingStateAction(
      canonicalToolName = canonicalToolName,
      signatureKey = filePath ?: targetSummary,
      text = text,
      sourceType = "workspace_mutation",
    )
  }

internal fun RecentToolObservationSupport.renderCommandAction(
    parsed: ParsedToolResult,
    canonicalToolName: String,
  ): RenderedWorkingStateAction? {
    val command = parsed.metadata["shellCommand"]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["command"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["targetSummary"]?.trim().takeUnless { it.isNullOrBlank() }
    val workingDirectory = parsed.metadata["workingDirectory"]?.trim().takeUnless { it.isNullOrBlank() }
    val parts = mutableListOf<String>()
    command?.let { value -> parts += "command=$value" }
    workingDirectory?.let { value -> parts += "working_directory=$value" }
    if (parts.isEmpty()) {
      return null
    }
    return renderedWorkingStateAction(
      canonicalToolName = canonicalToolName,
      signatureKey = command ?: workingDirectory,
      text = "$canonicalToolName ${parts.joinToString(separator = " ")}",
      sourceType = "command_execution",
    )
  }

internal fun RecentToolObservationSupport.renderPythonAction(
    parsed: ParsedToolResult,
  ): RenderedWorkingStateAction? {
    val scriptPath = parsed.metadata["scriptPath"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["targetSummary"]?.trim().takeUnless { it.isNullOrBlank() }
    val pythonExecutable = parsed.metadata["pythonExecutable"]?.trim().takeUnless { it.isNullOrBlank() }
    val parts = mutableListOf<String>()
    scriptPath?.let { value -> parts += "script_path=$value" }
    pythonExecutable?.let { value -> parts += "python_executable=$value" }
    if (parts.isEmpty()) {
      return null
    }
    return renderedWorkingStateAction(
      canonicalToolName = "python_exec",
      signatureKey = scriptPath ?: pythonExecutable,
      text = "python_exec ${parts.joinToString(separator = " ")}",
      sourceType = "python_execution",
    )
  }

internal fun RecentToolObservationSupport.renderManagedProcessAction(
    parsed: ParsedToolResult,
    canonicalToolName: String,
  ): RenderedWorkingStateAction? {
    val processId = parsed.metadata["processId"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["targetSummary"]?.trim().takeUnless { it.isNullOrBlank() }
    val processStatus = parsed.metadata["processStatus"]?.trim()?.lowercase().takeUnless { it.isNullOrBlank() }
    val scriptPath = parsed.metadata["scriptPath"]?.trim().takeUnless { it.isNullOrBlank() }
    val command = parsed.metadata["shellCommand"]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["command"]?.trim().takeUnless { it.isNullOrBlank() }
    val exitCode = parsed.metadata["exitCode"]?.trim().takeUnless { it.isNullOrBlank() }
    val runtimeBackend = parsed.metadata["runtimeBackend"]?.trim().takeUnless { it.isNullOrBlank() }
    val sandboxBackendResolvedKind = parsed.metadata["sandboxCommandBackendResolvedKind"]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val observationMode = parsed.metadata["sandboxCommandObservationMode"]?.trim().takeUnless { it.isNullOrBlank() }
    val parts = mutableListOf<String>()
    processId?.let { value -> parts += "process_id=$value" }
    processStatus?.let { value -> parts += "status=$value" }
    scriptPath?.let { value -> parts += "script_path=$value" } ?: command?.let { value ->
      parts += "command=$value"
    }
    exitCode?.let { value -> parts += "exit_code=$value" }
    runtimeBackend?.let { value -> parts += "backend=$value" }
      ?: sandboxBackendResolvedKind?.let { value -> parts += "backend=$value" }
    observationMode?.let { value -> parts += "observation=$value" }
    if (parts.isEmpty()) {
      return null
    }
    return renderedWorkingStateAction(
      canonicalToolName = canonicalToolName,
      signatureKey = processId ?: scriptPath ?: command ?: exitCode,
      text = "$canonicalToolName ${parts.joinToString(separator = " ")}",
      sourceType = "process_execution",
    )
  }

internal fun RecentToolObservationSupport.renderTodoWriteAction(
    parsed: ParsedToolResult,
  ): RenderedWorkingStateAction? {
    if (parsed.metadata[TodoWriteMetadataKeys.MUTATED]?.toBooleanStrictOrNull() == false) {
      return null
    }
    val todoCount = parsed.metadata[TodoWriteMetadataKeys.TODO_COUNT]?.trim().takeUnless { it.isNullOrBlank() }
    val activeTodo = parsed.metadata[TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val planChanged = parsed.metadata[TodoWriteMetadataKeys.PLAN_CHANGED] == "true"
    val parts = mutableListOf<String>()
    todoCount?.let { value -> parts += "todos=$value" }
    if (planChanged) {
      parts += "changed=true"
    }
    activeTodo?.let { value -> parts += "active=$value" }
    if (parts.isEmpty()) {
      return null
    }
    return renderedWorkingStateAction(
      canonicalToolName = "TodoWrite",
      signatureKey = activeTodo ?: todoCount ?: parts.joinToString(separator = "|"),
      text = "TodoWrite ${parts.joinToString(separator = " ")}",
      sourceType = "todo_management",
    )
  }

internal fun RecentToolObservationSupport.renderTargetSummaryAction(
    parsed: ParsedToolResult,
    canonicalToolName: String,
    sourceType: String,
  ): RenderedWorkingStateAction? {
    val targetSummary = parsed.metadata["targetSummary"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: return null
    return renderedWorkingStateAction(
      canonicalToolName = canonicalToolName,
      signatureKey = targetSummary,
      text = "$canonicalToolName target=$targetSummary",
      sourceType = sourceType,
    )
  }

internal fun RecentToolObservationSupport.renderedWorkingStateAction(
    canonicalToolName: String,
    signatureKey: String?,
    text: String,
    sourceType: String,
  ): RenderedWorkingStateAction = RenderedWorkingStateAction(
    signature = listOf(
      canonicalToolName,
      signatureKey?.trim().orEmpty(),
    ).joinToString(separator = "|"),
    entry = WorkingStateEntry(
      text = text.trim(),
      sourceType = sourceType,
    ),
  )

internal fun RecentToolObservationSupport.renderToolResultBlocker(
    parsed: ParsedToolResult,
  ): RenderedWorkingStateAction? {
    val errorCode = parsed.errorCode?.trim()?.uppercase() ?: return null
    val text = when (errorCode) {
      ERROR_APPROVAL_REQUIRED -> "Approval required for ${toolSubject(parsed.toolName)} before continuing."
      ERROR_HIGH_RISK_APPROVAL_REQUIRED ->
        "High-risk approval required for ${toolSubject(parsed.toolName)} before continuing."

      else -> return null
    }
    return RenderedWorkingStateAction(
      signature = listOf(
        "tool_blocker",
        errorCode,
        parsed.toolName.trim(),
        parsed.metadata["targetSummary"].orEmpty(),
      ).joinToString(separator = "|"),
      entry = WorkingStateEntry(
        text = text,
        sourceType = "approval_boundary",
        rationale = parsed.errorMessage?.trim()?.takeIf(String::isNotBlank),
      ),
    )
  }

internal fun RecentToolObservationSupport.renderDecisionEntry(
    event: ParsedReplayControlEvent,
  ): RenderedWorkingStateAction? = when (event.type) {
    "approval_rejected" -> RenderedWorkingStateAction(
      signature = replayEventSignature(event),
      entry = WorkingStateEntry(
        text = "Do not retry ${replayToolSubject(event)} automatically; wait for new instruction.",
        sourceType = "approval_decision",
      ),
    )

    "approval_approved" -> RenderedWorkingStateAction(
      signature = replayEventSignature(event),
      entry = WorkingStateEntry(
        text = if (event.toolName == null) {
          "Approval granted; resume from saved checkpoint."
        } else {
          "Approval granted for ${event.toolName}; resume from saved checkpoint."
        },
        sourceType = "approval_decision",
      ),
    )

    "retry_abandoned" -> RenderedWorkingStateAction(
      signature = replayEventSignature(event),
      entry = WorkingStateEntry(
        text = "Do not auto-rerun from task input; wait for explicit resume or new instruction.",
        sourceType = "retry_decision",
      ),
    )

    else -> null
  }

internal fun RecentToolObservationSupport.renderReplayBlocker(
    event: ParsedReplayControlEvent,
  ): RenderedWorkingStateAction? = when (event.type) {
    "approval_rejected" -> RenderedWorkingStateAction(
      signature = replayEventSignature(event),
      entry = WorkingStateEntry(
        text = "User rejected approval for ${replayToolSubject(event)}; await new instruction.",
        sourceType = "approval_boundary",
      ),
    )

    "run_interrupted" -> RenderedWorkingStateAction(
      signature = replayEventSignature(event),
      entry = WorkingStateEntry(
        text = event.toolName?.let { toolName ->
          "Run interrupted during $toolName; await user instruction."
        } ?: "Run interrupted before completion; await user instruction.",
        sourceType = "execution_blocker",
      ),
    )

    "retry_abandoned" -> RenderedWorkingStateAction(
      signature = replayEventSignature(event),
      entry = WorkingStateEntry(
        text = "Retry path exhausted after repeated failure; await explicit resume or new instruction.",
        sourceType = "execution_blocker",
        rationale = event.fields["error_code"]?.trim()?.takeIf(String::isNotBlank),
      ),
    )

    else -> null
  }

internal fun RecentToolObservationSupport.toolSubject(toolName: String): String = toolName.trim().ifBlank { "the requested action" }

internal fun RecentToolObservationSupport.replayToolSubject(event: ParsedReplayControlEvent): String =
    event.toolName ?: "the requested action"

internal fun RecentToolObservationSupport.replayEventSignature(event: ParsedReplayControlEvent): String = listOf(
    "replay",
    event.type,
    event.runId.orEmpty(),
    event.toolName.orEmpty(),
    event.fields["next_step"].orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.workingStateSourceTypeFor(canonicalToolName: String): String = when (canonicalToolName) {
    "Read" -> "workspace_read"
    "LS" -> "workspace_list"
    "Grep",
    "Glob",
    -> "workspace_search"

    "search_workspace_document" -> "workspace_document_search"
    "inspect_workspace_package" -> "workspace_package_inspect"
    "Task" -> "delegation"
    "ScheduledTaskCreate",
    "ScheduledTaskList",
    "ScheduledTaskGet",
    "ScheduledTaskUpdate",
    "ScheduledTaskDelete",
    -> "automation_scheduling"
    "SkillsFind",
    "SkillsList",
    "SkillsInspect",
    "SkillsCheck",
    -> "skills_discovery"

    else -> "recent_observation"
  }

internal fun RecentToolObservationSupport.renderReadObservation(parsed: ParsedToolResult): RenderedObservation? {
    val filePath = parsed.metadata["filePath"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val offset = parsed.metadata["offset"]?.toIntOrNull() ?: 1
    val limit = parsed.metadata["limit"]?.toIntOrNull()
    val returnedLineCount = parsed.metadata["returnedLineCount"]?.toIntOrNull()
    val totalLineCount = parsed.metadata["totalLineCount"]?.toIntOrNull()
    val truncated = parsed.resultTruncated()
    val limitKind = parsed.resultLimitKind()
    val lineSummary = buildReadLineSummary(
      offset = offset,
      returnedLineCount = returnedLineCount,
      totalLineCount = totalLineCount,
    )
    val detailParts = mutableListOf(lineSummary)
    detailParts += "limit=${limit?.toString() ?: "all"}"
    detailParts += "truncated=$truncated"
    limitKind?.let { detailParts += "limit_kind=$it" }
    val body = boundMultiline(
      text = parsed.content,
      maxChars = config.maxReadChars,
      maxLines = config.maxReadLines,
    )
    return RenderedObservation(
      signature = buildReadSignature(
        filePath = filePath,
        offset = offset,
        limit = limit,
      ),
      summaryLine = "- Read file_path=$filePath ${detailParts.joinToString(separator = " ")}",
      body = body,
    )
  }

internal fun RecentToolObservationSupport.renderListObservation(parsed: ParsedToolResult): RenderedObservation? {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: "."
    val entryCount = parsed.metadata["entryCount"]?.toIntOrNull()
    val detailParts = mutableListOf("path=$path", "entries=${entryCount?.toString() ?: "unknown"}")
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildListSignature(path = path),
      summaryLine = "- LS ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderGrepObservation(parsed: ParsedToolResult): RenderedObservation? {
    val pattern = parsed.metadata["pattern"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: "."
    val glob = parsed.metadata["glob"]?.trim().takeUnless { it.isNullOrBlank() }
    val matchCount = parsed.metadata["matchCount"]?.toIntOrNull()
    val detailParts = mutableListOf<String>()
    detailParts += "pattern=$pattern"
    detailParts += "path=$path"
    glob?.let { detailParts += "glob=$it" }
    detailParts += "matches=${matchCount?.toString() ?: "unknown"}"
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildGrepSignature(
        pattern = pattern,
        path = path,
        glob = glob,
      ),
      summaryLine = "- Grep ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderGlobObservation(parsed: ParsedToolResult): RenderedObservation? {
    val pattern = parsed.metadata["pattern"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: "."
    val matchCount = parsed.metadata["matchCount"]?.toIntOrNull()
    val detailParts = mutableListOf(
      "pattern=$pattern",
      "path=$path",
      "matches=${matchCount?.toString() ?: "unknown"}",
    )
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildGlobSignature(
        pattern = pattern,
        path = path,
      ),
      summaryLine = "- Glob ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderWorkspaceDocumentSearchObservation(parsed: ParsedToolResult): RenderedObservation? {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val query = parsed.metadata["query"]?.trim().takeUnless { it.isNullOrBlank() }
    val hitCount = parsed.metadata["hitCount"]?.toIntOrNull()
    val pageCount = parsed.metadata["pageCount"]?.toIntOrNull()
    val requestedPages = parsed.metadata["requestedPages"]?.trim().takeUnless { it.isNullOrBlank() }
    val pageFrom = parsed.metadata["pageFrom"]?.trim().takeUnless { it.isNullOrBlank() }
    val pageTo = parsed.metadata["pageTo"]?.trim().takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf(
      "path=$path",
      "query=${query ?: "<preview>"}",
      "hits=${hitCount?.toString() ?: "unknown"}",
    )
    pageCount?.let { detailParts += "page_count=$it" }
    requestedPages?.let { detailParts += "requested_pages=$it" }
    if (pageFrom != null || pageTo != null) {
      detailParts += "requested_range=${pageFrom ?: 1}-${pageTo ?: "end"}"
    }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildWorkspaceDocumentSearchSignature(
        path = path,
        query = query,
        requestedPages = requestedPages,
        pageFrom = pageFrom,
        pageTo = pageTo,
      ),
      summaryLine = "- search_workspace_document ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderWorkspacePackageInspectObservation(parsed: ParsedToolResult): RenderedObservation? {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val packageKind = parsed.metadata["packageKind"]?.trim().takeUnless { it.isNullOrBlank() }
    val matchedEntryCount = parsed.metadata["matchedEntryCount"]?.toIntOrNull()
    val returnedEntryCount = parsed.metadata["returnedEntryCount"]?.toIntOrNull()
    val previewCount = parsed.metadata["previewCount"]?.toIntOrNull()
    val requestedGlob = parsed.metadata["requestedGlob"]?.trim().takeUnless { it.isNullOrBlank() }
    val requestedPreviewEntries = parsed.metadata["requestedPreviewEntries"]?.trim().takeUnless { it.isNullOrBlank() }
    val requestedMaxEntries = parsed.metadata["requestedMaxEntries"]?.trim().takeUnless { it.isNullOrBlank() }
    val previewChars = parsed.metadata["previewChars"]?.trim().takeUnless { it.isNullOrBlank() }
    val includeRelationshipHints = parsed.metadata["includeRelationshipHints"]?.trim().takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf("path=$path")
    packageKind?.let { detailParts += "kind=$it" }
    matchedEntryCount?.let { detailParts += "matched=$it" }
    returnedEntryCount?.let { detailParts += "returned=$it" }
    previewCount?.let { detailParts += "previews=$it" }
    requestedGlob?.let { detailParts += "glob=$it" }
    requestedPreviewEntries?.let { detailParts += "preview_entries=$it" }
    requestedMaxEntries?.let { detailParts += "max_entries=$it" }
    previewChars?.let { detailParts += "preview_chars=$it" }
    includeRelationshipHints?.let { detailParts += "relationship_hints=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildWorkspacePackageInspectSignature(
        path = path,
        glob = requestedGlob,
        previewEntries = requestedPreviewEntries,
        maxEntries = requestedMaxEntries,
        previewChars = previewChars,
        includeRelationshipHints = includeRelationshipHints,
      ),
      summaryLine = "- inspect_workspace_package ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderTaskObservation(parsed: ParsedToolResult): RenderedObservation {
    val description = parsed.metadata["delegationDescription"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: "Task"
    val subagentType = parsed.metadata["delegationSubagentType"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["subagentType"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: "general-purpose"
    val contextMode = parsed.metadata["delegationContextMode"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["subagentContextMode"]?.trim().takeUnless { it.isNullOrBlank() }
    val executionState = parsed.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["childExecutionStatus"]?.trim()?.lowercase()
      ?: "completed"
    val childTurnCount = parsed.metadata["childTurnCount"]?.trim().takeUnless { it.isNullOrBlank() }
    val childToolCallCount = parsed.metadata["childToolCallCount"]?.trim().takeUnless { it.isNullOrBlank() }
    val headline = parsed.metadata[SubAgentResultMetadataKeys.SUMMARY_HEADLINE]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: parsed.content
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?: "Delegated child result."
    val details = parsed.metadata[SubAgentResultMetadataKeys.SUMMARY_DETAILS]
      ?.lines()
      ?.map(String::trim)
      ?.filter(String::isNotBlank)
      .orEmpty()
    val detailParts = mutableListOf(
      "description=$description",
      "subagent=$subagentType",
      "state=$executionState",
    )
    contextMode?.let { detailParts += "context=$it" }
    childTurnCount?.let { detailParts += "turns=$it" }
    childToolCallCount?.let { detailParts += "tool_calls=$it" }
    val bodyLines = buildList {
      add("Summary: $headline")
      details.forEach { line ->
        add("Detail: $line")
      }
    }
    return RenderedObservation(
      signature = buildTaskSignature(
        description = description,
        subagentType = subagentType,
        headline = headline,
      ),
      summaryLine = "- Task ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = bodyLines.joinToString(separator = "\n"),
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderScheduledTaskCreateObservation(
    parsed: ParsedToolResult,
  ): RenderedObservation {
    val scheduleId = parsed.metadata[ScheduledTaskToolMetadataKeys.SCHEDULE_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val sessionId = parsed.metadata[ScheduledTaskToolMetadataKeys.SESSION_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val triggerKind = parsed.metadata[ScheduledTaskToolMetadataKeys.TRIGGER_KIND]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: "unknown"
    val nextTriggerAtEpochMs = parsed.metadata[ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val enabled = parsed.metadata[ScheduledTaskToolMetadataKeys.ENABLED]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf("trigger=$triggerKind")
    scheduleId?.let { detailParts += "schedule=$it" }
    sessionId?.let { detailParts += "session=$it" }
    enabled?.let { detailParts += "enabled=$it" }
    nextTriggerAtEpochMs?.let { detailParts += "next_at=$it" }
    return RenderedObservation(
      signature = buildScheduledTaskCreateSignature(
        scheduleId = scheduleId,
        sessionId = sessionId,
        triggerKind = triggerKind,
      ),
      summaryLine = "- ScheduledTaskCreate ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderScheduledTaskListObservation(
    parsed: ParsedToolResult,
  ): RenderedObservation {
    val sessionId = parsed.metadata[ScheduledTaskToolMetadataKeys.SESSION_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val enabled = parsed.metadata[ScheduledTaskToolMetadataKeys.ENABLED]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val returnedCount = parsed.metadata[ScheduledTaskToolMetadataKeys.RETURNED_COUNT]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: "unknown"
    val totalCount = parsed.metadata[ScheduledTaskToolMetadataKeys.TOTAL_COUNT]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf("returned=$returnedCount")
    totalCount?.let { detailParts += "total=$it" }
    sessionId?.let { detailParts += "session=$it" }
    enabled?.let { detailParts += "enabled=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildScheduledTaskListSignature(
        sessionId = sessionId,
        enabled = enabled,
        limit = null,
      ),
      summaryLine = "- ScheduledTaskList ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderScheduledTaskGetObservation(
    parsed: ParsedToolResult,
  ): RenderedObservation {
    val scheduleId = parsed.metadata[ScheduledTaskToolMetadataKeys.SCHEDULE_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: "unknown"
    val sessionId = parsed.metadata[ScheduledTaskToolMetadataKeys.SESSION_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val triggerKind = parsed.metadata[ScheduledTaskToolMetadataKeys.TRIGGER_KIND]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: "unknown"
    val enabled = parsed.metadata[ScheduledTaskToolMetadataKeys.ENABLED]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val runCount = parsed.metadata[ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf("schedule=$scheduleId", "trigger=$triggerKind")
    sessionId?.let { detailParts += "session=$it" }
    enabled?.let { detailParts += "enabled=$it" }
    runCount?.let { detailParts += "recent_runs=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildScheduledTaskEntitySignature(
        toolName = "ScheduledTaskGet",
        scheduleId = scheduleId,
        triggerKind = triggerKind,
      ),
      summaryLine = "- ScheduledTaskGet ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderScheduledTaskUpdateObservation(
    parsed: ParsedToolResult,
  ): RenderedObservation {
    val scheduleId = parsed.metadata[ScheduledTaskToolMetadataKeys.SCHEDULE_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val sessionId = parsed.metadata[ScheduledTaskToolMetadataKeys.SESSION_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val triggerKind = parsed.metadata[ScheduledTaskToolMetadataKeys.TRIGGER_KIND]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: "unknown"
    val nextTriggerAtEpochMs = parsed.metadata[ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf("trigger=$triggerKind")
    scheduleId?.let { detailParts += "schedule=$it" }
    sessionId?.let { detailParts += "session=$it" }
    nextTriggerAtEpochMs?.let { detailParts += "next_at=$it" }
    return RenderedObservation(
      signature = buildScheduledTaskEntitySignature(
        toolName = "ScheduledTaskUpdate",
        scheduleId = scheduleId ?: "unknown",
        triggerKind = triggerKind,
      ),
      summaryLine = "- ScheduledTaskUpdate ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderScheduledTaskDeleteObservation(
    parsed: ParsedToolResult,
  ): RenderedObservation {
    val scheduleId = parsed.metadata[ScheduledTaskToolMetadataKeys.SCHEDULE_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: "unknown"
    val sessionId = parsed.metadata[ScheduledTaskToolMetadataKeys.SESSION_ID]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val title = parsed.metadata[ScheduledTaskToolMetadataKeys.TITLE]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf("schedule=$scheduleId")
    sessionId?.let { detailParts += "session=$it" }
    title?.let { detailParts += "title=$it" }
    return RenderedObservation(
      signature = buildScheduledTaskEntitySignature(
        toolName = "ScheduledTaskDelete",
        scheduleId = scheduleId,
        triggerKind = null,
      ),
      summaryLine = "- ScheduledTaskDelete ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderSkillsFindObservation(parsed: ParsedToolResult): RenderedObservation {
    val query = parsed.metadata["query"]?.trim().orEmpty()
    val resultCount = parsed.metadata["resultCount"]?.toIntOrNull()
    val remoteResultCount = parsed.metadata["remoteResultCount"]?.toIntOrNull()
    val localResultCount = parsed.metadata["localResultCount"]?.toIntOrNull()
    val providerName = parsed.metadata["providerName"]?.trim().takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf(
      "query=${query.ifBlank { "<catalog>" }}",
      "results=${resultCount?.toString() ?: "unknown"}",
    )
    providerName?.let { detailParts += "provider=$it" }
    remoteResultCount?.let { detailParts += "remote=$it" }
    localResultCount?.let { detailParts += "local=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsFindSignature(query = query),
      summaryLine = "- SkillsFind ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderSkillsListObservation(parsed: ParsedToolResult): RenderedObservation {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() }
    val skillCount = parsed.metadata["skillCount"]?.toIntOrNull()
    val detailParts = mutableListOf("skills=${skillCount?.toString() ?: "unknown"}")
    path?.let { detailParts += "path=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsListSignature(),
      summaryLine = "- SkillsList ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderSkillsInspectObservation(parsed: ParsedToolResult): RenderedObservation? {
    val sourceRef = parsed.metadata["sourceRef"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val sourceType = parsed.metadata["sourceType"]?.trim().takeUnless { it.isNullOrBlank() }
    val candidateCount = parsed.metadata["candidateCount"]?.toIntOrNull()
    val detailParts = mutableListOf(
      "source_ref=$sourceRef",
      "candidates=${candidateCount?.toString() ?: "unknown"}",
    )
    sourceType?.let { detailParts += "source_type=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsInspectSignature(sourceRef = sourceRef),
      summaryLine = "- SkillsInspect ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal fun RecentToolObservationSupport.renderSkillsCheckObservation(parsed: ParsedToolResult): RenderedObservation {
    val skillId = parsed.metadata["skillId"]?.trim().takeUnless { it.isNullOrBlank() }
    val checkedCount = parsed.metadata["checkedCount"]?.toIntOrNull()
    val updateAvailableCount = parsed.metadata["updateAvailableCount"]?.toIntOrNull()
    val upToDateCount = parsed.metadata["upToDateCount"]?.toIntOrNull()
    val sourceUnavailableCount = parsed.metadata["sourceUnavailableCount"]?.toIntOrNull()
    val unsupportedCount = parsed.metadata["unsupportedCount"]?.toIntOrNull()
    val detailParts = mutableListOf(
      "skill=${skillId ?: "<all>"}",
      "checked=${checkedCount?.toString() ?: "unknown"}",
    )
    updateAvailableCount?.let { detailParts += "updates=$it" }
    upToDateCount?.let { detailParts += "up_to_date=$it" }
    sourceUnavailableCount?.let { detailParts += "source_unavailable=$it" }
    unsupportedCount?.let { detailParts += "unsupported=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsCheckSignature(skillId = skillId),
      summaryLine = "- SkillsCheck ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

internal data class ParsedReplayControlEvent(
  val type: String,
  val fields: Map<String, String>,
) {
  val runId: String?
    get() = fields["run_id"]?.trim()?.takeIf(String::isNotBlank)

  val toolName: String?
    get() = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
}

internal data class RenderedObservation(
  val signature: String,
  val summaryLine: String,
  val body: String,
)

internal data class RenderedWorkingStateAction(
  val signature: String,
  val entry: WorkingStateEntry,
)

internal const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
internal const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
