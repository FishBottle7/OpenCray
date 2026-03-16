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
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.policy.ToolPolicyOverride
import com.opencray.runtime.memory.MemorySearchMatch
import com.opencray.runtime.memory.MemorySearchService
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.web.HttpUrlWebContentFetcher
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import com.opencray.runtime.web.WebContentFetcher
import com.opencray.runtime.web.WebFetchRequest
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.web.WebSearchRequest
import com.opencray.skills.SkillLoadReport
import com.opencray.skills.SkillLoader
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
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
  val readRoots: Set<Path> = workspaceRoots,
  val skillsRoots: List<File> = emptyList(),
  val mcpExposureReport: McpClientExposureReport? = null,
  val modePolicy: ModePolicy = ModePolicy(),
  val approvedTaskId: String? = null,
  val approvedToolName: String? = null,
  val commandExecutor: CommandExecutor? = null,
  val pythonRuntimeAdapter: PythonScriptRuntime = HostProcessPythonRuntime(),
  val commandApprovalToken: CommandApprovalToken? = null,
  val todoStore: AgentTodoStore = InMemoryAgentTodoStore(),
  val processRegistry: AgentProcessRegistry = InMemoryAgentProcessRegistry(),
  val webContentFetcher: WebContentFetcher = HttpUrlWebContentFetcher(),
  val webSearchProvider: WebSearchProvider = UnconfiguredWebSearchProvider,
  val memoryToolContext: MemoryToolContext? = null,
  val maxReadBytes: Int = 32_000,
  val maxDirectoryEntries: Int = 200,
  val maxWebFetchChars: Int = 12_000,
  val maxWebSearchResults: Int = 8,
  val maxMemorySearchResults: Int = 5,
  val maxMemoryGetLines: Int = 20,
  val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
  init {
    require(workspaceRoots.isNotEmpty()) { "OpenCrayToolDispatcherConfig workspaceRoots must not be empty." }
    require(readRoots.isNotEmpty()) { "OpenCrayToolDispatcherConfig readRoots must not be empty." }
    require(maxReadBytes > 0) { "OpenCrayToolDispatcherConfig maxReadBytes must be > 0." }
    require(maxDirectoryEntries > 0) { "OpenCrayToolDispatcherConfig maxDirectoryEntries must be > 0." }
    require(maxWebFetchChars > 0) { "OpenCrayToolDispatcherConfig maxWebFetchChars must be > 0." }
    require(maxWebSearchResults > 0) { "OpenCrayToolDispatcherConfig maxWebSearchResults must be > 0." }
    require(maxMemorySearchResults > 0) { "OpenCrayToolDispatcherConfig maxMemorySearchResults must be > 0." }
    require(maxMemoryGetLines > 0) { "OpenCrayToolDispatcherConfig maxMemoryGetLines must be > 0." }
  }
}

class OpenCrayToolDispatcher(
  private val config: OpenCrayToolDispatcherConfig,
) {
  private val writeBoundary = WorkspaceBoundary(config.workspaceRoots)
  private val readBoundary = WorkspaceBoundary(config.readRoots)
  private val fileOpsService = FileOpsService(writeBoundary.approvedRoots())
  private val todoStore = config.todoStore
  private val processRegistry = config.processRegistry
  private val webContentFetcher = config.webContentFetcher
  private val webSearchProvider = config.webSearchProvider
  private val memorySearchService = MemorySearchService()
  private val commandExecutor = config.commandExecutor ?: CommandExecutor(
    config = CommandExecutionConfig(
      approvedWorkingDirectories = writeBoundary.approvedRoots(),
    ),
  )

  fun definitions(): List<AgentToolDefinition> {
    val canonicalDefinitions = listOf(
      AgentToolDefinition(
        name = "LS",
        description = "List files and directories under the approved readable roots. Use workspace-relative paths for the main workspace, or absolute paths for approved external read-only roots listed in task metadata.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = false, description = "Workspace-relative path, or an absolute path inside an approved external read-only root. Defaults to the writable workspace root."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of entries to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Read",
        description = "Read a text file from the approved readable roots. Supports optional 1-based line offsets and limits.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
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
        description = "Search readable text files with a regular expression and return matching lines from the workspace or approved external read-only roots.",
        parameters = listOf(
          AgentToolParameter("pattern", "string", required = true, description = "Regular expression pattern to search for."),
          AgentToolParameter("path", "string", required = false, description = "Optional workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to relative file paths."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of matching lines to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Glob",
        description = "Recursively match readable paths with a glob pattern across the workspace and approved external read-only roots.",
        parameters = listOf(
          AgentToolParameter("pattern", "string", required = true, description = "Glob pattern to match against workspace-relative paths."),
          AgentToolParameter("path", "string", required = false, description = "Optional workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of matching paths to return."),
        ),
      ),
      AgentToolDefinition(
        name = "WebSearch",
        description = "Search the web through the configured search provider and return result titles, URLs, and snippets.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = true, description = "Search query to send to the web search provider."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of search results to return."),
        ),
      ),
      AgentToolDefinition(
        name = "WebFetch",
        description = "Fetch one HTTP or HTTPS page and return extracted readable text content.",
        parameters = listOf(
          AgentToolParameter("url", "string", required = true, description = "Absolute http or https URL to fetch."),
          AgentToolParameter("max_chars", "number", required = false, description = "Maximum number of extracted characters to return."),
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
        name = "ImportFile",
        description = "Copy a file or folder from an approved readable root into the writable workspace without mutating the source. Use this to bring photos or public files into the workspace.",
        parameters = listOf(
          AgentToolParameter("source_path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("destination_path", "string", required = true, description = "Destination path inside the writable workspace root."),
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
        name = "Bash",
        description = "Run one shell command string inside the approved workspace through the host shell. Each call starts a fresh managed shell process; if it keeps running after the initial wait, continue with ProcessRead, ProcessWait, or ProcessTerminate.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = true, description = "Shell command string to execute."),
          AgentToolParameter("working_directory", "string", required = false, description = "Workspace-relative working directory. Defaults to the workspace root."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "How long Bash should wait for completion before returning a still-running managed process."),
          AgentToolParameter("wait_timeout_ms", "number", required = false, description = "Explicit alias for timeout_ms."),
          AgentToolParameter("process_timeout_ms", "number", required = false, description = "Maximum lifetime for the managed shell process before it is terminated."),
          AgentToolParameter("background", "boolean", required = false, description = "If true, return immediately after the managed shell process starts."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessStart",
        description = "Start a managed background command or Python script inside the approved workspace and return a process id for later inspection.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = false, description = "Executable to launch. Provide exactly one of command or script_path."),
          AgentToolParameter("script_path", "string", required = false, description = "Workspace-relative Python script to launch through the managed Python runner. Provide exactly one of command or script_path."),
          AgentToolParameter("args", "string[]", required = false, description = "Optional command arguments."),
          AgentToolParameter("python_executable", "string", required = false, description = "Python executable used when script_path is provided. Defaults to python."),
          AgentToolParameter("working_directory", "string", required = false, description = "Workspace-relative working directory. Defaults to the workspace root."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "Maximum runtime before the managed process is terminated."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessList",
        description = "List managed background processes for the current chat session.",
      ),
      AgentToolDefinition(
        name = "ProcessRead",
        description = "Read the latest status and captured output for one managed background process.",
        parameters = listOf(
          AgentToolParameter("process_id", "string", required = true, description = "Managed process id returned by ProcessStart."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessWait",
        description = "Wait briefly for one managed background process to advance or finish, then return its latest status and output.",
        parameters = listOf(
          AgentToolParameter("process_id", "string", required = true, description = "Managed process id returned by ProcessStart."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "How long to wait before returning the current snapshot."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessTerminate",
        description = "Terminate one managed background process started in the current chat session.",
        parameters = listOf(
          AgentToolParameter("process_id", "string", required = true, description = "Managed process id returned by ProcessStart."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_list_files",
        description = "List files under the approved readable roots.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = false, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of entries to return."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_read_file",
        description = "Read a text file from the approved readable roots.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
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
        name = "workspace_import_file",
        description = "Copy a file or folder from an approved readable root into the writable workspace.",
        parameters = listOf(
          AgentToolParameter("source_path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("destination_path", "string", required = true, description = "Destination path inside the writable workspace root."),
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
    ) + memoryToolDefinitions()
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
        "workspace_import_file" -> importFileIntoWorkspace(task = task, arguments = call.arguments)
        "workspace_move_file" -> moveWorkspaceFile(task = task, arguments = call.arguments)
        "workspace_delete_file" -> deleteWorkspaceFile(task = task, arguments = call.arguments)
        "LS" -> listFilesForClaude(arguments = call.arguments)
        "Read" -> readFileForClaude(arguments = call.arguments)
        "Write" -> writeFileForClaude(task = task, arguments = call.arguments)
        "Grep" -> grepWorkspace(arguments = call.arguments)
        "Glob" -> globWorkspace(arguments = call.arguments)
        "ImportFile" -> importFileForClaude(task = task, arguments = call.arguments)
        "WebSearch" -> webSearch(task = task, arguments = call.arguments)
        "WebFetch" -> webFetch(task = task, arguments = call.arguments)
        "Edit" -> editWorkspaceFile(task = task, arguments = call.arguments)
        "MultiEdit" -> multiEditWorkspaceFile(task = task, arguments = call.arguments)
        "TodoWrite" -> writeTodoList(arguments = call.arguments)
        "Bash" -> executeClaudeBash(task = task, arguments = call.arguments)
        "ProcessStart" -> startManagedProcess(task = task, arguments = call.arguments)
        "ProcessList" -> listManagedProcesses()
        "ProcessRead" -> readManagedProcess(arguments = call.arguments)
        "ProcessWait" -> waitForManagedProcess(arguments = call.arguments)
        "ProcessTerminate" -> terminateManagedProcess(arguments = call.arguments)
        "command_exec" -> executeCommand(task = task, arguments = call.arguments, hooks = hooks)
        "python_exec" -> executePython(task = task, arguments = call.arguments)
        "skills_list" -> listSkills()
        "skill_read" -> readSkill(call.arguments)
        "mcp_list_servers" -> listMcpServers()
        "memory_search" -> searchProjectedMemory(call.arguments)
        "memory_get" -> getProjectedMemory(call.arguments)
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
    val directory = readBoundary.ensureDirectory(
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
        val kind = if (Files.isDirectory(entry)) "dir" else "file"
        "$kind\t${displayPathForModel(entry)}"
      }
    }
    return AgentToolResult(
      toolName = "workspace_list_files",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = mapOf(
        "path" to displayPathForModel(directory),
        "entryCount" to entries.size.toString(),
      ),
    )
  }

  private fun readWorkspaceFile(arguments: JsonObject): AgentToolResult {
    val file = readBoundary.ensureFile(arguments.requiredString("path"), label = "workspace read")
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
        "path" to displayPathForModel(file),
        "byteCount" to bytes.size.toString(),
        "truncated" to truncated.toString(),
      ),
    )
  }

  private fun writeWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = writeBoundary.resolve(arguments.requiredString("path"), label = "workspace write", defaultToRoot = false)
    return writeTextFile(
      task = task,
      toolName = "workspace_write_file",
      path = path,
      content = arguments.requiredText("content"),
      metadataPathKey = "path",
      successMessage = "Wrote ${displayWritablePath(path)} successfully.",
    )
  }

  private fun listFilesForClaude(arguments: JsonObject): AgentToolResult {
    val directory = readBoundary.ensureDirectory(
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
    val file = readBoundary.ensureFile(arguments.requiredStringFrom("file_path", "path"), label = "Read")
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
    val path = writeBoundary.resolve(arguments.requiredStringFrom("file_path", "path"), label = "Write", defaultToRoot = false)
    return writeTextFile(
      task = task,
      toolName = "Write",
      path = path,
      content = arguments.requiredText("content"),
      metadataPathKey = "filePath",
      successMessage = "Wrote ${displayWritablePath(path)} successfully.",
    )
  }

  private fun importFileForClaude(task: AgentTask, arguments: JsonObject): AgentToolResult =
    importFileIntoWorkspace(
      task = task,
      arguments = buildJsonObject {
        put(
          "source_path",
          JsonPrimitive(arguments.requiredStringFrom("source_path", "path")),
        )
        put(
          "destination_path",
          JsonPrimitive(arguments.requiredString("destination_path")),
        )
      },
      toolName = "ImportFile",
    )

  private fun importFileIntoWorkspace(
    task: AgentTask,
    arguments: JsonObject,
    toolName: String = "workspace_import_file",
  ): AgentToolResult {
    val source = readBoundary.resolve(
      arguments.requiredString("source_path"),
      label = "import source",
      defaultToRoot = false,
    )
    require(Files.exists(source)) { "Import source does not exist: $source" }
    val destination = writeBoundary.resolve(
      arguments.requiredString("destination_path"),
      label = "import destination",
      defaultToRoot = false,
    )
    if (Files.isDirectory(source)) {
      require(!destination.startsWith(source)) {
        "A folder cannot be imported into itself."
      }
    }
    require(!Files.exists(destination)) {
      "Import destination already exists: ${displayWritablePath(destination)}"
    }

    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.WRITE_FILE,
      targetPath = destination,
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
      affectedPaths = mapOf(
        "sourcePath" to displayPathForModel(source),
        "destinationPath" to displayWritablePath(destination),
      ),
    )?.let { return it }

    copyIntoWorkspace(source = source, destination = destination)

    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Imported ${displayPathForModel(source)} into ${displayWritablePath(destination)}.",
      metadata = mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "sourcePath" to displayPathForModel(source),
        "destinationPath" to displayWritablePath(destination),
      ),
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

  private fun webSearch(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val query = arguments.requiredString("query")
    val maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxWebSearchResults)
      ?: config.maxWebSearchResults
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.NETWORK_ACCESS,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "WebSearch",
      policyDecision = policyDecision,
    )
    gatePolicyControlledTool(
      task = task,
      toolName = "WebSearch",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf("query" to inlinePreview(query, maxChars = 256)),
      askDetail = "Approval is required before WebSearch can access the network.",
      denyDetail = "Policy denied WebSearch.",
    )?.let { return it }

    val result = webSearchProvider.search(
      WebSearchRequest(
        query = query,
        maxResults = maxResults,
      ),
    )
    if (!result.isSuccess) {
      return AgentToolResult(
        toolName = "WebSearch",
        status = AgentToolResultStatus.FAILED,
        content = result.errorMessage ?: "Web search failed.",
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        metadata = mapOf(
          "providerName" to result.providerName,
          "query" to query,
          "requestedMaxResults" to maxResults.toString(),
          "executionMode" to inferExecutionMode(task).name,
          "policyReasonCode" to effectivePolicyDecision.reasonCode,
        ) + approvalRiskMetadata(effectivePolicyDecision),
      )
    }

    val rendered = if (result.results.isEmpty()) {
      "No web search results."
    } else {
      buildString {
        appendLine("provider=${result.providerName}")
        result.results.forEachIndexed { index, hit ->
          append(index + 1)
          append(". ")
          appendLine(hit.title)
          appendLine("url=${hit.url}")
          hit.snippet.trim().takeIf(String::isNotBlank)?.let { snippet ->
            appendLine("snippet=$snippet")
          }
          if (index != result.results.lastIndex) {
            appendLine()
          }
        }
      }.trim()
    }
    return AgentToolResult(
      toolName = "WebSearch",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = mapOf(
        "providerName" to result.providerName,
        "query" to query,
        "resultCount" to result.results.size.toString(),
        "requestedMaxResults" to maxResults.toString(),
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
      ) + approvalRiskMetadata(effectivePolicyDecision),
    )
  }

  private fun webFetch(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val url = arguments.requiredString("url")
    val maxChars = arguments.optionalInt("max_chars")?.coerceIn(256, config.maxWebFetchChars)
      ?: config.maxWebFetchChars
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.NETWORK_ACCESS,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "WebFetch",
      policyDecision = policyDecision,
    )
    gatePolicyControlledTool(
      task = task,
      toolName = "WebFetch",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf("url" to inlinePreview(url, maxChars = 512)),
      askDetail = "Approval is required before WebFetch can access the network.",
      denyDetail = "Policy denied WebFetch.",
    )?.let { return it }

    val result = webContentFetcher.fetch(
      WebFetchRequest(
        url = url,
        maxChars = maxChars,
      ),
    )
    if (!result.isSuccess) {
      return AgentToolResult(
        toolName = "WebFetch",
        status = AgentToolResultStatus.FAILED,
        content = buildString {
          append(result.errorMessage ?: "WebFetch failed.")
          result.content.trim().takeIf(String::isNotBlank)?.let { preview ->
            appendLine()
            appendLine()
            append(preview)
          }
        }.trim(),
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        metadata = buildMap {
          put("requestedUrl", result.requestedUrl)
          put("finalUrl", result.finalUrl)
          put("requestedMaxChars", maxChars.toString())
          put("executionMode", inferExecutionMode(task).name)
          put("policyReasonCode", effectivePolicyDecision.reasonCode)
          result.statusCode?.let { put("statusCode", it.toString()) }
          result.contentType?.let { put("contentType", it) }
          putAll(approvalRiskMetadata(effectivePolicyDecision))
        },
      )
    }

    return AgentToolResult(
      toolName = "WebFetch",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        result.title?.takeIf(String::isNotBlank)?.let { title ->
          appendLine("title=$title")
        }
        appendLine("url=${result.finalUrl}")
        result.statusCode?.let { statusCode ->
          appendLine("status_code=$statusCode")
        }
        result.contentType?.takeIf(String::isNotBlank)?.let { contentType ->
          appendLine("content_type=$contentType")
        }
        appendLine("truncated=${result.truncated}")
        appendLine()
        append(result.content)
      }.trim(),
      metadata = buildMap {
        put("requestedUrl", result.requestedUrl)
        put("finalUrl", result.finalUrl)
        put("requestedMaxChars", maxChars.toString())
        put("truncated", result.truncated.toString())
        put("executionMode", inferExecutionMode(task).name)
        put("policyReasonCode", effectivePolicyDecision.reasonCode)
        result.statusCode?.let { put("statusCode", it.toString()) }
        result.contentType?.let { put("contentType", it) }
        result.title?.let { put("title", it) }
        putAll(approvalRiskMetadata(effectivePolicyDecision))
      },
    )
  }

  private fun editWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = writeBoundary.ensureFile(arguments.requiredStringFrom("file_path", "path"), label = "Edit")
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
    val file = writeBoundary.ensureFile(arguments.requiredStringFrom("file_path", "path"), label = "MultiEdit")
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

  private fun executeClaudeBash(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val waitTimeoutMs = arguments.optionalLong("wait_timeout_ms")
      ?: arguments.optionalLong("timeout_ms")
      ?: DEFAULT_BASH_WAIT_TIMEOUT_MS
    require(waitTimeoutMs >= 0L) { "Bash wait timeout must be >= 0." }
    val processTimeoutMs = arguments.optionalLong("process_timeout_ms")
      ?: DEFAULT_MANAGED_PROCESS_TIMEOUT_MS
    require(processTimeoutMs > 0L) { "Bash process_timeout_ms must be > 0." }
    val background = arguments.optionalBoolean("background") ?: false
    val launch = resolveBashLaunch(arguments = arguments)
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.EXECUTE_COMMAND,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "Bash",
      policyDecision = policyDecision,
    )
    gatePolicyControlledTool(
      task = task,
      toolName = "Bash",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf(
        "toolName" to "Bash",
        "workingDirectory" to displayPathForModel(launch.workingDirectory),
      ),
      askDetail = "Approval is required before Bash can run.",
      denyDetail = "Policy denied Bash.",
    )?.let { return it }

    val startedSnapshot = processRegistry.start(
      ManagedProcessStartRequest(
        processId = "proc-${UUID.randomUUID().toString().take(8)}",
        taskId = task.id,
        command = launch.command,
        args = launch.args,
        workingDirectory = launch.workingDirectory.toString(),
        timeoutMs = processTimeoutMs,
        requestedAtEpochMs = System.currentTimeMillis(),
        metadata = mapOf(
          "executionMode" to inferExecutionMode(task).name,
          "policyReasonCode" to effectivePolicyDecision.reasonCode,
          "workingDirectory" to displayPathForModel(launch.workingDirectory),
        ) + launch.metadata,
      ),
    )

    if (startedSnapshot.status != ManagedProcessStatus.RUNNING || background || waitTimeoutMs == 0L) {
      return AgentToolResult(
        toolName = "Bash",
        status = toolStatusForManagedProcessStart(startedSnapshot),
        content = buildString {
          appendLine(bashStartSummary(snapshot = startedSnapshot, background = background))
          append(
            renderManagedProcessSnapshot(
              snapshot = startedSnapshot,
              includeOutput = startedSnapshot.status != ManagedProcessStatus.RUNNING,
            ),
          )
        }.trim(),
        errorCode = startedSnapshot.errorCode,
        errorMessage = startedSnapshot.errorMessage,
        metadata = managedProcessMetadata(startedSnapshot) + mapOf(
          "waitTimeoutMs" to waitTimeoutMs.toString(),
          "background" to background.toString(),
        ),
      )
    }

    val waitedSnapshot = processRegistry.wait(startedSnapshot.processId, waitTimeoutMs)
      ?: startedSnapshot
    return AgentToolResult(
      toolName = "Bash",
      status = toolStatusForManagedProcessStart(waitedSnapshot),
      content = buildString {
        appendLine(bashWaitSummary(snapshot = waitedSnapshot, waitTimeoutMs = waitTimeoutMs))
        append(renderManagedProcessSnapshot(snapshot = waitedSnapshot, includeOutput = true))
      }.trim(),
      errorCode = waitedSnapshot.errorCode,
      errorMessage = waitedSnapshot.errorMessage,
      metadata = managedProcessMetadata(waitedSnapshot) + mapOf(
        "waitTimeoutMs" to waitTimeoutMs.toString(),
        "background" to background.toString(),
      ),
    )
  }

  private fun resolveBashLaunch(arguments: JsonObject): ManagedProcessLaunch {
    val command = arguments.requiredString("command")
    val workingDirectory = writeBoundary.resolve(
      candidate = arguments.optionalString("working_directory"),
      label = "Bash working directory",
      defaultToRoot = true,
    )
    val shell = defaultShellPlan(command)
    return ManagedProcessLaunch(
      command = shell.executable,
      args = shell.args,
      workingDirectory = workingDirectory,
      metadata = mapOf(
        "runtimeKind" to "bash",
        "shellKind" to shell.kind,
        "shellCommand" to inlinePreview(command),
      ),
    )
  }

  private fun startManagedProcess(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val timeoutMs = arguments.optionalLong("timeout_ms") ?: DEFAULT_MANAGED_PROCESS_TIMEOUT_MS
    val launch = resolveManagedProcessLaunch(arguments = arguments, timeoutMs = timeoutMs)
    val policyDecision = policyDecisionFor(
      task = task,
      toolClass = PolicyToolClass.EXECUTE_COMMAND,
    )
    val effectivePolicyDecision = applyApprovedToolOverride(
      task = task,
      toolName = "ProcessStart",
      policyDecision = policyDecision,
    )
    gatePolicyControlledTool(
      task = task,
      toolName = "ProcessStart",
      policyDecision = effectivePolicyDecision,
      affectedPaths = mapOf(
        "toolName" to "ProcessStart",
        "workingDirectory" to displayPathForModel(launch.workingDirectory),
      ) + launch.affectedPaths,
      askDetail = "Approval is required before ProcessStart can run.",
      denyDetail = "Policy denied ProcessStart.",
    )?.let { return it }

    val snapshot = processRegistry.start(
      ManagedProcessStartRequest(
        processId = "proc-${UUID.randomUUID().toString().take(8)}",
        taskId = task.id,
        command = launch.command,
        args = launch.args,
        workingDirectory = launch.workingDirectory.toString(),
        timeoutMs = timeoutMs,
        requestedAtEpochMs = System.currentTimeMillis(),
        metadata = mapOf(
          "executionMode" to inferExecutionMode(task).name,
          "policyReasonCode" to effectivePolicyDecision.reasonCode,
          "workingDirectory" to displayPathForModel(launch.workingDirectory),
        ) + launch.metadata,
      ),
    )
    return AgentToolResult(
      toolName = "ProcessStart",
      status = toolStatusForManagedProcessStart(snapshot),
      content = buildString {
        appendLine("Managed process started.")
        append(renderManagedProcessSnapshot(snapshot, includeOutput = false))
      }.trim(),
      errorCode = snapshot.errorCode,
      errorMessage = snapshot.errorMessage,
      metadata = managedProcessMetadata(snapshot),
    )
  }

  private fun resolveManagedProcessLaunch(
    arguments: JsonObject,
    timeoutMs: Long,
  ): ManagedProcessLaunch {
    val command = arguments.optionalString("command")?.trim()?.takeIf(String::isNotBlank)
    val scriptPathCandidate = arguments.optionalString("script_path")?.trim()?.takeIf(String::isNotBlank)
    require((command == null) != (scriptPathCandidate == null)) {
      "ProcessStart requires exactly one of 'command' or 'script_path'."
    }
    val workingDirectory = writeBoundary.resolve(
      candidate = arguments.optionalString("working_directory"),
      label = "process working directory",
      defaultToRoot = true,
    )
    val userArgs = arguments.optionalStringArray("args")
    if (scriptPathCandidate == null) {
      return ManagedProcessLaunch(
        command = requireNotNull(command),
        args = userArgs,
        workingDirectory = workingDirectory,
      )
    }

    val scriptPath = writeBoundary.resolve(
      candidate = scriptPathCandidate,
      label = "python script",
      defaultToRoot = false,
    )
    val pythonExecutable = arguments.optionalString("python_executable")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "python"
    val pythonRequest = PythonExecRequest(
      taskId = "managed-python-process",
      workspaceRoot = writeBoundary.defaultRoot,
      scriptPath = scriptPath,
      args = userArgs,
      timeoutMs = timeoutMs,
      pythonExecutable = pythonExecutable,
    )
    val pythonCommand = HostProcessPythonRuntime.commandFor(pythonRequest)
    return ManagedProcessLaunch(
      command = pythonCommand.first(),
      args = pythonCommand.drop(1),
      workingDirectory = workingDirectory,
      metadata = mapOf(
        "runtimeKind" to "python_exec",
        "scriptPath" to displayPathForModel(scriptPath),
        "pythonExecutable" to pythonExecutable,
      ),
      affectedPaths = mapOf("scriptPath" to displayPathForModel(scriptPath)),
    )
  }

  private fun listManagedProcesses(): AgentToolResult {
    val snapshots = processRegistry.list()
    val rendered = if (snapshots.isEmpty()) {
      "No managed processes."
    } else {
      snapshots.joinToString(separator = "\n") { snapshot ->
        buildString {
          append(snapshot.processId)
          append('\t')
          append(snapshot.status.name.lowercase())
          append('\t')
          append(snapshot.command)
          if (snapshot.args.isNotEmpty()) {
            append(' ')
            append(snapshot.args.joinToString(separator = " "))
          }
          displayWorkingDirectory(snapshot.workingDirectory)?.let { workingDirectory ->
            append("\tcwd=")
            append(workingDirectory)
          }
          snapshot.exitCode?.let { code ->
            append("\texit=")
            append(code)
          }
        }
      }
    }
    return AgentToolResult(
      toolName = "ProcessList",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = mapOf("processCount" to snapshots.size.toString()),
    )
  }

  private fun readManagedProcess(arguments: JsonObject): AgentToolResult {
    val processId = arguments.requiredString("process_id")
    val snapshot = processRegistry.read(processId)
      ?: return missingManagedProcess(processId = processId, toolName = "ProcessRead")
    return AgentToolResult(
      toolName = "ProcessRead",
      status = AgentToolResultStatus.SUCCESS,
      content = renderManagedProcessSnapshot(snapshot, includeOutput = true),
      metadata = managedProcessMetadata(snapshot),
    )
  }

  private fun waitForManagedProcess(arguments: JsonObject): AgentToolResult {
    val processId = arguments.requiredString("process_id")
    val timeoutMs = arguments.optionalLong("timeout_ms") ?: DEFAULT_MANAGED_PROCESS_WAIT_TIMEOUT_MS
    val snapshot = processRegistry.wait(processId, timeoutMs)
      ?: return missingManagedProcess(processId = processId, toolName = "ProcessWait")
    return AgentToolResult(
      toolName = "ProcessWait",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Waited ${timeoutMs}ms for managed process.")
        append(renderManagedProcessSnapshot(snapshot, includeOutput = true))
      }.trim(),
      metadata = managedProcessMetadata(snapshot) + mapOf("waitTimeoutMs" to timeoutMs.toString()),
    )
  }

  private fun terminateManagedProcess(arguments: JsonObject): AgentToolResult {
    val processId = arguments.requiredString("process_id")
    val snapshot = processRegistry.terminate(processId)
      ?: return missingManagedProcess(processId = processId, toolName = "ProcessTerminate")
    return AgentToolResult(
      toolName = "ProcessTerminate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Managed process termination requested.")
        append(renderManagedProcessSnapshot(snapshot, includeOutput = true))
      }.trim(),
      metadata = managedProcessMetadata(snapshot),
    )
  }

  private fun moveWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val source = writeBoundary.resolve(arguments.requiredString("source_path"), label = "workspace move source", defaultToRoot = false)
    val destination = writeBoundary.resolve(
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
        "sourcePath" to displayWritablePath(source),
        "destinationPath" to displayWritablePath(destination),
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
      content = "Moved ${displayWritablePath(source)} to ${displayWritablePath(destination)}.",
      metadata = mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "sourcePath" to displayWritablePath(source),
        "destinationPath" to displayWritablePath(destination),
      ),
    )
  }

  private fun deleteWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = writeBoundary.resolve(arguments.requiredString("path"), label = "workspace delete", defaultToRoot = false)
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
      affectedPaths = mapOf("path" to displayWritablePath(path)),
    )?.let { return it }
    fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Delete(path = path),
      ),
    )
    return AgentToolResult(
      toolName = "workspace_delete_file",
      status = AgentToolResultStatus.SUCCESS,
      content = "Deleted ${displayWritablePath(path)}.",
      metadata = mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "path" to displayWritablePath(path),
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
    "ImportFile",
    "Edit",
    "MultiEdit",
    "TodoWrite" -> displayPathForModel(path)
    else -> displayWritablePath(path)
  }

  private fun copyIntoWorkspace(source: Path, destination: Path) {
    if (Files.isDirectory(source)) {
      copyDirectoryIntoWorkspace(source = source, destination = destination)
      return
    }
    Files.createDirectories(destination.parent)
    Files.copy(source, destination)
  }

  private fun copyDirectoryIntoWorkspace(source: Path, destination: Path) {
    Files.walk(source).use { stream ->
      stream.forEach { current ->
        val relative = source.relativize(current)
        val target = if (relative.nameCount == 0) destination else destination.resolve(relative.toString())
        if (Files.isDirectory(current)) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          Files.copy(current, target)
        }
      }
    }
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
    val resolved = readBoundary.resolve(candidate = candidate, label = label, defaultToRoot = true)
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
    runCatching {
      val normalized = path.toAbsolutePath().normalize()
      val writableRoot = writeBoundary.defaultRoot
      if (normalized.startsWith(writableRoot)) {
        writableRoot.relativize(normalized).toString().ifBlank { "." }
      } else {
        normalized.toString()
      }
    }.getOrDefault(path.toAbsolutePath().normalize().toString()).replace(File.separatorChar, '/')

  private fun displayWritablePath(path: Path): String =
    runCatching {
      writeBoundary.defaultRoot
        .relativize(path.toAbsolutePath().normalize())
        .toString()
        .ifBlank { "." }
    }.getOrDefault(path.toAbsolutePath().normalize().toString()).replace(File.separatorChar, '/')

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
    val workingDirectory = writeBoundary.resolve(
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
    val scriptPath = writeBoundary.resolve(arguments.requiredString("script_path"), label = "python script", defaultToRoot = false)
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
      affectedPaths = mapOf("scriptPath" to displayWritablePath(scriptPath)),
      askDetail = "Approval is required before python_exec can run.",
      denyDetail = "Policy denied python_exec.",
    )?.let { return it }
    val executionResult = config.pythonRuntimeAdapter.exec(
      request = PythonExecRequest(
        taskId = task.id,
        workspaceRoot = writeBoundary.defaultRoot,
        scriptPath = scriptPath,
        args = arguments.optionalStringArray("args"),
      ),
    )
    val toolResult = executionResult.toAgentToolResult(toolName = "python_exec")
    return toolResult.copy(
      metadata = toolResult.metadata + mapOf(
        "executionMode" to inferExecutionMode(task).name,
        "policyReasonCode" to effectivePolicyDecision.reasonCode,
        "scriptPath" to displayWritablePath(scriptPath),
      ) + approvalRiskMetadata(effectivePolicyDecision),
    )
  }

  private fun toolStatusForManagedProcessStart(
    snapshot: ManagedProcessSnapshot,
  ): AgentToolResultStatus = when (snapshot.status) {
    ManagedProcessStatus.RUNNING,
    ManagedProcessStatus.SUCCESS,
    -> AgentToolResultStatus.SUCCESS

    ManagedProcessStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
    ManagedProcessStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
    ManagedProcessStatus.FAILED,
    ManagedProcessStatus.SPAWN_ERROR,
    -> AgentToolResultStatus.FAILED
  }

  private fun missingManagedProcess(
    processId: String,
    toolName: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "Managed process '$processId' was not found.",
    errorCode = "PROCESS_NOT_FOUND",
    errorMessage = "Managed process '$processId' was not found.",
    metadata = mapOf("processId" to processId),
  )

  private fun managedProcessMetadata(snapshot: ManagedProcessSnapshot): Map<String, String> = buildMap {
    put("processId", snapshot.processId)
    put("processStatus", snapshot.status.name)
    put("processStarted", snapshot.processStarted.toString())
    put("timeoutMs", snapshot.timeoutMs.toString())
    put("command", snapshot.command)
    if (snapshot.args.isNotEmpty()) {
      put("args", snapshot.args.joinToString(separator = "\u0000"))
    }
    displayWorkingDirectory(snapshot.workingDirectory)?.let { workingDirectory ->
      put("workingDirectory", workingDirectory)
    }
    snapshot.exitCode?.let { code -> put("exitCode", code.toString()) }
    snapshot.finishedAtEpochMs?.let { finishedAt -> put("finishedAtEpochMs", finishedAt.toString()) }
    put("startedAtEpochMs", snapshot.startedAtEpochMs.toString())
    put("updatedAtEpochMs", snapshot.updatedAtEpochMs.toString())
    if (snapshot.timedOut) {
      put("timedOut", "true")
    }
    if (snapshot.cancelled) {
      put("cancelled", "true")
    }
    if (snapshot.outputLimitExceeded) {
      put("outputLimitExceeded", "true")
    }
    putAll(snapshot.metadata)
  }

  private fun renderManagedProcessSnapshot(
    snapshot: ManagedProcessSnapshot,
    includeOutput: Boolean,
  ): String = buildString {
    appendLine("process_id=${snapshot.processId}")
    appendLine("status=${snapshot.status.name.lowercase()}")
    snapshot.metadata["shellKind"]?.let { shellKind ->
      appendLine("shell_kind=$shellKind")
    }
    snapshot.metadata["shellCommand"]?.let { shellCommand ->
      appendLine("shell_command=$shellCommand")
    }
    appendLine("command=${snapshot.command}")
    snapshot.metadata["runtimeKind"]?.let { runtimeKind ->
      appendLine("runtime_kind=$runtimeKind")
    }
    snapshot.metadata["scriptPath"]?.let { scriptPath ->
      appendLine("script_path=$scriptPath")
    }
    snapshot.metadata["pythonExecutable"]?.let { pythonExecutable ->
      appendLine("python_executable=$pythonExecutable")
    }
    if (snapshot.args.isNotEmpty()) {
      appendLine("args=${snapshot.args.joinToString(separator = " ")}")
    }
    displayWorkingDirectory(snapshot.workingDirectory)?.let { workingDirectory ->
      appendLine("working_directory=$workingDirectory")
    }
    appendLine("timeout_ms=${snapshot.timeoutMs}")
    appendLine("process_started=${snapshot.processStarted}")
    snapshot.exitCode?.let { code ->
      appendLine("exit_code=$code")
    }
    snapshot.errorCode?.let { code ->
      appendLine("error_code=$code")
    }
    snapshot.errorMessage?.let { message ->
      appendLine("error_message=$message")
    }
    appendLine("started_at_epoch_ms=${snapshot.startedAtEpochMs}")
    appendLine("updated_at_epoch_ms=${snapshot.updatedAtEpochMs}")
    snapshot.finishedAtEpochMs?.let { finishedAt ->
      appendLine("finished_at_epoch_ms=$finishedAt")
    }
    if (includeOutput) {
      if (snapshot.stdout.isNotBlank()) {
        appendLine()
        appendLine("[stdout]")
        appendLine(snapshot.stdout.trimEnd())
      }
      if (snapshot.stderr.isNotBlank()) {
        appendLine()
        appendLine("[stderr]")
        append(snapshot.stderr.trimEnd())
      }
    }
  }.trim()

  private fun displayWorkingDirectory(rawWorkingDirectory: String?): String? {
    val normalized = rawWorkingDirectory?.trim()?.takeIf(String::isNotBlank) ?: return null
    val path = runCatching { Paths.get(normalized) }.getOrNull() ?: return normalized
    return runCatching { displayPathForModel(path) }.getOrDefault(normalized)
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

  private fun memoryToolDefinitions(): List<AgentToolDefinition> {
    if (config.memoryToolContext == null) {
      return emptyList()
    }
    return listOf(
      AgentToolDefinition(
        name = "memory_search",
        description = "Search the projected runtime memory corpus before answering prior-work questions about decisions, preferences, dates, people, paths, or todos. Use this first, then memory_get for a narrow snippet.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = true, description = "Search query describing the prior work or memory to retrieve."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of memory matches to return."),
          AgentToolParameter("min_score", "number", required = false, description = "Minimum relevance score for returned matches."),
        ),
      ),
      AgentToolDefinition(
        name = "memory_get",
        description = "Read a narrow line range from the projected runtime memory corpus after memory_search identifies the relevant path and line range.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Projected memory path such as MEMORY.md or memory/YYYY-MM-DD.md."),
          AgentToolParameter("from", "number", required = false, description = "1-based start line to read."),
          AgentToolParameter("lines", "number", required = false, description = "Maximum number of lines to read."),
        ),
      ),
    )
  }

  private fun searchProjectedMemory(arguments: JsonObject): AgentToolResult {
    val context = config.memoryToolContext
      ?: return AgentToolResult(
        toolName = "memory_search",
        status = AgentToolResultStatus.FAILED,
        content = "Projected memory search is not configured for this runtime.",
        errorCode = "MEMORY_SEARCH_UNAVAILABLE",
      )
    val query = arguments.requiredString("query")
    val maxResults = (arguments.optionalInt("max_results") ?: arguments.optionalInt("maxResults")
      ?: config.maxMemorySearchResults).coerceIn(1, config.maxMemorySearchResults)
    val minScore = (arguments.optionalInt("min_score") ?: arguments.optionalInt("minScore")
      ?: 1).coerceAtLeast(1)
    val response = memorySearchService.search(
      context = context,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    val content = if (response.matches.isEmpty()) {
      "No matching projected memory snippets were found."
    } else {
      buildString {
        appendLine("Found ${response.matches.size} projected memory match(es).")
        response.matches.forEachIndexed { index, match ->
          append(index + 1)
          append(". ")
          append(renderMemorySearchHeader(match))
          appendLine()
          appendLine(match.snippet)
          if (index != response.matches.lastIndex) {
            appendLine()
          }
        }
      }.trim()
    }
    return AgentToolResult(
      toolName = "memory_search",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = buildMap {
        put("query", query)
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "paths",
            response.matches.joinToString(separator = ",") { match -> match.path },
          )
          put(
            "lineRanges",
            response.matches.joinToString(separator = ",") { match -> renderMemoryLineRange(match.startLine, match.endLine) },
          )
        }
      },
    )
  }

  private fun getProjectedMemory(arguments: JsonObject): AgentToolResult {
    val context = config.memoryToolContext
      ?: return AgentToolResult(
        toolName = "memory_get",
        status = AgentToolResultStatus.FAILED,
        content = "Projected memory reads are not configured for this runtime.",
        errorCode = "MEMORY_GET_UNAVAILABLE",
      )
    val path = arguments.requiredString("path")
    val from = arguments.optionalInt("from")?.coerceAtLeast(1)
    val lines = (arguments.optionalInt("lines") ?: config.maxMemoryGetLines)
      .coerceIn(1, config.maxMemoryGetLines)
    val response = memorySearchService.get(
      context = context,
      path = path,
      from = from,
      lines = lines,
    )
    return AgentToolResult(
      toolName = "memory_get",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        append(response.path)
        append("#")
        append(renderMemoryLineRange(response.startLine, response.endLine))
        appendLine()
        append(response.text)
      }.trim(),
      metadata = mapOf(
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
      ),
    )
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
        workspaceRoot = writeBoundary.defaultRoot,
        targetPath = targetPath,
        destinationPath = destinationPath,
      ),
    )
    val overriddenDecision = applySettingsPolicyOverride(
      task = task,
      toolClass = toolClass,
      policyDecision = fineGrainedDecision,
    )
    val mergedDecision = mergePolicyDecisions(
      coarseDecision = task.policyDecision,
      fineGrainedDecision = overriddenDecision,
    )
    return applyApprovedTaskOverride(task = task, policyDecision = mergedDecision)
  }

  private fun applySettingsPolicyOverride(
    task: AgentTask,
    toolClass: PolicyToolClass,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    if (policyDecision.outcome == PolicyDecisionOutcome.DENY) {
      return policyDecision
    }
    val override = settingsPolicyOverrideFor(task = task, toolClass = toolClass)
    if (override == ToolPolicyOverride.INHERIT) {
      return policyDecision
    }
    return when (override) {
      ToolPolicyOverride.INHERIT -> policyDecision
      ToolPolicyOverride.ALLOW -> PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "SETTINGS_OVERRIDE_ALLOW",
      )
      ToolPolicyOverride.BLOCK -> PolicyDecision(
        outcome = PolicyDecisionOutcome.DENY,
        reasonCode = "SETTINGS_OVERRIDE_BLOCK",
        detail = "Blocked by Safety settings.",
        approvalRisk = policyDecision.approvalRisk,
      )
      ToolPolicyOverride.ASK -> PolicyDecision(
        outcome = PolicyDecisionOutcome.ASK,
        reasonCode = "SETTINGS_OVERRIDE_ASK",
        detail = "Approval is required by Safety settings.",
        approvalRisk = approvalRiskForSettingsOverride(toolClass, policyDecision),
      )
    }
  }

  private fun settingsPolicyOverrideFor(
    task: AgentTask,
    toolClass: PolicyToolClass,
  ): ToolPolicyOverride {
    val metadataKey = when (toolClass) {
      PolicyToolClass.WRITE_FILE -> SafetySettingsMetadataKeys.FILE_CHANGES_POLICY_ID
      PolicyToolClass.DELETE_FILE,
      PolicyToolClass.MOVE_FILE,
      PolicyToolClass.RENAME_FILE,
      -> SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID
      PolicyToolClass.EXECUTE_COMMAND -> SafetySettingsMetadataKeys.SHELL_COMMANDS_POLICY_ID
      PolicyToolClass.READ_FILE,
      PolicyToolClass.NETWORK_ACCESS,
      -> null
    } ?: return ToolPolicyOverride.INHERIT
    return ToolPolicyOverride.fromWireValue(task.metadata[metadataKey])
  }

  private fun approvalRiskForSettingsOverride(
    toolClass: PolicyToolClass,
    policyDecision: PolicyDecision,
  ): PolicyApprovalRisk {
    if (policyDecision.outcome == PolicyDecisionOutcome.ASK) {
      return policyDecision.approvalRisk
    }
    return when (toolClass) {
      PolicyToolClass.WRITE_FILE -> PolicyApprovalRisk.STANDARD
      PolicyToolClass.DELETE_FILE,
      PolicyToolClass.MOVE_FILE,
      PolicyToolClass.RENAME_FILE,
      PolicyToolClass.EXECUTE_COMMAND,
      PolicyToolClass.NETWORK_ACCESS,
      -> PolicyApprovalRisk.HIGH_RISK
      PolicyToolClass.READ_FILE -> PolicyApprovalRisk.STANDARD
    }
  }

  private fun inferExecutionMode(task: AgentTask): ExecutionMode =
    listOf(
      SafetySettingsMetadataKeys.EXECUTION_MODE,
      SafetySettingsMetadataKeys.CHAT_MODE,
      "mode",
      "modeLabel",
    )
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

  private fun JsonObject.optionalLong(name: String): Long? {
    val element = this[name] ?: return null
    val primitive = element as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
    return primitive.content.toLongOrNull()
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

  private data class ManagedProcessLaunch(
    val command: String,
    val args: List<String>,
    val workingDirectory: Path,
    val metadata: Map<String, String> = emptyMap(),
    val affectedPaths: Map<String, String> = emptyMap(),
  )

  private data class TextEditOutcome(
    val content: String,
    val replacementCount: Int,
  )

  private data class ShellPlan(
    val executable: String,
    val args: List<String>,
    val kind: String,
  )

  companion object {
    private const val ERROR_DENY_POLICY: String = "DENY_POLICY"
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private const val DEFAULT_BASH_WAIT_TIMEOUT_MS: Long = 1_000L
    private const val DEFAULT_MANAGED_PROCESS_TIMEOUT_MS: Long = 300_000L
    private const val DEFAULT_MANAGED_PROCESS_WAIT_TIMEOUT_MS: Long = 1_000L
    private val TOOL_ALIASES: Map<String, String> = linkedMapOf(
      "bash" to "Bash",
      "list" to "LS",
      "ls" to "LS",
      "read" to "Read",
      "write" to "Write",
      "grep" to "Grep",
      "glob" to "Glob",
      "websearch" to "WebSearch",
      "webfetch" to "WebFetch",
      "edit" to "Edit",
      "multiedit" to "MultiEdit",
      "importfile" to "ImportFile",
      "import" to "ImportFile",
      "todowrite" to "TodoWrite",
      "processstart" to "ProcessStart",
      "processlist" to "ProcessList",
      "processread" to "ProcessRead",
      "processwait" to "ProcessWait",
      "processterminate" to "ProcessTerminate",
    )
  }

  private fun bashStartSummary(
    snapshot: ManagedProcessSnapshot,
    background: Boolean,
  ): String = when (snapshot.status) {
    ManagedProcessStatus.RUNNING -> if (background) {
      "Shell command started in background."
    } else {
      "Shell command started."
    }

    ManagedProcessStatus.SPAWN_ERROR -> "Shell command failed to start."
    ManagedProcessStatus.CANCELLED -> "Shell command was cancelled."
    ManagedProcessStatus.TIMEOUT -> "Shell command timed out."
    ManagedProcessStatus.SUCCESS -> "Shell command finished."
    ManagedProcessStatus.FAILED -> "Shell command failed."
  }

  private fun bashWaitSummary(
    snapshot: ManagedProcessSnapshot,
    waitTimeoutMs: Long,
  ): String = when (snapshot.status) {
    ManagedProcessStatus.RUNNING ->
      "Shell command is still running after waiting ${waitTimeoutMs}ms. Continue with ProcessRead, ProcessWait, or ProcessTerminate."

    ManagedProcessStatus.SUCCESS -> "Shell command finished."
    ManagedProcessStatus.SPAWN_ERROR -> "Shell command failed to start."
    ManagedProcessStatus.CANCELLED -> "Shell command was cancelled."
    ManagedProcessStatus.TIMEOUT -> "Shell command timed out."
    ManagedProcessStatus.FAILED -> "Shell command failed."
  }

  private fun defaultShellPlan(command: String): ShellPlan {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return if (osName.contains("win")) {
      ShellPlan(
        executable = "powershell.exe",
        args = listOf("-NoLogo", "-NoProfile", "-Command", command),
        kind = "powershell",
      )
    } else {
      ShellPlan(
        executable = "sh",
        args = listOf("-lc", command),
        kind = "sh",
      )
    }
  }

  private fun inlinePreview(
    value: String,
    maxChars: Int = 512,
  ): String {
    val normalized = value
      .replace("\r", "\\r")
      .replace("\n", "\\n")
    return if (normalized.length <= maxChars) {
      normalized
    } else {
      normalized.take(maxChars - 1).trimEnd() + "…"
    }
  }

  private fun canonicalToolName(requestedToolName: String): String =
    TOOL_ALIASES[requestedToolName] ?: requestedToolName

  private fun renderMemorySearchHeader(match: MemorySearchMatch): String = buildString {
    append(match.path)
    append("#")
    append(renderMemoryLineRange(match.startLine, match.endLine))
    append(" score=")
    append(match.score)
    append(" id=")
    append(match.recordId)
    append(" kind=")
    append(match.kind.name.lowercase())
    append(" scope=")
    append(match.scope.name.lowercase())
    append(" status=")
    append(match.status.name.lowercase())
    if (match.matchedTerms.isNotEmpty()) {
      append(" matched_terms=")
      append(match.matchedTerms.joinToString(separator = "|"))
    }
  }

  private fun renderMemoryLineRange(
    startLine: Int,
    endLine: Int,
  ): String = if (startLine == endLine) {
    "L$startLine"
  } else {
    "L$startLine-L$endLine"
  }

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
