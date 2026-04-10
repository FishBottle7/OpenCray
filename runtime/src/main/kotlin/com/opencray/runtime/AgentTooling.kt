package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.filesystem.FileMutationOperation
import com.opencray.filesystem.FileOpsService
import com.opencray.llm.LiteLlmToolDefinition
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
import com.opencray.runtime.policy.SchedulingIntent
import com.opencray.runtime.policy.SchedulingIntentKind
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
import com.opencray.runtime.process.ManagedProcessDeliveredObservationState
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.normalizedDeliveredObservationState
import com.opencray.runtime.process.normalizedObservationState
import com.opencray.runtime.process.normalizedReconnectState
import com.opencray.runtime.process.withNormalizedRemoteState
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
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
  val jsonSchema: JsonObject? = null,
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
  val id: String? = null,
  val toolName: String,
  val arguments: JsonObject = JsonObject(emptyMap()),
  val reason: String? = null,
) {
  init {
    require(id == null || id.isNotBlank()) { "AgentToolCall id must not be blank." }
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

data class OpenCrayChatAttachmentSource(
  val attachmentId: String,
  val displayName: String,
  val sourcePath: Path,
  val mimeType: String? = null,
) {
  init {
    require(attachmentId.isNotBlank()) { "OpenCrayChatAttachmentSource attachmentId must not be blank." }
    require(displayName.isNotBlank()) { "OpenCrayChatAttachmentSource displayName must not be blank." }
    require(mimeType == null || mimeType.isNotBlank()) {
      "OpenCrayChatAttachmentSource mimeType must not be blank when provided."
    }
  }
}

@Serializable
data class PythonRuntimeManifestSnapshot(
  val schemaVersion: Int = 1,
  val runtimeBackend: String,
  val packageInstallPolicy: String,
  val supportsDynamicInstall: Boolean,
  val interpreter: String? = null,
  val packages: List<String> = emptyList(),
  val packageVersions: Map<String, String> = emptyMap(),
  val notes: List<String> = emptyList(),
)

data class OpenCrayToolDispatcherConfig(
  val workspaceRoots: Set<Path>,
  val readRoots: Set<Path> = workspaceRoots,
  val allowedToolNames: Set<String>? = null,
  val hiddenToolNamePrefixes: Set<String> = emptySet(),
  val extraPolicyReadRoots: Set<Path> = emptySet(),
  val extraPolicyWriteRoots: Set<Path> = emptySet(),
  val skillsRoots: List<File> = emptyList(),
  val skillPackageManager: SkillPackageManager? = null,
  val mcpExposureReport: McpClientExposureReport? = null,
  val modePolicy: ModePolicy = ModePolicy(),
  val approvedTaskId: String? = null,
  val approvedToolName: String? = null,
  val rejectedTaskId: String? = null,
  val rejectedToolName: String? = null,
  val commandExecutor: CommandExecutor? = null,
  val pythonRuntimeAdapter: PythonScriptRuntime = HostProcessPythonRuntime(),
  val pythonRuntimeManifestProvider: (() -> PythonRuntimeManifestSnapshot?)? = null,
  val supportsManagedPythonProcessStart: Boolean = true,
  val managedPythonProcessUsesRuntimeAdapter: Boolean = false,
  val commandApprovalToken: CommandApprovalToken? = null,
  val todoStore: AgentTodoStore = InMemoryAgentTodoStore(),
  val processRegistry: AgentProcessRegistry = InMemoryAgentProcessRegistry(),
  val webContentFetcher: WebContentFetcher = HttpUrlWebContentFetcher(),
  val webSearchProvider: WebSearchProvider = UnconfiguredWebSearchProvider,
  val sandboxPreviewService: SandboxPreviewService? = null,
  val sandboxSessionControlService: SandboxSessionControlService? = null,
  val sandboxSessionInfoService: SandboxSessionInfoService? = null,
  val scheduledTaskManager: ScheduledTaskManager? = null,
  val mediaToolSettingsProvider: () -> OpenCrayMediaToolSettings? = { null },
  val imageGenerationClient: OpenCrayImageGenerationClient? = null,
  val speechSynthesisClient: OpenCraySpeechSynthesisClient? = null,
  val chatAttachmentResolver: ((String) -> OpenCrayChatAttachmentSource?)? = null,
  val documentSearchProvider: WorkspaceDocumentSearchProvider = DefaultWorkspaceDocumentSearchProvider(),
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

private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_FULL_SNAPSHOT: String = "full_snapshot"
private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_DELTA: String = "delta"
private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_NO_CHANGE: String = "no_change"
private const val MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL: String = "reset_full"

data class ManagedProcessObservationCursorState(
  val mode: String,
  val cursor: String,
  val stdoutBytes: Long,
  val stderrBytes: Long,
  val providerMode: String? = null,
  val providerCursor: String? = null,
  val providerEventCount: Long? = null,
)

private data class ManagedProcessProviderObservationBoundary(
  val cursorBefore: String,
  val cursorAfter: String,
  val eventCountBefore: Long? = null,
  val eventCountAfter: Long? = null,
)

private data class ManagedProcessObservationDelivery(
  val stdout: String,
  val stderr: String,
  val metadata: Map<String, String>,
  val renderLines: List<String>,
) {
  companion object {
    fun fullSnapshot(snapshot: ManagedProcessSnapshot): ManagedProcessObservationDelivery =
      ManagedProcessObservationDelivery(
        stdout = snapshot.stdout,
        stderr = snapshot.stderr,
        metadata = emptyMap(),
        renderLines = emptyList(),
      )

    fun snapshotMode(
      snapshot: ManagedProcessSnapshot,
      mode: String,
      cursorBefore: String,
      cursorAfter: String,
      stdoutDeltaBytes: Long,
      stderrDeltaBytes: Long,
      providerBoundary: ManagedProcessProviderObservationBoundary? = null,
      warning: String? = null,
    ): ManagedProcessObservationDelivery = deltaMode(
      mode = mode,
      cursorBefore = cursorBefore,
      cursorAfter = cursorAfter,
      stdout = snapshot.stdout,
      stderr = snapshot.stderr,
      stdoutDeltaBytes = stdoutDeltaBytes,
      stderrDeltaBytes = stderrDeltaBytes,
      providerBoundary = providerBoundary,
      warning = warning,
    )

    fun deltaMode(
      mode: String,
      cursorBefore: String,
      cursorAfter: String,
      stdout: String,
      stderr: String,
      stdoutDeltaBytes: Long,
      stderrDeltaBytes: Long,
      providerBoundary: ManagedProcessProviderObservationBoundary? = null,
      warning: String? = null,
    ): ManagedProcessObservationDelivery {
      val metadata = buildMap {
        put("sandboxCommandObservationDeliveryMode", mode)
        put("sandboxCommandObservationCursorBefore", cursorBefore)
        put("sandboxCommandObservationCursorAfter", cursorAfter)
        put("sandboxCommandObservationStdoutDeltaBytes", stdoutDeltaBytes.toString())
        put("sandboxCommandObservationStderrDeltaBytes", stderrDeltaBytes.toString())
        providerBoundary?.let { boundary ->
          put("sandboxCommandProviderObservationCursorBefore", boundary.cursorBefore)
          put("sandboxCommandProviderObservationCursorAfter", boundary.cursorAfter)
          boundary.eventCountBefore?.let { eventCount ->
            put("sandboxCommandProviderObservationEventCountBefore", eventCount.toString())
          }
          boundary.eventCountAfter?.let { eventCount ->
            put("sandboxCommandProviderObservationEventCountAfter", eventCount.toString())
          }
        }
        warning?.trim()?.takeIf(String::isNotBlank)?.let { message ->
          put("sandboxCommandObservationDeliveryWarning", message)
        }
      }
      val renderLines = buildList {
        add("sandbox_command_observation_delivery_mode=$mode")
        add("sandbox_command_observation_cursor_before=$cursorBefore")
        add("sandbox_command_observation_cursor_after=$cursorAfter")
        add("sandbox_command_observation_stdout_delta_bytes=$stdoutDeltaBytes")
        add("sandbox_command_observation_stderr_delta_bytes=$stderrDeltaBytes")
        providerBoundary?.let { boundary ->
          add("sandbox_command_provider_observation_cursor_before=${boundary.cursorBefore}")
          add("sandbox_command_provider_observation_cursor_after=${boundary.cursorAfter}")
          boundary.eventCountBefore?.let { eventCount ->
            add("sandbox_command_provider_observation_event_count_before=$eventCount")
          }
          boundary.eventCountAfter?.let { eventCount ->
            add("sandbox_command_provider_observation_event_count_after=$eventCount")
          }
        }
        warning?.trim()?.takeIf(String::isNotBlank)?.let { message ->
          add("observation_warning=$message")
        }
      }
      return ManagedProcessObservationDelivery(
        stdout = stdout,
        stderr = stderr,
        metadata = metadata,
        renderLines = renderLines,
      )
    }
  }
}

class ManagedProcessObservationTracker(
  private val maxTrackedProcesses: Int = 64,
) {
  private val lock = Any()
  private val statesByProcessId = linkedMapOf<String, ManagedProcessObservationCursorState>()

  fun recordAndReturnPrevious(
    processId: String,
    current: ManagedProcessObservationCursorState,
  ): ManagedProcessObservationCursorState? = synchronized(lock) {
    val previous = statesByProcessId.put(processId, current)
    while (statesByProcessId.size > maxTrackedProcesses) {
      val iterator = statesByProcessId.entries.iterator()
      if (!iterator.hasNext()) {
        break
      }
      iterator.next()
      iterator.remove()
    }
    previous
  }
}

class OpenCrayToolDispatcher(
  private val config: OpenCrayToolDispatcherConfig,
  private val managedProcessObservationTracker: ManagedProcessObservationTracker = ManagedProcessObservationTracker(),
) {
  private val toolCapabilityClassifier = ToolCapabilityClassifier()
  private val toolCallNormalizer = ToolCallNormalizer()
  private val toolPolicySupport = ToolPolicySupport()
  private val toolPolicyEvaluator = ToolPolicyEvaluator(
    modePolicy = config.modePolicy,
    approvedTaskId = config.approvedTaskId,
    approvedToolName = config.approvedToolName,
    rejectedTaskId = config.rejectedTaskId,
    rejectedToolName = config.rejectedToolName,
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
  private val hiddenToolNamePrefixes: Set<String> = config.hiddenToolNamePrefixes
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { prefix -> prefix.lowercase(Locale.US) }
    .toSet()

  fun todoSnapshot(): List<AgentTodoEntry> = todoStore.snapshot()

  fun definitions(): List<AgentToolDefinition> {
    val pythonManifestProviderAvailable = config.pythonRuntimeManifestProvider != null
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
        name = "GenerateImage",
        description = "Generate one or more images through the configured media provider and save them under the workspace media store so they can be attached in the final response by artifact_id.",
        parameters = listOf(
          AgentToolParameter("prompt", "string", required = true, description = "Text prompt describing the image to generate."),
          AgentToolParameter("count", "number", required = false, description = "How many images to generate. Maximum 9."),
          AgentToolParameter("size", "string", required = false, description = "Optional provider-specific size hint such as 1024x1024."),
          AgentToolParameter("format", "string", required = false, description = "Optional output image format. Supported values: png, jpg, jpeg, webp."),
          AgentToolParameter("model", "string", required = false, description = "Optional provider model override. Defaults to the configured image model."),
        ),
      ),
      AgentToolDefinition(
        name = "SynthesizeSpeech",
        description = "Convert text into a voice clip through the configured speech provider, save it under the workspace media store, and return an artifact_id that can be attached in the final response.",
        parameters = listOf(
          AgentToolParameter("text", "string", required = true, description = "Text to synthesize into spoken audio."),
          AgentToolParameter("format", "string", required = false, description = "Optional audio format. Supported values: mp3, wav, m4a."),
          AgentToolParameter("voice", "string", required = false, description = "Optional voice override. Defaults to the configured voice preset."),
          AgentToolParameter("model", "string", required = false, description = "Optional provider model override. Defaults to the configured speech model."),
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
          AgentToolParameter(
            name = "edits",
            type = "object[]",
            required = true,
            description = "Array of edit objects with old_string, new_string, and optional replace_all.",
            jsonSchema = multiEditArraySchema(
              description = "Array of exact text edit objects to apply in order.",
            ),
          ),
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
        description = "Read or replace the current chat session's in-memory todo list. Omit todos to inspect the current list; provide todos to replace it; provide an empty todos array to clear it. Keep todo contents unique, keep at most one todo in_progress, and only that active todo may set activeForm.",
        parameters = listOf(
          AgentToolParameter(
            name = "todos",
            type = "object[]",
            required = false,
            description = "Array of todo objects with unique content, status, and optional activeForm. At most one entry may use status=in_progress, and only that entry may include activeForm.",
            jsonSchema = todoEntryArraySchema(
              description = "Optional replacement todo list. Omit this field to inspect the current todos without mutating them. Send an empty array to clear the current todo list. Keep contents unique, keep at most one entry in_progress, and only that active entry may include activeForm.",
            ),
          ),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskCreate",
        description = "Create one persisted scheduled task that later enqueues a normal run on the target session queue. Use trigger.at for one absolute time, trigger.after for one relative delay, or trigger.start_at plus trigger.rrule for recurrence. If session_id is omitted, the current chat session is used.",
        parameters = listOf(
          AgentToolParameter("prompt", "string", required = true, description = "Prompt text that should be submitted when the schedule fires."),
          AgentToolParameter("title", "string", required = false, description = "Optional user-visible schedule title. Defaults to a short prompt-derived title."),
          AgentToolParameter("session_id", "string", required = false, description = "Optional existing target session id. Defaults to the current chat session."),
          AgentToolParameter(
            "trigger",
            "object",
            required = true,
            description = "Trigger object. Use exactly one form: at, after, or recurrence with start_at plus rrule.",
            jsonSchema = scheduledTaskTriggerSchema(),
          ),
          AgentToolParameter("enabled", "boolean", required = false, description = "Whether the new scheduled task is enabled immediately. Defaults to true."),
          AgentToolParameter("conflict_policy", "string", required = false, description = "Optional conflict policy. Supported values: enqueue_new_run, skip_if_session_busy, cancel_older_waiting_trigger."),
          AgentToolParameter("requires_foreground_notification", "boolean", required = false, description = "Whether detached execution should require the runtime foreground notification. Defaults to true."),
          AgentToolParameter("notify_on_queued", "boolean", required = false, description = "Whether to notify when the scheduled trigger is accepted into the queue."),
          AgentToolParameter("notify_on_approval", "boolean", required = false, description = "Whether to notify if the scheduled run later waits for approval."),
          AgentToolParameter("notify_on_completion", "boolean", required = false, description = "Whether to notify when the scheduled run completes."),
          AgentToolParameter("notify_on_interruption", "boolean", required = false, description = "Whether to notify when the scheduled run is interrupted or paused."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskList",
        description = "List persisted scheduled tasks. If session_id is omitted, the current chat session is used when available; otherwise all sessions are listed.",
        parameters = listOf(
          AgentToolParameter("session_id", "string", required = false, description = "Optional existing target session id filter."),
          AgentToolParameter("enabled", "boolean", required = false, description = "Optional enabled-state filter."),
          AgentToolParameter("limit", "number", required = false, description = "Maximum number of scheduled tasks to return. Defaults to 20."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskGet",
        description = "Inspect one persisted scheduled task in detail, including its prompt, trigger configuration, notification policy, next fire time, and a bounded slice of recent run history.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact scheduled task id."),
          AgentToolParameter("recent_run_limit", "number", required = false, description = "Maximum number of recent run records to return. Defaults to 5."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskUpdate",
        description = "Patch one persisted scheduled task. If trigger is provided, it replaces the full stored trigger definition. Enable or disable remains a host-side action and is not changed here.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact scheduled task id."),
          AgentToolParameter("title", "string", required = false, description = "Optional replacement user-visible title."),
          AgentToolParameter("prompt", "string", required = false, description = "Optional replacement prompt text that should be submitted when the schedule fires."),
          AgentToolParameter(
            "trigger",
            "object",
            required = false,
            description = "Optional full replacement trigger object. Use exactly one form: at, after, or recurrence with start_at plus rrule.",
            jsonSchema = scheduledTaskTriggerSchema(),
          ),
          AgentToolParameter("conflict_policy", "string", required = false, description = "Optional replacement conflict policy. Supported values: enqueue_new_run, skip_if_session_busy, cancel_older_waiting_trigger."),
          AgentToolParameter("requires_foreground_notification", "boolean", required = false, description = "Optional replacement foreground-notification requirement."),
          AgentToolParameter("notify_on_queued", "boolean", required = false, description = "Optional replacement queued notification flag."),
          AgentToolParameter("notify_on_approval", "boolean", required = false, description = "Optional replacement approval notification flag."),
          AgentToolParameter("notify_on_completion", "boolean", required = false, description = "Optional replacement completion notification flag."),
          AgentToolParameter("notify_on_interruption", "boolean", required = false, description = "Optional replacement interruption notification flag."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskDelete",
        description = "Delete one persisted scheduled task, unregister its future wake, and remove its stored run history.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact scheduled task id."),
        ),
      ),
      AgentToolDefinition(
        name = "Task",
        description = "Delegate one bounded subtask to a child runtime and wait for its summarized result before continuing. Prefer explorer or default for read-only work, and worker for bounded workspace edits. Legacy aliases researcher, reviewer, and general-purpose are still accepted.",
        parameters = listOf(
          AgentToolParameter("description", "string", required = true, description = "Short task label for the delegated child run."),
          AgentToolParameter("prompt", "string", required = true, description = "Exact instructions for the child run."),
          AgentToolParameter("subagent_type", "string", required = true, description = "Child profile id such as explorer, default, or worker. Legacy aliases researcher, reviewer, and general-purpose also work."),
          AgentToolParameter("context_mode", "string", required = false, description = "Optional child context override. Supported public values: minimal, delegated. mirrored is reserved for internal-only recovery/testing paths."),
        ),
      ),
      AgentToolDefinition(
        name = "spawn_agent",
        description = "Start one bounded subagent handle immediately. During prompt runs the child begins running in the background right away; use wait_agent later to inspect its latest stable state or block for completion.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "Optional explicit child handle id for the delegated child run."),
          AgentToolParameter("description", "string", required = true, description = "Short task label for the delegated child run."),
          AgentToolParameter("prompt", "string", required = true, description = "Exact instructions for the child run."),
          AgentToolParameter("subagent_type", "string", required = true, description = "Child profile id such as explorer, default, or worker. Legacy aliases researcher, reviewer, and general-purpose also work."),
          AgentToolParameter("context_mode", "string", required = false, description = "Optional child context override. Supported public values: minimal, delegated. mirrored is reserved for internal-only recovery/testing paths."),
        ),
      ),
      AgentToolDefinition(
        name = "wait_agent",
        description = "Wait for one delegated child handle to reach its latest stable state and return a summarized result. If that child is already running, wait_agent blocks until it finishes or pauses for approval. Approval-unlocked children resume through the runtime or host recovery path; use wait_agent later to observe that resumed state.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "One delegated child handle id returned by spawn_agent."),
          AgentToolParameter("agent_ids", "string[]", required = false, description = "Optional batch form. The first listed id is used in this runtime."),
          AgentToolParameter("ids", "string[]", required = false, description = "Compatibility alias for agent_ids."),
        ),
      ),
      AgentToolDefinition(
        name = "send_input",
        description = "Queue one parent follow-up message in the delegated child mailbox. Use it only for queued or approval-waiting children; it is not a mid-run interrupt.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "Delegated child handle id returned by spawn_agent."),
          AgentToolParameter("id", "string", required = false, description = "Compatibility alias for agent_id."),
          AgentToolParameter("message", "string", required = false, description = "Parent follow-up message to queue in the child mailbox before the next resume."),
          AgentToolParameter("input", "string", required = false, description = "Compatibility alias for message."),
        ),
      ),
      AgentToolDefinition(
        name = "close_agent",
        description = "Close one delegated child handle. Running or paused children are cancelled and removed; completed children are simply forgotten.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "Delegated child handle id returned by spawn_agent."),
          AgentToolParameter("id", "string", required = false, description = "Compatibility alias for agent_id."),
        ),
      ),
      AgentToolDefinition(
        name = "list_subagents",
        description = "List delegated child handles currently known to this runtime, including parent linkage, lifecycle state, mailbox backlog, and the latest summarized child result.",
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
        name = "import_chat_attachment",
        description = "Copy one existing chat attachment from the current session into the writable workspace without exposing the chat-media storage path.",
        parameters = listOf(
          AgentToolParameter("chat_attachment_id", "string", required = true, description = "Attachment id from the current chat history."),
          AgentToolParameter("destination_path", "string", required = true, description = "Destination path inside the writable workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "search_workspace_document",
        description = "Search a readable workspace document for relevant PDF pages and text excerpts. Use this before attaching a large PDF when you need to locate the right pages or verify whether specific keywords appear.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("query", "string", required = false, description = "Optional keyword or phrase to search for inside the document. When omitted, returns page previews instead."),
          AgentToolParameter("pages", "number[]", required = false, description = "Optional explicit 1-based page numbers to inspect."),
          AgentToolParameter("page_from", "number", required = false, description = "Optional inclusive 1-based page number to start scanning from."),
          AgentToolParameter("page_to", "number", required = false, description = "Optional inclusive 1-based page number to stop scanning at."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of preview or match results to return."),
        ),
      ),
      AgentToolDefinition(
        name = "inspect_workspace_package",
        description = "Inspect one readable ZIP-based package such as zip, docx, xlsx, pptx, odt, ods, or odp. Use this to list internal entries, preview specific XML or text parts, and identify the main document parts before extracting anything.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to package entry paths."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of matched entries to return."),
          AgentToolParameter("preview_entries", "string[]", required = false, description = "Optional exact package entry paths to preview when they are safe text or XML entries."),
          AgentToolParameter("preview_chars", "number", required = false, description = "Maximum characters to preview for each requested entry."),
          AgentToolParameter("include_relationship_hints", "boolean", required = false, description = "Whether to include package kind hints such as main parts and relationship parts."),
        ),
      ),
      AgentToolDefinition(
        name = "extract_workspace_package",
        description = "Extract selected entries from one readable ZIP-based package into a writable workspace directory. Requires entries or glob and never defaults to full-package extraction.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("destination_dir", "string", required = true, description = "Writable workspace directory where the selected entries will be extracted."),
          AgentToolParameter("entries", "string[]", required = false, description = "Optional exact package entry paths, or package subdirectories, to extract."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to package entry paths."),
          AgentToolParameter("strip_top_level", "boolean", required = false, description = "Whether to remove one shared top-level directory segment from extracted paths when present."),
          AgentToolParameter("overwrite", "boolean", required = false, description = "Whether existing destination files may be overwritten."),
        ),
      ),
      AgentToolDefinition(
        name = "view_workspace_image",
        description = "Attach one readable workspace image into the next model turn for direct visual inspection. Use this when you need to see what an existing image actually contains instead of guessing from its path or filename.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
        ),
      ),
      AgentToolDefinition(
        name = "view_workspace_document",
        description = "Attach one readable workspace image or PDF into the next model turn for direct inspection. Use this when you need the model to inspect the existing document itself instead of guessing from the path or filename.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
        ),
      ),
      AgentToolDefinition(
        name = "view_workspace_pdf",
        description = "Attach one readable workspace PDF into the next model turn for direct inspection. Use this when you need the model to inspect the PDF contents directly instead of guessing from the path or filename.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
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
        description = buildString {
          append("Execute one workspace-local Python script through the active Python runtime backend. ")
          append("Use this instead of Bash for workspace Python scripts and Python runtime diagnostics.")
          if (pythonManifestProviderAvailable) {
            append(" Runtime packages are preinstalled-only; call python_runtime_manifest for exact available packages when imports matter.")
          }
        },
        parameters = listOf(
          AgentToolParameter("script_path", "string", required = true, description = "Script path relative to the workspace root."),
          AgentToolParameter("args", "string[]", required = false, description = "Script arguments."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "Maximum runtime before the Python execution is timed out."),
          AgentToolParameter("startup_timeout_ms", "number", required = false, description = "Optional extra startup budget before Python script timeout accounting begins."),
        ),
      ),
      config.pythonRuntimeManifestProvider?.let {
        AgentToolDefinition(
          name = "python_runtime_manifest",
          description = "Inspect the active Python runtime package policy and exact preinstalled packages. Use this before writing Python imports when package availability matters.",
        )
      },
      config.sandboxPreviewService?.let {
        AgentToolDefinition(
          name = "sandbox_preview_open",
          description = "Return a preview URL for one port exposed by the active cloud sandbox session and run a short reachability probe against it. Use this only when cloud execution is enabled and the target service is expected to be running inside the sandbox. If exactly one candidate preview port has already been discovered from sandbox output, port can be omitted.",
          parameters = listOf(
            AgentToolParameter("port", "number", required = false, description = "TCP port exposed by the sandbox service. Optional when the active sandbox session has exactly one discovered preview candidate port."),
            AgentToolParameter("path", "string", required = false, description = "Optional path suffix such as / or /health."),
          ),
        )
      },
      config.sandboxSessionControlService?.let {
        AgentToolDefinition(
          name = "sandbox_session_close",
          description = "Terminate the active reusable cloud sandbox session for the current workspace and clear its local resume snapshot. Use this when cloud work is finished or when the next cloud run should start from a fresh sandbox.",
        )
      },
      config.sandboxSessionInfoService?.let {
        AgentToolDefinition(
          name = "sandbox_session_info",
          description = "Inspect the active reusable cloud sandbox session for the current workspace, including whether it is in memory, persisted for resume, which preview candidate ports are known, and whether any sandbox requests are still running.",
        )
      },
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
    ).filterNotNull() + memoryToolDefinitions()
    val visibleCanonicalDefinitions = canonicalDefinitions
      .filter { definition -> !isToolHiddenByConfig(definition.name) }
      .let { visibleDefinitions ->
        allowedToolNames?.let { allowed ->
          visibleDefinitions.filter { definition -> definition.name in allowed }
        } ?: visibleDefinitions
      }
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
      managedProcessObservationTracker = managedProcessObservationTracker,
    )

  fun withApprovalGrant(
    approvedTaskId: String?,
    approvedToolName: String?,
  ): OpenCrayToolDispatcher =
    OpenCrayToolDispatcher(
      config.copy(
        approvedTaskId = approvedTaskId?.trim()?.takeIf(String::isNotBlank),
        approvedToolName = approvedToolName?.trim()?.takeIf(String::isNotBlank),
      ),
      managedProcessObservationTracker = managedProcessObservationTracker,
    )

  fun withApprovalRejection(
    rejectedTaskId: String?,
    rejectedToolName: String?,
  ): OpenCrayToolDispatcher =
    OpenCrayToolDispatcher(
      config.copy(
        rejectedTaskId = rejectedTaskId?.trim()?.takeIf(String::isNotBlank),
        rejectedToolName = rejectedToolName?.trim()?.takeIf(String::isNotBlank),
      ),
      managedProcessObservationTracker = managedProcessObservationTracker,
    )

  internal fun planTaskDelegation(
    task: AgentTask,
    toolName: String = "Task",
    description: String,
    prompt: String,
    subagentType: String,
    contextMode: String,
    allowedToolNames: Set<String>,
  ): ToolPolicyPlan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
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

  internal fun gateTaskDelegation(
    plan: ToolPolicyPlan,
    toolName: String = "Task",
  ): AgentToolResult? =
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = emptyMap(),
      askDetail = "Approval is required before $toolName can delegate this work.",
      denyDetail = "Policy denied $toolName.",
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
    if (isToolHiddenByConfig(invocation.normalizedToolName)) {
      return toolCallNormalizer.decorateResult(
        result = AgentToolResult(
          toolName = invocation.requestedToolName,
          status = AgentToolResultStatus.DENIED,
          content = "Tool '${invocation.requestedToolName}' is unavailable in the current execution environment.",
          errorCode = "TOOL_UNAVAILABLE_IN_RUNTIME",
          errorMessage = "Tool '${invocation.requestedToolName}' is unavailable in the current execution environment.",
          metadata = mapOf(
            "hiddenToolNamePrefixes" to hiddenToolNamePrefixes.sorted().joinToString(separator = ","),
          ),
        ),
        invocation = invocation,
      )
    }
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
        "import_chat_attachment" -> importChatAttachmentIntoWorkspace(task = task, arguments = invocation.arguments)
        "search_workspace_document" -> searchWorkspaceDocument(task = task, arguments = invocation.arguments)
        "inspect_workspace_package" -> inspectWorkspacePackage(task = task, arguments = invocation.arguments)
        "extract_workspace_package" -> extractWorkspacePackage(task = task, arguments = invocation.arguments)
        "view_workspace_image" -> viewWorkspaceImage(task = task, arguments = invocation.arguments)
        "view_workspace_document" -> viewWorkspaceDocument(task = task, arguments = invocation.arguments)
        "view_workspace_pdf" -> viewWorkspacePdf(task = task, arguments = invocation.arguments)
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
        "GenerateImage" -> generateImage(task = task, arguments = invocation.arguments)
        "SynthesizeSpeech" -> synthesizeSpeech(task = task, arguments = invocation.arguments)
        "Edit" -> editWorkspaceFile(task = task, arguments = invocation.arguments)
        "MultiEdit" -> multiEditWorkspaceFile(task = task, arguments = invocation.arguments)
        "TodoWrite" -> writeTodoList(arguments = invocation.arguments)
        "ScheduledTaskCreate" -> createScheduledTask(task = task, arguments = invocation.arguments)
        "ScheduledTaskList" -> listScheduledTasks(task = task, arguments = invocation.arguments)
        "ScheduledTaskGet" -> getScheduledTask(task = task, arguments = invocation.arguments)
        "ScheduledTaskUpdate" -> updateScheduledTask(task = task, arguments = invocation.arguments)
        "ScheduledTaskDelete" -> deleteScheduledTask(task = task, arguments = invocation.arguments)
        "Bash" -> executeClaudeBash(task = task, arguments = invocation.arguments)
        "ProcessStart" -> startManagedProcess(task = task, arguments = invocation.arguments)
        "ProcessList" -> listManagedProcesses()
        "ProcessRead" -> readManagedProcess(arguments = invocation.arguments)
        "ProcessWait" -> waitForManagedProcess(arguments = invocation.arguments)
        "ProcessTerminate" -> terminateManagedProcess(task = task, arguments = invocation.arguments)
        "command_exec" -> executeCommand(task = task, arguments = invocation.arguments, hooks = hooks)
        "python_exec" -> executePython(task = task, arguments = invocation.arguments)
        "python_runtime_manifest" -> inspectPythonRuntimeManifest(task = task)
        "sandbox_preview_open" -> openSandboxPreview(task = task, arguments = invocation.arguments)
        "sandbox_session_close" -> closeSandboxSession(task = task)
        "sandbox_session_info" -> inspectSandboxSession(task = task)
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

  internal fun canExecuteInParallel(
    task: AgentTask,
    call: AgentToolCall,
  ): Boolean {
    val invocation = toolCallNormalizer.normalize(call)
    if (isToolHiddenByConfig(invocation.normalizedToolName)) {
      return false
    }
    if (allowedToolNames != null && invocation.normalizedToolName !in allowedToolNames) {
      return false
    }
    return runCatching {
      when (invocation.normalizedToolName) {
        "workspace_list_files" -> preflightWorkspaceListFiles(task = task, arguments = invocation.arguments)
        "workspace_read_file" -> preflightWorkspaceReadFile(task = task, arguments = invocation.arguments)
        "search_workspace_document" -> preflightSearchWorkspaceDocument(task = task, arguments = invocation.arguments)
        "inspect_workspace_package" -> preflightInspectWorkspacePackage(task = task, arguments = invocation.arguments)
        "LS" -> preflightListFilesForClaude(task = task, arguments = invocation.arguments)
        "Read" -> preflightReadFileForClaude(task = task, arguments = invocation.arguments)
        "Grep" -> preflightGrepWorkspace(task = task, arguments = invocation.arguments)
        "Glob" -> preflightGlobWorkspace(task = task, arguments = invocation.arguments)
        "WebSearch" -> preflightWebSearch(task = task, arguments = invocation.arguments)
        "WebFetch" -> preflightWebFetch(task = task, arguments = invocation.arguments)
        "ProcessList",
        "ProcessRead",
        "python_runtime_manifest",
        "sandbox_session_info",
        "skills_list",
        "skill_read",
        "SkillsList",
        "mcp_list_servers",
        "memory_search",
        "memory_get",
        -> true
        else -> false
      }
    }.getOrDefault(false)
  }

  private fun preflightWorkspaceListFiles(task: AgentTask, arguments: JsonObject): Boolean {
    val directory = toolTargetResolver.ensureReadableDirectory(
      candidate = arguments.optionalString("path"),
      label = "workspace list",
      defaultToRoot = true,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "workspace_list_files",
      targetPath = directory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = directory,
      ),
    )
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayModelPath(directory)),
    ) == null
  }

  private fun isToolHiddenByConfig(toolName: String): Boolean {
    if (hiddenToolNamePrefixes.isEmpty()) {
      return false
    }
    val normalizedName = toolName.trim().lowercase(Locale.US)
    return hiddenToolNamePrefixes.any { prefix -> normalizedName.startsWith(prefix) }
  }

  private fun preflightWorkspaceReadFile(task: AgentTask, arguments: JsonObject): Boolean {
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
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayModelPath(file)),
    ) == null
  }

  private fun preflightListFilesForClaude(task: AgentTask, arguments: JsonObject): Boolean {
    val directory = toolTargetResolver.ensureReadableDirectory(
      candidate = arguments.optionalString("path"),
      label = "LS",
      defaultToRoot = true,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "LS",
      targetPath = directory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = directory,
      ),
    )
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to toolTargetResolver.displayModelPath(directory)),
    ) == null
  }

  private fun preflightReadFileForClaude(task: AgentTask, arguments: JsonObject): Boolean {
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
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("filePath" to toolTargetResolver.displayModelPath(file)),
    ) == null
  }

  private fun preflightGrepWorkspace(task: AgentTask, arguments: JsonObject): Boolean {
    val pattern = arguments.requiredString("pattern")
    Regex(pattern)
    val searchRoot = toolTargetResolver.resolveSearchRoot(arguments.optionalString("path"), label = "Grep path")
    arguments.optionalString("glob")?.let(::compileGlobMatcher)
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
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", toolTargetResolver.displayModelPath(searchRoot))
        put("pattern", pattern)
        arguments.optionalString("glob")?.let { put("glob", it) }
      },
    ) == null
  }

  private fun preflightGlobWorkspace(task: AgentTask, arguments: JsonObject): Boolean {
    val pattern = arguments.requiredString("pattern")
    compileGlobMatcher(pattern)
    val searchRoot = toolTargetResolver.resolveSearchRoot(arguments.optionalString("path"), label = "Glob path")
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
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf(
        "path" to toolTargetResolver.displayModelPath(searchRoot),
        "pattern" to pattern,
      ),
    ) == null
  }

  private fun preflightWebSearch(task: AgentTask, arguments: JsonObject): Boolean {
    if (!webSearchEnabled(task)) {
      return false
    }
    val query = arguments.requiredString("query")
    val domains = arguments.optionalStringArray("domains")
      .map(String::trim)
      .filter(String::isNotEmpty)
      .distinct()
    val plan = webSearchPolicyPlan(
      task = task,
      query = query,
    )
    return toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("query", inlinePreview(query, maxChars = 256))
        if (domains.isNotEmpty()) {
          put("domains", domains.joinToString(separator = ","))
        }
      },
      askDetail = "Approval is required before WebSearch can access the network.",
      denyDetail = "Policy denied WebSearch.",
    ) == null
  }

  private fun webSearchEnabled(task: AgentTask): Boolean =
    task.metadata["webSearchEnabled"]
      ?.trim()
      ?.lowercase()
      ?.let { rawValue ->
        when (rawValue) {
          "true" -> true
          "false" -> false
          else -> null
        }
      } ?: true

  private fun webSearchPolicyPlan(
    task: AgentTask,
    query: String,
  ): ToolPolicyPlan {
    val basePlan = toolPolicyPipeline.plan(
      task = task,
      toolName = "WebSearch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = inlinePreview(query, maxChars = 256),
      ),
    )
    return basePlan.copy(
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "WEB_SEARCH_DEFAULT_ALLOW",
      ),
    )
  }

  private fun webSearchDisabledResult(
    query: String,
    domains: List<String>,
  ): AgentToolResult = AgentToolResult(
    toolName = "WebSearch",
    status = AgentToolResultStatus.DENIED,
    content = "Web search is disabled for this run.",
    errorCode = "WEB_SEARCH_DISABLED",
    errorMessage = "Web search is disabled for this run.",
    metadata = mapOf(
      "query" to query,
      "webSearchEnabled" to "false",
    ) + domainsMetadata(domains),
  )

  private fun preflightWebFetch(task: AgentTask, arguments: JsonObject): Boolean {
    val url = arguments.requiredString("url")
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "WebFetch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        primaryTargetPath = url,
        targetSummary = inlinePreview(url, maxChars = 256),
      ),
    )
    return toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf("url" to inlinePreview(url, maxChars = 256)),
      askDetail = "Approval is required before WebFetch can access the network.",
      denyDetail = "Policy denied WebFetch.",
    ) == null
  }

  private fun preflightSearchWorkspaceDocument(task: AgentTask, arguments: JsonObject): Boolean {
    val documentPath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace document",
    )
    val displayPath = toolTargetResolver.displayModelPath(documentPath)
    require(workspaceDocumentKindFor(documentPath) == WorkspaceDocumentKind.PDF) {
      "search_workspace_document currently supports PDF files only: $displayPath"
    }
    val pageFrom = arguments.optionalInt("page_from")
    val pageTo = arguments.optionalInt("page_to")
    require(pageFrom == null || pageFrom >= 1) {
      "page_from must be >= 1 when provided."
    }
    require(pageTo == null || pageTo >= 1) {
      "page_to must be >= 1 when provided."
    }
    arguments.optionalIntArray("pages").forEach { pageNumber ->
      require(pageNumber >= 1) {
        "pages must contain only 1-based page numbers."
      }
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "search_workspace_document",
      targetPath = documentPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = documentPath,
      ),
    )
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPath)
        arguments.optionalString("query")?.trim()?.takeIf(String::isNotBlank)?.let { put("query", it) }
      },
    ) == null
  }

  private fun preflightInspectWorkspacePackage(task: AgentTask, arguments: JsonObject): Boolean {
    val packagePath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace package",
    )
    val displayPath = toolTargetResolver.displayModelPath(packagePath)
    require(workspacePackageKindFor(packagePath) != null) {
      "inspect_workspace_package currently supports ZIP-based packages only: $displayPath"
    }
    require((arguments.optionalInt("max_entries") ?: 1) >= 1) {
      "max_entries must be >= 1 when provided."
    }
    require((arguments.optionalInt("preview_chars") ?: 1) >= 1) {
      "preview_chars must be >= 1 when provided."
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "inspect_workspace_package",
      targetPath = packagePath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = packagePath,
      ),
    )
    return gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPath)
        arguments.optionalString("glob")?.trim()?.takeIf(String::isNotBlank)?.let { put("glob", it) }
      },
    ) == null
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

  private fun importChatAttachmentIntoWorkspace(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val resolver = config.chatAttachmentResolver
      ?: error("Chat attachment importing is unavailable in this runtime.")
    val chatAttachmentId = arguments.requiredString("chat_attachment_id")
    val source = resolver(chatAttachmentId)
      ?: error("Chat attachment '$chatAttachmentId' was not found in the current session.")
    val sourcePath = source.sourcePath.toAbsolutePath().normalize()
    require(Files.exists(sourcePath) && Files.isRegularFile(sourcePath)) {
      "Chat attachment source is unavailable: ${source.displayName}"
    }
    val destination = toolTargetResolver.resolveWritablePath(
      arguments.requiredString("destination_path"),
      label = "import destination",
      defaultToRoot = false,
    )
    require(!Files.exists(destination)) {
      "Import destination already exists: ${toolTargetResolver.displayWritablePath(destination)}"
    }

    val destinationPath = toolTargetResolver.displayWritablePath(destination)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "import_chat_attachment",
      targetPath = destination,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = sourcePath,
        secondaryPath = destination,
        primaryTargetPath = "chat_attachment:${source.attachmentId}",
        secondaryTargetPath = destinationPath,
        targetSummary = "${source.displayName} -> $destinationPath",
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "chatAttachmentId" to source.attachmentId,
        "chatAttachmentDisplayName" to source.displayName,
        "destinationPath" to destinationPath,
      ),
    )?.let { return it }

    copyIntoWorkspace(source = sourcePath, destination = destination)
    val artifactMetadata = attachmentArtifactMetadata(destination)

    return AgentToolResult(
      toolName = "import_chat_attachment",
      status = AgentToolResultStatus.SUCCESS,
      content = "Imported chat attachment ${source.displayName} into $destinationPath.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "chatAttachmentId" to source.attachmentId,
          "chatAttachmentDisplayName" to source.displayName,
          "destinationPath" to destinationPath,
        ) + artifactMetadata,
      ),
    )
  }

  private fun searchWorkspaceDocument(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val documentPath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace document",
    )
    val displayPath = toolTargetResolver.displayModelPath(documentPath)
    require(workspaceDocumentKindFor(documentPath) == WorkspaceDocumentKind.PDF) {
      "search_workspace_document currently supports PDF files only: $displayPath"
    }
    val query = arguments.optionalString("query")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val pageNumbers = arguments.optionalIntArray("pages")
      .distinct()
      .sorted()
      .also { pages ->
        require(pages.all { pageNumber -> pageNumber >= 1 }) {
          "pages must contain only 1-based page numbers."
        }
      }
    val pageFrom = arguments.optionalInt("page_from")
      ?.also { pageNumber ->
        require(pageNumber >= 1) {
          "page_from must be >= 1 when provided."
        }
      }
    val pageTo = arguments.optionalInt("page_to")
      ?.also { pageNumber ->
        require(pageNumber >= 1) {
          "page_to must be >= 1 when provided."
        }
      }
    val request = WorkspaceDocumentSearchRequest(
      query = query,
      pageNumbers = pageNumbers,
      pageFrom = pageFrom,
      pageTo = pageTo,
      maxResults = arguments.optionalInt("max_results")?.coerceIn(1, config.maxDirectoryEntries)
        ?: DEFAULT_WORKSPACE_DOCUMENT_SEARCH_RESULTS,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "search_workspace_document",
      targetPath = documentPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = documentPath,
        targetSummary = buildString {
          append(displayPath)
          query?.let { append(" query=").append(it) }
        },
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPath)
        query?.let { put("query", it) }
        if (pageNumbers.isNotEmpty()) {
          put("pages", pageNumbers.joinToString(separator = ","))
        }
        pageFrom?.let { put("pageFrom", it.toString()) }
        pageTo?.let { put("pageTo", it.toString()) }
      },
    )?.let { return it }

    val result = config.documentSearchProvider.search(
      path = documentPath,
      request = request,
    )
    return AgentToolResult(
      toolName = "search_workspace_document",
      status = AgentToolResultStatus.SUCCESS,
      content = renderWorkspaceDocumentSearchResult(
        displayPath = displayPath,
        request = request,
        result = result,
      ),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayPath)
          put("documentKind", result.documentKind.name.lowercase(Locale.US))
          put("pageCount", result.pageCount.toString())
          put("hitCount", result.hits.size.toString())
          put("requestedMaxResults", request.maxResults.toString())
          query?.let { put("query", it) }
          if (pageNumbers.isNotEmpty()) {
            put("requestedPages", pageNumbers.joinToString(separator = ","))
          }
          pageFrom?.let { put("pageFrom", it.toString()) }
          pageTo?.let { put("pageTo", it.toString()) }
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = false,
          limitKind = ToolResultLimitKind.SEARCH_MATCH_LIMIT,
        ),
      ),
    )
  }

  private fun inspectWorkspacePackage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packagePath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace package",
    )
    val displayPath = toolTargetResolver.displayModelPath(packagePath)
    require(workspacePackageKindFor(packagePath) != null) {
      "inspect_workspace_package currently supports ZIP-based packages only: $displayPath"
    }
    val glob = arguments.optionalString("glob")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val previewEntries = arguments.optionalStringArray("preview_entries")
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .also { entries ->
        require(entries.size <= MAX_WORKSPACE_PACKAGE_PREVIEW_ENTRY_REQUESTS) {
          "preview_entries may contain at most $MAX_WORKSPACE_PACKAGE_PREVIEW_ENTRY_REQUESTS entries."
        }
      }
    val request = WorkspacePackageInspectionRequest(
      glob = glob,
      maxEntries = arguments.optionalInt("max_entries")?.coerceIn(1, config.maxDirectoryEntries)
        ?: DEFAULT_WORKSPACE_PACKAGE_INSPECTION_RESULTS,
      previewEntries = previewEntries,
      previewChars = arguments.optionalInt("preview_chars")?.coerceIn(1, MAX_WORKSPACE_PACKAGE_PREVIEW_CHARS)
        ?: DEFAULT_WORKSPACE_PACKAGE_PREVIEW_CHARS,
      includeRelationshipHints = arguments.optionalBoolean("include_relationship_hints") ?: true,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "inspect_workspace_package",
      targetPath = packagePath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = packagePath,
        targetSummary = buildString {
          append(displayPath)
          glob?.let { append(" glob=").append(it) }
        },
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPath)
        glob?.let { put("glob", it) }
      },
    )?.let { return it }

    val result = DefaultWorkspacePackageProvider().inspect(
      path = packagePath,
      request = request,
    )
    val previewTruncated = result.previews.any(WorkspacePackageEntryPreview::truncated)
    val limitKind = if (previewTruncated) {
      ToolResultLimitKind.READ_BYTE_BUDGET
    } else {
      ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT
    }
    return AgentToolResult(
      toolName = "inspect_workspace_package",
      status = AgentToolResultStatus.SUCCESS,
      content = renderWorkspacePackageInspectionResult(
        displayPath = displayPath,
        request = request,
        result = result,
      ),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayPath)
          put("packageKind", result.packageKind.name.lowercase(Locale.US))
          put("entryCount", result.entryCount.toString())
          put("matchedEntryCount", result.matchedEntryCount.toString())
          put("returnedEntryCount", result.entries.size.toString())
          put("previewCount", result.previews.size.toString())
          put("mediaEntryCount", result.mediaEntryCount.toString())
          put("requestedMaxEntries", request.maxEntries.toString())
          put("previewChars", request.previewChars.toString())
          put("includeRelationshipHints", request.includeRelationshipHints.toString())
          glob?.let { put("requestedGlob", it) }
          if (previewEntries.isNotEmpty()) {
            put("requestedPreviewEntries", previewEntries.joinToString(separator = ","))
          }
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = result.truncated,
          limitKind = limitKind,
        ),
      ),
    )
  }

  private fun extractWorkspacePackage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packagePath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace package",
    )
    val displayPath = toolTargetResolver.displayModelPath(packagePath)
    require(workspacePackageKindFor(packagePath) != null) {
      "extract_workspace_package currently supports ZIP-based packages only: $displayPath"
    }
    val destinationDir = toolTargetResolver.resolveWritablePath(
      arguments.requiredString("destination_dir"),
      label = "workspace package extraction directory",
      defaultToRoot = false,
    )
    val displayDestinationDir = toolTargetResolver.displayWritablePath(destinationDir)
    if (Files.exists(destinationDir)) {
      require(Files.isDirectory(destinationDir)) {
        "Package extraction destination must be a directory: $displayDestinationDir"
      }
    }
    val requestedEntries = arguments.optionalStringArray("entries")
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .also { entries ->
        require(entries.size <= MAX_WORKSPACE_PACKAGE_EXPLICIT_ENTRY_REQUESTS) {
          "entries may contain at most $MAX_WORKSPACE_PACKAGE_EXPLICIT_ENTRY_REQUESTS selections."
        }
      }
    val glob = arguments.optionalString("glob")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val request = WorkspacePackageExtractionRequest(
      destinationRoot = destinationDir,
      entries = requestedEntries,
      glob = glob,
      stripTopLevel = arguments.optionalBoolean("strip_top_level") ?: false,
      overwrite = arguments.optionalBoolean("overwrite") ?: false,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "extract_workspace_package",
      targetPath = packagePath,
      destinationPath = destinationDir,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = packagePath,
        secondaryPath = destinationDir,
        targetSummary = "$displayPath -> $displayDestinationDir",
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPath)
        put("destinationDir", displayDestinationDir)
        put("overwrite", request.overwrite.toString())
        put("stripTopLevel", request.stripTopLevel.toString())
        if (requestedEntries.isNotEmpty()) {
          put("entrySelectionCount", requestedEntries.size.toString())
        }
        glob?.let { put("glob", it) }
      },
    )?.let { return it }

    val result = DefaultWorkspacePackageProvider().extract(
      path = packagePath,
      request = request,
    )
    val extractedArtifacts = result.extractedPaths
      .take(MAX_WORKSPACE_PACKAGE_EXTRACTION_ARTIFACTS)
      .mapNotNull(::attachmentArtifactFor)
    val renderedPathCount = minOf(
      result.extractedPaths.size,
      MAX_RENDERED_WORKSPACE_PACKAGE_EXTRACTED_PATHS,
    )
    val renderedPathsTruncated = result.extractedPaths.size > renderedPathCount
    return AgentToolResult(
      toolName = "extract_workspace_package",
      status = AgentToolResultStatus.SUCCESS,
      content = renderWorkspacePackageExtractionResult(
        displayPath = displayPath,
        displayDestinationDir = displayDestinationDir,
        request = request,
        result = result,
      ),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayPath)
          put("destinationDir", displayDestinationDir)
          put("packageKind", result.packageKind.name.lowercase(Locale.US))
          put("entryCount", result.entryCount.toString())
          put("matchedEntryCount", result.matchedEntryCount.toString())
          put("extractedCount", result.extractedPaths.size.toString())
          put("renderedPathCount", renderedPathCount.toString())
          put("overwrite", request.overwrite.toString())
          put("stripTopLevel", request.stripTopLevel.toString())
          put("attachmentArtifactCount", extractedArtifacts.size.toString())
          glob?.let { put("requestedGlob", it) }
          if (requestedEntries.isNotEmpty()) {
            put("requestedEntries", requestedEntries.joinToString(separator = ","))
          }
          result.strippedTopLevel?.let { put("strippedTopLevel", it) }
          putAll(attachmentArtifactsMetadata(extractedArtifacts))
        },
        resultEnvelope = renderedPathsTruncated.takeIf { it }?.let {
          ToolResultEnvelope(
            limitApplied = true,
            truncated = true,
            limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
          )
        },
      ),
    )
  }

  private fun viewWorkspaceImage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult = viewWorkspaceImagePath(
    task = task,
    imagePath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace image",
    ),
    toolName = "view_workspace_image",
  )

  private fun viewWorkspaceDocument(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val documentPath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace document",
    )
    return when (workspaceDocumentKindFor(documentPath)) {
      WorkspaceDocumentKind.IMAGE -> viewWorkspaceImagePath(
        task = task,
        imagePath = documentPath,
        toolName = "view_workspace_document",
      )
      WorkspaceDocumentKind.PDF -> viewWorkspacePdfPath(
        task = task,
        pdfPath = documentPath,
        toolName = "view_workspace_document",
      )
      null -> throw IllegalArgumentException(
        "view_workspace_document currently supports image and PDF files only: ${toolTargetResolver.displayModelPath(documentPath)}",
      )
    }
  }

  private fun viewWorkspaceImagePath(
    task: AgentTask,
    imagePath: Path,
    toolName: String,
  ): AgentToolResult {
    val displayPath = toolTargetResolver.displayModelPath(imagePath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = toolName,
      targetPath = imagePath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = imagePath,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displayPath),
    )?.let { return it }

    val displayName = imagePath.fileName?.toString()
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: error("Workspace image path must point to a file.")
    val mimeType = workspaceImageMimeType(path = imagePath, displayName = displayName)
      ?: error("Workspace image must be a supported image file: $displayPath")
    val byteCount = Files.size(imagePath)
    require(byteCount in 1..MAX_VIEW_WORKSPACE_IMAGE_BYTES) {
      "Workspace image must be between 1 byte and ${MAX_VIEW_WORKSPACE_IMAGE_BYTES / (1024 * 1024)} MB: $displayPath"
    }

    val promptSupplement = OpenCrayPromptSupplementMetadata.encodeMetadata(
      json = config.json,
      text = "Inspect the attached workspace image from $displayPath directly before deciding what it contains.",
      attachments = listOf(
        RuntimeConversationAttachment(
          attachmentId = "workspace-image-${UUID.randomUUID().toString().take(8)}",
          kind = RuntimeConversationAttachmentKind.IMAGE,
          displayName = displayName,
          filePath = imagePath.toAbsolutePath().normalize().toString().replace('\\', '/'),
          mimeType = mimeType,
        ),
      ),
    )

    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Attached workspace image $displayPath for direct visual inspection on the next turn.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displayPath,
          "documentKind" to WorkspaceDocumentKind.IMAGE.name.lowercase(Locale.US),
          "displayName" to displayName,
          "mimeType" to mimeType,
          "byteCount" to byteCount.toString(),
        ) + promptSupplement,
      ),
    )
  }

  private fun viewWorkspacePdf(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult = viewWorkspacePdfPath(
    task = task,
    pdfPath = toolTargetResolver.ensureReadableFile(
      arguments.requiredString("path"),
      label = "workspace PDF",
    ),
    toolName = "view_workspace_pdf",
  )

  private fun viewWorkspacePdfPath(
    task: AgentTask,
    pdfPath: Path,
    toolName: String,
  ): AgentToolResult {
    val displayPath = toolTargetResolver.displayModelPath(pdfPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = toolName,
      targetPath = pdfPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = pdfPath,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displayPath),
    )?.let { return it }

    val displayName = pdfPath.fileName?.toString()
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: error("Workspace PDF path must point to a file.")
    val mimeType = workspacePdfMimeType(path = pdfPath, displayName = displayName)
      ?: error("Workspace PDF must be a supported PDF file: $displayPath")
    val byteCount = Files.size(pdfPath)
    require(byteCount in 1..MAX_VIEW_WORKSPACE_PDF_BYTES) {
      "Workspace PDF must be between 1 byte and ${MAX_VIEW_WORKSPACE_PDF_BYTES / (1024 * 1024)} MB: $displayPath"
    }

    val promptSupplement = OpenCrayPromptSupplementMetadata.encodeMetadata(
      json = config.json,
      text = "Inspect the attached workspace PDF from $displayPath directly before deciding what it contains.",
      attachments = listOf(
        RuntimeConversationAttachment(
          attachmentId = "workspace-pdf-${UUID.randomUUID().toString().take(8)}",
          kind = RuntimeConversationAttachmentKind.FILE,
          displayName = displayName,
          filePath = pdfPath.toAbsolutePath().normalize().toString().replace('\\', '/'),
          mimeType = mimeType,
        ),
      ),
    )

    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Attached workspace PDF $displayPath for direct inspection on the next turn.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displayPath,
          "documentKind" to WorkspaceDocumentKind.PDF.name.lowercase(Locale.US),
          "displayName" to displayName,
          "mimeType" to mimeType,
          "byteCount" to byteCount.toString(),
        ) + promptSupplement,
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
    if (!webSearchEnabled(task)) {
      return webSearchDisabledResult(query = query, domains = domains)
    }
    val plan = webSearchPolicyPlan(
      task = task,
      query = query,
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

    val rendered = when {
      result.results.isNotEmpty() -> {
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

      result.summaryText.isNotBlank() -> buildString {
        appendLine("provider=${result.providerName}")
        appendLine("summary=${result.summaryText}")
      }.trim()

      else -> "No web search results."
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
        primaryTargetPath = url,
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

  private fun generateImage(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()?.imageGeneration
      ?: return unavailableMediaTool(
        toolName = "GenerateImage",
        message = "Image generation settings are unavailable on this runtime.",
      )
    if (!settings.isConfigured()) {
      return unavailableMediaTool(
        toolName = "GenerateImage",
        message = "Image generation is not configured. Set provider base URL, endpoint, and model first.",
      )
    }
    val client = config.imageGenerationClient
      ?: return unavailableMediaTool(
        toolName = "GenerateImage",
        message = "Image generation provider support is unavailable on this runtime.",
      )
    val prompt = arguments.requiredText("prompt").trim()
    require(prompt.isNotBlank()) { "GenerateImage prompt must not be blank." }
    val count = arguments.optionalInt("count")?.coerceIn(1, MAX_GENERATED_IMAGE_COUNT)
      ?: 1
    val format = normalizeGeneratedImageFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_IMAGE_FORMAT
    val size = arguments.optionalString("size")?.trim()?.takeIf(String::isNotBlank)
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val outputDirectory = generatedMediaDirectory("images")
    val endpoint = buildConfiguredEndpointPreview(
      baseUrl = settings.baseUrl,
      endpoint = settings.endpoint,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "GenerateImage",
      targetPath = outputDirectory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = outputDirectory,
        primaryTargetPath = toolTargetResolver.displayWritablePath(outputDirectory),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = inlinePreview(prompt, maxChars = 240),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("provider", settings.provider)
        put("endpoint", endpoint)
        put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      },
      askDetail = "Approval is required before GenerateImage can access the network.",
      denyDetail = "Policy denied GenerateImage.",
    )?.let { return it }

    val response = client.generate(
      OpenCrayImageGenerationRequest(
        prompt = prompt,
        count = count,
        size = size,
        format = format,
        modelOverride = modelOverride,
        settings = settings,
      ),
    )
    require(response.images.isNotEmpty()) { "Image provider returned no images." }
    require(response.images.size <= MAX_GENERATED_IMAGE_COUNT) {
      "Image provider returned too many images (${response.images.size})."
    }

    val batchId = UUID.randomUUID().toString().replace("-", "").take(12)
    val artifacts = response.images.mapIndexed { index, asset ->
      writeGeneratedWorkspaceArtifact(
        directory = outputDirectory,
        stem = buildString {
          append("image-")
          append(batchId)
          if (response.images.size > 1) {
            append("-")
            append(index + 1)
          }
        },
        requestedExtension = format,
        defaultExtension = DEFAULT_GENERATED_IMAGE_FORMAT,
        asset = asset,
        kindHint = "image",
      )
    }

    return AgentToolResult(
      toolName = "GenerateImage",
      status = AgentToolResultStatus.SUCCESS,
      content = buildGeneratedImageResultContent(artifacts = artifacts),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("provider", settings.provider)
          put("endpoint", endpoint)
          put("promptPreview", inlinePreview(prompt, maxChars = 240))
          put("imageCount", artifacts.size.toString())
          put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
          put("format", format)
          size?.let { put("size", it) }
          modelOverride?.let { put("modelOverride", it) }
          response.providerRequestId?.let { put("providerRequestId", it) }
          putAll(attachmentArtifactsMetadata(artifacts))
          putAll(response.metadata)
        },
      ),
    )
  }

  private fun synthesizeSpeech(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()?.speechSynthesis
      ?: return unavailableMediaTool(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis settings are unavailable on this runtime.",
      )
    if (!settings.isConfigured()) {
      return unavailableMediaTool(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis is not configured. Set provider base URL, endpoint, and default voice first.",
      )
    }
    val client = config.speechSynthesisClient
      ?: return unavailableMediaTool(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis provider support is unavailable on this runtime.",
      )
    val text = arguments.requiredText("text").trim()
    require(text.isNotBlank()) { "SynthesizeSpeech text must not be blank." }
    val format = normalizeGeneratedAudioFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_AUDIO_FORMAT
    val voiceOverride = arguments.optionalString("voice")?.trim()?.takeIf(String::isNotBlank)
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val outputDirectory = generatedMediaDirectory("voices")
    val endpoint = buildConfiguredEndpointPreview(
      baseUrl = settings.baseUrl,
      endpoint = settings.endpoint,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SynthesizeSpeech",
      targetPath = outputDirectory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = outputDirectory,
        primaryTargetPath = toolTargetResolver.displayWritablePath(outputDirectory),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = inlinePreview(text, maxChars = 240),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("provider", settings.provider)
        put("endpoint", endpoint)
        put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      },
      askDetail = "Approval is required before SynthesizeSpeech can access the network.",
      denyDetail = "Policy denied SynthesizeSpeech.",
    )?.let { return it }

    val response = client.synthesize(
      OpenCraySpeechSynthesisRequest(
        text = text,
        format = format,
        voiceOverride = voiceOverride,
        modelOverride = modelOverride,
        settings = settings,
      ),
    )
    val transcriptText = response.transcriptText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: text.takeIf(String::isNotBlank)
    val artifact = writeGeneratedWorkspaceArtifact(
      directory = outputDirectory,
      stem = "voice-${UUID.randomUUID().toString().replace("-", "").take(12)}",
      requestedExtension = format,
      defaultExtension = DEFAULT_GENERATED_AUDIO_FORMAT,
      asset = response.audio,
      kindHint = "voice",
      durationMs = response.durationMs,
      transcriptText = transcriptText,
    )

    return AgentToolResult(
      toolName = "SynthesizeSpeech",
      status = AgentToolResultStatus.SUCCESS,
      content = buildGeneratedSpeechResultContent(artifact = artifact),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("provider", settings.provider)
          put("endpoint", endpoint)
          put("textPreview", inlinePreview(text, maxChars = 240))
          put("format", format)
          voiceOverride?.let { put("voiceOverride", it) }
          modelOverride?.let { put("modelOverride", it) }
          response.providerRequestId?.let { put("providerRequestId", it) }
          putAll(attachmentArtifactsMetadata(listOf(artifact)))
          putAll(response.metadata)
        },
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
    val previousEntries = todoStore.snapshot()
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
    val renderedEntries = snapshot
    val pendingCount = renderedEntries.count { todo -> todo.status == AgentTodoStatus.PENDING }
    val inProgressCount = renderedEntries.count { todo -> todo.status == AgentTodoStatus.IN_PROGRESS }
    val completedCount = renderedEntries.count { todo -> todo.status == AgentTodoStatus.COMPLETED }
    val previousByContent = previousEntries.associateBy { entry -> entry.content }
    val currentByContent = renderedEntries.associateBy { entry -> entry.content }
    val addedTodoCount = currentByContent.keys.count { content -> content !in previousByContent }
    val removedTodoCount = previousByContent.keys.count { content -> content !in currentByContent }
    val statusChangedTodoCount = currentByContent.count { (content, entry) ->
      previousByContent[content]?.status?.let { previousStatus -> previousStatus != entry.status } == true
    }
    val completedTodoDeltaCount = currentByContent.count { (content, entry) ->
      entry.status == AgentTodoStatus.COMPLETED &&
        previousByContent[content]?.status != AgentTodoStatus.COMPLETED
    }
    val previousActiveTodo = previousEntries.firstOrNull { todo -> todo.status == AgentTodoStatus.IN_PROGRESS }
    val activeTodo = renderedEntries.firstOrNull { todo -> todo.status == AgentTodoStatus.IN_PROGRESS }
    val planChanged = previousEntries != renderedEntries
    val rendered = if (renderedEntries.isEmpty()) {
      "Todo list is empty."
    } else {
      renderedEntries.joinToString(separator = "\n") { todo ->
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
          targetSummary = "${renderedEntries.size} todo(s)",
        ),
        metadata = mapOf(
          TodoWriteMetadataKeys.TODO_COUNT to renderedEntries.size.toString(),
          TodoWriteMetadataKeys.MUTATED to (todos != null).toString(),
          TodoWriteMetadataKeys.PLAN_CHANGED to planChanged.toString(),
          TodoWriteMetadataKeys.PENDING_TODO_COUNT to pendingCount.toString(),
          TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT to inProgressCount.toString(),
          TodoWriteMetadataKeys.COMPLETED_TODO_COUNT to completedCount.toString(),
          TodoWriteMetadataKeys.ADDED_TODO_COUNT to addedTodoCount.toString(),
          TodoWriteMetadataKeys.REMOVED_TODO_COUNT to removedTodoCount.toString(),
          TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT to statusChangedTodoCount.toString(),
          TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT to completedTodoDeltaCount.toString(),
          TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED to (previousActiveTodo?.content != activeTodo?.content).toString(),
        ) + listOfNotNull(
          activeTodo?.content?.takeIf(String::isNotBlank)?.let { content ->
            TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT to content
          },
        ),
      ),
    )
  }

  private fun createScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskCreate")
    val prompt = arguments.requiredString("prompt").trim()
    val explicitSessionId = arguments.optionalStringFrom("session_id", "sessionId")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val resolvedSessionId = explicitSessionId
      ?: hostSessionId(task)
      ?: return AgentToolResult(
        toolName = "ScheduledTaskCreate",
        status = AgentToolResultStatus.FAILED,
        content = "ScheduledTaskCreate requires session_id when the current host session id is unavailable.",
        errorCode = "SCHEDULED_TASK_SESSION_UNRESOLVED",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "ScheduledTaskCreate",
          request = ToolMetadataContextRequest(
            workspaceRelation = ToolWorkspaceRelation.NONE,
          ),
        ),
      )
    val title = arguments.optionalStringFrom("title", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val conflictPolicy = requireNotNull(
      parseScheduledTaskConflictPolicy(
        arguments = arguments,
        toolName = "ScheduledTaskCreate",
        defaultValue = ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN,
      ),
    )
    val trigger = requireNotNull(
      parseScheduledTaskTrigger(
        arguments = arguments,
        toolName = "ScheduledTaskCreate",
        required = true,
      ),
    )
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val targetSummary = buildString {
      append(resolvedSessionId)
      append(" -> ")
      append(title ?: inlinePreview(prompt, maxChars = 80))
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskCreate",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = targetSummary,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.CREATE_SCHEDULED_TASK,
        triggerKind = scheduledTaskTriggerKind(trigger),
        sessionMode = if (explicitSessionId != null) "explicit_session" else "current_session",
        targetSessionId = resolvedSessionId,
        title = title,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SESSION_ID to resolvedSessionId,
      ),
    )?.let { return it }

    val request = ScheduledTaskCreateRequest(
      sessionId = resolvedSessionId,
      title = title,
      prompt = prompt,
      trigger = trigger,
      enabled = arguments.optionalBoolean("enabled") ?: true,
      conflictPolicy = conflictPolicy,
      requiresForegroundNotification = arguments.optionalBooleanFrom(
        "requires_foreground_notification",
        "requiresForegroundNotification",
      ) ?: true,
      notifyOnQueued = arguments.optionalBooleanFrom("notify_on_queued", "notifyOnQueued") ?: false,
      notifyOnApproval = arguments.optionalBooleanFrom(
        "notify_on_approval",
        "notifyOnApproval",
      ) ?: true,
      notifyOnCompletion = arguments.optionalBooleanFrom(
        "notify_on_completion",
        "notifyOnCompletion",
      ) ?: true,
      notifyOnInterruption = arguments.optionalBooleanFrom(
        "notify_on_interruption",
        "notifyOnInterruption",
      ) ?: true,
    )
    val result = runCatching {
      scheduledTaskManager.create(request)
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to create the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskCreate",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_CREATE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SESSION_ID to resolvedSessionId,
            ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to conflictPolicy.name.lowercase(Locale.US),
          ),
        ),
      )
    }
    return AgentToolResult(
      toolName = "ScheduledTaskCreate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task created.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        appendLine("title=${result.title}")
        appendLine("trigger_kind=${result.triggerKind}")
        appendLine("trigger_summary=${result.triggerSummary}")
        append("enabled=${result.enabled}")
        result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
          appendLine()
          append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
        }
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
          ScheduledTaskToolMetadataKeys.TRIGGER_KIND to result.triggerKind,
          ScheduledTaskToolMetadataKeys.TRIGGER_SUMMARY to result.triggerSummary,
          ScheduledTaskToolMetadataKeys.ENABLED to result.enabled.toString(),
          ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to conflictPolicy.name.lowercase(Locale.US),
        ) + listOfNotNull(
          result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
          },
        ).toMap(),
      ),
    )
  }

  private fun listScheduledTasks(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskList")
    val explicitSessionId = arguments.optionalStringFrom("session_id", "sessionId")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val resolvedSessionId = explicitSessionId ?: hostSessionId(task)
    val sessionMode = when {
      explicitSessionId != null -> "explicit_session"
      resolvedSessionId != null -> "current_session"
      else -> "all_sessions"
    }
    val enabled = arguments.optionalBoolean("enabled")
    val limit = (arguments.optionalInt("limit") ?: 20).coerceIn(1, config.maxDirectoryEntries)
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val targetSummary = buildString {
      append(resolvedSessionId ?: displayPolicyTargetPath)
      enabled?.let { append(" enabled=$it") }
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskList",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = targetSummary,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.LIST_SCHEDULED_TASKS,
        sessionMode = sessionMode,
        targetSessionId = resolvedSessionId,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = buildMap {
        put("path", displayPolicyTargetPath)
        put("limit", limit.toString())
        resolvedSessionId?.let { put(ScheduledTaskToolMetadataKeys.SESSION_ID, it) }
        enabled?.let { put(ScheduledTaskToolMetadataKeys.ENABLED, it.toString()) }
      },
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.list(
        ScheduledTaskListRequest(
          sessionId = resolvedSessionId,
          enabled = enabled,
          limit = limit,
        ),
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to list scheduled tasks."
      return AgentToolResult(
        toolName = "ScheduledTaskList",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_LIST_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            resolvedSessionId?.let { put(ScheduledTaskToolMetadataKeys.SESSION_ID, it) }
            enabled?.let { put(ScheduledTaskToolMetadataKeys.ENABLED, it.toString()) }
            put(ScheduledTaskToolMetadataKeys.RETURNED_COUNT, "0")
            put(ScheduledTaskToolMetadataKeys.TOTAL_COUNT, "0")
          },
        ),
      )
    }
    val truncated = result.totalCount > result.tasks.size
    return AgentToolResult(
      toolName = "ScheduledTaskList",
      status = AgentToolResultStatus.SUCCESS,
      content = renderScheduledTaskListResult(
        result = result,
        sessionMode = sessionMode,
      ),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          resolvedSessionId?.let { put(ScheduledTaskToolMetadataKeys.SESSION_ID, it) }
          enabled?.let { put(ScheduledTaskToolMetadataKeys.ENABLED, it.toString()) }
          put(ScheduledTaskToolMetadataKeys.RETURNED_COUNT, result.tasks.size.toString())
          put(ScheduledTaskToolMetadataKeys.TOTAL_COUNT, result.totalCount.toString())
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        ),
      ),
    )
  }

  private fun getScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskGet")
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId")
      .trim()
    val recentRunLimit = (arguments.optionalInt("recent_run_limit")
      ?: arguments.optionalInt("recentRunLimit")
      ?: 5).coerceIn(1, config.maxDirectoryEntries)
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskGet",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = scheduleId,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.GET_SCHEDULED_TASK,
        targetScheduleId = scheduleId,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.get(
        ScheduledTaskGetRequest(
          scheduleId = scheduleId,
          recentRunLimit = recentRunLimit,
        ),
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to inspect the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskGet",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_GET_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
            ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT to "0",
          ),
        ),
      )
    }
    val details = result.task
    val truncated = result.totalRunCount > result.recentRuns.size
    return AgentToolResult(
      toolName = "ScheduledTaskGet",
      status = AgentToolResultStatus.SUCCESS,
      content = renderScheduledTaskGetResult(result),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = scheduledTaskDetailsMetadata(details) + buildMap {
          put(ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT, result.recentRuns.size.toString())
          put(ScheduledTaskToolMetadataKeys.TOTAL_COUNT, result.totalRunCount.toString())
        },
        resultEnvelope = ToolResultEnvelope(
          limitApplied = true,
          truncated = truncated,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        ),
      ),
    )
  }

  private fun updateScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskUpdate")
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId")
      .trim()
    val title = arguments.optionalStringFrom("title", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val prompt = arguments.optionalString("prompt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val trigger = parseScheduledTaskTrigger(
      arguments = arguments,
      toolName = "ScheduledTaskUpdate",
      required = false,
    )
    val conflictPolicy = parseScheduledTaskConflictPolicy(
      arguments = arguments,
      toolName = "ScheduledTaskUpdate",
      defaultValue = null,
    )
    val requiresForegroundNotification = arguments.optionalBooleanFrom(
      "requires_foreground_notification",
      "requiresForegroundNotification",
    )
    val notifyOnQueued = arguments.optionalBooleanFrom("notify_on_queued", "notifyOnQueued")
    val notifyOnApproval = arguments.optionalBooleanFrom(
      "notify_on_approval",
      "notifyOnApproval",
    )
    val notifyOnCompletion = arguments.optionalBooleanFrom(
      "notify_on_completion",
      "notifyOnCompletion",
    )
    val notifyOnInterruption = arguments.optionalBooleanFrom(
      "notify_on_interruption",
      "notifyOnInterruption",
    )
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val targetSummary = buildString {
      append(scheduleId)
      title?.let { append(" -> $it") }
      if (title == null) {
        prompt?.let { append(" -> ${inlinePreview(it, maxChars = 80)}") }
      }
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskUpdate",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = targetSummary,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.UPDATE_SCHEDULED_TASK,
        triggerKind = trigger?.let(::scheduledTaskTriggerKind),
        targetScheduleId = scheduleId,
        title = title,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val request = runCatching {
      ScheduledTaskUpdateRequest(
        scheduleId = scheduleId,
        title = title,
        prompt = prompt,
        trigger = trigger,
        conflictPolicy = conflictPolicy,
        requiresForegroundNotification = requiresForegroundNotification,
        notifyOnQueued = notifyOnQueued,
        notifyOnApproval = notifyOnApproval,
        notifyOnCompletion = notifyOnCompletion,
        notifyOnInterruption = notifyOnInterruption,
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "ScheduledTaskUpdate requires at least one mutable field."
      return AgentToolResult(
        toolName = "ScheduledTaskUpdate",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_UPDATE_INVALID",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
          ),
        ),
      )
    }
    val result = runCatching {
      scheduledTaskManager.update(request)
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to update the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskUpdate",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_UPDATE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
          ) + listOfNotNull(
            conflictPolicy?.let {
              ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to it.name.lowercase(Locale.US)
            },
          ).toMap(),
        ),
      )
    }
    return AgentToolResult(
      toolName = "ScheduledTaskUpdate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task updated.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        appendLine("title=${result.title}")
        appendLine("trigger_kind=${result.triggerKind}")
        appendLine("trigger_summary=${result.triggerSummary}")
        append("enabled=${result.enabled}")
        result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
          appendLine()
          append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
        }
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
          ScheduledTaskToolMetadataKeys.TRIGGER_KIND to result.triggerKind,
          ScheduledTaskToolMetadataKeys.TRIGGER_SUMMARY to result.triggerSummary,
          ScheduledTaskToolMetadataKeys.ENABLED to result.enabled.toString(),
        ) + listOfNotNull(
          conflictPolicy?.let {
            ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to it.name.lowercase(Locale.US)
          },
          result.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
          },
        ).toMap(),
      ),
    )
  }

  private fun deleteScheduledTask(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val scheduledTaskManager = config.scheduledTaskManager
      ?: return unavailableScheduledTaskManager(toolName = "ScheduledTaskDelete")
    val scheduleId = arguments.requiredStringFrom("schedule_id", "scheduleId")
      .trim()
    val policyTargetPath = scheduledTaskManager.policyTargetPath().toAbsolutePath().normalize()
    val displayPolicyTargetPath = displayScheduledTaskPolicyPath(policyTargetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "ScheduledTaskDelete",
      targetPath = policyTargetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = policyTargetPath,
        primaryTargetPath = displayPolicyTargetPath,
        targetSummary = scheduleId,
      ),
      intent = SchedulingIntent(
        kind = SchedulingIntentKind.DELETE_SCHEDULED_TASK,
        targetScheduleId = scheduleId,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to displayPolicyTargetPath,
        ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
      ),
    )?.let { return it }

    val result = runCatching {
      scheduledTaskManager.delete(
        ScheduledTaskDeleteRequest(
          scheduleId = scheduleId,
        ),
      )
    }.getOrElse { throwable ->
      val detail = throwable.message?.trim()?.takeIf(String::isNotBlank)
        ?: "Failed to delete the scheduled task."
      return AgentToolResult(
        toolName = "ScheduledTaskDelete",
        status = AgentToolResultStatus.FAILED,
        content = detail,
        errorCode = "SCHEDULED_TASK_DELETE_FAILED",
        errorMessage = detail,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to scheduleId,
          ),
        ),
      )
    }
    return AgentToolResult(
      toolName = "ScheduledTaskDelete",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Scheduled task deleted.")
        appendLine("schedule_id=${result.scheduleId}")
        appendLine("session_id=${result.sessionId}")
        append("title=${result.title}")
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          ScheduledTaskToolMetadataKeys.SCHEDULE_ID to result.scheduleId,
          ScheduledTaskToolMetadataKeys.SESSION_ID to result.sessionId,
          ScheduledTaskToolMetadataKeys.TITLE to result.title,
        ),
      ),
    )
  }

  private fun unavailableScheduledTaskManager(
    toolName: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "$toolName is unavailable in the current execution environment.",
    errorCode = "SCHEDULED_TASK_MANAGER_UNAVAILABLE",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        workspaceRelation = ToolWorkspaceRelation.NONE,
      ),
    ),
  )

  private fun displayScheduledTaskPolicyPath(path: Path): String =
    path.toString().replace(File.separatorChar, '/')

  private fun hostSessionId(task: AgentTask): String? =
    task.metadata[HOST_SESSION_ID_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun parseScheduledTaskTrigger(
    arguments: JsonObject,
    toolName: String,
    required: Boolean,
  ): ScheduledTaskTriggerRequest? {
    val trigger = arguments.optionalObjectFrom("trigger")
      ?: return if (required) {
        throw IllegalArgumentException("$toolName requires a trigger object.")
      } else {
        null
      }
    val at = trigger.optionalStringFrom("at")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val after = trigger.optionalStringFrom("after")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val startAt = trigger.optionalStringFrom("start_at", "startAt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val timezone = trigger.optionalStringFrom("timezone", "time_zone", "timeZone")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val rrule = trigger.optionalStringFrom("rrule")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val exdates = trigger.optionalStringArrayFrom("exdates", "ex_dates", "exDates")
      .map(String::trim)
    val rdates = trigger.optionalStringArrayFrom("rdates", "r_dates", "rDates")
      .map(String::trim)
    val hasRecurrenceFields =
      startAt != null ||
        rrule != null ||
        timezone != null ||
        exdates.isNotEmpty() ||
        rdates.isNotEmpty()
    val configuredTriggerKinds = listOfNotNull(
      at?.let { "trigger.at" },
      after?.let { "trigger.after" },
      hasRecurrenceFields.takeIf { it }?.let { "trigger.rrule" },
    )
    require(configuredTriggerKinds.size == 1) {
      "$toolName trigger must use exactly one of trigger.at, trigger.after, or trigger.start_at plus trigger.rrule."
    }
    return when {
      at != null -> ScheduledTaskTriggerRequest.At(
        at = at,
      )

      after != null -> ScheduledTaskTriggerRequest.After(after = after)

      hasRecurrenceFields -> ScheduledTaskTriggerRequest.Recurrence(
        startAt = startAt
          ?: throw IllegalArgumentException("$toolName trigger.start_at is required for recurrence."),
        timezone = timezone,
        rrule = rrule
          ?: throw IllegalArgumentException("$toolName trigger.rrule is required for recurrence."),
        exdates = exdates,
        rdates = rdates,
      )

      else -> error("$toolName trigger configuration unexpectedly resolved empty.")
    }
  }

  private fun scheduledTaskTriggerKind(trigger: ScheduledTaskTriggerRequest): String = when (trigger) {
    is ScheduledTaskTriggerRequest.At -> "at"
    is ScheduledTaskTriggerRequest.After -> "after"
    is ScheduledTaskTriggerRequest.Recurrence -> "rrule"
  }

  private fun parseScheduledTaskConflictPolicy(
    arguments: JsonObject,
    toolName: String,
    defaultValue: ScheduledTaskConflictPolicy?,
  ): ScheduledTaskConflictPolicy? {
    val raw = arguments.optionalStringFrom("conflict_policy", "conflictPolicy")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return defaultValue
    return when (raw.lowercase(Locale.US)) {
      "enqueue_new_run", "enqueue-new-run", "enqueuenewrun" ->
        ScheduledTaskConflictPolicy.ENQUEUE_NEW_RUN

      "skip_if_session_busy", "skip-if-session-busy", "skipifsessionbusy" ->
        ScheduledTaskConflictPolicy.SKIP_IF_SESSION_BUSY

      "cancel_older_waiting_trigger",
      "cancel-older-waiting-trigger",
      "cancelolderwaitingtrigger",
      -> ScheduledTaskConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER

      else -> throw IllegalArgumentException(
        "$toolName conflict_policy must be enqueue_new_run, skip_if_session_busy, or cancel_older_waiting_trigger.",
      )
    }
  }

  private fun renderScheduledTaskListResult(
    result: ScheduledTaskListResult,
    sessionMode: String,
  ): String = if (result.tasks.isEmpty()) {
    "No scheduled tasks matched the current filter."
  } else {
    buildString {
      appendLine("Listed ${result.tasks.size} scheduled task(s) (session_mode=$sessionMode total=${result.totalCount}).")
      result.tasks.forEachIndexed { index, summary ->
        if (index > 0) {
          appendLine("--")
        }
        append(renderScheduledTaskSummary(summary))
        if (index < result.tasks.lastIndex) {
          appendLine()
        }
      }
    }.trim()
  }

  private fun renderScheduledTaskGetResult(
    result: ScheduledTaskGetResult,
  ): String = buildString {
    appendLine("Scheduled task details.")
    appendLine(renderScheduledTaskDetails(result.task))
    appendLine("recent_runs_returned=${result.recentRuns.size}")
    appendLine("recent_runs_total=${result.totalRunCount}")
    result.recentRuns.forEachIndexed { index, run ->
      appendLine("run[${index + 1}]=${renderScheduledTaskRunRecord(run)}")
    }
  }.trim()

  private fun renderScheduledTaskSummary(summary: ScheduledTaskSummary): String = buildString {
    appendLine("schedule_id=${summary.scheduleId}")
    appendLine("session_id=${summary.sessionId}")
    appendLine("title=${summary.title}")
    appendLine("trigger_kind=${summary.triggerKind}")
    appendLine("trigger_summary=${summary.triggerSummary}")
    append("enabled=${summary.enabled}")
    summary.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
      appendLine()
      append("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
    }
  }

  private fun renderScheduledTaskDetails(details: ScheduledTaskDetails): String = buildString {
    appendLine("schedule_id=${details.scheduleId}")
    appendLine("session_id=${details.sessionId}")
    appendLine("title=${details.title}")
    appendLine("prompt=${details.prompt.replace("\n", "\\n")}")
    appendLine("enabled=${details.enabled}")
    appendLine("trigger_kind=${details.triggerKind}")
    appendLine("trigger_summary=${details.triggerSummary}")
    append(renderScheduledTaskTriggerSnapshot(details.trigger))
    appendLine()
    details.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
      appendLine("next_trigger_at_epoch_ms=$nextTriggerAtEpochMs")
    }
    appendLine("conflict_policy=${details.conflictPolicy}")
    appendLine("requires_foreground_notification=${details.requiresForegroundNotification}")
    appendLine("notify_on_queued=${details.notifyOnQueued}")
    appendLine("notify_on_approval=${details.notifyOnApproval}")
    appendLine("notify_on_completion=${details.notifyOnCompletion}")
    appendLine("notify_on_interruption=${details.notifyOnInterruption}")
    appendLine("created_at_epoch_ms=${details.createdAtEpochMs}")
    append("updated_at_epoch_ms=${details.updatedAtEpochMs}")
  }

  private fun renderScheduledTaskTriggerSnapshot(
    trigger: ScheduledTaskTriggerSnapshot,
  ): String = when (trigger) {
    is ScheduledTaskTriggerSnapshot.At ->
      "trigger.at=${trigger.at}"

    is ScheduledTaskTriggerSnapshot.After ->
      "trigger.after=${trigger.after}"

    is ScheduledTaskTriggerSnapshot.Recurrence -> buildString {
      appendLine("trigger.start_at=${trigger.startAt}")
      appendLine("trigger.timezone=${trigger.timezone}")
      appendLine("trigger.rrule=${trigger.rrule}")
      if (trigger.exdates.isNotEmpty()) {
        appendLine("trigger.exdates=${trigger.exdates.joinToString(separator = ",")}")
      }
      append("trigger.rdates=${trigger.rdates.joinToString(separator = ",")}")
    }
  }

  private fun renderScheduledTaskRunRecord(
    run: ScheduledTaskRunRecordSummary,
  ): String = buildString {
    append("schedule_run_id=${run.scheduleRunId}")
    append(" result=${run.result}")
    append(" trigger_reason=${run.triggerReason}")
    append(" triggered_at_epoch_ms=${run.triggeredAtEpochMs}")
    run.acceptedAtEpochMs?.let { append(" accepted_at_epoch_ms=$it") }
    run.createdRunId?.let { append(" created_run_id=$it") }
    run.createdTaskId?.let { append(" created_task_id=$it") }
    run.failureReason?.let { append(" failure_reason=${it.replace("\n", "\\n")}") }
    run.recoverySource?.let { append(" recovery_source=$it") }
    append(" updated_at_epoch_ms=${run.updatedAtEpochMs}")
  }

  private fun scheduledTaskDetailsMetadata(
    details: ScheduledTaskDetails,
  ): Map<String, String> = mapOf(
    ScheduledTaskToolMetadataKeys.SCHEDULE_ID to details.scheduleId,
    ScheduledTaskToolMetadataKeys.SESSION_ID to details.sessionId,
    ScheduledTaskToolMetadataKeys.TITLE to details.title,
    ScheduledTaskToolMetadataKeys.TRIGGER_KIND to details.triggerKind,
    ScheduledTaskToolMetadataKeys.TRIGGER_SUMMARY to details.triggerSummary,
    ScheduledTaskToolMetadataKeys.ENABLED to details.enabled.toString(),
    ScheduledTaskToolMetadataKeys.CONFLICT_POLICY to details.conflictPolicy,
  ) + listOfNotNull(
    details.nextTriggerAtEpochMs?.let { nextTriggerAtEpochMs ->
      ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to nextTriggerAtEpochMs.toString()
    },
  ).toMap()

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
      return managedProcessToolResult(
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
        snapshot = startedSnapshot,
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
    return managedProcessToolResult(
      toolName = "Bash",
      status = toolStatusForManagedProcessStart(waitedSnapshot),
      content = buildString {
        appendLine(bashWaitSummary(snapshot = waitedSnapshot, waitTimeoutMs = waitTimeoutMs))
        append(renderManagedProcessSnapshot(snapshot = waitedSnapshot, includeOutput = true))
      }.trim(),
      snapshot = waitedSnapshot,
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
    return managedProcessToolResult(
      toolName = "ProcessStart",
      status = toolStatusForManagedProcessStart(snapshot),
      content = buildString {
        appendLine("Managed process started.")
        append(renderManagedProcessSnapshot(snapshot, includeOutput = false))
      }.trim(),
      snapshot = snapshot,
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
    val observationDelivery = observeManagedProcessOutput(snapshot)
    recordManagedProcessObservationDelivery(snapshot)
    return managedProcessToolResult(
      toolName = "ProcessRead",
      status = AgentToolResultStatus.SUCCESS,
      content = renderManagedProcessSnapshot(
        snapshot = snapshot,
        includeOutput = true,
        observationDelivery = observationDelivery,
      ),
      snapshot = snapshot,
      stdout = observationDelivery.stdout,
      stderr = observationDelivery.stderr,
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "ProcessRead",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.PROCESS,
          primaryPath = managedProcessWorkingDirectoryPath(snapshot),
          primaryTargetPath = toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory),
          targetSummary = processId,
        ),
        metadata = managedProcessMetadata(snapshot) + observationDelivery.metadata,
        resultEnvelope = managedProcessResultEnvelope(snapshot),
      ),
    )
  }

  private fun waitForManagedProcess(arguments: JsonObject): AgentToolResult {
    val processId = arguments.requiredString("process_id")
    val timeoutMs = arguments.optionalLong("timeout_ms") ?: DEFAULT_MANAGED_PROCESS_WAIT_TIMEOUT_MS
    val snapshot = processRegistry.wait(processId, timeoutMs)
      ?: return missingManagedProcess(processId = processId, toolName = "ProcessWait")
    val observationDelivery = observeManagedProcessOutput(snapshot)
    recordManagedProcessObservationDelivery(snapshot)
    return managedProcessToolResult(
      toolName = "ProcessWait",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Waited ${timeoutMs}ms for managed process.")
        append(
          renderManagedProcessSnapshot(
            snapshot = snapshot,
            includeOutput = true,
            observationDelivery = observationDelivery,
          ),
        )
      }.trim(),
      snapshot = snapshot,
      stdout = observationDelivery.stdout,
      stderr = observationDelivery.stderr,
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "ProcessWait",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.PROCESS,
          primaryPath = managedProcessWorkingDirectoryPath(snapshot),
          primaryTargetPath = toolTargetResolver.displayWorkingDirectory(snapshot.workingDirectory),
          targetSummary = processId,
        ),
        metadata = managedProcessMetadata(snapshot) +
          observationDelivery.metadata +
          mapOf("waitTimeoutMs" to timeoutMs.toString()),
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
      terminationSupport == "provider_native_signal" &&
        snapshot.status == ManagedProcessStatus.RUNNING &&
        terminationRequestAccepted == "true" ->
        "Managed process kill signal requested."
      terminationSupport == "cooperative" &&
        snapshot.status == ManagedProcessStatus.RUNNING &&
        terminationRequestAccepted == "false" ->
        "Managed process cancellation could not be delivered to the runtime and is still running."
      terminationSupport == "provider_native_signal" &&
        snapshot.status == ManagedProcessStatus.RUNNING &&
        terminationRequestAccepted == "false" ->
        "Managed process kill signal could not be delivered to the sandbox and is still running."
      terminationSupport == "unsupported" &&
        snapshot.status == ManagedProcessStatus.RUNNING ->
        "Managed process does not support termination on this runtime and is still running."
      else -> "Managed process termination requested."
    }
    return managedProcessToolResult(
      toolName = "ProcessTerminate",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine(terminationMessage)
        append(renderManagedProcessSnapshot(snapshot, includeOutput = true))
      }.trim(),
      snapshot = snapshot,
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
    val artifact = attachmentArtifactFor(path) ?: return emptyMap()
    return attachmentArtifactsMetadata(listOf(artifact))
  }

  private fun attachmentArtifactsMetadata(
    artifacts: List<OpenCrayGeneratedWorkspaceArtifact>,
  ): Map<String, String> {
    val descriptors = artifacts.mapNotNull(::attachmentArtifactDescriptor)
    return OpenCrayAttachmentArtifacts.encodeMetadata(config.json, descriptors)
  }

  private fun attachmentArtifactDescriptor(
    artifact: OpenCrayGeneratedWorkspaceArtifact,
  ): OpenCrayAttachmentArtifact? {
    val relativePath = toolTargetResolver.displayWritablePath(artifact.path)
      .trim()
      .takeIf(String::isNotBlank)
      ?.takeIf { candidate -> candidate != "." }
      ?: return null
    val displayName = artifact.displayName
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: artifact.path.fileName?.toString()
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: return null
    val kindHint = artifact.kindHint?.trim()?.takeIf(String::isNotBlank)
      ?: OpenCrayAttachmentArtifacts.kindHintForDisplayName(displayName)
    val mimeType = artifact.mimeType?.trim()?.takeIf(String::isNotBlank)
      ?: OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(displayName)
    return OpenCrayAttachmentArtifact(
      artifactId = OpenCrayAttachmentArtifacts.buildArtifactId(
        relativePath = relativePath,
        displayName = displayName,
      ),
      relativePath = relativePath,
      displayName = displayName,
      kindHint = kindHint,
      mimeType = mimeType,
      durationMs = artifact.durationMs,
      waveformBars = artifact.waveformBars,
      transcriptText = artifact.transcriptText?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun attachmentArtifactFor(path: Path): OpenCrayGeneratedWorkspaceArtifact? =
    path.fileName?.toString()
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { displayName ->
        OpenCrayGeneratedWorkspaceArtifact(
          path = path,
          kindHint = OpenCrayAttachmentArtifacts.kindHintForDisplayName(displayName),
          mimeType = OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(displayName),
          displayName = displayName,
        )
      }

  private fun workspaceImageMimeType(
    path: Path,
    displayName: String,
  ): String? {
    val probedMimeType = Files.probeContentType(path)
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf { mimeType -> mimeType.startsWith("image/") }
    if (probedMimeType != null) {
      return probedMimeType
    }
    return OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(displayName)
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf { mimeType -> mimeType.startsWith("image/") }
  }

  private fun workspacePdfMimeType(
    path: Path,
    displayName: String,
  ): String? {
    val probedMimeType = Files.probeContentType(path)
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf { mimeType -> mimeType == "application/pdf" }
    if (probedMimeType != null) {
      return probedMimeType
    }
    return OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(displayName)
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf { mimeType -> mimeType == "application/pdf" }
  }

  private fun unavailableMediaTool(
    toolName: String,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "MEDIA_TOOL_UNAVAILABLE",
    errorMessage = message,
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = mapOf("configured" to "false"),
    ),
  )

  private fun buildConfiguredEndpointPreview(
    baseUrl: String,
    endpoint: String,
  ): String {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedEndpoint = endpoint.trim()
    if (normalizedEndpoint.startsWith("http://") || normalizedEndpoint.startsWith("https://")) {
      return normalizedEndpoint
    }
    val endpointSuffix = normalizedEndpoint.trimStart('/')
    return when {
      normalizedBaseUrl.isBlank() -> normalizedEndpoint
      endpointSuffix.isBlank() -> normalizedBaseUrl
      else -> "$normalizedBaseUrl/$endpointSuffix"
    }
  }

  private fun buildGeneratedImageResultContent(
    artifacts: List<OpenCrayGeneratedWorkspaceArtifact>,
  ): String = buildString {
    appendLine("Generated ${artifacts.size} image file(s).")
    artifacts.forEachIndexed { index, artifact ->
      val descriptor = attachmentArtifactDescriptor(artifact) ?: return@forEachIndexed
      appendLine("${index + 1}. artifact_id=${descriptor.artifactId}")
      appendLine("   relative_path=${descriptor.relativePath}")
    }
    append("Attach the artifact_id values in the final response attachments array to send these images.")
  }.trim()

  private fun buildGeneratedSpeechResultContent(
    artifact: OpenCrayGeneratedWorkspaceArtifact,
  ): String {
    val descriptor = attachmentArtifactDescriptor(artifact)
      ?: return "Synthesized speech successfully."
    return buildString {
      appendLine("Synthesized one voice clip.")
      appendLine("artifact_id=${descriptor.artifactId}")
      appendLine("relative_path=${descriptor.relativePath}")
      append("Use kind=voice when attaching this artifact in the final response.")
    }.trim()
  }

  private fun generatedMediaDirectory(bucket: String): Path =
    writeBoundary.defaultRoot
      .resolve(".opencray")
      .resolve("generated-media")
      .resolve(bucket)
      .normalize()

  private fun writeGeneratedWorkspaceArtifact(
    directory: Path,
    stem: String,
    requestedExtension: String?,
    defaultExtension: String,
    asset: OpenCrayBinaryAsset,
    kindHint: String? = null,
    durationMs: Long? = null,
    transcriptText: String? = null,
  ): OpenCrayGeneratedWorkspaceArtifact {
    require(asset.bytes.isNotEmpty()) { "Generated media asset was empty." }
    val resolvedExtension = resolveGeneratedAssetExtension(
      requestedExtension = requestedExtension,
      defaultExtension = defaultExtension,
      fileName = asset.fileName,
      mimeType = asset.mimeType,
    )
    Files.createDirectories(directory)
    val outputPath = directory.resolve("$stem.$resolvedExtension")
    Files.write(outputPath, asset.bytes)
    return OpenCrayGeneratedWorkspaceArtifact(
      path = outputPath,
      kindHint = kindHint,
      mimeType = asset.mimeType?.trim()?.takeIf(String::isNotBlank)
        ?: OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(outputPath.fileName.toString()),
      displayName = outputPath.fileName.toString(),
      durationMs = durationMs,
      transcriptText = transcriptText,
    )
  }

  private fun resolveGeneratedAssetExtension(
    requestedExtension: String?,
    defaultExtension: String,
    fileName: String?,
    mimeType: String?,
  ): String = requestedExtension
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf(String::isNotBlank)
    ?: fileName
      ?.substringAfterLast('.', "")
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank)
      ?: mimeTypeToExtension(mimeType)
      ?: defaultExtension

  private fun mimeTypeToExtension(mimeType: String?): String? = when (mimeType?.trim()?.lowercase(Locale.US)) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "audio/mpeg",
    "audio/mp3",
    -> "mp3"
    "audio/wav",
    "audio/x-wav",
    -> "wav"
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    -> "m4a"
    else -> null
  }

  private fun normalizeGeneratedImageFormat(rawValue: String?): String? = when (rawValue?.trim()?.lowercase(Locale.US)) {
    null,
    "",
    -> null
    "jpg" -> "jpg"
    "jpeg" -> "jpeg"
    "png" -> "png"
    "webp" -> "webp"
    else -> throw IllegalArgumentException("GenerateImage format must be png, jpg, jpeg, or webp.")
  }

  private fun normalizeGeneratedAudioFormat(rawValue: String?): String? = when (rawValue?.trim()?.lowercase(Locale.US)) {
    null,
    "",
    -> null
    "mp3" -> "mp3"
    "wav" -> "wav"
    "m4a" -> "m4a"
    else -> throw IllegalArgumentException("SynthesizeSpeech format must be mp3, wav, or m4a.")
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
        startupTimeoutMs = arguments.optionalLong("startup_timeout_ms"),
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

  private fun inspectPythonRuntimeManifest(task: AgentTask): AgentToolResult {
    val metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = "python runtime manifest",
    )

    val manifest = config.pythonRuntimeManifestProvider?.invoke()
      ?: return AgentToolResult(
        toolName = "python_runtime_manifest",
        status = AgentToolResultStatus.FAILED,
        content = "Python runtime manifest is unavailable in this execution environment.",
        errorCode = "PYTHON_RUNTIME_MANIFEST_UNAVAILABLE",
        errorMessage = "Python runtime manifest is unavailable in this execution environment.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "python_runtime_manifest",
          request = metadataRequest,
          metadata = mapOf(
            "manifestAvailable" to "false",
          ),
        ),
      )

    val payload = buildJsonObject {
      put("schema_version", manifest.schemaVersion)
      put("runtime_backend", manifest.runtimeBackend)
      put("package_install_policy", manifest.packageInstallPolicy)
      put("supports_dynamic_install", manifest.supportsDynamicInstall)
      manifest.interpreter?.let { put("interpreter", it) }
      put(
        "packages",
        buildJsonArray {
          manifest.packages.forEach { packageName ->
            add(JsonPrimitive(packageName))
          }
        },
      )
      if (manifest.packageVersions.isNotEmpty()) {
        put(
          "package_versions",
          buildJsonObject {
            manifest.packageVersions.toSortedMap().forEach { (packageName, version) ->
              put(packageName, version)
            }
          },
        )
      }
      if (manifest.notes.isNotEmpty()) {
        put(
          "notes",
          buildJsonArray {
            manifest.notes.forEach { note ->
              add(JsonPrimitive(note))
            }
          },
        )
      }
    }

    return AgentToolResult(
      toolName = "python_runtime_manifest",
      status = AgentToolResultStatus.SUCCESS,
      content = config.json.encodeToString(JsonObject.serializer(), payload),
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "python_runtime_manifest",
        request = metadataRequest,
        metadata = buildMap {
          put("manifestAvailable", "true")
          put("runtimeBackend", manifest.runtimeBackend)
          put("packageInstallPolicy", manifest.packageInstallPolicy)
          put("supportsDynamicInstall", manifest.supportsDynamicInstall.toString())
          put("packageCount", manifest.packages.size.toString())
          manifest.interpreter?.let { put("interpreter", it) }
        },
      ),
    )
  }

  private fun openSandboxPreview(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val previewService = config.sandboxPreviewService ?: return AgentToolResult(
      toolName = "sandbox_preview_open",
      status = AgentToolResultStatus.FAILED,
      content = "Sandbox preview support is unavailable on this runtime.",
      errorCode = "SANDBOX_PREVIEW_UNAVAILABLE",
      errorMessage = "Sandbox preview support is unavailable on this runtime.",
    )
    val requestedPort = arguments.optionalInt("port")
      ?.also { value ->
        require(value in 1..65_535) { "sandbox_preview_open port must be between 1 and 65535." }
      }
    val rawPath = arguments.optionalString("path")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val normalizedPath = rawPath?.let { value ->
      if (value.startsWith("/")) value else "/$value"
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "sandbox_preview_open",
      targetPath = writeBoundary.defaultRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = writeBoundary.defaultRoot,
        primaryTargetPath = toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = requestedPort
          ?.let { port -> "sandbox port $port${normalizedPath.orEmpty()}" }
          ?: "sandbox preview${normalizedPath.orEmpty()}",
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("workspaceRoot", toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot))
        requestedPort?.let { put("port", it.toString()) }
        normalizedPath?.let { put("path", it) }
      },
      askDetail = "Approval is required before sandbox_preview_open can expose a sandbox preview URL.",
      denyDetail = "Policy denied sandbox_preview_open.",
    )?.let { return it }
    val preview = previewService.open(
      SandboxPreviewRequest(
        workspaceRoot = writeBoundary.defaultRoot,
        port = requestedPort,
        path = normalizedPath,
      ),
    )
    val content = buildString {
      when (preview.probeStatus) {
        SandboxPreviewProbeStatus.READY -> appendLine("Sandbox preview is available and responded to the probe.")
        SandboxPreviewProbeStatus.REACHABLE -> appendLine("Sandbox preview URL is available and the sandbox endpoint responded.")
        SandboxPreviewProbeStatus.UNREACHABLE -> appendLine("Sandbox preview URL was generated, but the sandbox endpoint did not respond to the probe yet.")
        SandboxPreviewProbeStatus.SKIPPED -> appendLine("Sandbox preview is available.")
      }
      appendLine("preview_url=${preview.url}")
      appendLine("port=${preview.port}")
      preview.path?.let { appendLine("path=$it") }
      appendLine("probe_status=${preview.probeStatus.wireValue}")
      preview.probeHttpStatusCode?.let { appendLine("probe_http_status=$it") }
      preview.probeMessage?.let { appendLine("probe_message=$it") }
      appendLine("provider=${preview.providerId}")
      preview.sandboxId?.let { appendLine("sandbox_id=$it") }
      preview.sandboxDomain?.let { appendLine("sandbox_domain=$it") }
      preview.accessHeaderName?.let { headerName ->
        appendLine("access_header=$headerName")
        if (preview.accessTokenConfigured) {
          append("If preview access is restricted by the provider, use the recorded access token with that header.")
        }
      }
    }.trim()
    return AgentToolResult(
      toolName = "sandbox_preview_open",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("workspaceRoot", toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot))
          put("previewUrl", preview.url)
          put("previewPort", preview.port.toString())
          put("previewPortSelection", if (requestedPort == null) "auto" else "explicit")
          put("sandboxProvider", preview.providerId)
          put("previewProbeStatus", preview.probeStatus.wireValue)
          preview.sandboxId?.let { put("sandboxId", it) }
          preview.sandboxDomain?.let { put("sandboxDomain", it) }
          preview.path?.let { put("previewPath", it) }
          preview.accessHeaderName?.let { put("previewAccessHeader", it) }
          put("previewAccessTokenConfigured", preview.accessTokenConfigured.toString())
          preview.probeHttpStatusCode?.let { put("previewProbeHttpStatus", it.toString()) }
          preview.probeMessage?.let { put("previewProbeMessage", it) }
        },
      ),
    )
  }

  private fun closeSandboxSession(task: AgentTask): AgentToolResult {
    val sessionControlService = config.sandboxSessionControlService ?: return AgentToolResult(
      toolName = "sandbox_session_close",
      status = AgentToolResultStatus.FAILED,
      content = "Sandbox session control is unavailable on this runtime.",
      errorCode = "SANDBOX_SESSION_CONTROL_UNAVAILABLE",
      errorMessage = "Sandbox session control is unavailable on this runtime.",
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "sandbox_session_close",
      targetPath = writeBoundary.defaultRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = writeBoundary.defaultRoot,
        primaryTargetPath = toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = "close sandbox session",
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf(
        "workspaceRoot" to toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot),
      ),
      askDetail = "Approval is required before sandbox_session_close can terminate a cloud sandbox session.",
      denyDetail = "Policy denied sandbox_session_close.",
    )?.let { return it }
    val closeResult = sessionControlService.close(
      SandboxSessionCloseRequest(
        workspaceRoot = writeBoundary.defaultRoot,
      ),
    )
    val status = when (closeResult.outcome) {
      SandboxSessionCloseOutcome.BUSY -> AgentToolResultStatus.FAILED
      SandboxSessionCloseOutcome.TERMINATED,
      SandboxSessionCloseOutcome.NOT_FOUND,
      -> AgentToolResultStatus.SUCCESS
    }
    val content = buildString {
      when (closeResult.outcome) {
        SandboxSessionCloseOutcome.TERMINATED ->
          appendLine("Closed the reusable cloud sandbox session for this workspace.")

        SandboxSessionCloseOutcome.NOT_FOUND ->
          appendLine("No reusable cloud sandbox session was recorded for this workspace.")

        SandboxSessionCloseOutcome.BUSY ->
          appendLine("Cannot close the reusable cloud sandbox session because a sandbox request is still running.")
      }
      closeResult.sandboxId?.let { appendLine("sandbox_id=$it") }
      closeResult.sandboxDomain?.let { appendLine("sandbox_domain=$it") }
      if (closeResult.previewCandidatePorts.isNotEmpty()) {
        appendLine("preview_candidate_ports=${closeResult.previewCandidatePorts.joinToString(separator = ",")}")
      }
      closeResult.blockingRequestId?.let { appendLine("blocking_request_id=$it") }
      appendLine("provider=${closeResult.providerId}")
      append("close_outcome=${closeResult.outcome.wireValue}")
    }.trim()
    val errorCode = when (closeResult.outcome) {
      SandboxSessionCloseOutcome.BUSY -> "SANDBOX_SESSION_BUSY"
      else -> null
    }
    val errorMessage = when (closeResult.outcome) {
      SandboxSessionCloseOutcome.BUSY ->
        closeResult.blockingRequestId?.let { requestId ->
          "Sandbox session cannot be closed while request '$requestId' is still running."
        } ?: "Sandbox session cannot be closed while a request is still running."

      else -> null
    }
    return AgentToolResult(
      toolName = "sandbox_session_close",
      status = status,
      content = content,
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("workspaceRoot", toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot))
          put("sandboxProvider", closeResult.providerId)
          put("sandboxSessionCloseOutcome", closeResult.outcome.wireValue)
          closeResult.sandboxId?.let { put("sandboxId", it) }
          closeResult.sandboxDomain?.let { put("sandboxDomain", it) }
          if (closeResult.previewCandidatePorts.isNotEmpty()) {
            put(
              "sandboxPreviewCandidatePorts",
              closeResult.previewCandidatePorts.joinToString(separator = ","),
            )
          }
          closeResult.blockingRequestId?.let { put("blockingRequestId", it) }
        },
      ),
    )
  }

  private fun inspectSandboxSession(task: AgentTask): AgentToolResult {
    val sessionInfoService = config.sandboxSessionInfoService ?: return AgentToolResult(
      toolName = "sandbox_session_info",
      status = AgentToolResultStatus.FAILED,
      content = "Sandbox session info is unavailable on this runtime.",
      errorCode = "SANDBOX_SESSION_INFO_UNAVAILABLE",
      errorMessage = "Sandbox session info is unavailable on this runtime.",
    )
    val metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      targetSummary = "sandbox session info",
    )
    val info = sessionInfoService.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = writeBoundary.defaultRoot,
      ),
    )
    val content = buildString {
      when {
        info.lifecycleStatus == SandboxSessionLifecycleStatus.RECLAIMED ->
          appendLine("Reclaimed a stale reusable cloud sandbox session for this workspace.")

        info.sessionPresent && info.lifecycleStatus == SandboxSessionLifecycleStatus.STALE ->
          appendLine("Reusable cloud sandbox session is recorded for this workspace, but its lifecycle appears stale.")

        info.sessionPresent ->
          appendLine("Reusable cloud sandbox session is available for this workspace.")

        else ->
          appendLine("No reusable cloud sandbox session is recorded for this workspace.")
      }
      info.sandboxId?.let { appendLine("sandbox_id=$it") }
      info.sandboxDomain?.let { appendLine("sandbox_domain=$it") }
      info.templateId?.let { appendLine("template_id=$it") }
      info.workspaceRoot?.let { appendLine("workspace_root=$it") }
      info.remoteWorkspaceRoot?.let { appendLine("remote_workspace_root=$it") }
      info.updatedAtEpochMs?.let { appendLine("updated_at_epoch_ms=$it") }
      info.sessionLastActivityAtEpochMs?.let { appendLine("session_last_activity_at_epoch_ms=$it") }
      info.sessionStaleAfterEpochMs?.let { appendLine("session_stale_after_epoch_ms=$it") }
      if (info.previewCandidatePorts.isNotEmpty()) {
        appendLine("preview_candidate_ports=${info.previewCandidatePorts.joinToString(separator = ",")}")
      }
      info.lastPreviewUrl?.let { appendLine("last_preview_url=$it") }
      info.lastPreviewPort?.let { appendLine("last_preview_port=$it") }
      info.lastPreviewPath?.let { appendLine("last_preview_path=$it") }
      info.lastPreviewProbeStatus?.let { appendLine("last_preview_probe_status=$it") }
      info.lastPreviewProbeHttpStatusCode?.let { appendLine("last_preview_probe_http_status=$it") }
      info.lastPreviewProbeMessage?.let { appendLine("last_preview_probe_message=$it") }
      info.lastPreviewOpenedAtEpochMs?.let { appendLine("last_preview_opened_at_epoch_ms=$it") }
      info.lastPreviewProbeObservedAtEpochMs?.let { appendLine("last_preview_probe_observed_at_epoch_ms=$it") }
      info.lastPreviewProbeSource?.let { appendLine("last_preview_probe_source=$it") }
      info.recommendedRefreshAfterMs?.let { appendLine("auto_refresh_after_ms=$it") }
      appendLine("running_request_count=${info.runningRequestIds.size}")
      if (info.runningRequestIds.isNotEmpty()) {
        appendLine("running_request_ids=${info.runningRequestIds.joinToString(separator = ",")}")
      }
      appendLine("provider=${info.providerId}")
      appendLine("session_present=${info.sessionPresent}")
      appendLine("session_lifecycle_status=${info.lifecycleStatus.wireValue}")
      appendLine("session_is_stale=${info.sessionIsStale}")
      appendLine("preview_auto_probe_attempted=${info.previewAutoProbeAttempted}")
      append("session_source=${info.source.wireValue}")
    }.trim()
    return AgentToolResult(
      toolName = "sandbox_session_info",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "sandbox_session_info",
        request = metadataRequest,
        metadata = buildMap {
          put("workspaceRoot", toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot))
          put("sandboxProvider", info.providerId)
          put("sandboxSessionPresent", info.sessionPresent.toString())
          put("sandboxSessionSource", info.source.wireValue)
          put("sandboxSessionLifecycleStatus", info.lifecycleStatus.wireValue)
          put("sandboxSessionIsStale", info.sessionIsStale.toString())
          put("sandboxPreviewAutoProbeAttempted", info.previewAutoProbeAttempted.toString())
          info.sandboxId?.let { put("sandboxId", it) }
          info.sandboxDomain?.let { put("sandboxDomain", it) }
          info.templateId?.let { put("sandboxTemplateId", it) }
          info.workspaceRoot?.let { put("sandboxWorkspaceRoot", it) }
          info.remoteWorkspaceRoot?.let { put("sandboxRemoteWorkspaceRoot", it) }
          info.updatedAtEpochMs?.let { put("sandboxSessionUpdatedAtEpochMs", it.toString()) }
          info.sessionLastActivityAtEpochMs?.let { put("sandboxSessionLastActivityAtEpochMs", it.toString()) }
          info.sessionStaleAfterEpochMs?.let { put("sandboxSessionStaleAfterEpochMs", it.toString()) }
          info.recommendedRefreshAfterMs?.let { put("sandboxSessionAutoRefreshAfterMs", it.toString()) }
          if (info.previewCandidatePorts.isNotEmpty()) {
            put("sandboxPreviewCandidatePorts", info.previewCandidatePorts.joinToString(separator = ","))
          }
          info.lastPreviewUrl?.let { put("sandboxLastPreviewUrl", it) }
          info.lastPreviewPort?.let { put("sandboxLastPreviewPort", it.toString()) }
          info.lastPreviewPath?.let { put("sandboxLastPreviewPath", it) }
          info.lastPreviewProbeStatus?.let { put("sandboxLastPreviewProbeStatus", it) }
          info.lastPreviewProbeHttpStatusCode?.let { put("sandboxLastPreviewProbeHttpStatus", it.toString()) }
          info.lastPreviewProbeMessage?.let { put("sandboxLastPreviewProbeMessage", it) }
          info.lastPreviewOpenedAtEpochMs?.let { put("sandboxLastPreviewOpenedAtEpochMs", it.toString()) }
          info.lastPreviewProbeObservedAtEpochMs?.let { put("sandboxLastPreviewProbeObservedAtEpochMs", it.toString()) }
          info.lastPreviewProbeSource?.let { put("sandboxLastPreviewProbeSource", it) }
          put("sandboxRunningRequestCount", info.runningRequestIds.size.toString())
          if (info.runningRequestIds.isNotEmpty()) {
            put("sandboxRunningRequestIds", info.runningRequestIds.joinToString(separator = ","))
          }
        },
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

  private fun managedProcessToolResult(
    toolName: String,
    status: AgentToolResultStatus,
    content: String,
    snapshot: ManagedProcessSnapshot,
    stdout: String = snapshot.stdout,
    stderr: String = snapshot.stderr,
    metadata: Map<String, String>,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = status,
    content = content,
    exitCode = snapshot.exitCode,
    stdout = stdout,
    stderr = stderr,
    errorCode = snapshot.errorCode,
    errorMessage = snapshot.errorMessage,
    metadata = metadata,
  )

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
    val normalizedSnapshot = snapshot.withNormalizedRemoteState()
    put("processId", normalizedSnapshot.processId)
    put("processStatus", normalizedSnapshot.status.name)
    put("processStarted", normalizedSnapshot.processStarted.toString())
    put("timeoutMs", normalizedSnapshot.timeoutMs.toString())
    put("command", normalizedSnapshot.command)
    if (normalizedSnapshot.args.isNotEmpty()) {
      put("args", normalizedSnapshot.args.joinToString(separator = "\u0000"))
    }
    toolTargetResolver.displayWorkingDirectory(normalizedSnapshot.workingDirectory)?.let { workingDirectory ->
      put("workingDirectory", workingDirectory)
    }
    normalizedSnapshot.exitCode?.let { code -> put("exitCode", code.toString()) }
    normalizedSnapshot.finishedAtEpochMs?.let { finishedAt -> put("finishedAtEpochMs", finishedAt.toString()) }
    put("startedAtEpochMs", normalizedSnapshot.startedAtEpochMs.toString())
    put("updatedAtEpochMs", normalizedSnapshot.updatedAtEpochMs.toString())
    if (normalizedSnapshot.timedOut) {
      put("timedOut", "true")
    }
    if (normalizedSnapshot.cancelled) {
      put("cancelled", "true")
    }
    if (normalizedSnapshot.outputLimitExceeded) {
      put("outputLimitExceeded", "true")
    }
    putAll(normalizedSnapshot.metadata.filterKeys(::isManagedProcessRuntimeMetadataKey))
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
    observationDelivery: ManagedProcessObservationDelivery = ManagedProcessObservationDelivery.fullSnapshot(snapshot),
  ): String = buildString {
    val normalizedSnapshot = snapshot.withNormalizedRemoteState()
    appendLine("process_id=${normalizedSnapshot.processId}")
    appendLine("status=${normalizedSnapshot.status.name.lowercase()}")
    normalizedSnapshot.metadata["shellKind"]?.let { shellKind ->
      appendLine("shell_kind=$shellKind")
    }
    normalizedSnapshot.metadata["shellCommand"]?.let { shellCommand ->
      appendLine("shell_command=$shellCommand")
    }
    appendLine("command=${normalizedSnapshot.command}")
    normalizedSnapshot.metadata["runtimeKind"]?.let { runtimeKind ->
      appendLine("runtime_kind=$runtimeKind")
    }
    appendManagedProcessMetadataLine(normalizedSnapshot, "runtimeBackend", "runtime_backend")
    appendManagedProcessMetadataLine(normalizedSnapshot, "runtimeTransport", "runtime_transport")
    normalizedSnapshot.metadata["scriptPath"]?.let { scriptPath ->
      appendLine("script_path=$scriptPath")
    }
    normalizedSnapshot.metadata["pythonExecutable"]?.let { pythonExecutable ->
      appendLine("python_executable=$pythonExecutable")
    }
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandBackendKind", "sandbox_backend_kind")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandBackendResolvedKind", "sandbox_backend_resolved_kind")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandProviderNative", "sandbox_provider_native")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsStreamingLogs",
      "sandbox_supports_streaming_logs",
    )
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandSupportsReconnect", "sandbox_supports_reconnect")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsManagedProcessLiveObservation",
      "sandbox_supports_managed_process_live_observation",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsManagedProcessObservationCursorResume",
      "sandbox_supports_managed_process_observation_cursor_resume",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandSupportsManagedProcessObservationBackfill",
      "sandbox_supports_managed_process_observation_backfill",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderHandleKind",
      "sandbox_command_provider_handle_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderStableSelectorKind",
      "sandbox_command_provider_stable_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderStableSelectorValue",
      "sandbox_command_provider_stable_selector_value",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderLiveSelectorKind",
      "sandbox_command_provider_live_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderLiveSelectorValue",
      "sandbox_command_provider_live_selector_value",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandIdKind",
      "sandbox_command_id_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandId",
      "sandbox_command_id",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationMode",
      "sandbox_command_provider_observation_mode",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationEventCount",
      "sandbox_command_provider_observation_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationCursor",
      "sandbox_command_provider_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationBackfillSupported",
      "sandbox_command_provider_observation_backfill_supported",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationResumeContract",
      "sandbox_command_provider_observation_resume_contract",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandProviderObservationResumeBlocker",
      "sandbox_command_provider_observation_resume_blocker",
    )
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandHandleIdKind", "sandbox_command_handle_id_kind")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandHandleId", "sandbox_command_handle_id")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandHandleTag", "sandbox_command_handle_tag")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandObservationMode", "sandbox_observation_mode")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationEventCount",
      "sandbox_command_observation_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationCursor",
      "sandbox_command_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationStdoutBytes",
      "sandbox_command_observation_stdout_bytes",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandObservationStderrBytes",
      "sandbox_command_observation_stderr_bytes",
    )
    observationDelivery.renderLines.forEach(::appendLine)
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandApi", "sandbox_command_api")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandReconnectApi", "sandbox_command_reconnect_api")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectStatus",
      "sandbox_command_reconnect_status",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectRecoveryState",
      "sandbox_command_reconnect_recovery_state",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSource",
      "sandbox_command_reconnect_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectHttpStatusCode",
      "sandbox_command_reconnect_http_status_code",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectResumeMode",
      "sandbox_command_reconnect_resume_mode",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectBackfillSupported",
      "sandbox_command_reconnect_backfill_supported",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectOutputGapRisk",
      "sandbox_command_reconnect_output_gap_risk",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectRetryable",
      "sandbox_command_reconnect_retryable",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectRetryAfterEpochMs",
      "sandbox_command_reconnect_retry_after_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastAttachedAtEpochMs",
      "sandbox_command_reconnect_last_attached_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastEventAtEpochMs",
      "sandbox_command_reconnect_last_event_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastEventKind",
      "sandbox_command_reconnect_last_event_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectLastFailureAtEpochMs",
      "sandbox_command_reconnect_last_failure_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectAttemptCount",
      "sandbox_command_reconnect_attempt_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSelectorKind",
      "sandbox_command_reconnect_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSelectorValue",
      "sandbox_command_reconnect_selector_value",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSelectorSource",
      "sandbox_command_reconnect_selector_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedObservationCursor",
      "sandbox_command_reconnect_seed_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedProviderObservationCursor",
      "sandbox_command_reconnect_seed_provider_observation_cursor",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedEventCount",
      "sandbox_command_reconnect_seed_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedProviderObservationEventCount",
      "sandbox_command_reconnect_seed_provider_observation_event_count",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeedSource",
      "sandbox_command_reconnect_seed_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedConsumed",
      "sandbox_command_reconnect_provider_observation_seed_consumed",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedState",
      "sandbox_command_reconnect_provider_observation_seed_state",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedSource",
      "sandbox_command_reconnect_provider_observation_seed_source",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationResumeApplied",
      "sandbox_command_reconnect_provider_observation_resume_applied",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationResumeReason",
      "sandbox_command_reconnect_provider_observation_resume_reason",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs",
      "sandbox_command_reconnect_provider_observation_seed_consumed_at_epoch_ms",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeededStdoutBytes",
      "sandbox_command_reconnect_seeded_stdout_bytes",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectSeededStderrBytes",
      "sandbox_command_reconnect_seeded_stderr_bytes",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandNativeProtocol",
      "sandbox_command_native_protocol",
    )
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandSessionSource", "sandbox_command_session_source")
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandPid", "sandbox_command_pid")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandNativeProcessStatus",
      "sandbox_command_native_process_status",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandNativeFailureStage",
      "sandbox_command_native_failure_stage",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandReconnectFailureStage",
      "sandbox_command_reconnect_failure_stage",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandBackendFallbackReasonCode",
      "sandbox_backend_fallback_reason",
    )
    val reconnectProviderObservationSeedState =
      normalizedSnapshot.metadata["sandboxCommandReconnectProviderObservationSeedState"]
    if (normalizedSnapshot.metadata["sandboxCommandReconnectOutputGapRisk"] == "true") {
      when (reconnectProviderObservationSeedState) {
        "pending_live_attach" -> appendLine(
          "observation_warning=provider reconnect restored a persisted output seed and is still waiting for live attach; current output may only reflect the persisted host snapshot",
        )

        "retry_scheduled_before_live_attach",
        "failed_terminal_before_live_attach",
        -> Unit

        else -> appendLine(
          "observation_warning=provider reconnect resumed from persisted snapshot without log backfill; output emitted before attach may be missing",
        )
      }
    }
    if (
      normalizedSnapshot.metadata["sandboxCommandReconnectRecoveryState"] == "retry_scheduled" ||
      normalizedSnapshot.metadata["sandboxCommandReconnectRetryable"] == "true"
    ) {
      appendLine(
        when (reconnectProviderObservationSeedState) {
          "retry_scheduled_before_live_attach" ->
            "observation_warning=provider reconnect has not yet reattached live output; current output still reflects the persisted host snapshot seed and a later ProcessRead or ProcessWait may retry attach after backoff"

          else ->
            "observation_warning=provider reconnect failed without terminal process state; a later ProcessRead or ProcessWait may retry attach after backoff"
        },
      )
    }
    if (normalizedSnapshot.metadata["sandboxCommandReconnectRecoveryState"] == "failed_terminal") {
      appendLine(
        "observation_warning=provider reconnect terminated before live attach; current output may only reflect the persisted host snapshot",
      )
    }
    normalizedSnapshot.metadata["terminationSupport"]?.let { terminationSupport ->
      appendLine("termination_support=$terminationSupport")
    }
    if (normalizedSnapshot.metadata["terminationRequested"] == "true") {
      appendLine("termination_requested=true")
    }
    normalizedSnapshot.metadata["terminationRequestAccepted"]?.let { terminationRequestAccepted ->
      appendLine("termination_request_accepted=$terminationRequestAccepted")
    }
    appendManagedProcessMetadataLine(normalizedSnapshot, "sandboxCommandTerminateApi", "sandbox_command_terminate_api")
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandTerminateRequestedSignal",
      "sandbox_command_terminate_requested_signal",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandTerminateSelectorKind",
      "sandbox_command_terminate_selector_kind",
    )
    appendManagedProcessMetadataLine(
      normalizedSnapshot,
      "sandboxCommandTerminateSelectorValue",
      "sandbox_command_terminate_selector_value",
    )
    if (normalizedSnapshot.args.isNotEmpty()) {
      appendLine("args=${normalizedSnapshot.args.joinToString(separator = " ")}")
    }
    toolTargetResolver.displayWorkingDirectory(normalizedSnapshot.workingDirectory)?.let { workingDirectory ->
      appendLine("working_directory=$workingDirectory")
    }
    appendLine("timeout_ms=${normalizedSnapshot.timeoutMs}")
    appendLine("process_started=${normalizedSnapshot.processStarted}")
    normalizedSnapshot.exitCode?.let { code ->
      appendLine("exit_code=$code")
    }
    normalizedSnapshot.errorCode?.let { code ->
      appendLine("error_code=$code")
    }
    normalizedSnapshot.errorMessage?.let { message ->
      appendLine("error_message=$message")
    }
    appendLine("started_at_epoch_ms=${normalizedSnapshot.startedAtEpochMs}")
    appendLine("updated_at_epoch_ms=${normalizedSnapshot.updatedAtEpochMs}")
    normalizedSnapshot.finishedAtEpochMs?.let { finishedAt ->
      appendLine("finished_at_epoch_ms=$finishedAt")
    }
    if (includeOutput) {
      if (observationDelivery.stdout.isNotBlank()) {
        appendLine()
        appendLine("[stdout]")
        appendLine(observationDelivery.stdout.trimEnd())
      }
      if (observationDelivery.stderr.isNotBlank()) {
        appendLine()
        appendLine("[stderr]")
        append(observationDelivery.stderr.trimEnd())
      }
    }
  }.trim()

  private fun observeManagedProcessOutput(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationDelivery {
    val current = managedProcessObservationCursorState(snapshot)
      ?: return ManagedProcessObservationDelivery.fullSnapshot(snapshot)
    val previous = managedProcessObservationTracker.recordAndReturnPrevious(
      processId = snapshot.processId,
      current = current,
    )
    if (previous != null) {
      return deliverManagedProcessObservationDelta(
        snapshot = snapshot,
        current = current,
        previous = previous,
        resetWarning = "host observation cursor regressed or output window changed; returning full snapshot output",
        stdoutAlignmentWarning = "host observation cursor could not be aligned with stdout bytes; returning full snapshot output",
        stderrAlignmentWarning = "host observation cursor could not be aligned with stderr bytes; returning full snapshot output",
      )
    }
    val persistedDelivery = managedProcessDeliveredObservationCursorState(snapshot)
    if (persistedDelivery != null) {
      return deliverManagedProcessObservationDelta(
        snapshot = snapshot,
        current = current,
        previous = persistedDelivery,
        resetWarning = "persisted delivered observation cursor regressed or output window changed; returning full snapshot output",
        stdoutAlignmentWarning = "persisted delivered observation cursor could not be aligned with stdout bytes; returning full snapshot output",
        stderrAlignmentWarning = "persisted delivered observation cursor could not be aligned with stderr bytes; returning full snapshot output",
      )
    }
    val reconnectSeed = managedProcessReconnectSeedObservationCursorState(snapshot)
    if (reconnectSeed != null) {
      return deliverManagedProcessObservationDelta(
        snapshot = snapshot,
        current = current,
        previous = reconnectSeed,
        resetWarning = "persisted reconnect seed cursor regressed or output window changed; returning full snapshot output",
        stdoutAlignmentWarning = "persisted reconnect seed could not be aligned with stdout bytes; returning full snapshot output",
        stderrAlignmentWarning = "persisted reconnect seed could not be aligned with stderr bytes; returning full snapshot output",
      )
    }
    return ManagedProcessObservationDelivery.snapshotMode(
      snapshot = snapshot,
      mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_FULL_SNAPSHOT,
      cursorBefore = "none",
      cursorAfter = current.cursor,
      stdoutDeltaBytes = current.stdoutBytes,
      stderrDeltaBytes = current.stderrBytes,
    )
  }

  private fun deliverManagedProcessObservationDelta(
    snapshot: ManagedProcessSnapshot,
    current: ManagedProcessObservationCursorState,
    previous: ManagedProcessObservationCursorState,
    resetWarning: String,
    stdoutAlignmentWarning: String,
    stderrAlignmentWarning: String,
  ): ManagedProcessObservationDelivery {
    val providerBoundary = managedProcessProviderObservationBoundary(current = current, previous = previous)
    if (
      current.cursor == previous.cursor &&
      current.stdoutBytes == previous.stdoutBytes &&
      current.stderrBytes == previous.stderrBytes
    ) {
      return ManagedProcessObservationDelivery.deltaMode(
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_NO_CHANGE,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdout = "",
        stderr = "",
        stdoutDeltaBytes = 0L,
        stderrDeltaBytes = 0L,
        providerBoundary = providerBoundary,
      )
    }
    managedProcessProviderObservationResetWarning(
      current = current,
      previous = previous,
    )?.let { warning ->
      return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = warning,
      )
    }
    if (current.stdoutBytes < previous.stdoutBytes || current.stderrBytes < previous.stderrBytes) {
      return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = resetWarning,
      )
    }
    val stdoutDelta = utf8DeltaFromByteOffset(snapshot.stdout, previous.stdoutBytes)
      ?: return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = stdoutAlignmentWarning,
      )
    val stderrDelta = utf8DeltaFromByteOffset(snapshot.stderr, previous.stderrBytes)
      ?: return ManagedProcessObservationDelivery.snapshotMode(
        snapshot = snapshot,
        mode = MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_RESET_FULL,
        cursorBefore = previous.cursor,
        cursorAfter = current.cursor,
        stdoutDeltaBytes = current.stdoutBytes,
        stderrDeltaBytes = current.stderrBytes,
        providerBoundary = providerBoundary,
        warning = stderrAlignmentWarning,
      )
    return ManagedProcessObservationDelivery.deltaMode(
      mode = if (stdoutDelta.isBlank() && stderrDelta.isBlank()) {
        MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_NO_CHANGE
      } else {
        MANAGED_PROCESS_OBSERVATION_DELIVERY_MODE_DELTA
      },
      cursorBefore = previous.cursor,
      cursorAfter = current.cursor,
      stdout = stdoutDelta,
      stderr = stderrDelta,
      stdoutDeltaBytes = (current.stdoutBytes - previous.stdoutBytes).coerceAtLeast(0L),
      stderrDeltaBytes = (current.stderrBytes - previous.stderrBytes).coerceAtLeast(0L),
      providerBoundary = providerBoundary,
    )
  }

  private fun recordManagedProcessObservationDelivery(
    snapshot: ManagedProcessSnapshot,
  ) {
    val current = managedProcessObservationCursorState(snapshot)
    processRegistry.recordObservationDelivery(
      processId = snapshot.processId,
      deliveredObservationState = current?.toDeliveredObservationState(snapshot),
    )
  }

  private fun managedProcessObservationCursorState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationCursorState? {
    val observationState = snapshot.normalizedObservationState()
    val mode = observationState?.mode ?: snapshot.metadata["sandboxCommandObservationMode"]
    if (mode != "host_managed_snapshot") {
      return null
    }
    val cursor = observationState?.hostCursor
      ?: snapshot.metadata["sandboxCommandObservationCursor"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val stdoutBytes = observationState?.stdoutBytes
      ?: snapshot.metadata["sandboxCommandObservationStdoutBytes"]?.toLongOrNull()
      ?: snapshot.stdout.toUtf8Length()
    val stderrBytes = observationState?.stderrBytes
      ?: snapshot.metadata["sandboxCommandObservationStderrBytes"]?.toLongOrNull()
      ?: snapshot.stderr.toUtf8Length()
    if (stdoutBytes < 0L || stderrBytes < 0L) {
      return null
    }
    return ManagedProcessObservationCursorState(
      mode = mode,
      cursor = cursor,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerMode = observationState?.providerMode
        ?: snapshot.metadata["sandboxCommandProviderObservationMode"]?.trim()?.takeIf(String::isNotBlank),
      providerCursor = observationState?.providerCursor
        ?: snapshot.metadata["sandboxCommandProviderObservationCursor"]?.trim()?.takeIf(String::isNotBlank),
      providerEventCount = observationState?.providerEventCount
        ?: snapshot.metadata["sandboxCommandProviderObservationEventCount"]?.toLongOrNull()
          ?.takeIf { eventCount -> eventCount >= 0L },
    )
  }

  private fun managedProcessDeliveredObservationCursorState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationCursorState? {
    val deliveredObservationState = snapshot.normalizedDeliveredObservationState()
    val mode =
      deliveredObservationState?.mode
        ?: snapshot.metadata["sandboxCommandLastDeliveredObservationMode"]
        ?: return null
    if (mode != "host_managed_snapshot") {
      return null
    }
    val cursor =
      deliveredObservationState?.cursor
        ?: snapshot.metadata["sandboxCommandLastDeliveredObservationCursor"]
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: return null
    val stdoutBytes =
      deliveredObservationState?.stdoutBytes
        ?: snapshot.metadata["sandboxCommandLastDeliveredStdoutBytes"]?.toLongOrNull()
        ?: return null
    val stderrBytes =
      deliveredObservationState?.stderrBytes
        ?: snapshot.metadata["sandboxCommandLastDeliveredStderrBytes"]?.toLongOrNull()
        ?: return null
    if (stdoutBytes < 0L || stderrBytes < 0L) {
      return null
    }
    return ManagedProcessObservationCursorState(
      mode = mode,
      cursor = cursor,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerMode =
        deliveredObservationState?.providerMode
          ?: snapshot.metadata["sandboxCommandLastDeliveredProviderObservationMode"]
            ?.trim()
            ?.takeIf(String::isNotBlank),
      providerCursor =
        deliveredObservationState?.providerCursor
          ?: snapshot.metadata["sandboxCommandLastDeliveredProviderObservationCursor"]
            ?.trim()
            ?.takeIf(String::isNotBlank),
      providerEventCount =
        deliveredObservationState?.providerEventCount
          ?: snapshot.metadata["sandboxCommandLastDeliveredProviderObservationEventCount"]
            ?.toLongOrNull()
            ?.takeIf { eventCount -> eventCount >= 0L },
    )
  }

  private fun managedProcessReconnectSeedObservationCursorState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessObservationCursorState? {
    val reconnectSeed = snapshot.normalizedReconnectState()?.seed
    val seedSource = reconnectSeed?.source ?: snapshot.metadata["sandboxCommandReconnectSeedSource"]
    if (seedSource?.trim().isNullOrBlank()) {
      return null
    }
    val cursor = reconnectSeed?.hostObservationCursor
      ?: snapshot.metadata["sandboxCommandReconnectSeedObservationCursor"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val stdoutBytes = reconnectSeed?.stdoutBytes
      ?: snapshot.metadata["sandboxCommandReconnectSeededStdoutBytes"]?.toLongOrNull()
      ?: return null
    val stderrBytes = reconnectSeed?.stderrBytes
      ?: snapshot.metadata["sandboxCommandReconnectSeededStderrBytes"]?.toLongOrNull()
      ?: return null
    if (stdoutBytes < 0L || stderrBytes < 0L) {
      return null
    }
    return ManagedProcessObservationCursorState(
      mode = "host_managed_snapshot",
      cursor = cursor,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerMode = snapshot.normalizedObservationState()?.providerMode
        ?: snapshot.metadata["sandboxCommandProviderObservationMode"]?.trim()?.takeIf(String::isNotBlank),
      providerCursor = reconnectSeed?.providerObservationCursor
        ?: snapshot.metadata["sandboxCommandReconnectSeedProviderObservationCursor"]
          ?.trim()
          ?.takeIf(String::isNotBlank),
      providerEventCount = reconnectSeed?.providerObservationEventCount
        ?: snapshot.metadata["sandboxCommandReconnectSeedProviderObservationEventCount"]
          ?.toLongOrNull()
          ?.takeIf { eventCount -> eventCount >= 0L },
    )
  }

  private fun ManagedProcessObservationCursorState.toDeliveredObservationState(
    snapshot: ManagedProcessSnapshot,
  ):
    ManagedProcessDeliveredObservationState = ManagedProcessDeliveredObservationState(
    mode = mode,
    cursor = cursor,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    providerMode = providerMode ?: snapshot.normalizedObservationState()?.providerMode,
    providerCursor = providerCursor ?: snapshot.normalizedObservationState()?.providerCursor,
    providerEventCount = providerEventCount ?: snapshot.normalizedObservationState()?.providerEventCount,
    deliveredAtEpochMs = System.currentTimeMillis(),
  )

  private fun managedProcessProviderObservationBoundary(
    current: ManagedProcessObservationCursorState,
    previous: ManagedProcessObservationCursorState,
  ): ManagedProcessProviderObservationBoundary? {
    val currentProviderCursor = current.providerCursor?.trim()?.takeIf(String::isNotBlank) ?: return null
    val previousProviderCursor = previous.providerCursor?.trim()?.takeIf(String::isNotBlank) ?: return null
    return ManagedProcessProviderObservationBoundary(
      cursorBefore = previousProviderCursor,
      cursorAfter = currentProviderCursor,
      eventCountBefore = previous.providerEventCount,
      eventCountAfter = current.providerEventCount,
    )
  }

  private fun managedProcessProviderObservationResetWarning(
    current: ManagedProcessObservationCursorState,
    previous: ManagedProcessObservationCursorState,
  ): String? {
    val currentEventCount = current.providerEventCount ?: return null
    val previousEventCount = previous.providerEventCount ?: return null
    if (currentEventCount < previousEventCount) {
      return "provider observation cursor regressed; returning full snapshot output"
    }
    if (
      currentEventCount == previousEventCount &&
      (
        current.stdoutBytes > previous.stdoutBytes ||
          current.stderrBytes > previous.stderrBytes
        )
    ) {
      return "provider observation cursor did not advance while output changed; returning full snapshot output"
    }
    return null
  }

  private fun utf8DeltaFromByteOffset(
    text: String,
    byteOffset: Long,
  ): String? {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (byteOffset < 0L) {
      return null
    }
    if (byteOffset > bytes.size.toLong()) {
      return null
    }
    return try {
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, byteOffset.toInt(), bytes.size - byteOffset.toInt()))
        .toString()
    } catch (_: CharacterCodingException) {
      null
    }
  }

  private fun String.toUtf8Length(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

  private fun StringBuilder.appendManagedProcessMetadataLine(
    snapshot: ManagedProcessSnapshot,
    metadataKey: String,
    renderedKey: String,
  ) {
    snapshot.metadata[metadataKey]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { value -> appendLine("$renderedKey=$value") }
  }

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
      approvedHostManagedReadRoots = skillPackageHostManagedReadRoots(packageManager),
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
      approvedHostManagedReadRoots = skillPackageHostManagedReadRoots(packageManager),
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
      approvedHostManagedReadRoots = skillPackageHostManagedReadRoots(packageManager),
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

  private fun skillPackageHostManagedReadRoots(packageManager: SkillPackageManager): Set<Path> =
    packageManager.policyReadRoots()
      .map { root -> root.toPath().toAbsolutePath().normalize() }
      .toSet()

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
          OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to plan.toolName,
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
          OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to plan.toolName,
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

  private fun JsonObject.requiredInt(name: String): Int =
    optionalInt(name)
      ?: throw IllegalArgumentException("Required argument '$name' must be a JSON number.")

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

  private fun JsonObject.optionalIntArray(name: String): List<Int> {
    val element = this[name] ?: return emptyList()
    if (element == JsonNull) {
      return emptyList()
    }
    val array = element as? JsonArray
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON array of numbers.")
    return array.mapIndexed { index, entry ->
      val primitive = entry as? JsonPrimitive
        ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON number.")
      primitive.content.toIntOrNull()
        ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON number.")
    }
  }

  private fun JsonObject.optionalObject(name: String): JsonObject? {
    val element = this[name] ?: return null
    if (element == JsonNull) {
      return null
    }
    return element as? JsonObject
      ?: throw IllegalArgumentException("Argument '$name' must be a JSON object.")
  }

  private fun JsonObject.optionalObjectFrom(vararg names: String): JsonObject? =
    names.firstNotNullOfOrNull { name -> optionalObject(name) }

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
    val renderedContent = appendExecutionAttachmentArtifactSummary(
      toolName = toolName,
      content = content,
      metadata = metadata,
    )
    return AgentToolResult(
      toolName = toolName,
      status = status,
      content = renderedContent,
      exitCode = exitCode,
      stdout = stdout,
      stderr = stderr,
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = metadata,
    )
  }

  private fun appendExecutionAttachmentArtifactSummary(
    toolName: String,
    content: String,
    metadata: Map<String, String>,
  ): String {
    if (toolName != "python_exec" && toolName != "command_exec") {
      return content
    }
    val artifacts = OpenCrayAttachmentArtifacts.decodeMetadata(config.json, metadata)
    if (artifacts.isEmpty()) {
      return content
    }
    val previewArtifacts = artifacts.take(MAX_EXECUTION_ATTACHMENT_ARTIFACT_PREVIEW_COUNT)
    if (previewArtifacts.any { artifact -> content.contains("artifact_id=${artifact.artifactId}") }) {
      return content
    }
    val summary = buildString {
      appendLine("Workspace artifact(s) available:")
      previewArtifacts.forEachIndexed { index, artifact ->
        appendLine("${index + 1}. artifact_id=${artifact.artifactId}")
        appendLine("   relative_path=${artifact.relativePath}")
      }
      val remainingCount = artifacts.size - previewArtifacts.size
      if (remainingCount > 0) {
        appendLine("...and $remainingCount more artifact(s).")
      }
      append("You may attach these artifact_id values in the final response attachments array.")
    }.trim()
    return buildString {
      if (content.isNotBlank()) {
        append(content.trimEnd())
        append("\n\n")
      }
      append(summary)
    }
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
    private const val HOST_SESSION_ID_METADATA_KEY: String = "_host.sessionId"
    private const val DEFAULT_BASH_WAIT_TIMEOUT_MS: Long = 1_000L
    private const val DEFAULT_MANAGED_PROCESS_TIMEOUT_MS: Long = 300_000L
    private const val DEFAULT_MANAGED_PROCESS_WAIT_TIMEOUT_MS: Long = 1_000L
    private const val DEFAULT_WORKSPACE_DOCUMENT_SEARCH_RESULTS: Int = 5
    private const val DEFAULT_WORKSPACE_PACKAGE_INSPECTION_RESULTS: Int = 50
    private const val DEFAULT_WORKSPACE_PACKAGE_PREVIEW_CHARS: Int = 2_000
    private const val MAX_WORKSPACE_PACKAGE_PREVIEW_CHARS: Int = 4_000
    private const val MAX_WORKSPACE_PACKAGE_PREVIEW_ENTRY_REQUESTS: Int = 8
    private const val MAX_WORKSPACE_PACKAGE_EXPLICIT_ENTRY_REQUESTS: Int = 128
    private const val MAX_WORKSPACE_PACKAGE_EXTRACTION_ARTIFACTS: Int = 24
    private const val MAX_RENDERED_WORKSPACE_PACKAGE_EXTRACTED_PATHS: Int = 50
    private const val MAX_VIEW_WORKSPACE_IMAGE_BYTES: Long = 20L * 1024L * 1024L
    private const val MAX_VIEW_WORKSPACE_PDF_BYTES: Long = 32L * 1024L * 1024L
    private const val DEFAULT_GENERATED_IMAGE_FORMAT: String = "png"
    private const val DEFAULT_GENERATED_AUDIO_FORMAT: String = "mp3"
    private const val MAX_GENERATED_IMAGE_COUNT: Int = 9
    private const val MAX_EXECUTION_ATTACHMENT_ARTIFACT_PREVIEW_COUNT: Int = 12
    private val WINDOWS_ABSOLUTE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/].+")
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

  private fun renderWorkspaceDocumentSearchResult(
    displayPath: String,
    request: WorkspaceDocumentSearchRequest,
    result: WorkspaceDocumentSearchResult,
  ): String = buildString {
    appendLine("Workspace document search: $displayPath")
    appendLine(
      buildString {
        append("kind=")
        append(result.documentKind.name.lowercase(Locale.US))
        append(" page_count=")
        append(result.pageCount)
        append(" query=")
        append(result.query ?: "<preview>")
        append(" results=")
        append(result.hits.size)
      },
    )
    renderWorkspaceDocumentPageSelectionSummary(request)?.let(::appendLine)
    if (result.hits.isEmpty()) {
      appendLine(
        if (result.query == null) {
          "No preview pages were returned."
        } else {
          "No matches found."
        },
      )
      return@buildString
    }
    result.hits.forEach { hit ->
      appendLine()
      append("page ")
      append(hit.pageNumber)
      if (hit.matchCount > 0) {
        append(" matches=")
        append(hit.matchCount)
      }
      appendLine()
      appendLine(hit.excerpt)
    }
  }.trim()

  private fun renderWorkspaceDocumentPageSelectionSummary(
    request: WorkspaceDocumentSearchRequest,
  ): String? = when {
    request.pageNumbers.isNotEmpty() -> "requested_pages=${request.pageNumbers.joinToString(separator = ",")}"
    request.pageFrom != null || request.pageTo != null -> "requested_range=${request.pageFrom ?: 1}-${request.pageTo ?: "end"}"
    else -> null
  }

  private fun renderWorkspacePackageInspectionResult(
    displayPath: String,
    request: WorkspacePackageInspectionRequest,
    result: WorkspacePackageInspectionResult,
  ): String = buildString {
    appendLine("Workspace package inspection: $displayPath")
    appendLine(
      buildString {
        append("kind=")
        append(result.packageKind.name.lowercase(Locale.US))
        append(" entry_count=")
        append(result.entryCount)
        append(" matched_entries=")
        append(result.matchedEntryCount)
        append(" returned_entries=")
        append(result.entries.size)
        append(" previews=")
        append(result.previews.size)
        append(" media_entries=")
        append(result.mediaEntryCount)
      },
    )
    request.glob?.let { appendLine("requested_glob=$it") }
    if (request.previewEntries.isNotEmpty()) {
      appendLine("requested_preview_entries=${request.previewEntries.joinToString(separator = ",")}")
    }
    if (result.mainPartHints.isNotEmpty()) {
      appendLine("main_part_hints=${result.mainPartHints.joinToString(separator = ",")}")
    }
    if (result.relationshipPartHints.isNotEmpty()) {
      appendLine("relationship_part_hints=${result.relationshipPartHints.joinToString(separator = ",")}")
    }
    appendLine()
    appendLine("entries:")
    if (result.entries.isEmpty()) {
      appendLine("<no matched entries>")
    } else {
      result.entries.forEachIndexed { index, entry ->
        append("${index + 1}. ")
        append(entry.path)
        append(" type=")
        append(if (entry.isDirectory) "directory" else "file")
        append(" previewable=")
        append(entry.previewable)
        entry.mimeType?.let { append(" mime=").append(it) }
        entry.uncompressedSize?.let { append(" size=").append(it) }
        entry.compressedSize?.let { append(" compressed=").append(it) }
        appendLine()
      }
    }
    if (result.previews.isNotEmpty()) {
      result.previews.forEach { preview ->
        appendLine()
        appendLine("preview: ${preview.path}")
        appendLine(preview.content)
      }
    }
    if (result.truncated) {
      appendLine()
      append("Result was truncated by package inspection limits.")
    }
  }.trim()

  private fun renderWorkspacePackageExtractionResult(
    displayPath: String,
    displayDestinationDir: String,
    request: WorkspacePackageExtractionRequest,
    result: WorkspacePackageExtractionResult,
  ): String = buildString {
    appendLine("Workspace package extraction: $displayPath")
    appendLine(
      buildString {
        append("kind=")
        append(result.packageKind.name.lowercase(Locale.US))
        append(" entry_count=")
        append(result.entryCount)
        append(" matched_entries=")
        append(result.matchedEntryCount)
        append(" extracted_entries=")
        append(result.extractedPaths.size)
      },
    )
    appendLine("destination_dir=$displayDestinationDir")
    if (request.entries.isNotEmpty()) {
      appendLine("requested_entries=${request.entries.joinToString(separator = ",")}")
    }
    request.glob?.let { appendLine("requested_glob=$it") }
    appendLine("overwrite=${request.overwrite}")
    appendLine("strip_top_level=${request.stripTopLevel}")
    result.strippedTopLevel?.let { appendLine("stripped_top_level=$it") }
    appendLine()
    appendLine("extracted_paths:")
    if (result.extractedPaths.isEmpty()) {
      appendLine("<no extracted entries>")
    } else {
      val renderedPaths = result.extractedPaths.take(MAX_RENDERED_WORKSPACE_PACKAGE_EXTRACTED_PATHS)
      renderedPaths.forEachIndexed { index, path ->
        appendLine("${index + 1}. ${toolTargetResolver.displayWritablePath(path)}")
      }
      val omittedCount = result.extractedPaths.size - renderedPaths.size
      if (omittedCount > 0) {
        append("...and $omittedCount more extracted path(s).")
      }
    }
  }.trim()

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
  put("type", "object")
  put(
    "properties",
    buildJsonObject {
      parameters.forEach { parameter ->
        put(parameter.name, parameter.toJsonSchemaProperty())
      }
    },
  )
  parameters
    .filter(AgentToolParameter::required)
    .map(AgentToolParameter::name)
    .takeIf { requiredParameters -> requiredParameters.isNotEmpty() }
    ?.let { requiredParameters ->
      put(
        "required",
        buildJsonArray {
          requiredParameters.forEach { requiredParameter ->
            add(JsonPrimitive(requiredParameter))
          }
        },
      )
    }
  put("additionalProperties", false)
}

internal fun AgentToolDefinition.toLiteLlmToolDefinition(strict: Boolean = false): LiteLlmToolDefinition =
  LiteLlmToolDefinition(
    name = name,
    description = description,
    inputSchema = toJsonSchema(),
    strict = strict.takeIf { it },
  )

private fun AgentToolParameter.toJsonSchemaProperty(): JsonObject {
  jsonSchema?.let { explicitSchema -> return explicitSchema }
  return buildJsonObject {
    when (type.trim().lowercase(Locale.ROOT)) {
      "string" -> put("type", "string")
      "number" -> put("type", "number")
      "boolean" -> put("type", "boolean")
      "string[]" -> {
        put("type", "array")
        put(
          "items",
          buildJsonObject {
            put("type", "string")
          },
        )
      }

      "object[]" -> {
        put("type", "array")
        put(
          "items",
          buildJsonObject {
            put("type", "object")
          },
        )
      }

      else -> put("type", "string")
    }
    put("description", description)
  }
}

private fun multiEditArraySchema(description: String): JsonObject = objectArraySchema(
  description = description,
  itemParameters = listOf(
    AgentToolParameter(
      name = "old_string",
      type = "string",
      required = true,
      description = "Exact text to replace.",
    ),
    AgentToolParameter(
      name = "new_string",
      type = "string",
      required = true,
      description = "Replacement text.",
    ),
    AgentToolParameter(
      name = "replace_all",
      type = "boolean",
      required = false,
      description = "Replace every match instead of requiring a unique match.",
    ),
  ),
)

private fun todoEntryArraySchema(description: String): JsonObject = objectArraySchema(
  description = description,
  itemParameters = listOf(
    AgentToolParameter(
      name = "content",
      type = "string",
      required = true,
      description = "User-visible todo text.",
    ),
    AgentToolParameter(
      name = "status",
      type = "string",
      required = true,
      description = "Todo lifecycle state.",
      jsonSchema = buildJsonObject {
        put("type", "string")
        put("description", "Todo lifecycle state. Supported values: pending, in_progress, completed.")
        put(
          "enum",
          buildJsonArray {
            add(JsonPrimitive("pending"))
            add(JsonPrimitive("in_progress"))
            add(JsonPrimitive("completed"))
          },
        )
      },
    ),
    AgentToolParameter(
      name = "activeForm",
      type = "string",
      required = false,
      description = "Optional present-progress phrasing shown while the todo is active.",
    ),
  ),
)

private fun scheduledTaskTriggerSchema(): JsonObject = buildJsonObject {
  put("type", "object")
  put(
    "description",
    "Use exactly one trigger form: at for one absolute time, after for one relative delay, or start_at plus rrule for recurrence.",
  )
  put(
    "properties",
    buildJsonObject {
      put(
        "at",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "One absolute run time as an ISO-8601 date-time with offset, for example 2026-04-11T21:00:00+08:00.",
          )
        },
      )
      put(
        "after",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "One relative delay as an ISO-8601 duration, for example PT2H or P1D.",
          )
        },
      )
      put(
        "start_at",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "Recurrence anchor time as an ISO-8601 date-time. Include an offset, or pair a local date-time with timezone.",
          )
        },
      )
      put(
        "timezone",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "Optional recurrence timezone such as Asia/Shanghai. Required when start_at does not already include an offset or timezone.",
          )
        },
      )
      put(
        "rrule",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "RFC5545-style recurrence rule, for example FREQ=WEEKLY;BYDAY=MO,TU or FREQ=MONTHLY;BYMONTHDAY=1.",
          )
        },
      )
      put(
        "exdates",
        buildJsonObject {
          put("type", "array")
          put("description", "Optional ISO-8601 date-times to skip from the recurrence set.")
          put(
            "items",
            buildJsonObject {
              put("type", "string")
            },
          )
        },
      )
      put(
        "rdates",
        buildJsonObject {
          put("type", "array")
          put("description", "Optional ISO-8601 date-times to add to the recurrence set.")
          put(
            "items",
            buildJsonObject {
              put("type", "string")
            },
          )
        },
      )
    },
  )
  put("additionalProperties", false)
}

private fun objectArraySchema(
  description: String,
  itemParameters: List<AgentToolParameter>,
): JsonObject = buildJsonObject {
  put("type", "array")
  put("description", description)
  put(
    "items",
    buildJsonObject {
      put("type", "object")
      put(
        "properties",
        buildJsonObject {
          itemParameters.forEach { parameter ->
            put(parameter.name, parameter.toJsonSchemaProperty())
          }
        },
      )
      itemParameters
        .filter(AgentToolParameter::required)
        .map(AgentToolParameter::name)
        .takeIf(List<String>::isNotEmpty)
        ?.let { requiredParameters ->
          put(
            "required",
            buildJsonArray {
              requiredParameters.forEach { requiredParameter ->
                add(JsonPrimitive(requiredParameter))
              }
            },
          )
        }
      put("additionalProperties", false)
    },
  )
}
