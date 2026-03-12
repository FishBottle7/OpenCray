package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.filesystem.FileMutationOperation
import com.opencray.filesystem.FileOpsService
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpToolExposure
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
  val commandExecutor: CommandExecutor? = null,
  val pythonRuntimeAdapter: PythonRuntimeAdapter = PythonRuntimeAdapter(),
  val commandApprovalToken: CommandApprovalToken? = null,
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
  private val commandExecutor = config.commandExecutor ?: CommandExecutor(
    config = CommandExecutionConfig(
      approvedWorkingDirectories = boundary.approvedRoots(),
    ),
  )

  fun definitions(): List<AgentToolDefinition> = listOf(
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
      description = "Execute a workspace-local Python script through the Python runtime adapter.",
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
      description = "Inspect currently exposed MCP servers and their trust state.",
    ),
  )

  fun dispatch(
    task: AgentTask,
    call: AgentToolCall,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult = try {
    when (call.toolName) {
      "workspace_list_files" -> listWorkspaceFiles(call.arguments)
      "workspace_read_file" -> readWorkspaceFile(call.arguments)
      "workspace_write_file" -> writeWorkspaceFile(call.arguments)
      "workspace_move_file" -> moveWorkspaceFile(call.arguments)
      "workspace_delete_file" -> deleteWorkspaceFile(call.arguments)
      "command_exec" -> executeCommand(task = task, arguments = call.arguments, hooks = hooks)
      "python_exec" -> executePython(task = task, arguments = call.arguments)
      "skills_list" -> listSkills()
      "skill_read" -> readSkill(call.arguments)
      "mcp_list_servers" -> listMcpServers()
      else -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.FAILED,
        content = "Tool '${call.toolName}' is not registered.",
        errorCode = "TOOL_NOT_FOUND",
      )
    }
  } catch (error: Throwable) {
    AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.FAILED,
      content = error.message ?: "${call.toolName} failed.",
      errorCode = "TOOL_EXECUTION_FAILED",
      errorMessage = error.message ?: error::class.java.simpleName,
    )
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

  private fun writeWorkspaceFile(arguments: JsonObject): AgentToolResult {
    val path = boundary.resolve(arguments.requiredString("path"), label = "workspace write", defaultToRoot = false)
    val content = arguments.requiredString("content")
    val batchResult = fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Write(
          path = path,
          content = content,
        ),
      ),
    )
    return AgentToolResult(
      toolName = "workspace_write_file",
      status = AgentToolResultStatus.SUCCESS,
      content = "Wrote ${boundary.defaultRoot.relativize(path)} successfully.",
      metadata = mapOf(
        "path" to boundary.defaultRoot.relativize(path).toString(),
        "checkpointId" to batchResult.checkpointId,
        "checkpointEntryCount" to batchResult.checkpointEntryCount.toString(),
      ),
    )
  }

  private fun moveWorkspaceFile(arguments: JsonObject): AgentToolResult {
    val source = boundary.resolve(arguments.requiredString("source_path"), label = "workspace move source", defaultToRoot = false)
    val destination = boundary.resolve(
      arguments.requiredString("destination_path"),
      label = "workspace move destination",
      defaultToRoot = false,
    )
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
        "sourcePath" to boundary.defaultRoot.relativize(source).toString(),
        "destinationPath" to boundary.defaultRoot.relativize(destination).toString(),
      ),
    )
  }

  private fun deleteWorkspaceFile(arguments: JsonObject): AgentToolResult {
    val path = boundary.resolve(arguments.requiredString("path"), label = "workspace delete", defaultToRoot = false)
    fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Delete(path = path),
      ),
    )
    return AgentToolResult(
      toolName = "workspace_delete_file",
      status = AgentToolResultStatus.SUCCESS,
      content = "Deleted ${boundary.defaultRoot.relativize(path)}.",
      metadata = mapOf("path" to boundary.defaultRoot.relativize(path).toString()),
    )
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
    val executionResult = commandExecutor.execute(
      request = CommandExecutionRequest(
        taskId = task.id,
        command = command,
        args = arguments.optionalStringArray("args"),
        workingDirectory = workingDirectory.toString(),
        requestedAtEpochMs = System.currentTimeMillis(),
        metadata = mapOf("toolName" to "command_exec"),
      ),
      policyDecision = task.policyDecision,
      approvalToken = config.commandApprovalToken,
      hooks = hooks,
    )
    return executionResult.toAgentToolResult(toolName = "command_exec")
  }

  private fun executePython(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val scriptPath = boundary.resolve(arguments.requiredString("script_path"), label = "python script", defaultToRoot = false)
    val executionResult = config.pythonRuntimeAdapter.exec(
      request = PythonExecRequest(
        taskId = task.id,
        workspaceRoot = boundary.defaultRoot,
        scriptPath = scriptPath,
        args = arguments.optionalStringArray("args"),
      ),
    )
    return executionResult.toAgentToolResult(toolName = "python_exec")
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

  private fun JsonObject.requiredString(name: String): String =
    optionalString(name)?.takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("Required argument '$name' must be a non-blank string.")

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
