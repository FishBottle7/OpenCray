package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.filesystem.FileMutationOperation
import com.opencray.filesystem.FileOpsService
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpRuntimeSupport
import com.opencray.mcp.McpToolExposure
import com.opencray.policy.ModePolicy
import com.opencray.runtime.memory.MemorySearchMatch
import com.opencray.runtime.memory.MemorySearchService
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.policy.ToolCapabilityClassifier
import com.opencray.runtime.policy.ToolCallNormalizer
import com.opencray.runtime.policy.ToolMetadataContextRequest
import com.opencray.runtime.policy.ToolPolicyPipeline
import com.opencray.runtime.policy.ToolPolicyEvaluator
import com.opencray.runtime.policy.ToolMetadataContext
import com.opencray.runtime.policy.ToolPolicySupport
import com.opencray.runtime.policy.DelegationIntent
import com.opencray.runtime.policy.DelegationIntentKind
import com.opencray.runtime.policy.ExecutionIntent
import com.opencray.runtime.policy.ExecutionIntentKind
import com.opencray.runtime.policy.ExecutionTransport
import com.opencray.runtime.policy.ProcessLifecycleIntent
import com.opencray.runtime.policy.ProcessLifecycleIntentKind
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolTargetResolver
import com.opencray.runtime.policy.ToolRuntimeIntent
import com.opencray.runtime.policy.ToolResultEnvelope
import com.opencray.runtime.policy.ToolResultLimitKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.skills.SkillPackageBatchInstallEntry
import com.opencray.runtime.skills.SkillPackageCheckResult
import com.opencray.runtime.skills.SkillInstallManifestEntry
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.skills.SkillPackageUpdateResult
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
import java.security.MessageDigest
import java.util.Locale
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
  val allowedToolNames: Set<String>? = null,
  val extraPolicyReadRoots: Set<Path> = emptySet(),
  val extraPolicyWriteRoots: Set<Path> = emptySet(),
  val skillsRoots: List<File> = emptyList(),
  val skillPackageManager: SkillPackageManager? = null,
  val mcpExposureReport: McpClientExposureReport? = null,
  val modePolicy: ModePolicy = ModePolicy(),
  val approvedTaskId: String? = null,
  val approvedToolName: String? = null,
  val commandExecutor: CommandExecutor? = null,
  val pythonRuntimeAdapter: PythonScriptRuntime = HostProcessPythonRuntime(),
  val supportsManagedPythonProcessStart: Boolean = true,
  val managedPythonProcessUsesRuntimeAdapter: Boolean = false,
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
  private val toolCapabilityClassifier = ToolCapabilityClassifier()
  private val toolCallNormalizer = ToolCallNormalizer()
  private val toolPolicySupport = ToolPolicySupport()
  private val toolPolicyEvaluator = ToolPolicyEvaluator(
    modePolicy = config.modePolicy,
    approvedTaskId = config.approvedTaskId,
    approvedToolName = config.approvedToolName,
  )
  private val writeBoundary = WorkspaceBoundary(config.workspaceRoots)
  private val readBoundary = WorkspaceBoundary(config.readRoots)
  private val toolTargetResolver = ToolTargetResolver(
    readBoundary = readBoundary,
    writeBoundary = writeBoundary,
  )
  private val toolPolicyPipeline = ToolPolicyPipeline(
    toolPolicyEvaluator = toolPolicyEvaluator,
    toolPolicySupport = toolPolicySupport,
    toolCapabilityClassifier = toolCapabilityClassifier,
    toolTargetResolver = toolTargetResolver,
    workspaceRoot = writeBoundary.defaultRoot,
    readRoots = readBoundary.approvedRoots() + config.extraPolicyReadRoots,
    writeRoots = writeBoundary.approvedRoots() + config.extraPolicyWriteRoots,
  )
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
  private val allowedToolNames: Set<String>? = config.allowedToolNames
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    ?.toSet()

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
          AgentToolParameter("domains", "string[]", required = false, description = "Optional domain filter. Only return results from these domains or their subdomains."),
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
        name = "Task",
        description = "Delegate one bounded read-only subtask to a child runtime and wait for its summarized result before continuing.",
        parameters = listOf(
          AgentToolParameter("description", "string", required = true, description = "Short task label for the delegated child run."),
          AgentToolParameter("prompt", "string", required = true, description = "Exact instructions for the child run."),
          AgentToolParameter("subagent_type", "string", required = true, description = "Child profile id such as general-purpose, researcher, or reviewer."),
          AgentToolParameter("context_mode", "string", required = false, description = "Optional child context override. Supported values: minimal, delegated, mirrored."),
        ),
      ),
      AgentToolDefinition(
        name = "Bash",
        description = "Run one shell command string inside the approved workspace through the host shell. Each call starts a fresh managed shell process; if it keeps running after the initial wait, continue with ProcessRead, ProcessWait, or ProcessTerminate. Do not use Bash for python/python3/py invocations or Python runtime diagnostics.",
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
        description = "Start a managed background command inside the approved workspace and return a process id for later inspection. Use python_exec instead of ProcessStart for workspace Python scripts unless the runtime explicitly supports managed Python process launches.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = false, description = "Executable to launch. Provide exactly one of command or script_path."),
          AgentToolParameter("script_path", "string", required = false, description = "Workspace-relative Python script to launch through the managed Python runner on runtimes that support host Python processes. Prefer python_exec for workspace-local Python scripts."),
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
        description = "Execute one workspace-local Python script through the active Python runtime backend. Use this instead of Bash for workspace Python scripts and Python runtime diagnostics.",
        parameters = listOf(
          AgentToolParameter("script_path", "string", required = true, description = "Script path relative to the workspace root."),
          AgentToolParameter("args", "string[]", required = false, description = "Script arguments."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "Maximum runtime before the Python execution is timed out."),
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
        name = "SkillsFind",
        description = "Search installable skills from the remote skills index and the host-managed local catalog.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = false, description = "Optional case-insensitive search query. Non-blank queries also search the remote skills index."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of combined results to return."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsInspect",
        description = "Inspect an explicit local path, GitHub source, or GitLab source and list the installable skills it contains before installation.",
        parameters = listOf(
          AgentToolParameter("source_ref", "string", required = true, description = "Explicit local path, owner/repo, gitlab:group/project/repo, GitHub URL, GitLab URL, or supported git remote URL."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsList",
        description = "List skills currently installed in the host-managed skills directory.",
      ),
      AgentToolDefinition(
        name = "SkillsCheck",
        description = "Check installed skills against their recorded source provenance and report whether updates are available.",
        parameters = listOf(
          AgentToolParameter("skill_id", "string", required = false, description = "Optional exact installed skill id to check. Defaults to all installed skills."),
          AgentToolParameter("all", "boolean", required = false, description = "Optional compatibility flag. When true, check all installed skills."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsAdd",
        description = "Install one skill from the host-managed local catalog, an explicit local path, or a supported remote source such as owner/repo, gitlab:group/project/repo, a GitHub URL, or a GitLab URL.",
        parameters = listOf(
          AgentToolParameter("source_ref", "string", required = true, description = "Catalog skill id, explicit local path, owner/repo, owner/repo@skill-name, gitlab:group/project/repo, GitHub URL, GitLab URL, or supported git remote URL."),
          AgentToolParameter("skill", "string", required = false, description = "Optional explicit skill name when a remote source exposes multiple skills."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsAddBatch",
        description = "Install multiple skills from one explicit local path, GitHub source, or GitLab source through the shared host-managed skills pipeline.",
        parameters = listOf(
          AgentToolParameter("source_ref", "string", required = true, description = "Explicit local path, owner/repo, gitlab:group/project/repo, GitHub URL, GitLab URL, or supported git remote URL."),
          AgentToolParameter("skills", "string[]", required = false, description = "Optional explicit skill names to install from the inspected source."),
          AgentToolParameter("install_all", "boolean", required = false, description = "When true, install every valid skill discovered in the source."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsUpdate",
        description = "Update installed skills in place using their recorded source provenance.",
        parameters = listOf(
          AgentToolParameter("skill_id", "string", required = false, description = "Optional exact installed skill id to update. Defaults to all installed skills."),
          AgentToolParameter("all", "boolean", required = false, description = "Optional compatibility flag. When true, update all installed skills."),
          AgentToolParameter("yes", "boolean", required = false, description = "Optional compatibility flag. Native updates are non-interactive, so this is accepted but ignored."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsRemove",
        description = "Remove one installed skill from the host-managed skills directory.",
        parameters = listOf(
          AgentToolParameter("skill_id", "string", required = true, description = "Exact installed skill id."),
        ),
      ),
      AgentToolDefinition(
        name = "mcp_list_servers",
        description = "Inspect currently exposed MCP servers and their trust state. This runtime does not proxy remote MCP tools yet.",
      ),
    ) + memoryToolDefinitions()
    val visibleCanonicalDefinitions = allowedToolNames?.let { allowed ->
      canonicalDefinitions.filter { definition -> definition.name in allowed }
    } ?: canonicalDefinitions
    val aliasDefinitions = toolCallNormalizer.aliasDefinitions(visibleCanonicalDefinitions)
    return visibleCanonicalDefinitions + aliasDefinitions
  }

  fun restrictTo(allowedToolNames: Set<String>): OpenCrayToolDispatcher =
    OpenCrayToolDispatcher(
      config.copy(
        allowedToolNames = allowedToolNames
          .map(String::trim)
          .filter(String::isNotBlank)
          .toSet(),
      ),
    )

  internal fun planTaskDelegation(
    task: AgentTask,
    description: String,
    prompt: String,
    subagentType: String,
    contextMode: String,
    allowedToolNames: Set<String>,
  ): ToolPolicyPlan = toolPolicyPipeline.plan(
    task = task,
    toolName = "Task",
    targetPath = writeBoundary.defaultRoot,
    metadataRequest = ToolMetadataContextRequest(
      targetSummary = inlinePreview(description, maxChars = 256),
    ),
    intent = DelegationIntent(
      kind = DelegationIntentKind.SUBAGENT_TASK,
      subagentType = subagentType,
      contextMode = contextMode,
      description = inlinePreview(description, maxChars = 256),
      promptPreview = inlinePreview(prompt, maxChars = 512),
      allowedToolNames = allowedToolNames,
    ),
  )

  internal fun gateTaskDelegation(plan: ToolPolicyPlan): AgentToolResult? =
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = emptyMap(),
      askDetail = "Approval is required before Task can delegate this work.",
      denyDetail = "Policy denied Task.",
    )

  internal fun taskDelegationResultMetadata(
    plan: ToolPolicyPlan,
    metadata: Map<String, String> = emptyMap(),
    includeOutcome: Boolean = false,
  ): Map<String, String> = toolPolicyPipeline.resultMetadata(
    plan = plan,
    metadata = metadata,
    includeOutcome = includeOutcome,
  )

  fun dispatch(
    task: AgentTask,
    call: AgentToolCall,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val invocation = toolCallNormalizer.normalize(call)
    if (allowedToolNames != null && invocation.normalizedToolName !in allowedToolNames) {
      return toolCallNormalizer.decorateResult(
        result = AgentToolResult(
          toolName = invocation.requestedToolName,
          status = AgentToolResultStatus.DENIED,
          content = "Tool '${invocation.requestedToolName}' is unavailable in this delegated child runtime.",
          errorCode = "SUBAGENT_TOOL_NOT_ALLOWED",
          errorMessage = "Tool '${invocation.requestedToolName}' is unavailable in this delegated child runtime.",
          metadata = mapOf(
            "allowedToolNames" to allowedToolNames.sorted().joinToString(separator = ","),
          ),
        ),
        invocation = invocation,
      )
    }
    return try {
      val result = when (invocation.normalizedToolName) {
        "workspace_list_files" -> listWorkspaceFiles(task = task, arguments = invocation.arguments)
        "workspace_read_file" -> readWorkspaceFile(task = task, arguments = invocation.arguments)
        "workspace_write_file" -> writeWorkspaceFile(task = task, arguments = invocation.arguments)
        "workspace_import_file" -> importFileIntoWorkspace(task = task, arguments = invocation.arguments)
        "workspace_move_file" -> moveWorkspaceFile(task = task, arguments = invocation.arguments)
        "workspace_delete_file" -> deleteWorkspaceFile(task = task, arguments = invocation.arguments)
        "LS" -> listFilesForClaude(task = task, arguments = invocation.arguments)
        "Read" -> readFileForClaude(task = task, arguments = invocation.arguments)
        "Write" -> writeFileForClaude(task = task, arguments = invocation.arguments)
        "Grep" -> grepWorkspace(task = task, arguments = invocation.arguments)
        "Glob" -> globWorkspace(task = task, arguments = invocation.arguments)
        "ImportFile" -> importFileForClaude(task = task, arguments = invocation.arguments)
        "WebSearch" -> webSearch(task = task, arguments = invocation.arguments)
        "WebFetch" -> webFetch(task = task, arguments = invocation.arguments)
        "Edit" -> editWorkspaceFile(task = task, arguments = invocation.arguments)
        "MultiEdit" -> multiEditWorkspaceFile(task = task, arguments = invocation.arguments)
        "TodoWrite" -> writeTodoList(arguments = invocation.arguments)
        "Bash" -> executeClaudeBash(task = task, arguments = invocation.arguments)
        "ProcessStart" -> startManagedProcess(task = task, arguments = invocation.arguments)
        "ProcessList" -> listManagedProcesses()
        "ProcessRead" -> readManagedProcess(arguments = invocation.arguments)
        "ProcessWait" -> waitForManagedProcess(arguments = invocation.arguments)
        "ProcessTerminate" -> terminateManagedProcess(task = task, arguments = invocation.arguments)
        "command_exec" -> executeCommand(task = task, arguments = invocation.arguments, hooks = hooks)
        "python_exec" -> executePython(task = task, arguments = invocation.arguments)
        "skills_list" -> listSkills()
        "skill_read" -> readSkill(invocation.arguments)
        "SkillsFind" -> findSkillPackages(task = task, arguments = invocation.arguments)
        "SkillsInspect" -> inspectSkillPackageSource(task = task, arguments = invocation.arguments)
        "SkillsList" -> listInstalledSkillPackages(task = task)
        "SkillsCheck" -> checkInstalledSkillPackages(task = task, arguments = invocation.arguments)
        "SkillsAdd" -> installSkillPackage(task = task, arguments = invocation.arguments)
        "SkillsAddBatch" -> installSkillPackagesBatch(task = task, arguments = invocation.arguments)
        "SkillsUpdate" -> updateInstalledSkillPackages(task = task, arguments = invocation.arguments)
        "SkillsRemove" -> removeSkillPackage(task = task, arguments = invocation.arguments)
        "mcp_list_servers" -> listMcpServers()
        "memory_search" -> searchProjectedMemory(invocation.arguments)
        "memory_get" -> getProjectedMemory(invocation.arguments)
        else -> AgentToolResult(
          toolName = invocation.requestedToolName,
          status = AgentToolResultStatus.FAILED,
          content = "Tool '${invocation.requestedToolName}' is not registered.",
          errorCode = "TOOL_NOT_FOUND",
        )
      }
      toolCallNormalizer.decorateResult(result = result, invocation = invocation)
    } catch (error: Throwable) {
      toolCallNormalizer.decorateResult(
        result = AgentToolResult(
          toolName = invocation.requestedToolName,
          status = AgentToolResultStatus.FAILED,
          content = error.message ?: "${invocation.requestedToolName} failed.",
          errorCode = "TOOL_EXECUTION_FAILED",
          errorMessage = error.message ?: error::class.java.simpleName,
        ),
        invocation = invocation,
      )
    }
  }

  private fun listWorkspaceFiles(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val directory = toolTargetResolver.ensureReadableDirectory(
      candidate = arguments.optionalString("path"),
      label = "workspace list",
      defaultToRoot = true,
    )
    val maxEntries = arguments.optionalInt("max_entries")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "workspace_list_files",
      targetPath = directory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = directory,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayModelPath(directory)),
    )?.let { return it }

    val (entries, truncated) = Files.list(directory).use { stream ->
      val collected = mutableListOf<Path>()
      val iterator = stream.sorted().iterator()
      while (iterator.hasNext() && collected.size < maxEntries) {
        collected.add(iterator.next())
      }
      collected to iterator.hasNext()
    }
    val rendered = if (entries.isEmpty()) {
      "Directory is empty."
    } else {
      entries.joinToString(separator = "\n") { entry ->
        val kind = if (Files.isDirectory(entry)) "dir" else "file"
        "$kind\t${toolTargetResolver.displayModelPath(entry)}"
      }
    }
    return AgentToolResult(
      toolName = "workspace_list_files",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayModelPath(directory),
          "entryCount" to entries.size.toString(),
        ),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        ),
      ),
    )
  }

  private fun readWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = toolTargetResolver.ensureReadableFile(arguments.requiredString("path"), label = "workspace read")
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "workspace_read_file",
      targetPath = file,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = file,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayModelPath(file)),
    )?.let { return it }
    val bytes = Files.readAllBytes(file)
    val truncated = bytes.size > config.maxReadBytes
    val body = bytes.toString(StandardCharsets.UTF_8)
      .take(config.maxReadBytes)
      .ifBlank { "<empty file>" }
    return AgentToolResult(
      toolName = "workspace_read_file",
      status = AgentToolResultStatus.SUCCESS,
      content = body,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayModelPath(file),
          "byteCount" to bytes.size.toString(),
          "truncated" to truncated.toString(),
        ),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.READ_BYTE_BUDGET,
        ),
      ),
    )
  }

  private fun writeWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredString("path"),
      label = "workspace write",
      defaultToRoot = false,
    )
    return writeTextFile(
      task = task,
      toolName = "workspace_write_file",
      path = path,
      content = arguments.requiredText("content"),
      metadataPathKey = "path",
      successMessage = "Wrote ${toolTargetResolver.displayWritablePath(path)} successfully.",
    )
  }

  private fun listFilesForClaude(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val directory = toolTargetResolver.ensureReadableDirectory(
      candidate = arguments.optionalString("path"),
      label = "LS",
      defaultToRoot = true,
    )
    val maxEntries = arguments.optionalInt("max_entries")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "LS",
      targetPath = directory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = directory,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayModelPath(directory)),
    )?.let { return it }
    val (entries, truncated) = Files.list(directory).use { stream ->
      val collected = mutableListOf<Path>()
      val iterator = stream.sorted().iterator()
      while (iterator.hasNext() && collected.size < maxEntries) {
        collected.add(iterator.next())
      }
      collected to iterator.hasNext()
    }
    val rendered = if (entries.isEmpty()) {
      "Directory is empty."
    } else {
      entries.joinToString(separator = "\n") { entry ->
        val kind = if (Files.isDirectory(entry)) "dir" else "file"
        "$kind\t${toolTargetResolver.displayModelPath(entry)}"
      }
    }
    return AgentToolResult(
      toolName = "LS",
      status = AgentToolResultStatus.SUCCESS,
      content = rendered,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayModelPath(directory),
          "entryCount" to entries.size.toString(),
        ),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        ),
      ),
    )
  }

  private fun readFileForClaude(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = toolTargetResolver.ensureReadableFile(
      arguments.requiredStringFrom("file_path", "path"),
      label = "Read",
    )
    val offset = arguments.optionalInt("offset") ?: 1
    require(offset >= 1) { "Read offset must be >= 1." }
    val limit = arguments.optionalInt("limit")
    require(limit == null || limit >= 1) { "Read limit must be >= 1 when provided." }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "Read",
      targetPath = file,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = file,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("filePath" to toolTargetResolver.displayModelPath(file)),
    )?.let { return it }

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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "filePath" to toolTargetResolver.displayModelPath(file),
          "byteCount" to bytes.size.toString(),
          "totalLineCount" to lines.size.toString(),
          "offset" to offset.toString(),
          "returnedLineCount" to returnedLineCount.toString(),
          "truncated" to truncated.toString(),
        ) + (limit?.let { mapOf("limit" to it.toString()) } ?: emptyMap()),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.READ_BYTE_BUDGET,
        ),
      ),
    )
  }

  private fun writeFileForClaude(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("file_path", "path"),
      label = "Write",
      defaultToRoot = false,
    )
    return writeTextFile(
      task = task,
      toolName = "Write",
      path = path,
      content = arguments.requiredText("content"),
      metadataPathKey = "filePath",
      successMessage = "Wrote ${toolTargetResolver.displayWritablePath(path)} successfully.",
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
    val source = toolTargetResolver.resolveReadablePath(
      arguments.requiredString("source_path"),
      label = "import source",
      defaultToRoot = false,
    )
    require(Files.exists(source)) { "Import source does not exist: $source" }
    val destination = toolTargetResolver.resolveWritablePath(
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
      "Import destination already exists: ${toolTargetResolver.displayWritablePath(destination)}"
    }

    val targetKind = if (Files.isDirectory(source)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = toolName,
      targetPath = destination,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = targetKind,
        primaryPath = source,
        secondaryPath = destination,
        primaryTargetPath = toolTargetResolver.displayModelPath(source),
        secondaryTargetPath = toolTargetResolver.displayWritablePath(destination),
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "sourcePath" to toolTargetResolver.displayModelPath(source),
        "destinationPath" to toolTargetResolver.displayWritablePath(destination),
      ),
    )?.let { return it }

    copyIntoWorkspace(source = source, destination = destination)
    val artifactMetadata = if (Files.isRegularFile(source)) {
      attachmentArtifactMetadata(destination)
    } else {
      emptyMap()
    }

    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Imported ${toolTargetResolver.displayModelPath(source)} into ${toolTargetResolver.displayWritablePath(destination)}.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "sourcePath" to toolTargetResolver.displayModelPath(source),
          "destinationPath" to toolTargetResolver.displayWritablePath(destination),
        ) + artifactMetadata,
      ),
    )
  }

  private fun grepWorkspace(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val pattern = arguments.requiredString("pattern")
    val regex = runCatching { Regex(pattern) }
      .getOrElse { error -> throw IllegalArgumentException("Invalid Grep pattern: ${error.message}") }
    val searchRoot = toolTargetResolver.resolveSearchRoot(arguments.optionalString("path"), label = "Grep path")
    val globMatcher = arguments.optionalString("glob")?.let(::compileGlobMatcher)
    val maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "Grep",
      targetPath = searchRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.SEARCH_ROOT,
        primaryPath = searchRoot,
        targetSummary = "$pattern @ ${toolTargetResolver.displayModelPath(searchRoot)}",
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", toolTargetResolver.displayModelPath(searchRoot))
        put("pattern", pattern)
        arguments.optionalString("glob")?.let { put("glob", it) }
      },
    )?.let { return it }
    val matches = mutableListOf<String>()
    var truncated = false

    for (file in collectRegularFiles(searchRoot)) {
      if (globMatcher != null && !globMatcher.matches(toolTargetResolver.displayModelPath(file))) {
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
              collected.add("${toolTargetResolver.displayModelPath(file)}:$lineNumber:$line")
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
        truncated = true
        break
      }
    }

    return AgentToolResult(
      toolName = "Grep",
      status = AgentToolResultStatus.SUCCESS,
      content = matches.joinToString(separator = "\n").ifBlank { "No matches found." },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayModelPath(searchRoot),
          "pattern" to pattern,
          "matchCount" to matches.size.toString(),
        ) + (arguments.optionalString("glob")?.let { mapOf("glob" to it) } ?: emptyMap()),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.SEARCH_MATCH_LIMIT,
        ),
      ),
    )
  }

  private fun globWorkspace(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val pattern = arguments.requiredString("pattern")
    val matcher = compileGlobMatcher(pattern)
    val searchRoot = toolTargetResolver.resolveSearchRoot(arguments.optionalString("path"), label = "Glob path")
    val maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxDirectoryEntries)
      ?: config.maxDirectoryEntries
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "Glob",
      targetPath = searchRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.SEARCH_ROOT,
        primaryPath = searchRoot,
        targetSummary = "$pattern @ ${toolTargetResolver.displayModelPath(searchRoot)}",
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf(
        "path" to toolTargetResolver.displayModelPath(searchRoot),
        "pattern" to pattern,
      ),
    )?.let { return it }
    val matches = mutableListOf<String>()
    var truncated = false

    for (candidate in collectSearchCandidates(searchRoot)) {
      if (matcher.matches(toolTargetResolver.displayModelPath(candidate))) {
        matches.add(toolTargetResolver.displayModelPath(candidate))
      }
      if (matches.size >= maxResults) {
        truncated = true
        break
      }
    }

    return AgentToolResult(
      toolName = "Glob",
      status = AgentToolResultStatus.SUCCESS,
      content = matches.joinToString(separator = "\n").ifBlank { "No matches found." },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayModelPath(searchRoot),
          "matchCount" to matches.size.toString(),
          "pattern" to pattern,
        ),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.SEARCH_MATCH_LIMIT,
        ),
      ),
    )
  }

  private fun webSearch(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val query = arguments.requiredString("query")
    val maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxWebSearchResults)
      ?: config.maxWebSearchResults
    val domains = arguments.optionalStringArray("domains")
      .map(String::trim)
      .filter(String::isNotEmpty)
      .distinct()
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "WebSearch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = inlinePreview(query, maxChars = 256),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("query", inlinePreview(query, maxChars = 256))
        if (domains.isNotEmpty()) {
          put("domains", domains.joinToString(separator = ","))
        }
      },
      askDetail = "Approval is required before WebSearch can access the network.",
      denyDetail = "Policy denied WebSearch.",
    )?.let { return it }

    val result = webSearchProvider.search(
      WebSearchRequest(
        query = query,
        maxResults = maxResults,
        domains = domains,
      ),
    )
    if (!result.isSuccess) {
      return AgentToolResult(
        toolName = "WebSearch",
        status = AgentToolResultStatus.FAILED,
        content = result.errorMessage ?: "Web search failed.",
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        metadata = toolPolicyPipeline.policyMetadata(plan) + mapOf(
          "providerName" to result.providerName,
          "query" to query,
          "requestedMaxResults" to maxResults.toString(),
        ) + domainsMetadata(domains),
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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "providerName" to result.providerName,
          "query" to query,
          "resultCount" to result.results.size.toString(),
          "requestedMaxResults" to maxResults.toString(),
        ) + domainsMetadata(domains),
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = false,
          limitKind = ToolResultLimitKind.WEB_SEARCH_RESULT_LIMIT,
        ),
      ),
    )
  }

  private fun domainsMetadata(domains: List<String>): Map<String, String> =
    if (domains.isEmpty()) {
      emptyMap()
    } else {
      mapOf(
        "domains" to domains.joinToString(separator = ","),
        "requestedDomainCount" to domains.size.toString(),
      )
    }

  private fun webFetch(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val url = arguments.requiredString("url")
    val maxChars = arguments.optionalInt("max_chars")?.coerceIn(256, config.maxWebFetchChars)
      ?: config.maxWebFetchChars
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "WebFetch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = inlinePreview(url, maxChars = 512),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
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
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("requestedUrl", result.requestedUrl)
            put("finalUrl", result.finalUrl)
            put("requestedMaxChars", maxChars.toString())
            result.statusCode?.let { put("statusCode", it.toString()) }
            result.contentType?.let { put("contentType", it) }
          },
          resultEnvelope = ToolResultEnvelope(
            limitApplied = true,
            truncated = result.truncated,
            limitKind = ToolResultLimitKind.WEB_FETCH_CHAR_LIMIT,
          ),
        ),
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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("requestedUrl", result.requestedUrl)
          put("finalUrl", result.finalUrl)
          put("requestedMaxChars", maxChars.toString())
          put("truncated", result.truncated.toString())
          result.statusCode?.let { put("statusCode", it.toString()) }
          result.contentType?.let { put("contentType", it) }
          result.title?.let { put("title", it) }
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = result.truncated,
          limitKind = ToolResultLimitKind.WEB_FETCH_CHAR_LIMIT,
        ),
      ),
    )
  }

  private fun editWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("file_path", "path"),
      label = "Edit",
      defaultToRoot = false,
    ).also { resolved ->
      require(Files.isRegularFile(resolved)) { "Edit path is not a file: $resolved" }
    }
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
      successMessage = "Updated ${toolTargetResolver.displayModelPath(file)} with ${outcome.replacementCount} replacement(s).",
      extraMetadata = mapOf("replacementCount" to outcome.replacementCount.toString()),
    )
  }

  private fun multiEditWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("file_path", "path"),
      label = "MultiEdit",
      defaultToRoot = false,
    ).also { resolved ->
      require(Files.isRegularFile(resolved)) { "MultiEdit path is not a file: $resolved" }
    }
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
      successMessage = "Updated ${toolTargetResolver.displayModelPath(file)} with ${outcome.replacementCount} replacement(s) across ${edits.size} edit(s).",
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
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "TodoWrite",
        request = ToolMetadataContextRequest(
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = "${snapshot.size} todo(s)",
        ),
        metadata = mapOf(
          "todoCount" to snapshot.size.toString(),
          "mutated" to (todos != null).toString(),
        ),
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
    unsupportedBashPythonInvocation(arguments = arguments)?.let { return it }
    val launch = resolveBashLaunch(arguments = arguments)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "Bash",
      metadataRequest = launch.metadataRequest,
      intent = launch.intent,
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf(
        "toolName" to "Bash",
        "workingDirectory" to toolTargetResolver.displayModelPath(launch.workingDirectory),
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
        metadata = toolPolicyPipeline.policyMetadata(plan) + mapOf(
          "workingDirectory" to toolTargetResolver.displayModelPath(launch.workingDirectory),
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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = managedProcessMetadata(startedSnapshot) + mapOf(
          "waitTimeoutMs" to waitTimeoutMs.toString(),
          "background" to background.toString(),
        ),
        resultEnvelope = managedProcessResultEnvelope(startedSnapshot),
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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = managedProcessMetadata(waitedSnapshot) + mapOf(
          "waitTimeoutMs" to waitTimeoutMs.toString(),
          "background" to background.toString(),
        ),
        resultEnvelope = managedProcessResultEnvelope(waitedSnapshot),
      ),
    )
  }

  private fun resolveBashLaunch(arguments: JsonObject): ManagedProcessLaunch {
    val command = arguments.requiredString("command")
    val workingDirectory = toolTargetResolver.resolveWritablePath(
      candidate = arguments.optionalString("working_directory"),
      label = "Bash working directory",
      defaultToRoot = true,
    )
    val shell = defaultShellPlan(command)
    return ManagedProcessLaunch(
      command = shell.executable,
      args = shell.args,
      workingDirectory = workingDirectory,
      intent = ExecutionIntent(
        kind = ExecutionIntentKind.SHELL_COMMAND,
        transport = ExecutionTransport.MANAGED_PROCESS,
        commandPreview = inlinePreview(command),
        workingDirectory = toolTargetResolver.displayModelPath(workingDirectory),
      ),
      metadata = mapOf(
        "runtimeKind" to "bash",
        "shellKind" to shell.kind,
        "shellCommand" to inlinePreview(command),
      ),
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.WORKING_DIRECTORY,
        primaryPath = workingDirectory,
        primaryTargetPath = toolTargetResolver.displayModelPath(workingDirectory),
        targetSummary = inlinePreview(command),
      ),
    )
  }

  private fun unsupportedBashPythonInvocation(arguments: JsonObject): AgentToolResult? {
    val command = arguments.requiredString("command").trimStart()
    val normalized = command.lowercase()
    val pythonExecutable = when {
      normalized == "python" || normalized.startsWith("python ") -> "python"
      normalized == "python3" || normalized.startsWith("python3 ") -> "python3"
      normalized == "py" || normalized.startsWith("py ") -> "py"
      else -> null
    } ?: return null
    return AgentToolResult(
      toolName = "Bash",
      status = AgentToolResultStatus.FAILED,
      content = buildString {
        append("Bash cannot be used for Python commands or Python runtime diagnostics on this runtime. ")
        append("Use python_exec instead. ")
        append("If you need Python version or environment details, create or reuse a small workspace-local probe script and run it with python_exec.")
      },
      errorCode = "BASH_PYTHON_UNSUPPORTED",
      errorMessage = "Bash Python invocation is blocked. Use python_exec for Python-related tasks.",
      metadata = toolPolicySupport.commonMetadata(
        toolName = "Bash",
        metadataContext = policyMetadataContext(
          toolName = "Bash",
          targetKind = ToolTargetKind.WORKING_DIRECTORY,
          primaryPath = null,
          targetSummary = inlinePreview(command),
        ),
      ) + mapOf(
        "pythonCommand" to pythonExecutable,
        "recommendedTool" to "python_exec",
        "bashPythonInvocationBlocked" to "true",
      ),
    )
  }

  private fun startManagedProcess(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val timeoutMs = arguments.optionalLong("timeout_ms") ?: DEFAULT_MANAGED_PROCESS_TIMEOUT_MS
    unsupportedManagedPythonProcessStart(arguments = arguments)?.let { return it }
    val launch = resolveManagedProcessLaunch(arguments = arguments, timeoutMs = timeoutMs)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ProcessStart",
      metadataRequest = launch.metadataRequest,
      intent = launch.intent,
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf(
        "toolName" to "ProcessStart",
        "workingDirectory" to toolTargetResolver.displayModelPath(launch.workingDirectory),
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
        metadata = toolPolicyPipeline.policyMetadata(plan) + mapOf(
          "workingDirectory" to toolTargetResolver.displayModelPath(launch.workingDirectory),
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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = managedProcessMetadata(snapshot),
        resultEnvelope = managedProcessResultEnvelope(snapshot),
      ),
    )
  }

  private fun unsupportedManagedPythonProcessStart(arguments: JsonObject): AgentToolResult? {
    if (config.supportsManagedPythonProcessStart) {
      return null
    }
    val command = arguments.optionalString("command")?.trim()?.takeIf(String::isNotBlank)
    val scriptPath = arguments.optionalString("script_path")?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (command != null) {
      return null
    }
    return AgentToolResult(
      toolName = "ProcessStart",
      status = AgentToolResultStatus.FAILED,
      content = "ProcessStart with script_path is unavailable on this runtime. Use python_exec for workspace-local Python scripts.",
      errorCode = "PROCESSSTART_PYTHON_UNSUPPORTED",
      errorMessage = "Managed Python process launches are disabled for this runtime.",
      metadata = mapOf(
        "scriptPath" to scriptPath.replace('\\', '/'),
        "runtimeCapability" to "managed_python_process_start_disabled",
      ),
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
    val workingDirectory = toolTargetResolver.resolveWritablePath(
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
        intent = ExecutionIntent(
          kind = ExecutionIntentKind.MANAGED_COMMAND,
          transport = ExecutionTransport.MANAGED_PROCESS,
          commandPreview = inlinePreview(requireNotNull(command)),
          workingDirectory = toolTargetResolver.displayModelPath(workingDirectory),
        ),
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.WORKING_DIRECTORY,
          primaryPath = workingDirectory,
          primaryTargetPath = toolTargetResolver.displayModelPath(workingDirectory),
          targetSummary = inlinePreview(requireNotNull(command)),
        ),
      )
    }

    val scriptPath = toolTargetResolver.resolveWritablePath(
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
    if (config.managedPythonProcessUsesRuntimeAdapter) {
      return ManagedProcessLaunch(
        command = "python_exec",
        args = userArgs,
        workingDirectory = workingDirectory,
        intent = ExecutionIntent(
          kind = ExecutionIntentKind.MANAGED_PYTHON_SCRIPT,
          transport = ExecutionTransport.MANAGED_PROCESS,
          scriptPath = toolTargetResolver.displayModelPath(scriptPath),
          workingDirectory = toolTargetResolver.displayModelPath(workingDirectory),
        ),
        metadata = mapOf(
          "runtimeKind" to "python_exec",
          "scriptPath" to toolTargetResolver.displayModelPath(scriptPath),
          "pythonExecutable" to pythonExecutable,
          "managedByPythonRuntime" to "true",
        ),
        affectedPaths = mapOf("scriptPath" to toolTargetResolver.displayModelPath(scriptPath)),
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.SCRIPT,
          primaryPath = scriptPath,
          secondaryPath = workingDirectory,
          primaryTargetPath = toolTargetResolver.displayModelPath(scriptPath),
          secondaryTargetPath = toolTargetResolver.displayModelPath(workingDirectory),
          targetSummary = toolTargetResolver.displayModelPath(scriptPath),
        ),
      )
    }
    val pythonCommand = HostProcessPythonRuntime.commandFor(pythonRequest)
    return ManagedProcessLaunch(
      command = pythonCommand.first(),
      args = pythonCommand.drop(1),
      workingDirectory = workingDirectory,
      intent = ExecutionIntent(
        kind = ExecutionIntentKind.MANAGED_PYTHON_SCRIPT,
        transport = ExecutionTransport.MANAGED_PROCESS,
        scriptPath = toolTargetResolver.displayModelPath(scriptPath),
        workingDirectory = toolTargetResolver.displayModelPath(workingDirectory),
      ),
      metadata = mapOf(
        "runtimeKind" to "python_exec",
        "scriptPath" to toolTargetResolver.displayModelPath(scriptPath),
        "pythonExecutable" to pythonExecutable,
      ),
      affectedPaths = mapOf("scriptPath" to toolTargetResolver.displayModelPath(scriptPath)),
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.SCRIPT,
        primaryPath = scriptPath,
        secondaryPath = workingDirectory,
        primaryTargetPath = toolTargetResolver.displayModelPath(scriptPath),
        secondaryTargetPath = toolTargetResolver.displayModelPath(workingDirectory),
        targetSummary = toolTargetResolver.displayModelPath(scriptPath),
      ),
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
          toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory)?.let { workingDirectory ->
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
      metadata = toolPolicySupport.commonMetadata(
        toolName = "ProcessList",
        metadataContext = policyMetadataContext(
          toolName = "ProcessList",
          targetKind = ToolTargetKind.PROCESS,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = "${snapshots.size} process(es)",
        ),
      ) + mapOf("processCount" to snapshots.size.toString()),
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
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "ProcessRead",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.PROCESS,
          primaryPath = managedProcessWorkingDirectoryPath(snapshot),
          primaryTargetPath = toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory),
          targetSummary = processId,
        ),
        metadata = managedProcessMetadata(snapshot),
        resultEnvelope = managedProcessResultEnvelope(snapshot),
      ),
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
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "ProcessWait",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.PROCESS,
          primaryPath = managedProcessWorkingDirectoryPath(snapshot),
          primaryTargetPath = toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory),
          targetSummary = processId,
        ),
        metadata = managedProcessMetadata(snapshot) + mapOf("waitTimeoutMs" to timeoutMs.toString()),
        resultEnvelope = managedProcessResultEnvelope(snapshot),
      ),
    )
  }

  private fun terminateManagedProcess(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val processId = arguments.requiredString("process_id")
    val currentSnapshot = processRegistry.read(processId)
      ?: return missingManagedProcess(processId = processId, toolName = "ProcessTerminate")
    val workingDirectoryPath = currentSnapshot.workingDirectory
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { candidate -> runCatching { Paths.get(candidate) }.getOrNull() }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ProcessTerminate",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.PROCESS,
        primaryPath = workingDirectoryPath,
        primaryTargetPath = toolTargetResolver.displayWorkingDirectory(currentSnapshot.workingDirectory),
        targetSummary = processId,
      ),
      intent = ProcessLifecycleIntent(
        kind = ProcessLifecycleIntentKind.TERMINATE,
        processId = processId,
        workingDirectory = toolTargetResolver.displayWorkingDirectory(currentSnapshot.workingDirectory),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("processId", processId)
        toolTargetResolver.displayWorkingDirectory(currentSnapshot.workingDirectory)?.let { workingDirectory ->
          put("workingDirectory", workingDirectory)
        }
      },
      askDetail = "Approval is required before ProcessTerminate can run.",
      denyDetail = "Policy denied ProcessTerminate.",
    )?.let { return it }
    val snapshot = processRegistry.terminate(processId)
      ?: return missingManagedProcess(processId = processId, toolName = "ProcessTerminate")
    val terminationSupport = snapshot.metadata["terminationSupport"]
    val terminationRequestAccepted = snapshot.metadata["terminationRequestAccepted"]
    val terminationMessage = when {
      snapshot.status == ManagedProcessStatus.CANCELLED ->
        "Managed process cancelled."
      terminationSupport == "cooperative" &&
        snapshot.status == ManagedProcessStatus.RUNNING &&
        terminationRequestAccepted == "true" ->
        "Managed process cancellation requested."
      terminationSupport == "cooperative" &&
        snapshot.status == ManagedProcessStatus.RUNNING &&
        terminationRequestAccepted == "false" ->
        "Managed process cancellation could not be delivered to the runtime and is still running."
      terminationSupport == "unsupported" &&
        snapshot.status == ManagedProcessStatus.RUNNING ->
        "Managed process does not support termination on this runtime and is still running."
      else -> "Managed process termination requested."
    }
    return AgentToolResult(
      toolName = "ProcessTerminate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine(terminationMessage)
        append(renderManagedProcessSnapshot(snapshot, includeOutput = true))
      }.trim(),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = managedProcessMetadata(snapshot),
        resultEnvelope = managedProcessResultEnvelope(snapshot),
      ),
    )
  }

  private fun moveWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val source = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredString("source_path"),
      label = "workspace move source",
      defaultToRoot = false,
    )
    val destination = toolTargetResolver.resolveWritablePath(
      arguments.requiredString("destination_path"),
      label = "workspace move destination",
      defaultToRoot = false,
    )
    val targetKind = if (Files.isDirectory(source)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE
    val artifactMetadata = if (Files.isRegularFile(source)) {
      attachmentArtifactMetadata(destination)
    } else {
      emptyMap()
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "workspace_move_file",
      targetPath = source,
      destinationPath = destination,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = targetKind,
        primaryPath = source,
        secondaryPath = destination,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "sourcePath" to toolTargetResolver.displayWritablePath(source),
        "destinationPath" to toolTargetResolver.displayWritablePath(destination),
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
      content = "Moved ${toolTargetResolver.displayWritablePath(source)} to ${toolTargetResolver.displayWritablePath(destination)}.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "sourcePath" to toolTargetResolver.displayWritablePath(source),
          "destinationPath" to toolTargetResolver.displayWritablePath(destination),
        ) + artifactMetadata,
      ),
    )
  }

  private fun deleteWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val path = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredString("path"),
      label = "workspace delete",
      defaultToRoot = false,
    )
    val targetKind = if (Files.isDirectory(path)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "workspace_delete_file",
      targetPath = path,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = targetKind,
        primaryPath = path,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayWritablePath(path)),
    )?.let { return it }
    fileOpsService.executeBatch(
      operations = listOf(
        FileMutationOperation.Delete(path = path),
      ),
    )
    return AgentToolResult(
      toolName = "workspace_delete_file",
      status = AgentToolResultStatus.SUCCESS,
      content = "Deleted ${toolTargetResolver.displayWritablePath(path)}.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayWritablePath(path),
        ),
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
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = toolName,
      targetPath = path,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = path,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        metadataPathKey to toolTargetResolver.displayPathForTool(toolName = toolName, path = path),
      ),
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
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          metadataPathKey to toolTargetResolver.displayPathForTool(toolName = toolName, path = path),
          "checkpointId" to batchResult.checkpointId,
          "checkpointEntryCount" to batchResult.checkpointEntryCount.toString(),
        ) + attachmentArtifactMetadata(path) + extraMetadata,
      ),
    )
  }

  private fun attachmentArtifactMetadata(path: Path): Map<String, String> {
    val relativePath = toolTargetResolver.displayWritablePath(path)
      .trim()
      .takeIf(String::isNotBlank)
      ?.takeIf { candidate -> candidate != "." }
      ?: return emptyMap()
    val displayName = path.fileName?.toString()
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyMap()
    val artifactId = buildAttachmentArtifactId(
      relativePath = relativePath,
      displayName = displayName,
    )
    return buildMap {
      put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID, artifactId)
      put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH, relativePath)
      put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME, displayName)
      attachmentArtifactKindHint(displayName)?.let { kindHint ->
        put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT, kindHint)
      }
      attachmentArtifactMimeType(displayName)?.let { mimeType ->
        put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE, mimeType)
      }
    }
  }

  private fun buildAttachmentArtifactId(
    relativePath: String,
    displayName: String,
  ): String {
    val baseName = displayName.substringBeforeLast('.', displayName)
      .lowercase(Locale.US)
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
      .ifBlank { "file" }
      .take(48)
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(relativePath.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }
      .take(8)
    return "artifact-$baseName-$digest"
  }

  private fun attachmentArtifactKindHint(displayName: String): String? {
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
    return when {
      extension in ATTACHMENT_IMAGE_EXTENSIONS -> "image"
      extension in ATTACHMENT_AUDIO_EXTENSIONS -> "voice"
      else -> "file"
    }
  }

  private fun attachmentArtifactMimeType(displayName: String): String? {
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
    return ATTACHMENT_FALLBACK_MIME_TYPES[extension]
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
    val workingDirectory = toolTargetResolver.resolveWritablePath(
      candidate = arguments.optionalString("working_directory"),
      label = "command working directory",
      defaultToRoot = true,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "command_exec",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.WORKING_DIRECTORY,
        primaryPath = workingDirectory,
        primaryTargetPath = toolTargetResolver.displayModelPath(workingDirectory),
        targetSummary = inlinePreview(command),
      ),
      intent = ExecutionIntent(
        kind = ExecutionIntentKind.HOST_COMMAND,
        transport = ExecutionTransport.FOREGROUND,
        commandPreview = inlinePreview(command),
        workingDirectory = toolTargetResolver.displayModelPath(workingDirectory),
      ),
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
        ) + toolPolicyPipeline.policyMetadata(plan),
      ),
      policyDecision = plan.policyDecision,
      approvalToken = config.commandApprovalToken,
      hooks = hooks,
    )
    val toolResult = executionResult.toAgentToolResult(toolName = "command_exec")
    return toolResult.copy(
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = toolResult.metadata,
        resultEnvelope = commandResultEnvelope(toolResult),
      ),
    )
  }

  private fun executePython(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val scriptPath = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredString("script_path"),
      label = "python script",
      defaultToRoot = false,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "python_exec",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.SCRIPT,
        primaryPath = scriptPath,
        targetSummary = toolTargetResolver.displayWritablePath(scriptPath),
      ),
      intent = ExecutionIntent(
        kind = ExecutionIntentKind.PYTHON_SCRIPT,
        transport = ExecutionTransport.PYTHON_RUNTIME,
        scriptPath = toolTargetResolver.displayWritablePath(scriptPath),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf("scriptPath" to toolTargetResolver.displayWritablePath(scriptPath)),
      askDetail = "Approval is required before python_exec can run.",
      denyDetail = "Policy denied python_exec.",
    )?.let { return it }
    val executionResult = config.pythonRuntimeAdapter.exec(
      request = PythonExecRequest(
        taskId = task.id,
        workspaceRoot = writeBoundary.defaultRoot,
        scriptPath = scriptPath,
        args = arguments.optionalStringArray("args"),
        timeoutMs = arguments.optionalLong("timeout_ms") ?: 30_000L,
      ),
    )
    val toolResult = executionResult.toAgentToolResult(toolName = "python_exec")
    return toolResult.copy(
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = toolResult.metadata + mapOf(
          "scriptPath" to toolTargetResolver.displayWritablePath(scriptPath),
        ),
        resultEnvelope = commandResultEnvelope(toolResult),
      ),
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
    metadata = toolPolicySupport.commonMetadata(
      toolName = toolName,
      metadataContext = policyMetadataContext(
        toolName = toolName,
        targetKind = ToolTargetKind.PROCESS,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = processId,
      ),
    ) + mapOf("processId" to processId),
  )

  private fun managedProcessWorkingDirectoryPath(snapshot: ManagedProcessSnapshot): Path? =
    snapshot.workingDirectory
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { candidate -> runCatching { Paths.get(candidate) }.getOrNull() }

  private fun managedProcessMetadata(snapshot: ManagedProcessSnapshot): Map<String, String> = buildMap {
    put("processId", snapshot.processId)
    put("processStatus", snapshot.status.name)
    put("processStarted", snapshot.processStarted.toString())
    put("timeoutMs", snapshot.timeoutMs.toString())
    put("command", snapshot.command)
    if (snapshot.args.isNotEmpty()) {
      put("args", snapshot.args.joinToString(separator = "\u0000"))
    }
    toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory)?.let { workingDirectory ->
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
    putAll(snapshot.metadata.filterKeys(::isManagedProcessRuntimeMetadataKey))
  }

  private fun managedProcessResultEnvelope(
    snapshot: ManagedProcessSnapshot,
  ): ToolResultEnvelope = ToolResultEnvelope(
    limitApplied = true,
    truncated = snapshot.outputLimitExceeded,
    limitKind = ToolResultLimitKind.PROCESS_OUTPUT_BYTE_LIMIT,
  )

  private fun commandResultEnvelope(
    result: AgentToolResult,
  ): ToolResultEnvelope = ToolResultEnvelope(
    limitApplied = true,
    truncated = result.errorCode == "OUTPUT_LIMIT_EXCEEDED",
    limitKind = ToolResultLimitKind.COMMAND_OUTPUT_BYTE_LIMIT,
  )

  private fun isManagedProcessRuntimeMetadataKey(key: String): Boolean = key !in MANAGED_PROCESS_RESERVED_METADATA_KEYS

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
    snapshot.metadata["terminationSupport"]?.let { terminationSupport ->
      appendLine("termination_support=$terminationSupport")
    }
    if (snapshot.metadata["terminationRequested"] == "true") {
      appendLine("termination_requested=true")
    }
    snapshot.metadata["terminationRequestAccepted"]?.let { terminationRequestAccepted ->
      appendLine("termination_request_accepted=$terminationRequestAccepted")
    }
    if (snapshot.args.isNotEmpty()) {
      appendLine("args=${snapshot.args.joinToString(separator = " ")}")
    }
    toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory)?.let { workingDirectory ->
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

  private fun listSkills(): AgentToolResult {
    val report = loadSkillsReport()
    val skillCount = report?.loadedSkills?.size ?: 0
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
      metadata = toolPolicySupport.commonMetadata(
        toolName = "skills_list",
        metadataContext = policyMetadataContext(
          toolName = "skills_list",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = "$skillCount skill(s)",
        ),
      ) + mapOf("skillCount" to skillCount.toString()),
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
      metadata = toolPolicySupport.commonMetadata(
        toolName = "skill_read",
        metadataContext = policyMetadataContext(
          toolName = "skill_read",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = loadedSkill.source.relativePath,
          targetSummary = loadedSkill.name,
        ),
      ) + mapOf(
        "skillName" to loadedSkill.name,
        "relativePath" to loadedSkill.source.relativePath,
      ),
    )
  }

  private fun findSkillPackages(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsFind")
    val query = arguments.optionalString("query")?.trim().orEmpty()
    val maxResults = (arguments.optionalInt("max_results") ?: config.maxDirectoryEntries)
      .coerceIn(1, config.maxDirectoryEntries)
    if (query.isNotBlank()) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsFind",
        url = "https://skills.sh/api/search",
        targetSummary = inlinePreview(query, maxChars = 256),
        affectedPaths = mapOf("query" to query),
      )?.let { return it }
    }
    val catalogRoot = packageManager.catalogRootPath().toPath().toAbsolutePath().normalize()
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsFind",
      targetPath = catalogRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = catalogRoot,
        primaryTargetPath = displaySkillPackagePath(catalogRoot),
        targetSummary = if (query.isBlank()) {
          displaySkillPackagePath(catalogRoot)
        } else {
          inlinePreview(query, maxChars = 256)
        },
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displaySkillPackagePath(catalogRoot)),
    )?.let { return it }

    val installedSkillIds = packageManager.listManagedSkills().mapTo(linkedSetOf()) { skill -> skill.name }
    val localMatches = packageManager.listCatalogSkills()
      .asSequence()
      .filter { skill ->
        query.isBlank() ||
          skill.name.contains(query, ignoreCase = true) ||
          skill.metadata.skillSpec.description.contains(query, ignoreCase = true)
      }
      .toList()
    val remoteSearch = if (query.isBlank()) {
      null
    } else {
      packageManager.searchRemoteSkills(
        query = query,
        limit = maxResults,
      )
    }
    if (remoteSearch?.errorCode != null && localMatches.isEmpty()) {
      return AgentToolResult(
        toolName = "SkillsFind",
        status = AgentToolResultStatus.FAILED,
        content = remoteSearch.errorMessage ?: "Remote skill search failed.",
        errorCode = remoteSearch.errorCode,
        errorMessage = remoteSearch.errorMessage,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "SkillsFind",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            primaryTargetPath = "https://skills.sh/api/search",
            workspaceRelation = ToolWorkspaceRelation.NONE,
            targetSummary = inlinePreview(query, maxChars = 256),
          ),
          metadata = mapOf(
            "query" to query,
            "providerName" to remoteSearch.providerName,
            "remoteResultCount" to "0",
            "localResultCount" to "0",
            "resultCount" to "0",
          ),
        ),
      )
    }

    val remoteLines = remoteSearch?.hits
      .orEmpty()
      .take(maxResults)
      .map { hit ->
        val installState = if (hit.name in installedSkillIds) "installed_remote" else "remote"
        "${hit.name}\t$installState\tinstall_ref=${hit.installRef}\tsource=${hit.source}\tinstalls=${hit.installs}\tdetail_url=${hit.detailUrl}"
      }
    val remainingLocalBudget = (maxResults - remoteLines.size).coerceAtLeast(0)
    val localLines = localMatches
      .take(remainingLocalBudget.takeIf { it > 0 } ?: 0)
      .map { skill ->
        val installState = if (skill.name in installedSkillIds) "installed_local" else "catalog"
        "${skill.name}\t$installState\tsource=local_catalog\tdescription=${skill.metadata.skillSpec.description}"
      }
    val lines = buildList {
      if (remoteSearch?.errorCode != null && localLines.isNotEmpty()) {
        add("Remote search unavailable: ${remoteSearch.errorMessage ?: remoteSearch.errorCode}")
      }
      addAll(remoteLines)
      addAll(localLines)
    }
    val content = if (lines.isEmpty()) {
      if (query.isBlank()) {
        "No skills were found in the host-managed catalog."
      } else {
        "No local or remote skills matched '$query'."
      }
    } else {
      lines.joinToString(separator = "\n")
    }
    return AgentToolResult(
      toolName = "SkillsFind",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displaySkillPackagePath(catalogRoot),
          "query" to query,
          "providerName" to (remoteSearch?.providerName ?: "local-catalog"),
          "remoteResultCount" to remoteLines.size.toString(),
          "localResultCount" to localLines.size.toString(),
          "resultCount" to (remoteLines.size + localLines.size).toString(),
        ),
      ),
    )
  }

  private fun listInstalledSkillPackages(task: AgentTask): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsList")
    packageManager.refreshManifest()
    val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsList",
      targetPath = managedRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = managedRoot,
        primaryTargetPath = displaySkillPackagePath(managedRoot),
        targetSummary = displaySkillPackagePath(managedRoot),
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displaySkillPackagePath(managedRoot)),
    )?.let { return it }

    val installationsById = packageManager.listInstallations().associateBy(SkillInstallManifestEntry::skillId)
    val managedSkills = packageManager.listManagedSkills()
    val content = if (managedSkills.isEmpty()) {
      "No skills are installed in the host-managed skills directory."
    } else {
      managedSkills.joinToString(separator = "\n") { skill ->
        val installation = installationsById[skill.name]
        val sourceType = installation?.sourceType ?: "unknown"
        val updatedAt = installation?.updatedAtEpochMs?.toString() ?: "unknown"
        "${skill.name}\t$sourceType\tupdated_at=$updatedAt\tdescription=${skill.metadata.skillSpec.description}"
      }
    }
    return AgentToolResult(
      toolName = "SkillsList",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displaySkillPackagePath(managedRoot),
          "skillCount" to managedSkills.size.toString(),
        ),
      ),
    )
  }

  private fun inspectSkillPackageSource(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsInspect")
    val sourceRef = arguments.requiredStringFrom("source_ref", "sourceRef", "source", "path", "url")
    val localSourcePath = resolveExplicitLocalSkillSourcePath(sourceRef)
    if (localSourcePath != null) {
      gateLocalSkillSourceReadAccess(
        task = task,
        resultToolName = "SkillsInspect",
        sourcePath = localSourcePath,
        sourceRef = sourceRef,
      )?.let { return it }

      val normalizedSourcePath = localSourcePath.toAbsolutePath().normalize()
      val displayPath = toolTargetResolver.displayModelPath(normalizedSourcePath)
      val plan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsInspect",
        targetPath = normalizedSourcePath,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = if (Files.isDirectory(normalizedSourcePath)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE,
          primaryPath = normalizedSourcePath,
          primaryTargetPath = displayPath,
          targetSummary = sourceRef,
        ),
      )
      gateReadOnlyTool(
        plan = plan,
        affectedPaths = mapOf("sourcePath" to displayPath),
      )?.let { return it }

      val attempt = packageManager.inspectLocalSource(
        sourcePath = localSourcePath.toFile(),
        sourceRef = sourceRef,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to inspect '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_SOURCE_INSPECTION_FAILED",
        errorMessage = attempt.errorMessage,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            "sourceRef" to sourceRef,
            "candidateCount" to "0",
          ),
        ),
      )
      return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.SUCCESS,
        content = renderSkillSourceInspection(result),
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("sourceRef", result.sourceRef)
            put("sourceType", result.sourceType)
            put("candidateCount", result.candidates.size.toString())
            result.sourcePath?.let { put("sourcePath", it) }
            result.resolvedRevision?.let { put("resolvedRevision", it) }
            result.resolvedCommitSha?.let { put("resolvedCommitSha", it) }
          },
        ),
      )
    }

    val remoteSource = packageManager.resolveRemoteSource(sourceRef = sourceRef)
    if (remoteSource != null) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsInspect",
        url = remoteSource.policyTargetUrl,
        targetSummary = remoteSource.requestedSourceRef,
        affectedPaths = mapOf("sourceRef" to remoteSource.requestedSourceRef),
      )?.let { return it }

      val metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryTargetPath = remoteSource.policyTargetUrl,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = remoteSource.requestedSourceRef,
      )
      val attempt = packageManager.inspectRemoteSource(sourceRef = sourceRef)
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to inspect '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_SOURCE_INSPECTION_FAILED",
        errorMessage = attempt.errorMessage,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "SkillsInspect",
          request = metadataRequest,
          metadata = mapOf(
            "sourceRef" to remoteSource.requestedSourceRef,
            "candidateCount" to "0",
          ),
        ),
      )
      return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.SUCCESS,
        content = renderSkillSourceInspection(result),
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "SkillsInspect",
          request = metadataRequest,
          metadata = buildMap {
            put("sourceRef", result.sourceRef)
            put("sourceType", result.sourceType)
            put("candidateCount", result.candidates.size.toString())
            result.sourcePath?.let { put("sourcePath", it) }
            result.resolvedRevision?.let { put("resolvedRevision", it) }
            result.resolvedCommitSha?.let { put("resolvedCommitSha", it) }
          },
        ),
      )
    }

    return AgentToolResult(
      toolName = "SkillsInspect",
      status = AgentToolResultStatus.FAILED,
      content = "Source '$sourceRef' is not a supported local path, GitHub source, or GitLab source.",
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "SkillsInspect",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NONE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = sourceRef,
        ),
        metadata = mapOf(
          "sourceRef" to sourceRef,
          "candidateCount" to "0",
        ),
      ),
    )
  }

  private fun checkInstalledSkillPackages(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsCheck")
    val requestedSkillId = arguments.optionalStringFrom("skill_id", "skillId", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    packageManager.refreshManifest()
    val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
    val displayManagedRoot = displaySkillPackagePath(managedRoot)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsCheck",
      targetPath = managedRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = managedRoot,
        primaryTargetPath = displayManagedRoot,
        targetSummary = requestedSkillId ?: displayManagedRoot,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displayManagedRoot),
    )?.let { return it }

    val managedSkills = packageManager.listManagedSkills()
    val managedSkillIds = managedSkills.mapTo(linkedSetOf()) { skill -> skill.name }
    val installations = packageManager.listInstallations()
    val relevantInstallations = installations.filter { entry ->
      requestedSkillId == null || entry.skillId == requestedSkillId
    }
    if (requestedSkillId != null &&
      requestedSkillId !in managedSkillIds &&
      relevantInstallations.isEmpty()
    ) {
      return AgentToolResult(
        toolName = "SkillsCheck",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$requestedSkillId' is not installed in the host-managed skills directory.",
        errorCode = "SKILL_NOT_INSTALLED",
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            "path" to displayManagedRoot,
            "skillId" to requestedSkillId,
            "checkedCount" to "0",
          ),
        ),
      )
    }
    relevantInstallations.forEach { entry ->
      if (!isRemoteSkillSourceType(entry.sourceType)) {
        return@forEach
      }
      val resolvedSource = packageManager.resolveRemoteSource(
        sourceRef = entry.sourceRef,
        selectedSkillName = entry.selectedSkillName,
      )
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsCheck",
        url = resolvedSource?.policyTargetUrl ?: entry.sourceRef,
        targetSummary = entry.sourceRef,
        affectedPaths = mapOf("sourceRef" to entry.sourceRef),
      )?.let { return it }
    }

    val report = packageManager.checkInstalledSkills(requestedSkillId)
    val content = if (report.results.isEmpty()) {
      "No installed skills are available for update checks."
    } else {
      report.results.joinToString(separator = "\n", transform = ::renderSkillPackageCheckLine)
    }
    return AgentToolResult(
      toolName = "SkillsCheck",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayManagedRoot)
          put("checkedCount", report.results.size.toString())
          put("upToDateCount", report.upToDateCount.toString())
          put("updateAvailableCount", report.updateAvailableCount.toString())
          put("sourceUnavailableCount", report.sourceUnavailableCount.toString())
          put("unsupportedCount", report.unsupportedCount.toString())
          requestedSkillId?.let { put("skillId", it) }
        },
      ),
    )
  }

  private fun installSkillPackage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsAdd")
    val sourceRef = arguments.requiredStringFrom("source_ref", "sourceRef", "skill_id", "skillId", "name")
    val selectedSkillName = arguments.optionalStringFrom("skill", "selected_skill", "selectedSkill")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val localSourcePath = resolveExplicitLocalSkillSourcePath(sourceRef)
    if (localSourcePath != null) {
      gateLocalSkillSourceReadAccess(
        task = task,
        resultToolName = "SkillsAdd",
        sourcePath = localSourcePath,
        sourceRef = sourceRef,
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val localPlan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAdd",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "$sourceRef -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = localPlan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromLocalSource(
        sourcePath = localSourcePath.toFile(),
        sourceRef = sourceRef,
        selectedSkillName = selectedSkillName,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to install '$sourceRef' from the local source.",
        errorCode = attempt.errorCode ?: "SKILL_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.SUCCESS,
        content = "Installed skill '${result.skillId}' from local source '$sourceRef'.",
        metadata = toolPolicyPipeline.resultMetadata(
          plan = localPlan,
          metadata = mapOf(
            "path" to displaySkillPackagePath(result.targetDirectory.toPath().toAbsolutePath().normalize()),
            "skillId" to result.skillId,
            "sourceType" to result.manifestEntry.sourceType,
            "sourceRef" to result.manifestEntry.sourceRef,
          ),
        ),
      )
    }

    val remoteSource = packageManager.resolveRemoteSource(
      sourceRef = sourceRef,
      selectedSkillName = selectedSkillName,
    )
    if (remoteSource != null) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsAdd",
        url = remoteSource.policyTargetUrl,
        targetSummary = remoteSource.requestedSourceRef,
        affectedPaths = mapOf("sourceRef" to remoteSource.requestedSourceRef),
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val remotePlan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAdd",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "${remoteSource.requestedSourceRef} -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = remotePlan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromRemoteSource(
        sourceRef = sourceRef,
        selectedSkillName = selectedSkillName,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to install '$sourceRef' from the remote source.",
        errorCode = attempt.errorCode ?: "SKILL_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.SUCCESS,
        content = "Installed skill '${result.skillId}' from remote source '${result.manifestEntry.sourceRef}'.",
        metadata = toolPolicyPipeline.resultMetadata(
          plan = remotePlan,
          metadata = mapOf(
            "path" to displaySkillPackagePath(result.targetDirectory.toPath().toAbsolutePath().normalize()),
            "skillId" to result.skillId,
            "sourceType" to result.manifestEntry.sourceType,
            "sourceRef" to result.manifestEntry.sourceRef,
          ) + listOfNotNull(
            result.manifestEntry.resolvedRevision?.let { "resolvedRevision" to it },
            result.manifestEntry.resolvedCommitSha?.let { "resolvedCommitSha" to it },
          ).toMap(),
        ),
      )
    }

    val skillId = sourceRef
    val targetDirectory = packageManager.resolveCatalogInstallTarget(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$skillId' was not found in the host-managed catalog.",
        errorCode = "SKILL_NOT_FOUND",
      )
    val targetPath = targetDirectory.toPath().toAbsolutePath().normalize()
    val displayPath = displaySkillPackagePath(targetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsAdd",
      targetPath = targetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = targetPath,
        primaryTargetPath = displayPath,
        targetSummary = "$skillId -> $displayPath",
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf("path" to displayPath),
    )?.let { return it }

    val result = packageManager.installFromCatalog(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = "Failed to install '$skillId' from the host-managed catalog.",
        errorCode = "SKILL_INSTALL_FAILED",
      )
    return AgentToolResult(
      toolName = "SkillsAdd",
      status = AgentToolResultStatus.SUCCESS,
      content = "Installed skill '${result.skillId}' from the host-managed catalog.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displayPath,
          "skillId" to result.skillId,
          "sourceType" to result.manifestEntry.sourceType,
        ),
      ),
    )
  }

  private fun installSkillPackagesBatch(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsAddBatch")
    val sourceRef = arguments.requiredStringFrom("source_ref", "sourceRef", "source", "path", "url")
    val selectedSkillNames = arguments.optionalStringArrayFrom(
      "skills",
      "selected_skills",
      "selectedSkills",
      "skill_ids",
      "skillIds",
    )
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    val installAll = arguments.optionalBooleanFrom("install_all", "installAll", "all") == true

    val localSourcePath = resolveExplicitLocalSkillSourcePath(sourceRef)
    if (localSourcePath != null) {
      gateLocalSkillSourceReadAccess(
        task = task,
        resultToolName = "SkillsAddBatch",
        sourcePath = localSourcePath,
        sourceRef = sourceRef,
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val plan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAddBatch",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "$sourceRef -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromLocalSourceBatch(
        sourcePath = localSourcePath.toFile(),
        sourceRef = sourceRef,
        selectedSkillNames = selectedSkillNames,
        installAll = installAll,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to batch install from local source '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_BATCH_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      val status = if (result.failedCount > 0) {
        AgentToolResultStatus.FAILED
      } else {
        AgentToolResultStatus.SUCCESS
      }
      return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = status,
        content = renderSkillBatchInstallResult(result),
        errorCode = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorCode)
            ?: "SKILL_BATCH_INSTALL_FAILED"
        } else {
          null
        },
        errorMessage = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorMessage)
        } else {
          null
        },
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("path", displayManagedRoot)
            put("sourceType", result.sourceType)
            put("sourceRef", result.sourceRef)
            put("requestedCount", result.requestedCount.toString())
            put("installedCount", result.installedCount.toString())
            put("failedCount", result.failedCount.toString())
            result.entries
              .mapNotNull(SkillPackageBatchInstallEntry::installedSkillId)
              .singleOrNull()
              ?.let { put("skillId", it) }
          },
        ),
      )
    }

    val remoteSource = packageManager.resolveRemoteSource(sourceRef = sourceRef)
    if (remoteSource != null) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsAddBatch",
        url = remoteSource.policyTargetUrl,
        targetSummary = remoteSource.requestedSourceRef,
        affectedPaths = mapOf("sourceRef" to remoteSource.requestedSourceRef),
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val plan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAddBatch",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "${remoteSource.requestedSourceRef} -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromRemoteSourceBatch(
        sourceRef = sourceRef,
        selectedSkillNames = selectedSkillNames,
        installAll = installAll,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to batch install from remote source '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_BATCH_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      val status = if (result.failedCount > 0) {
        AgentToolResultStatus.FAILED
      } else {
        AgentToolResultStatus.SUCCESS
      }
      return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = status,
        content = renderSkillBatchInstallResult(result),
        errorCode = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorCode)
            ?: "SKILL_BATCH_INSTALL_FAILED"
        } else {
          null
        },
        errorMessage = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorMessage)
        } else {
          null
        },
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("path", displayManagedRoot)
            put("sourceType", result.sourceType)
            put("sourceRef", result.sourceRef)
            put("requestedCount", result.requestedCount.toString())
            put("installedCount", result.installedCount.toString())
            put("failedCount", result.failedCount.toString())
            result.sourcePath?.let { put("sourcePath", it) }
            result.resolvedRevision?.let { put("resolvedRevision", it) }
            result.resolvedCommitSha?.let { put("resolvedCommitSha", it) }
            result.entries
              .mapNotNull(SkillPackageBatchInstallEntry::installedSkillId)
              .singleOrNull()
              ?.let { put("skillId", it) }
          },
        ),
      )
    }

    return AgentToolResult(
      toolName = "SkillsAddBatch",
      status = AgentToolResultStatus.FAILED,
      content = "Batch installation requires an explicit local path, GitHub source, or GitLab source. Use SkillsAdd for the host-managed catalog.",
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "SkillsAddBatch",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NONE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = sourceRef,
        ),
        metadata = mapOf(
          "sourceRef" to sourceRef,
          "requestedCount" to selectedSkillNames.size.toString(),
          "installedCount" to "0",
          "failedCount" to "0",
        ),
      ),
    )
  }

  private fun updateInstalledSkillPackages(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsUpdate")
    val requestedSkillId = arguments.optionalStringFrom("skill_id", "skillId", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    packageManager.refreshManifest()
    val managedSkills = packageManager.listManagedSkills()
    val managedSkillIds = managedSkills.mapTo(linkedSetOf()) { skill -> skill.name }
    val installations = packageManager.listInstallations()
    val relevantInstallations = installations.filter { entry ->
      requestedSkillId == null || entry.skillId == requestedSkillId
    }
    if (requestedSkillId != null &&
      requestedSkillId !in managedSkillIds &&
      relevantInstallations.isEmpty()
    ) {
      return AgentToolResult(
        toolName = "SkillsUpdate",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$requestedSkillId' is not installed in the host-managed skills directory.",
        errorCode = "SKILL_NOT_INSTALLED",
      )
    }
    relevantInstallations.forEach { entry ->
      if (entry.sourceType == "local_path") {
        val sourcePath = entry.sourcePath?.trim()?.takeIf(String::isNotBlank)
        if (sourcePath != null) {
          gateLocalSkillSourceReadAccess(
            task = task,
            resultToolName = "SkillsUpdate",
            sourcePath = Paths.get(sourcePath),
            sourceRef = entry.sourceRef,
          )?.let { return it }
        }
      }
      if (isRemoteSkillSourceType(entry.sourceType)) {
        val resolvedSource = packageManager.resolveRemoteSource(
          sourceRef = entry.sourceRef,
          selectedSkillName = entry.selectedSkillName,
        )
        gateRemoteSkillNetworkAccess(
          task = task,
          resultToolName = "SkillsUpdate",
          url = resolvedSource?.policyTargetUrl ?: entry.sourceRef,
          targetSummary = entry.sourceRef,
          affectedPaths = mapOf("sourceRef" to entry.sourceRef),
        )?.let { return it }
      }
    }

    val checkReport = packageManager.checkInstalledSkills(requestedSkillId)
    val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
    val displayManagedRoot = displaySkillPackagePath(managedRoot)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsUpdate",
      targetPath = managedRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = managedRoot,
        primaryTargetPath = displayManagedRoot,
        targetSummary = requestedSkillId ?: displayManagedRoot,
      ),
    )
    if (checkReport.updateAvailableCount > 0) {
      toolPolicyPipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }
    }
    val updateReport = packageManager.updateInstalledSkills(checkReport)
    val status = if (updateReport.updatedCount == 0 &&
      updateReport.failedCount > 0 &&
      updateReport.skippedCount == 0
    ) {
      AgentToolResultStatus.FAILED
    } else {
      AgentToolResultStatus.SUCCESS
    }
    val content = if (updateReport.results.isEmpty()) {
      "No installed skills are available for update."
    } else {
      updateReport.results.joinToString(separator = "\n", transform = ::renderSkillPackageUpdateLine)
    }
    return AgentToolResult(
      toolName = "SkillsUpdate",
      status = status,
      content = content,
      errorCode = if (status == AgentToolResultStatus.FAILED) {
        updateReport.results.firstNotNullOfOrNull(SkillPackageUpdateResult::errorCode)
          ?: "SKILL_UPDATE_FAILED"
      } else {
        null
      },
      errorMessage = if (status == AgentToolResultStatus.FAILED) {
        updateReport.results.firstNotNullOfOrNull(SkillPackageUpdateResult::errorMessage)
      } else {
        null
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayManagedRoot)
          put("resultCount", updateReport.results.size.toString())
          put("updatedCount", updateReport.updatedCount.toString())
          put("skippedCount", updateReport.skippedCount.toString())
          put("failedCount", updateReport.failedCount.toString())
          requestedSkillId?.let { put("skillId", it) }
        },
      ),
    )
  }

  private fun removeSkillPackage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsRemove")
    val skillId = arguments.requiredStringFrom("skill_id", "skillId", "name")
    val targetDirectory = packageManager.resolveInstalledSkillDirectory(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsRemove",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$skillId' is not installed in the host-managed skills directory.",
        errorCode = "SKILL_NOT_INSTALLED",
      )
    val targetPath = targetDirectory.toPath().toAbsolutePath().normalize()
    val displayPath = displaySkillPackagePath(targetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsRemove",
      targetPath = targetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = targetPath,
        primaryTargetPath = displayPath,
        targetSummary = displayPath,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf("path" to displayPath),
    )?.let { return it }

    val result = packageManager.removeInstalledSkill(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsRemove",
        status = AgentToolResultStatus.FAILED,
        content = "Failed to remove '$skillId' from the host-managed skills directory.",
        errorCode = "SKILL_REMOVE_FAILED",
      )
    return AgentToolResult(
      toolName = "SkillsRemove",
      status = AgentToolResultStatus.SUCCESS,
      content = "Removed skill '${result.skillId}' from the host-managed skills directory.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displayPath,
          "skillId" to result.skillId,
        ),
      ),
    )
  }

  private fun renderSkillPackageCheckLine(
    result: SkillPackageCheckResult,
  ): String = buildList {
    add(result.skillId)
    add(result.status.wireValue)
    add("source=${result.sourceType}")
    add("source_ref=${result.sourceRef}")
    result.installedRevision?.takeIf(String::isNotBlank)?.let { add("installed_revision=$it") }
    result.installedCommitSha?.takeIf(String::isNotBlank)?.let { add("installed_commit=$it") }
    result.latestRevision?.takeIf(String::isNotBlank)?.let { add("latest_revision=$it") }
    result.latestCommitSha?.takeIf(String::isNotBlank)?.let { add("latest_commit=$it") }
    result.latestContentHash?.takeIf(String::isNotBlank)?.let { add("latest_hash=$it") }
    result.errorCode?.let { add("error_code=$it") }
    result.errorMessage?.takeIf(String::isNotBlank)?.let { add("message=${inlinePreview(it, maxChars = 160)}") }
  }.joinToString(separator = "\t")

  private fun renderSkillPackageUpdateLine(
    result: SkillPackageUpdateResult,
  ): String = buildList {
    add(result.skillId)
    add(result.status.wireValue)
    add("source=${result.sourceType}")
    add("source_ref=${result.sourceRef}")
    result.checkStatus?.let { add("reason=${it.wireValue}") }
    result.manifestEntry?.resolvedRevision?.takeIf(String::isNotBlank)?.let { add("resolved_revision=$it") }
    result.manifestEntry?.resolvedCommitSha?.takeIf(String::isNotBlank)?.let { add("resolved_commit=$it") }
    result.errorCode?.let { add("error_code=$it") }
    result.errorMessage?.takeIf(String::isNotBlank)?.let { add("message=${inlinePreview(it, maxChars = 160)}") }
  }.joinToString(separator = "\t")

  private fun renderSkillSourceInspection(
    result: com.opencray.runtime.skills.SkillSourceInspectionResult,
  ): String = buildList {
    add(
      buildList {
        add("inspection")
        add(result.sourceType)
        add("source_ref=${result.sourceRef}")
        result.sourcePath?.takeIf(String::isNotBlank)?.let { add("source_path=$it") }
        result.resolvedRevision?.takeIf(String::isNotBlank)?.let { add("resolved_revision=$it") }
        result.resolvedCommitSha?.takeIf(String::isNotBlank)?.let { add("resolved_commit=$it") }
        add("candidate_count=${result.candidates.size}")
      }.joinToString(separator = "\t"),
    )
    addAll(
      result.candidates.map { candidate ->
        buildList {
          add("candidate")
          add(candidate.name)
          add("description=${candidate.description}")
          add("relative_path=${candidate.relativePath}")
        }.joinToString(separator = "\t")
      },
    )
  }.joinToString(separator = "\n")

  private fun renderSkillBatchInstallResult(
    result: com.opencray.runtime.skills.SkillPackageBatchInstallResult,
  ): String = buildList {
    add(
      buildList {
        add("batch_install")
        add(result.sourceType)
        add("source_ref=${result.sourceRef}")
        result.sourcePath?.takeIf(String::isNotBlank)?.let { add("source_path=$it") }
        result.resolvedRevision?.takeIf(String::isNotBlank)?.let { add("resolved_revision=$it") }
        result.resolvedCommitSha?.takeIf(String::isNotBlank)?.let { add("resolved_commit=$it") }
        add("requested_count=${result.requestedCount}")
        add("installed_count=${result.installedCount}")
        add("failed_count=${result.failedCount}")
      }.joinToString(separator = "\t"),
    )
    addAll(
      result.entries.map { entry ->
        buildList {
          add(if (entry.succeeded) "installed" else "failed")
          add(entry.installedSkillId ?: entry.requestedSkillName)
          add("requested=${entry.requestedSkillName}")
          entry.manifestEntry?.sourceRelativePath
            ?.takeIf(String::isNotBlank)
            ?.let { add("relative_path=$it") }
          entry.errorCode?.let { add("error_code=$it") }
          entry.errorMessage
            ?.takeIf(String::isNotBlank)
            ?.let { add("message=${inlinePreview(it, maxChars = 160)}") }
        }.joinToString(separator = "\t")
      },
    )
  }.joinToString(separator = "\n")

  private fun isRemoteSkillSourceType(sourceType: String): Boolean = when (sourceType) {
    "remote_github",
    "remote_gitlab",
    -> true

    else -> false
  }

  private fun unavailableSkillPackageManager(toolName: String): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "The host-managed skills package manager is not configured for this runtime.",
    errorCode = "SKILL_PACKAGE_MANAGER_UNAVAILABLE",
  )

  private fun displaySkillPackagePath(path: Path): String {
    val normalized = path.toAbsolutePath().normalize()
    val packageManager = config.skillPackageManager
    if (packageManager != null) {
      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      if (normalized.startsWith(managedRoot)) {
        return labeledHostPath(label = "skills-managed", root = managedRoot, path = normalized)
      }
      val catalogRoot = packageManager.catalogRootPath().toPath().toAbsolutePath().normalize()
      if (normalized.startsWith(catalogRoot)) {
        return labeledHostPath(label = "skills-catalog", root = catalogRoot, path = normalized)
      }
    }
    return normalized.toString().replace('\\', '/')
  }

  private fun labeledHostPath(
    label: String,
    root: Path,
    path: Path,
  ): String {
    val relative = runCatching {
      root.relativize(path).toString().replace('\\', '/')
    }.getOrDefault(path.toString().replace('\\', '/'))
    return if (relative.isBlank()) {
      label
    } else {
      "$label/$relative"
    }
  }

  private fun listMcpServers(): AgentToolResult {
    val report = config.mcpExposureReport
    val activeCount = report?.activeClients?.size ?: 0
    val blockedCount = report?.blockedClients?.size ?: 0
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
      metadata = toolPolicySupport.commonMetadata(
        toolName = "mcp_list_servers",
        metadataContext = policyMetadataContext(
          toolName = "mcp_list_servers",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = "active=$activeCount, blocked=$blockedCount",
        ),
      ) + mapOf(
        "activeCount" to activeCount.toString(),
        "blockedCount" to blockedCount.toString(),
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
      metadata = toolPolicySupport.commonMetadata(
        toolName = "memory_search",
        metadataContext = policyMetadataContext(
          toolName = "memory_search",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = inlinePreview(query, maxChars = 256),
        ),
      ) + buildMap {
        put("query", query)
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "recordIds",
            response.matches.joinToString(separator = ",") { match -> match.recordId },
          )
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
      metadata = toolPolicySupport.commonMetadata(
        toolName = "memory_get",
        metadataContext = policyMetadataContext(
          toolName = "memory_get",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = response.path,
          targetSummary = response.path,
        ),
      ) + mapOf(
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
        "recordIds" to response.recordIds.joinToString(separator = ","),
      ),
    )
  }

  private fun policyMetadataContext(
    toolName: String,
    targetKind: ToolTargetKind = ToolTargetKind.NONE,
    primaryPath: Path? = null,
    secondaryPath: Path? = null,
    primaryTargetPath: String? = null,
    secondaryTargetPath: String? = null,
    workspaceRelation: ToolWorkspaceRelation? = null,
    targetSummary: String? = null,
  ): ToolMetadataContext = toolPolicyPipeline.metadataContext(
    toolName = toolName,
    request = ToolMetadataContextRequest(
      targetKind = targetKind,
      primaryPath = primaryPath,
      secondaryPath = secondaryPath,
      primaryTargetPath = primaryTargetPath,
      secondaryTargetPath = secondaryTargetPath,
      workspaceRelation = workspaceRelation,
      targetSummary = targetSummary,
    ),
  )

  private fun gateReadOnlyTool(
    plan: com.opencray.runtime.policy.ToolPolicyPlan,
    affectedPaths: Map<String, String> = emptyMap(),
  ): AgentToolResult? = toolPolicyPipeline.gate(
    plan = plan,
    affectedPaths = affectedPaths,
    askDetail = "Approval is required before ${plan.toolName} can read this path.",
    denyDetail = "Policy denied ${plan.toolName}.",
  )

  private fun gateLocalSkillSourceReadAccess(
    task: AgentTask,
    resultToolName: String,
    sourcePath: Path,
    sourceRef: String,
  ): AgentToolResult? {
    val normalizedSourcePath = sourcePath.toAbsolutePath().normalize()
    val displayPath = toolTargetResolver.displayModelPath(normalizedSourcePath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "Read",
      targetPath = normalizedSourcePath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = if (Files.isDirectory(normalizedSourcePath)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE,
        primaryPath = normalizedSourcePath,
        primaryTargetPath = displayPath,
        targetSummary = sourceRef,
      ),
    )
    return toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf("sourcePath" to displayPath),
      askDetail = "Approval is required before $resultToolName can read the local skill source.",
      denyDetail = "Policy denied $resultToolName local source access.",
    )?.let { result ->
      result.copy(
        toolName = resultToolName,
        metadata = result.metadata + mapOf(
          "requestedToolName" to resultToolName,
          "normalizedToolName" to resultToolName,
        ),
      )
    }
  }

  private fun gateRemoteSkillNetworkAccess(
    task: AgentTask,
    resultToolName: String,
    url: String,
    targetSummary: String,
    affectedPaths: Map<String, String> = emptyMap(),
  ): AgentToolResult? {
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "WebFetch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryTargetPath = url,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = targetSummary,
      ),
    )
    return toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = affectedPaths,
      askDetail = "Approval is required before $resultToolName can access the remote skills service.",
      denyDetail = "Policy denied $resultToolName remote network access.",
    )?.let { result ->
      result.copy(
        toolName = resultToolName,
        metadata = result.metadata + mapOf(
          "requestedToolName" to resultToolName,
          "normalizedToolName" to resultToolName,
        ),
      )
    }
  }

  private fun resolveExplicitLocalSkillSourcePath(sourceRef: String): Path? {
    if (!looksLikeExplicitLocalSkillSource(sourceRef)) {
      return null
    }
    return runCatching {
      toolTargetResolver.resolveReadablePath(
        candidate = sourceRef,
        label = "skill source",
        defaultToRoot = false,
      )
    }.getOrNull()
  }

  private fun looksLikeExplicitLocalSkillSource(sourceRef: String): Boolean {
    val normalized = sourceRef.trim()
    return normalized.startsWith(".") ||
      normalized.startsWith("/") ||
      normalized.startsWith("\\") ||
      normalized.contains("\\") ||
      WINDOWS_ABSOLUTE_PATH_REGEX.matches(normalized)
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

  private fun JsonObject.optionalBooleanFrom(vararg names: String): Boolean? =
    names.firstNotNullOfOrNull { name -> optionalBoolean(name) }

  private fun JsonObject.optionalStringArrayFrom(vararg names: String): List<String> =
    names.firstNotNullOfOrNull { name ->
      this[name]?.let { optionalStringArray(name) }
    } ?: emptyList()

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
    val intent: ToolRuntimeIntent? = null,
    val metadata: Map<String, String> = emptyMap(),
    val affectedPaths: Map<String, String> = emptyMap(),
    val metadataRequest: ToolMetadataContextRequest = ToolMetadataContextRequest(),
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
    private const val DEFAULT_BASH_WAIT_TIMEOUT_MS: Long = 1_000L
    private const val DEFAULT_MANAGED_PROCESS_TIMEOUT_MS: Long = 300_000L
    private const val DEFAULT_MANAGED_PROCESS_WAIT_TIMEOUT_MS: Long = 1_000L
    private val WINDOWS_ABSOLUTE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/].+")
    private val ATTACHMENT_IMAGE_EXTENSIONS: Set<String> = setOf(
      "png",
      "jpg",
      "jpeg",
      "webp",
      "gif",
      "bmp",
      "heic",
      "heif",
    )
    private val ATTACHMENT_AUDIO_EXTENSIONS: Set<String> = setOf(
      "mp3",
      "wav",
      "m4a",
    )
    private val ATTACHMENT_FALLBACK_MIME_TYPES: Map<String, String> = mapOf(
      "png" to "image/png",
      "jpg" to "image/jpeg",
      "jpeg" to "image/jpeg",
      "webp" to "image/webp",
      "gif" to "image/gif",
      "bmp" to "image/bmp",
      "heic" to "image/heic",
      "heif" to "image/heif",
      "mp3" to "audio/mpeg",
      "wav" to "audio/wav",
      "m4a" to "audio/mp4",
    )
    private val MANAGED_PROCESS_RESERVED_METADATA_KEYS: Set<String> = setOf(
      "capabilityKind",
      "targetKind",
      "workspaceRelation",
      "primaryTargetPath",
      "secondaryTargetPath",
      "targetSummary",
      "executionMode",
      "policyOutcome",
      "policyReasonCode",
      "approvalRisk",
      "intentCategory",
      "executionIntentKind",
      "executionTransport",
      "executionCommandPreview",
      "executionScriptPath",
      "executionWorkingDirectory",
      "processLifecycleIntentKind",
      "intentProcessId",
      "intentWorkingDirectory",
      "resultLimitApplied",
      "resultTruncated",
      "resultLimitKind",
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
