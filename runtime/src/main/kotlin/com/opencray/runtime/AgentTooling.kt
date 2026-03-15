package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.filesystem.FileMutationOperation
import com.opencray.filesystem.FileOpsService
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpRuntimeSupport
import com.opencray.mcp.McpToolExposure
import com.opencray.policy.ExecutionMode
import com.opencray.policy.ModePolicy
import com.opencray.policy.PolicyRequest
import com.opencray.policy.PolicyToolClass
import com.opencray.skills.SkillLoadReport
import com.opencray.skills.SkillLoader
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentToolParameter(
  val name: String,
  val type: String,
  val required: Boolean,
  val description: String,
) {
  init {
    require(name.isNotBlank()) { "AgentToolParameter name must not be blank." }
    require(type.isNotBlank()) { "AgentToolParameter type must not be blank." }
    require(description.isNotBlank()) { "AgentToolParameter description must not be blank." }
  }
}

data class AgentToolDefinition(
  val name: String,
  val description: String,
  val parameters: List<AgentToolParameter> = emptyList(),
) {
  init {
    require(name.isNotBlank()) { "AgentToolDefinition name must not be blank." }
    require(description.isNotBlank()) { "AgentToolDefinition description must not be blank." }
  }

  fun renderForPrompt(): String {
    val parameterText = if (parameters.isEmpty()) {
      "no arguments"
    } else {
      parameters.joinToString(separator = "; ") { parameter ->
        val requirement = if (parameter.required) "required" else "optional"
        "${parameter.name}:${parameter.type} ($requirement) ${parameter.description}"
      }
    }
    return "- $name: $description. Args: $parameterText."
  }
}

data class AgentToolCall(
  val toolName: String,
  val arguments: JsonObject = JsonObject(emptyMap()),
  val reason: String? = null,
) {
  init {
    require(toolName.isNotBlank()) { "AgentToolCall toolName must not be blank." }
  }
}

enum class AgentToolResultStatus {
  SUCCESS,
  FAILED,
  DENIED,
  CANCELLED,
  TIMEOUT,
}

data class AgentToolResult(
  val toolName: String,
  val status: AgentToolResultStatus,
  val content: String,
  val exitCode: Int? = null,
  val stdout: String = "",
  val stderr: String = "",
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(toolName.isNotBlank()) { "AgentToolResult toolName must not be blank." }
    require(content.isNotBlank()) { "AgentToolResult content must not be blank." }
  }

  fun toObservationText(json: Json): String = json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
      put("tool_name", toolName)
      put("status", status.name.lowercase())
      put("content", content)
      exitCode?.let { put("exit_code", it) }
      if (stdout.isNotBlank()) {
        put("stdout", stdout)
      }
      if (stderr.isNotBlank()) {
        put("stderr", stderr)
      }
      errorCode?.let { put("error_code", it) }
      errorMessage?.let { put("error_message", it) }
      put(
        "metadata",
        buildJsonObject {
          metadata.toSortedMap().forEach { (key, value) -> put(key, value) }
        },
      )
    },
  )
}

data class OpenCrayToolDispatcherConfig(
  val workspaceRoots: Set<Path>,
  val skillsRoots: List<File> = emptyList(),
  val mcpExposureReport: McpClientExposureReport? = null,
  val modePolicy: ModePolicy = ModePolicy(),
  val approvedTaskId: String? = null,
  val approvedToolName: String? = null,
  val commandExecutor: CommandExecutor? = null,
  val pythonRuntimeAdapter: PythonRuntimeAdapter = PythonRuntimeAdapter(),
  val commandApprovalToken: CommandApprovalToken? = null,
  val todoStore: AgentTodoStore = InMemoryAgentTodoStore(),
  val maxReadBytes: Int = 32_000,
  val maxDirectoryEntries: Int = 200,
  val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
  init {
    require(workspaceRoots.isNotEmpty()) { "OpenCrayToolDispatcherConfig workspaceRoots must not be empty." }
    require(maxReadBytes > 0) { "OpenCrayToolDispatcherConfig maxReadBytes must be > 0." }
    require(maxDirectoryEntries > 0) { "OpenCrayToolDispatcherConfig maxDirectoryEntries must be > 0." }
  }
}

class OpenCrayToolDispatcher(
  private val config: OpenCrayToolDispatcherConfig,
) {
  private val boundary = WorkspaceBoundary(config.workspaceRoots)
  private val fileOpsService = FileOpsService(boundary.approvedRoots())
  private val todoStore = config.todoStore
  private val commandExecutor = config.commandExecutor ?: CommandExecutor(
    config = CommandExecutionConfig(
      approvedWorkingDirectories = boundary.approvedRoots(),
    ),
  )

  fun definitions(): List<AgentToolDefinition> {
    val canonicalDefinitions = listOf(
      AgentToolDefinition(
        name = "LS",
        description = "List files and directories under the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = false, description = "Directory path relative to the workspace root. Defaults to the workspace root."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of entries to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Read",
        description = "Read a text file from the approved workspace. Supports optional 1-based line offsets and limits.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("offset", "number", required = false, description = "1-based starting line number."),
          AgentToolParameter("limit", "number", required = false, description = "Maximum number of lines to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Write",
        description = "Create or overwrite a text file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("content", "string", required = true, description = "Full UTF-8 text content to write."),
        ),
      ),
      AgentToolDefinition(
        name = "Grep",
        description = "Search workspace text files with a regular expression and return matching lines.",
        parameters = listOf(
          AgentToolParameter("pattern", "string", required = true, description = "Regular expression pattern to search for."),
          AgentToolParameter("path", "string", required = false, description = "Optional file or directory path relative to the workspace root."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to relative file paths."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of matching lines to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Glob",
        description = "Recursively match workspace paths with a glob pattern.",
        parameters = listOf(
          AgentToolParameter("pattern", "string", required = true, description = "Glob pattern to match against workspace-relative paths."),
          AgentToolParameter("path", "string", required = false, description = "Optional file or directory path relative to the workspace root."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of matching paths to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Edit",
        description = "Apply an exact string replacement to one existing text file. Fails if the target text is missing or ambiguous unless replace_all is true.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("old_string", "string", required = true, description = "Exact text to replace."),
          AgentToolParameter("new_string", "string", required = true, description = "Replacement text."),
          AgentToolParameter("replace_all", "boolean", required = false, description = "Replace every match instead of requiring a unique match."),
        ),
      ),
      AgentToolDefinition(
        name = "MultiEdit",
        description = "Apply multiple exact string replacements to one existing text file atomically.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("edits", "object[]", required = true, description = "Array of edit objects with old_string, new_string, and optional replace_all."),
        ),
      ),
      AgentToolDefinition(
        name = "TodoWrite",
        description = "Read or replace the current chat session's in-memory todo list. Omit todos to inspect the current list; provide todos to replace it.",
        parameters = listOf(
          AgentToolParameter("todos", "object[]", required = false, description = "Array of todo objects with content, status, and optional activeForm."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_list_files",
        description = "List files under the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = false, description = "Directory path relative to the workspace root."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of entries to return."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_read_file",
        description = "Read a text file from the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "File path relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_write_file",
        description = "Create or overwrite a text file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("content", "string", required = true, description = "Full UTF-8 text content to write."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_move_file",
        description = "Move or rename a file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("source_path", "string", required = true, description = "Existing file path relative to the workspace root."),
          AgentToolParameter("destination_path", "string", required = true, description = "New file path relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_delete_file",
        description = "Delete a file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "File path relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "command_exec",
        description = "Execute a local command inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = true, description = "Executable name or command path."),
          AgentToolParameter("args", "string[]", required = false, description = "Command arguments."),
          AgentToolParameter("working_directory", "string", required = false, description = "Directory relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "python_exec",
        description = "Execute a workspace-local Python script through the Python runtime adapter. Today this shells out to a local runner process, so it follows execute-command policy gates.",
        parameters = listOf(
          AgentToolParameter("script_path", "string", required = true, description = "Script path relative to the workspace root."),
          AgentToolParameter("args", "string[]", required = false, description = "Script arguments."),
        ),
      ),
      AgentToolDefinition(
        name = "skills_list",
        description = "List discovered skills from configured skills roots.",
      ),
      AgentToolDefinition(
        name = "skill_read",
        description = "Read one discovered skill's metadata and markdown body.",
        parameters = listOf(
          AgentToolParameter("name", "string", required = true, description = "Exact skill name."),
        ),
      ),
      AgentToolDefinition(
        name = "mcp_list_servers",
        description = "Inspect currently exposed MCP servers and their trust state. This runtime does not proxy remote MCP tools yet.",
      ),
    )
    val definitionsByName = canonicalDefinitions.associateBy(AgentToolDefinition::name)
    val aliasDefinitions = TOOL_ALIASES.mapNotNull { (aliasName, canonicalName) ->
      val canonicalDefinition = definitionsByName[canonicalName] ?: return@mapNotNull null
      AgentToolDefinition(
        name = aliasName,
        description = aliasDescriptionFor(canonicalDefinition),
        parameters = canonicalDefinition.parameters,
      )
    }
    return canonicalDefinitions + aliasDefinitions
  }

  fun dispatch(
    task: AgentTask,
    call: AgentToolCall,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val requestedToolName = call.toolName
    val canonicalToolName = canonicalToolName(requestedToolName)
    return try {
      val result = when (canonicalToolName) {
        "workspace_list_files" -> listWorkspaceFiles(call.arguments)
        "workspace_read_file" -> readWorkspaceFile(call.arguments)
        "workspace_write_file" -> writeWorkspaceFile(task = task, arguments = call.arguments)
        "workspace_move_file" -> moveWorkspaceFile(task = task, arguments = call.arguments)
        "workspace_delete_file" -> deleteWorkspaceFile(task = task, arguments = call.arguments)
        "LS" -> listFilesForClaude(arguments = call.arguments)
        "Read" -> readFileForClaude(arguments = call.arguments)
        "Write" -> writeFileForClaude(task = task, arguments = call.arguments)
        "Grep" -> grepWorkspace(arguments = call.arguments)
        "Glob" -> globWorkspace(arguments = call.arguments)
        "Edit" -> editWorkspaceFile(task = task, arguments = call.arguments)
        "MultiEdit" -> multiEditWorkspaceFile(task = task, arguments = call.arguments)
        "TodoWrite" -> writeTodoList(arguments = call.arguments)
        "command_exec" -> executeCommand(task = task, arguments = call.arguments, hooks = hooks)
        "python_exec" -> executePython(task = task, arguments = call.arguments)
        "skills_list" -> listSkills()
        "skill_read" -> readSkill(call.arguments)
        "mcp_list_servers" -> listMcpServers()
        else -> AgentToolResult(
          toolName = requestedToolName,
          status = AgentToolResultStatus.FAILED,
          content = "Tool '$requestedToolName' is not registered.",
          errorCode = "TOOL_NOT_FOUND",
        )
      }
      result.relabelForAlias(requestedToolName = requestedToolName, canonicalToolName = canonicalToolName)
    } catch (error: Throwable) {
      AgentToolResult(
        toolName = requestedToolName,
        status = AgentToolResultStatus.FAILED,
        content = error.message ?: "$requestedToolName failed.",
        errorCode = "TOOL_EXECUTION_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  private fun listWorkspaceFiles(arguments: JsonObject): AgentToolResult {
    val directory = boundary.ensureDirectory(
      candidate = arguments.optionalString("path"),
      label = "workspace list",
      defaultToRoot = true,
    )
    val maxEntries = arguments.optionalInt("max_entries")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries

    val entries = Files.list(directory).use { stream ->
      val collected = mutableListOf<Path>()
      val iterator = stream.sorted().iterator()
      while (iterator.hasNext() && collected.size < maxEntries) {
        collected.add(iterator.next())
      }
      collected
    }
    val rendered = if (entries.isEmpty()) {
      "Directory is empty."
    } else {
      entries.joinToString(separator = "\n") { entry ->
        val relative = boundary.defaultRoot.relativize(entry).toString().ifBlank { "." }
        val kind = if (Files.isDirectory(entry)) "dir" else "file"
        "$kind\t$relative"
      }
    }
    return AgentToolResult(
      toolName = "workspace_list_files",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = mapOf(
        "path" to boundary.defaultRoot.relativize(directory).toString().ifBlank { "." },
        "entryCount" to entries.size.toString(),
      ),
    )
  }

  private fun readWorkspaceFile(arguments: JsonObject): AgentToolResult {
    val file = boundary.ensureFile(arguments.requiredString("path"), label = "workspace read")
    val bytes = Files.readAllBytes(file)
    val truncated = bytes.size > config.maxReadBytes
    val body = bytes.toString(StandardCharsets.UTF_8)
      .take(config.maxReadBytes)
      .ifBlank { "<empty file>" }
    return AgentToolResult(
      toolName = "workspace_read_file",
      status = AgentToolResultStatus.SUCCESS,
      content = body,
      metadata = mapOf(
        "path" to boundary.defaultRoot.relativize(file).toString(),
        "byteCount" to bytes.size.toString(),
        "truncated" to truncated.toString(),
      ),
    )
  }

  private fun writeWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = boundary.resolve(arguments.requiredString("path"), label = "workspace write", defaultToRoot = false)
    return writeTextFile(
      task = task,
      toolName = "workspace_write_file",
      path = path,
      content = arguments.requiredText("content"),
      metadataPathKey = "path",
      successMessage = "Wrote ${boundary.defaultRoot.relativize(path)} successfully.",
    )
  }

  private fun listFilesForClaude(arguments: JsonObject): AgentToolResult {
    val directory = boundary.ensureDirectory(
      candidate = arguments.optionalString("path"),
      label = "LS",
      defaultToRoot = true,
    )
    val maxEntries = arguments.optionalInt("max_entries")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val entries = Files.list(directory).use { stream ->
      val collected = mutableListOf<Path>()
      val iterator = stream.sorted().iterator()
      while (iterator.hasNext() && collected.size < maxEntries) {
        collected.add(iterator.next())
      }
      collected
    }
    val rendered = if (entries.isEmpty()) {
      "Directory is empty."
    } else {
      entries.joinToString(separator = "\n") { entry ->
        val kind = if (Files.isDirectory(entry)) "dir" else "file"
        "$kind\t${displayPathForModel(entry)}"
      }
    }
    return AgentToolResult(
      toolName = "LS",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = mapOf(
        "path" to displayPathForModel(directory),
        "entryCount" to entries.size.toString(),
      ),
    )
  }

  private fun readFileForClaude(arguments: JsonObject): AgentToolResult {
    val file = boundary.ensureFile(arguments.requiredStringFrom("file_path", "path"), label = "Read")
    val offset = arguments.optionalInt("offset") ?: 1
    require(offset >= 1) { "Read offset must be >= 1." }
    val limit = arguments.optionalInt("limit")
    require(limit == null || limit >= 1) { "Read limit must be >= 1 when provided." }

    val bytes = Files.readAllBytes(file)
    val fullBody = bytes.toString(StandardCharsets.UTF_8)
    val lines = splitLines(fullBody)
    val startIndex = (offset - 1).coerceAtMost(lines.size)
    val selectedLines = if (limit == null) {
      lines.drop(startIndex)
    } else {
      lines.drop(startIndex).take(limit)
    }
    val rawContent = when {
      fullBody.isEmpty() -> "<empty file>"
      selectedLines.isEmpty() -> "Requested line range is empty."
      else -> selectedLines.joinToString(separator = "\n")
    }
    val (body, truncated) = truncateToReadBudget(rawContent)
    val returnedLineCount = if (fullBody.isEmpty()) 0 else selectedLines.size
    return AgentToolResult(
      toolName = "Read",
      status = AgentToolResultStatus.SUCCESS,
      content = body,
      metadata = mapOf(
        "filePath" to displayPathForModel(file),
        "byteCount" to bytes.size.toString(),
        "totalLineCount" to lines.size.toString(),
        "offset" to offset.toString(),
        "returnedLineCount" to returnedLineCount.toString(),
        "truncated" to truncated.toString(),
      ) + (limit?.let { mapOf("limit" to it.toString()) } ?: emptyMap()),
    )
  }

  private fun writeFileForClaude(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = boundary.resolve(arguments.requiredStringFrom("file_path", "path"), label = "Write", defaultToRoot = false)
    return writeTextFile(
      task = task,
      toolName = "Write",
      path = path,
      content = arguments.requiredText("content"),
      metadataPathKey = "filePath",
      successMessage = "Wrote ${displayPathForModel(path)} successfully.",
    )
  }

  private fun grepWorkspace(arguments: JsonObject): AgentToolResult {
    val pattern = arguments.requiredString("pattern")
    val regex = runCatching { Regex(pattern) }
      .getOrElse { error -> throw IllegalArgumentException("Invalid Grep pattern: ${error.message}") }
    val searchRoot = resolveSearchRoot(arguments.optionalString("path"), label = "Grep path")
    val globMatcher = arguments.optionalString("glob")?.let(::compileGlobMatcher)
    val maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val matches = mutableListOf<String>()

    for (file in collectRegularFiles(searchRoot)) {
      if (globMatcher != null && !globMatcher.matches(displayPathForModel(file))) {
        continue
      }
      val fileMatches: List<String> = runCatching {
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
          val collected = mutableListOf<String>()
          var lineNumber = 0
          while (true) {
            val line = reader.readLine() ?: break
            lineNumber += 1
            if (regex.containsMatchIn(line)) {
              collected.add("${displayPathForModel(file)}:$lineNumber:$line")
              if (collected.size >= maxResults - matches.size) {
                break
              }
            }
          }
          collected
        }
      }.getOrElse { emptyList() }
      matches.addAll(fileMatches)
      if (matches.size >= maxResults) {
        break
      }
    }

    return AgentToolResult(
      toolName = "Grep",
      status = AgentToolResultStatus.SUCCESS,
      content = matches.joinToString(separator = "\n").ifBlank { "No matches found." },
      metadata = mapOf(
        "path" to displayPathForModel(searchRoot),
        "pattern" to pattern,
        "matchCount" to matches.size.toString(),
      ) + (arguments.optionalString("glob")?.let { mapOf("glob" to it) } ?: emptyMap()),
    )
  }

  private fun globWorkspace(arguments: JsonObject): AgentToolResult {
    val matcher = compileGlobMatcher(arguments.requiredString("pattern"))
    val searchRoot = resolveSearchRoot(arguments.optionalString("path"), label = "Glob path")
    val maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val matches = mutableListOf<String>()

    for (candidate in collectSearchCandidates(searchRoot)) {
      if (matcher.matches(displayPathForModel(candidate))) {
        matches.add(displayPathForModel(candidate))
      }
      if (matches.size >= maxResults) {
        break
      }
    }

    return AgentToolResult(
      toolName = "Glob",
      status = AgentToolResultStatus.SUCCESS,
      content = matches.joinToString(separator = "\n").ifBlank { "No matches found." },
      metadata = mapOf(
        "path" to displayPathForModel(searchRoot),
        "matchCount" to matches.size.toString(),
      ),
    )
  }

  private fun editWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = boundary.ensureFile(arguments.requiredStringFrom("file_path", "path"), label = "Edit")
    val edit = TextEdit(
      oldString = arguments.requiredString("old_string"),
      newString = arguments.requiredText("new_string"),
      replaceAll = arguments.optionalBoolean("replace_all") ?: false,
    )
    val source = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
    val outcome = applyTextEdits(source, listOf(edit))
    return writeTextFile(
      task = task,
      toolName = "Edit",
      path = file,
      content = outcome.content,
      metadataPathKey = "filePath",
      successMessage = "Updated ${displayPathForModel(file)} with ${outcome.replacementCount} replacement(s).",
      extraMetadata = mapOf("replacementCount" to outcome.replacementCount.toString()),
    )
  }

  private fun multiEditWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = boundary.ensureFile(arguments.requiredStringFrom("file_path", "path"), label = "MultiEdit")
    val edits = arguments.requiredObjectArray("edits").mapIndexed { index, entry ->
      TextEdit(
        oldString = entry.requiredString("old_string"),
        newString = entry.requiredText("new_string"),
        replaceAll = entry.optionalBoolean("replace_all") ?: false,
      ).also {
        require(it.oldString.isNotEmpty()) { "MultiEdit edit ${index + 1} old_string must not be empty." }
      }
    }
    val source = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
    val outcome = applyTextEdits(source, edits)
    return writeTextFile(
      task = task,
      toolName = "MultiEdit",
      path = file,
      content = outcome.content,
      metadataPathKey = "filePath",
      successMessage = "Updated ${displayPathForModel(file)} with ${outcome.replacementCount} replacement(s) across ${edits.size} edit(s).",
      extraMetadata = mapOf(
        "replacementCount" to outcome.replacementCount.toString(),
        "editCount" to edits.size.toString(),
      ),
    )
  }

  private fun writeTodoList(arguments: JsonObject): AgentToolResult {
    val todos = arguments.optionalObjectArray("todos")?.mapIndexed { index, entry ->
      val content = entry.requiredString("content")
      val status = AgentTodoStatus.fromLabelOrNull(entry.requiredString("status"))
        ?: throw IllegalArgumentException("TodoWrite todo ${index + 1} status is invalid.")
      AgentTodoEntry(
        content = content,
        status = status,
        activeForm = entry.optionalStringFrom("activeForm", "active_form"),
      )
    }
    if (todos != null) {
      todoStore.replaceAll(todos)
    }
    val snapshot = todoStore.snapshot()
    val rendered = if (snapshot.isEmpty()) {
      "Todo list is empty."
    } else {
      snapshot.joinToString(separator = "\n") { todo ->
        val statusLabel = when (todo.status) {
          AgentTodoStatus.PENDING -> "[pending]"
          AgentTodoStatus.IN_PROGRESS -> "[in_progress]"
          AgentTodoStatus.COMPLETED -> "[completed]"
        }
        val activeSuffix = todo.activeForm
          ?.takeIf(String::isNotBlank)
          ?.let { " | active: $it" }
          .orEmpty()
        "$statusLabel ${todo.content}$activeSuffix"
      }
    }
    return AgentToolResult(
      toolName = "TodoWrite",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = mapOf(
        "todoCount" to snapshot.size.toString(),
        "mutated" to (todos != null).toString(),
      ),
    )
  }

  private fun moveWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val source = boundary.resolve(arguments.requiredString("source_path"), label = "workspace move source", defaultToRoot = false)
    val destination = boundary.resolve(
      arguments.requiredString("destination_path"),
      label = "workspace move destination",
      defaultToRoot = false,
    )
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.MOVE_FILE,
      targetPath = source,
      destinationPath = destination,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "workspace_move_file",
      policyDecision = policyDecision,
    )
    gateFileMutation(
      task = task,
      toolName = "workspace_move_file",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf(
        "sourcePath" to boundary.defaultRoot.relativize(source).toString(),
        "destinationPath" to boundary.defaultRoot.relativize(destination).toString(),
      ),
    )?.let { return it }
    fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Move(
          sourcePath = source,
          destinationPath = destination,
        ),
      ),
    )
    return AgentToolResult(
      toolName = "workspace_move_file",
      status = AgentToolResultStatus.SUCCESS,
      content = "Moved ${boundary.defaultRoot.relativize(source)} to ${boundary.defaultRoot.relativize(destination)}.",
      metadata = mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "sourcePath" to boundary.defaultRoot.relativize(source).toString(),
        "destinationPath" to boundary.defaultRoot.relativize(destination).toString(),
      ),
    )
  }

  private fun deleteWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = boundary.resolve(arguments.requiredString("path"), label = "workspace delete", defaultToRoot = false)
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.DELETE_FILE,
      targetPath = path,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "workspace_delete_file",
      policyDecision = policyDecision,
    )
    gateFileMutation(
      task = task,
      toolName = "workspace_delete_file",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf("path" to boundary.defaultRoot.relativize(path).toString()),
    )?.let { return it }
    fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Delete(path = path),
      ),
    )
    return AgentToolResult(
      toolName = "workspace_delete_file",
      status = AgentToolResultStatus.SUCCESS,
      content = "Deleted ${boundary.defaultRoot.relativize(path)}.",
      metadata = mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "path" to boundary.defaultRoot.relativize(path).toString(),
      ),
    )
  }

  private fun writeTextFile(
    task: AgentTask,
    toolName: String,
    path: Path,
    content: String,
    metadataPathKey: String,
    successMessage: String,
    extraMetadata: Map<String, String> = emptyMap(),
  ): AgentToolResult {
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.WRITE_FILE,
      targetPath = path,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = toolName,
      policyDecision = policyDecision,
    )
    gateFileMutation(
      task = task,
      toolName = toolName,
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf(metadataPathKey to pathMetadataValue(toolName = toolName, path = path)),
    )?.let { return it }
    val batchResult = fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Write(
          path = path,
          content = content,
        ),
      ),
    )
    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = successMessage,
      metadata = mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        metadataPathKey to pathMetadataValue(toolName = toolName, path = path),
        "checkpointId" to batchResult.checkpointId,
        "checkpointEntryCount" to batchResult.checkpointEntryCount.toString(),
      ) + extraMetadata,
    )
  }

  private fun pathMetadataValue(toolName: String, path: Path): String = when (toolName) {
    "LS",
    "Read",
    "Write",
    "Grep",
    "Glob",
    "Edit",
    "MultiEdit",
    "TodoWrite" -> displayPathForModel(path)
    else -> boundary.defaultRoot.relativize(path).toString().ifBlank { "." }
  }

  private fun truncateToReadBudget(text: String): Pair<String, Boolean> {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= config.maxReadBytes) {
      return text to false
    }
    return bytes.copyOf(config.maxReadBytes).toString(StandardCharsets.UTF_8) to true
  }

  private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) {
      return emptyList()
    }
    return text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split('\n')
  }

  private fun collectSearchCandidates(root: Path): List<Path> {
    if (!Files.isDirectory(root)) {
      return listOf(root)
    }
    return Files.walk(root).use { stream ->
      val collected = mutableListOf<Path>()
      val iterator = stream.sorted().iterator()
      while (iterator.hasNext()) {
        val candidate = iterator.next()
        if (candidate != root) {
          collected.add(candidate)
        }
      }
      collected
    }
  }

  private fun collectRegularFiles(root: Path): List<Path> {
    if (!Files.isDirectory(root)) {
      return if (Files.isRegularFile(root)) listOf(root) else emptyList()
    }
    return Files.walk(root).use { stream ->
      val collected = mutableListOf<Path>()
      val iterator = stream.sorted().iterator()
      while (iterator.hasNext()) {
        val candidate = iterator.next()
        if (Files.isRegularFile(candidate)) {
          collected.add(candidate)
        }
      }
      collected
    }
  }

  private fun resolveSearchRoot(candidate: String?, label: String): Path {
    val resolved = boundary.resolve(candidate = candidate, label = label, defaultToRoot = true)
    require(Files.exists(resolved)) { "$label does not exist: $resolved" }
    require(Files.isDirectory(resolved) || Files.isRegularFile(resolved)) { "$label is not a file or directory: $resolved" }
    return resolved
  }

  private fun compileGlobMatcher(pattern: String): Regex =
    Regex("^${globPatternToRegex(normalizeGlobPattern(pattern))}$")

  private fun normalizeGlobPattern(pattern: String): String = pattern.replace('\\', '/')

  private fun globPatternToRegex(pattern: String): String {
    val regex = StringBuilder()
    var index = 0
    while (index < pattern.length) {
      val current = pattern[index]
      when (current) {
        '*' -> {
          val isDoubleStar = index + 1 < pattern.length && pattern[index + 1] == '*'
          if (isDoubleStar) {
            val consumesSlash = index + 2 < pattern.length && pattern[index + 2] == '/'
            regex.append(if (consumesSlash) "(?:.*/)?" else ".*")
            index += if (consumesSlash) 3 else 2
          } else {
            regex.append("[^/]*")
            index += 1
          }
        }

        '?' -> {
          regex.append("[^/]")
          index += 1
        }

        '/', '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> {
          regex.append("\\").append(current)
          index += 1
        }

        else -> {
          regex.append(current)
          index += 1
        }
      }
    }
    return regex.toString()
  }

  private fun displayPathForModel(path: Path): String =
    boundary.defaultRoot.relativize(path).toString().ifBlank { "." }.replace(File.separatorChar, '/')

  private fun applyTextEdits(
    source: String,
    edits: List<TextEdit>,
  ): TextEditOutcome {
    var current = source
    var replacementCount = 0
    edits.forEachIndexed { index, edit ->
      require(edit.oldString.isNotEmpty()) { "Edit ${index + 1} old_string must not be empty." }
      val matchCount = countOccurrences(current, edit.oldString)
      require(matchCount > 0) { "Edit ${index + 1} old_string was not found in the target file." }
      require(matchCount == 1 || edit.replaceAll) {
        "Edit ${index + 1} old_string is ambiguous; found $matchCount matches. Set replace_all=true to replace every match."
      }
      current = if (edit.replaceAll) {
        current.replace(edit.oldString, edit.newString)
      } else {
        current.replaceFirst(edit.oldString, edit.newString)
      }
      replacementCount += if (edit.replaceAll) matchCount else 1
    }
    return TextEditOutcome(content = current, replacementCount = replacementCount)
  }

  private fun countOccurrences(text: String, target: String): Int {
    if (target.isEmpty()) {
      return 0
    }
    var count = 0
    var index = text.indexOf(target)
    while (index >= 0) {
      count += 1
      index = text.indexOf(target, startIndex = index + target.length)
    }
    return count
  }

  private fun executeCommand(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val command = arguments.requiredString("command")
    val workingDirectory = boundary.resolve(
      candidate = arguments.optionalString("working_directory"),
      label = "command working directory",
      defaultToRoot = true,
    )
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.EXECUTE_COMMAND,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "command_exec",
      policyDecision = policyDecision,
    )
    val executionResult = commandExecutor.execute(
      request = CommandExecutionRequest(
        taskId = task.id,
        command = command,
        args = arguments.optionalStringArray("args"),
        workingDirectory = workingDirectory.toString(),
        requestedAtEpochMs = System.currentTimeMillis(),
        metadata = mapOf(
          "toolName" to "command_exec",
          "executionMode" to inferExecutionMode(task).name,
          "policyReasonCode" to effectivePolicyDecision.reasonCode,
        ) + approvalRiskMetadata(effectivePolicyDecision),
      ),
      policyDecision = effectivePolicyDecision,
      approvalToken = config.commandApprovalToken,
      hooks = hooks,
    )
    return executionResult.toAgentToolResult(toolName = "command_exec")
  }

  private fun executePython(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val scriptPath = boundary.resolve(arguments.requiredString("script_path"), label = "python script", defaultToRoot = false)
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.EXECUTE_COMMAND,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "python_exec",
      policyDecision = policyDecision,
    )
    gatePolicyControlledTool(
      task = task,
      toolName = "python_exec",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf("scriptPath" to boundary.defaultRoot.relativize(scriptPath).toString()),
      askDetail = "Approval is required before python_exec can run.",
      denyDetail = "Policy denied python_exec.",
    )?.let { return it }
    val executionResult = config.pythonRuntimeAdapter.exec(
      request = PythonExecRequest(
        taskId = task.id,
        workspaceRoot = boundary.defaultRoot,
        scriptPath = scriptPath,
        args = arguments.optionalStringArray("args"),
      ),
    )
    val toolResult = executionResult.toAgentToolResult(toolName = "python_exec")
    return toolResult.copy(
      metadata = toolResult.metadata + mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "scriptPath" to boundary.defaultRoot.relativize(scriptPath).toString(),
      ) + approvalRiskMetadata(effectivePolicyDecision),
    )
  }

  private fun listSkills(): AgentToolResult {
    val report = loadSkillsReport()
    val content = if (report == null || report.loadedSkills.isEmpty()) {
      "No skills discovered."
    } else {
      report.loadedSkills.joinToString(separator = "\n") { skill ->
        val allowedTools = skill.metadata.skillSpec.allowedTools.joinToString(separator = ",").ifBlank { "none" }
        "${skill.name}\t${skill.metadata.invocationControl}\ttools=$allowedTools"
      }
    }
    return AgentToolResult(
      toolName = "skills_list",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = mapOf("skillCount" to (report?.loadedSkills?.size ?: 0).toString()),
    )
  }

  private fun readSkill(arguments: JsonObject): AgentToolResult {
    val skillName = arguments.requiredString("name")
    val report = loadSkillsReport()
    val loadedSkill = report?.registry?.get(skillName)
      ?: return AgentToolResult(
        toolName = "skill_read",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$skillName' was not found.",
        errorCode = "SKILL_NOT_FOUND",
      )

    return AgentToolResult(
      toolName = "skill_read",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("name: ${loadedSkill.name}")
        appendLine("description: ${loadedSkill.metadata.skillSpec.description}")
        appendLine("invocation_control: ${loadedSkill.metadata.invocationControl}")
        appendLine("allowed_tools: ${loadedSkill.metadata.skillSpec.allowedTools.joinToString(separator = ",")}")
        appendLine("---")
        append(loadedSkill.document.markdownBody.ifBlank { "<empty body>" })
      },
      metadata = mapOf(
        "skillName" to loadedSkill.name,
        "relativePath" to loadedSkill.source.relativePath,
      ),
    )
  }

  private fun listMcpServers(): AgentToolResult {
    val report = config.mcpExposureReport
    val lines = buildList {
      add(McpRuntimeSupport.bridgeSummary())
      report?.activeClients?.forEach { client ->
        add("active\t${client.id}\t${client.displayName}\t${client.transport::class.simpleName}\t${client.trustState}")
      }
      report?.blockedClients?.forEach { client ->
        add("blocked\t${client.id}\t${client.displayName}\t${client.transport::class.simpleName}\t${client.blockReason}")
      }
    }
    return AgentToolResult(
      toolName = "mcp_list_servers",
      status = AgentToolResultStatus.SUCCESS,
      content = lines.joinToString(separator = "\n").ifBlank { "No MCP servers exposed." },
      metadata = mapOf(
        "activeCount" to (report?.activeClients?.size ?: 0).toString(),
        "blockedCount" to (report?.blockedClients?.size ?: 0).toString(),
        "bridgeStatus" to McpRuntimeSupport.BRIDGE_STATUS_EXPOSURE_ONLY,
        "remoteToolBridgeAvailable" to McpRuntimeSupport.REMOTE_TOOL_BRIDGE_AVAILABLE.toString(),
        "supportedAgentTools" to McpRuntimeSupport.SUPPORTED_AGENT_TOOL_NAMES.sorted().joinToString(separator = ","),
        "toolExposure" to (
          report?.activeClients?.firstOrNull()?.toolExposure
            ?: report?.blockedClients?.firstOrNull()?.toolExposure
            ?: McpToolExposure.BLOCKED
          ).name,
      ),
    )
  }

  private fun loadSkillsReport(): SkillLoadReport? {
    if (config.skillsRoots.isEmpty()) {
      return null
    }
    return SkillLoader.load(config.skillsRoots)
  }

  private fun policyDecisionFor(
    task: AgentTask,
    toolClass: PolicyToolClass,
    targetPath: Path? = null,
    destinationPath: Path? = null,
  ): PolicyDecision {
    val fineGrainedDecision = config.modePolicy.decide(
      PolicyRequest(
        mode = inferExecutionMode(task),
        toolClass = toolClass,
        workspaceRoot = boundary.defaultRoot,
        targetPath = targetPath,
        destinationPath = destinationPath,
      ),
    )
    val mergedDecision = mergePolicyDecisions(
      coarseDecision = task.policyDecision,
      fineGrainedDecision = fineGrainedDecision,
    )
    return applyApprovedTaskOverride(task = task, policyDecision = mergedDecision)
  }

  private fun inferExecutionMode(task: AgentTask): ExecutionMode =
    listOf("executionMode", "chatMode", "mode", "modeLabel")
      .firstNotNullOfOrNull { key -> ExecutionMode.fromLabelOrNull(task.metadata[key]) }
      ?: ExecutionMode.AUTO

  private fun mergePolicyDecisions(
    coarseDecision: PolicyDecision,
    fineGrainedDecision: PolicyDecision,
  ): PolicyDecision {
    val coarseRank = policyRank(coarseDecision.outcome)
    val fineRank = policyRank(fineGrainedDecision.outcome)
    val winningDecision = when {
      fineRank > coarseRank -> fineGrainedDecision
      coarseRank > fineRank -> coarseDecision
      coarseDecision.outcome == PolicyDecisionOutcome.ASK &&
        fineGrainedDecision.outcome == PolicyDecisionOutcome.ASK -> when {
          approvalRiskRank(fineGrainedDecision.approvalRisk) > approvalRiskRank(coarseDecision.approvalRisk) -> fineGrainedDecision
          approvalRiskRank(coarseDecision.approvalRisk) > approvalRiskRank(fineGrainedDecision.approvalRisk) -> coarseDecision
          else -> fineGrainedDecision
        }
      else -> fineGrainedDecision
    }
    return winningDecision.copy(
      detail = winningDecision.detail ?: coarseDecision.detail ?: fineGrainedDecision.detail,
    )
  }

  private fun policyRank(outcome: PolicyDecisionOutcome): Int = when (outcome) {
    PolicyDecisionOutcome.ALLOW -> 0
    PolicyDecisionOutcome.ASK -> 1
    PolicyDecisionOutcome.DENY -> 2
  }

  private fun approvalRiskRank(approvalRisk: PolicyApprovalRisk): Int = when (approvalRisk) {
    PolicyApprovalRisk.STANDARD -> 0
    PolicyApprovalRisk.HIGH_RISK -> 1
  }

  private fun applyApprovedTaskOverride(
    task: AgentTask,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    if (!config.approvedToolName.isNullOrBlank()) {
      return policyDecision
    }
    val approvedTaskId = config.approvedTaskId
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    if (approvedTaskId != task.id || policyDecision.outcome != PolicyDecisionOutcome.ASK) {
      return policyDecision
    }
    return PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "USER_APPROVED_RETRY",
      detail = "User approved this task retry.",
      approvalRisk = policyDecision.approvalRisk,
    )
  }

  private fun applyApprovedToolOverride(
    task: AgentTask,
    toolName: String,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    val approvedTaskId = config.approvedTaskId
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    val approvedToolName = config.approvedToolName
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    if (approvedTaskId != task.id || approvedToolName != toolName || policyDecision.outcome != PolicyDecisionOutcome.ASK) {
      return policyDecision
    }
    return PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "USER_APPROVED_RETRY",
      detail = "User approved this task retry for $toolName.",
      approvalRisk = policyDecision.approvalRisk,
    )
  }

  private fun gateFileMutation(
    task: AgentTask,
    toolName: String,
    policyDecision: PolicyDecision,
    affectedPaths: Map<String, String>,
  ): AgentToolResult? = gatePolicyControlledTool(
    task = task,
    toolName = toolName,
    policyDecision = policyDecision,
    affectedPaths = affectedPaths,
    askDetail = "Approval is required before $toolName can run.",
    denyDetail = "Policy denied $toolName.",
  )

  private fun gatePolicyControlledTool(
    task: AgentTask,
    toolName: String,
    policyDecision: PolicyDecision,
    affectedPaths: Map<String, String>,
    askDetail: String,
    denyDetail: String,
  ): AgentToolResult? {
    if (policyDecision.outcome == PolicyDecisionOutcome.ALLOW) {
      return null
    }
    val detail = when (policyDecision.outcome) {
      PolicyDecisionOutcome.ASK -> approvalRequiredDetail(
        policyDecision = policyDecision,
        fallback = policyDecision.detail ?: askDetail,
      )
      PolicyDecisionOutcome.DENY -> denyDetail
      PolicyDecisionOutcome.ALLOW -> error("ALLOW decisions should not be gated.")
    }
    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.DENIED,
      content = detail,
      errorCode = when (policyDecision.outcome) {
        PolicyDecisionOutcome.ASK -> approvalRequiredErrorCode(policyDecision)
        PolicyDecisionOutcome.DENY -> ERROR_DENY_POLICY
        PolicyDecisionOutcome.ALLOW -> error("ALLOW decisions should not be gated.")
      },
      errorMessage = detail,
      metadata = affectedPaths + mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyOutcome" to policyDecision.outcome.name,
        "policyReasonCode" to policyDecision.reasonCode,
      ) + approvalRiskMetadata(policyDecision),
    )
  }

  private fun approvalRequiredErrorCode(policyDecision: PolicyDecision): String =
    when (policyDecision.approvalRisk) {
      PolicyApprovalRisk.HIGH_RISK -> ERROR_HIGH_RISK_APPROVAL_REQUIRED
      PolicyApprovalRisk.STANDARD -> ERROR_APPROVAL_REQUIRED
    }

  private fun approvalRequiredDetail(
    policyDecision: PolicyDecision,
    fallback: String,
  ): String = when (policyDecision.approvalRisk) {
    PolicyApprovalRisk.HIGH_RISK -> if (fallback.contains("high-risk", ignoreCase = true)) {
      fallback
    } else {
      "High-risk approval required. Review this request carefully before approving. $fallback"
    }

    PolicyApprovalRisk.STANDARD -> fallback
  }

  private fun approvalRiskMetadata(policyDecision: PolicyDecision): Map<String, String> =
    if (policyDecision.outcome == PolicyDecisionOutcome.ASK) {
      mapOf("approvalRisk" to policyDecision.approvalRisk.name)
    } else {
      emptyMap()
    }

  private fun JsonObject.requiredString(name: String): String =
    optionalString(name)?.takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("Required argument '$name' must be a non-blank string.")

  private fun JsonObject.requiredText(name: String): String {
    val element = this[name]
      ?: throw IllegalArgumentException("Required argument '$name' must be a JSON string.")
    if (element == JsonNull) {
      throw IllegalArgumentException("Required argument '$name' must be a JSON string.")
    }
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON string.")
    return primitive.content
  }

  private fun JsonObject.requiredStringFrom(vararg names: String): String =
    optionalStringFrom(*names)?.takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("One of ${names.joinToString(separator = ", ")} must be a non-blank string.")

  private fun JsonObject.optionalStringFrom(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> optionalString(name) }

  private fun JsonObject.optionalString(name: String): String? {
    val element = this[name] ?: return null
    if (element == JsonNull) {
      return null
    }
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON string.")
    return primitive.content
  }

  private fun JsonObject.optionalInt(name: String): Int? {
    val element = this[name] ?: return null
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
    return primitive.content.toIntOrNull()
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
  }

  private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val element = this[name] ?: return null
    if (element == JsonNull) {
      return null
    }
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON boolean.")
    return when (primitive.content.trim().lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw IllegalArgumentException("Argument '$name' must be a JSON boolean.")
    }
  }

  private fun JsonObject.optionalStringArray(name: String): List<String> {
    val element = this[name] ?: return emptyList()
    val array = element as? JsonArray
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON array of strings.")
    return array.mapIndexed { index, entry ->
      val primitive = entry as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON string.")
      primitive.content
    }
  }

  private fun JsonObject.optionalObjectArray(name: String): List<JsonObject>? {
    val element = this[name] ?: return null
    if (element == JsonNull) {
      return null
    }
    val array = element as? JsonArray
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON array of objects.")
    return array.mapIndexed { index, entry ->
      entry as? JsonObject
        ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON object.")
    }
  }

  private fun JsonObject.requiredObjectArray(name: String): List<JsonObject> =
    optionalObjectArray(name)
      ?: throw IllegalArgumentException("Required argument '$name' must be a JSON array of objects.")

  private fun ExecutionResult.toAgentToolResult(toolName: String): AgentToolResult {
    val status = when (status) {
      ExecutionStatus.SUCCESS -> AgentToolResultStatus.SUCCESS
      ExecutionStatus.DENIED -> AgentToolResultStatus.DENIED
      ExecutionStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
      ExecutionStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
      ExecutionStatus.FAILED -> AgentToolResultStatus.FAILED
    }
    val content = when {
      stdout.isNotBlank() -> stdout
      stderr.isNotBlank() -> stderr
      errorMessage != null -> errorMessage.orEmpty()
      else -> "Tool finished with status ${status.name.lowercase()}."
    }
    return AgentToolResult(
      toolName = toolName,
      status = status,
      content = content,
      exitCode = exitCode,
      stdout = stdout,
      stderr = stderr,
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = metadata,
    )
  }

  private data class TextEdit(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean,
  )

  private data class TextEditOutcome(
    val content: String,
    val replacementCount: Int,
  )

  companion object {
    private const val ERROR_DENY_POLICY: String = "DENY_POLICY"
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private val TOOL_ALIASES: Map<String, String> = linkedMapOf(
      "list" to "LS",
      "ls" to "LS",
      "read" to "Read",
      "write" to "Write",
      "grep" to "Grep",
      "glob" to "Glob",
      "edit" to "Edit",
      "multiedit" to "MultiEdit",
      "todowrite" to "TodoWrite",
    )
  }

  private fun canonicalToolName(requestedToolName: String): String =
    TOOL_ALIASES[requestedToolName] ?: requestedToolName

  private fun aliasDescriptionFor(
    canonicalDefinition: AgentToolDefinition,
  ): String = "Compatibility alias for ${canonicalDefinition.name}. ${canonicalDefinition.description}"

  private fun AgentToolResult.relabelForAlias(
    requestedToolName: String,
    canonicalToolName: String,
  ): AgentToolResult {
    if (requestedToolName == canonicalToolName) {
      return this
    }
    return copy(
      toolName = requestedToolName,
      metadata = metadata + mapOf("canonicalToolName" to canonicalToolName),
    )
  }
}

internal fun AgentToolDefinition.toJsonSchema(): JsonObject = buildJsonObject {
  put("name", name)
  put("description", description)
  put(
    "parameters",
    buildJsonArray {
      parameters.forEach { parameter ->
        add(
          buildJsonObject {
            put("name", parameter.name)
            put("type", parameter.type)
            put("required", parameter.required)
            put("description", parameter.description)
          },
        )
      }
    },
  )
}
