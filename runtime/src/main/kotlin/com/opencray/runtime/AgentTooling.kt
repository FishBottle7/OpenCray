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
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessRuntimeIdentity
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.commandResultEnvelope
import com.opencray.runtime.process.managedProcessMetadata
import com.opencray.runtime.process.managedProcessOwnerIdentity
import com.opencray.runtime.process.managedProcessResultEnvelope
import com.opencray.runtime.process.managedProcessToolResult
import com.opencray.runtime.process.managedProcessWorkingDirectoryPath
import com.opencray.runtime.process.missingManagedProcess
import com.opencray.runtime.process.observeManagedProcessOutput
import com.opencray.runtime.process.recordManagedProcessObservationDelivery
import com.opencray.runtime.process.renderManagedProcessSnapshot
import com.opencray.runtime.process.toolStatusForManagedProcessStart
import com.opencray.runtime.session.SessionSearchMatch
import com.opencray.runtime.session.SessionSearchService
import com.opencray.runtime.session.SessionSearchToolContext
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

class OpenCrayToolDispatcher(
  internal val config: OpenCrayToolDispatcherConfig,
  internal val managedProcessObservationTracker: ManagedProcessObservationTracker = ManagedProcessObservationTracker(),
) {
  private val toolCapabilityClassifier = ToolCapabilityClassifier()
  internal val toolCallNormalizer = ToolCallNormalizer()
  internal val toolPolicySupport = ToolPolicySupport()
  private val toolPolicyEvaluator = ToolPolicyEvaluator(
    modePolicy = config.modePolicy,
    approvedTaskId = config.approvedTaskId,
    approvedToolName = config.approvedToolName,
    rejectedTaskId = config.rejectedTaskId,
    rejectedToolName = config.rejectedToolName,
  )
  private val writeBoundary = WorkspaceBoundary(config.workspaceRoots)
  private val readBoundary = WorkspaceBoundary(config.readRoots)
  internal val toolTargetResolver = ToolTargetResolver(
    readBoundary = readBoundary,
    writeBoundary = writeBoundary,
  )
  internal val toolPolicyPipeline = ToolPolicyPipeline(
    toolPolicyEvaluator = toolPolicyEvaluator,
    toolPolicySupport = toolPolicySupport,
    toolCapabilityClassifier = toolCapabilityClassifier,
    toolTargetResolver = toolTargetResolver,
    workspaceRoot = writeBoundary.defaultRoot,
    readRoots = readBoundary.approvedRoots() + config.extraPolicyReadRoots,
    writeRoots = writeBoundary.approvedRoots() + config.extraPolicyWriteRoots,
  )
  private val fileOpsService = FileOpsService(
    approvedRoots = writeBoundary.approvedRoots(),
    mutationLockDirectory = config.fileMutationLockDirectory,
  )
  private val todoStore = config.todoStore
  internal val processRegistry = config.processRegistry
  private val webContentFetcher = config.webContentFetcher
  private val webSearchProvider = config.webSearchProvider
  private val memorySearchService = MemorySearchService()
  private val sessionSearchService = SessionSearchService()
  private val commandExecutor = config.commandExecutor ?: CommandExecutor(
    config = CommandExecutionConfig(
      approvedWorkingDirectories = writeBoundary.approvedRoots(),
    ),
  )
  internal val allowedToolNames: Set<String>? = config.allowedToolNames
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    ?.toSet()
  private val hiddenToolNamePrefixes: Set<String> = config.hiddenToolNamePrefixes
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { prefix -> prefix.lowercase(Locale.US) }
    .toSet()
  private val videoGenerationClient: OpenCrayVideoGenerationClient? =
    config.imageGenerationClient as? OpenCrayVideoGenerationClient
      ?: config.speechSynthesisClient as? OpenCrayVideoGenerationClient
  private val providerMediaJobClient: OpenCrayMediaJobClient? =
    config.imageGenerationClient as? OpenCrayMediaJobClient
      ?: config.speechSynthesisClient as? OpenCrayMediaJobClient
  private val mediaJobExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable).apply {
      name = "OpenCrayMediaJob"
      isDaemon = true
    }
  }
  private val mediaJobIdCounter = AtomicLong(0L)
  private val mediaJobs = linkedMapOf<String, MediaJobHandle>()

  fun todoSnapshot(): List<AgentTodoEntry> = todoStore.snapshot()

  fun definitions(): List<AgentToolDefinition> = toolDefinitions()

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
    contextModeSource: String? = null,
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
      contextModeSource = contextModeSource,
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
        "GenerateImage" -> generateImage(task = task, arguments = invocation.arguments, hooks = hooks)
        "GenerateVideo" -> generateVideo(task = task, arguments = invocation.arguments, hooks = hooks)
        "SynthesizeSpeech" -> synthesizeSpeech(task = task, arguments = invocation.arguments, hooks = hooks)
        "PublishMediaArtifact" -> publishMediaArtifact(task = task, arguments = invocation.arguments)
        "PollMediaJob" -> pollMediaJob(arguments = invocation.arguments)
        "CancelMediaJob" -> cancelMediaJob(arguments = invocation.arguments)
        "Edit" -> editWorkspaceFile(task = task, arguments = invocation.arguments)
        "MultiEdit" -> multiEditWorkspaceFile(task = task, arguments = invocation.arguments)
        "TodoWrite" -> writeTodoList(arguments = invocation.arguments)
        "ScheduledTaskCreate" -> createScheduledTask(task = task, arguments = invocation.arguments)
        "ScheduledTaskList" -> listScheduledTasks(task = task, arguments = invocation.arguments)
        "ScheduledTaskGet" -> getScheduledTask(task = task, arguments = invocation.arguments)
        "ScheduledTaskUpdate" -> updateScheduledTask(task = task, arguments = invocation.arguments)
        "ScheduledTaskRunNow" -> runScheduledTaskNow(task = task, arguments = invocation.arguments)
        "ScheduledTaskSnooze" -> snoozeScheduledTask(task = task, arguments = invocation.arguments)
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
        "session_search" -> searchProjectedSessionHistory(invocation.arguments)
        "session_get" -> getProjectedSessionHistory(invocation.arguments)
        "past_session_search" -> searchPastSessionArchive(invocation.arguments)
        "past_session_get" -> getPastSessionArchive(invocation.arguments)
        else -> AgentToolResult(
          toolName = invocation.requestedToolName,
          status = AgentToolResultStatus.FAILED,
          content = "Tool '${invocation.requestedToolName}' is not registered.",
          errorCode = "TOOL_NOT_FOUND",
        )
      }
      registerMediaArtifactsFromResult(task = task, result = result)
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
        "skills_list",
        "skill_read",
        "SkillsList",
        "mcp_list_servers",
        "memory_search",
        "memory_get",
        "session_search",
        "session_get",
        "past_session_search",
        "past_session_get",
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

  internal fun isToolHiddenByConfig(toolName: String): Boolean {
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

  private fun generateImage(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
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
    val count = arguments.optionalInt("count")?.coerceIn(1, MAX_GENERATED_IMAGE_COUNT) ?: 1
    val format = normalizeGeneratedImageFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_IMAGE_FORMAT
    val size = arguments.optionalString("size")?.trim()?.takeIf(String::isNotBlank)
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val runAsync = arguments.optionalBoolean("async") == true
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
    val baseMetadata = buildMap {
      put("provider", settings.provider)
      put("endpoint", endpoint)
      put("promptPreview", inlinePreview(prompt, maxChars = 240))
      put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      put("format", format)
      put("asyncCapable", "true")
      put("asyncRequested", runAsync.toString())
      size?.let { put("size", it) }
      modelOverride?.let { put("modelOverride", it) }
    }
    return executeImageGeneration(
      client = client,
      settings = settings,
      prompt = prompt,
      count = count,
      size = size,
      format = format,
      modelOverride = modelOverride,
      preferAsync = runAsync,
      outputDirectory = outputDirectory,
      plan = plan,
      endpoint = endpoint,
      baseMetadata = baseMetadata,
      cancellationRequested = hooks.isCancellationRequested,
    )
  }

  private fun generateVideo(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()?.videoGeneration
      ?: return unavailableMediaTool(
        toolName = "GenerateVideo",
        message = "Video generation settings are unavailable on this runtime.",
      )
    if (!settings.isConfigured()) {
      return unavailableMediaTool(
        toolName = "GenerateVideo",
        message = "Video generation is not configured. Set provider base URL, endpoint, and model first.",
      )
    }
    val client = videoGenerationClient
      ?: return unavailableMediaTool(
        toolName = "GenerateVideo",
        message = "Video generation provider support is unavailable on this runtime.",
      )
    val prompt = arguments.requiredText("prompt").trim()
    require(prompt.isNotBlank()) { "GenerateVideo prompt must not be blank." }
    val durationSeconds = arguments.optionalInt("duration_seconds")?.coerceIn(1, MAX_GENERATED_VIDEO_DURATION_SECONDS)
    val size = arguments.optionalString("size")?.trim()?.takeIf(String::isNotBlank)
    val format = normalizeGeneratedVideoFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_VIDEO_FORMAT
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val runAsync = arguments.optionalBoolean("async") ?: true
    val outputDirectory = generatedMediaDirectory("videos")
    val endpoint = buildConfiguredEndpointPreview(
      baseUrl = settings.baseUrl,
      endpoint = settings.endpoint,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "GenerateVideo",
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
      askDetail = "Approval is required before GenerateVideo can access the network.",
      denyDetail = "Policy denied GenerateVideo.",
    )?.let { return it }
    val baseMetadata = buildMap {
      put("provider", settings.provider)
      put("endpoint", endpoint)
      put("promptPreview", inlinePreview(prompt, maxChars = 240))
      put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      put("format", format)
      put("asyncCapable", "true")
      put("asyncRequested", runAsync.toString())
      durationSeconds?.let { put("durationSeconds", it.toString()) }
      size?.let { put("size", it) }
      modelOverride?.let { put("modelOverride", it) }
    }
    return executeVideoGeneration(
      client = client,
      settings = settings,
      prompt = prompt,
      durationSeconds = durationSeconds,
      size = size,
      format = format,
      modelOverride = modelOverride,
      preferAsync = runAsync,
      outputDirectory = outputDirectory,
      plan = plan,
      endpoint = endpoint,
      baseMetadata = baseMetadata,
      cancellationRequested = hooks.isCancellationRequested,
    )
  }

  private fun synthesizeSpeech(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
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
    val runAsync = arguments.optionalBoolean("async") == true
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
    val baseMetadata = buildMap {
      put("provider", settings.provider)
      put("endpoint", endpoint)
      put("textPreview", inlinePreview(text, maxChars = 240))
      put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      put("format", format)
      put("asyncCapable", "true")
      put("asyncRequested", runAsync.toString())
      voiceOverride?.let { put("voiceOverride", it) }
      modelOverride?.let { put("modelOverride", it) }
    }
    return executeSpeechSynthesis(
      client = client,
      settings = settings,
      text = text,
      format = format,
      voiceOverride = voiceOverride,
      modelOverride = modelOverride,
      preferAsync = runAsync,
      outputDirectory = outputDirectory,
      plan = plan,
      endpoint = endpoint,
      baseMetadata = baseMetadata,
      cancellationRequested = hooks.isCancellationRequested,
    )
  }

  private fun pollMediaJob(arguments: JsonObject): AgentToolResult {
    val jobId = arguments.requiredText("job_id").trim()
    require(jobId.isNotBlank()) { "PollMediaJob job_id must not be blank." }
    decodeProviderMediaJobId(jobId)?.let { providerSnapshot ->
      return pollProviderMediaJob(
        externalJobId = jobId,
        snapshot = providerSnapshot,
      )
    }
    val handle = synchronized(mediaJobs) { mediaJobs[jobId] }
      ?: return missingMediaJobResult(toolName = "PollMediaJob", jobId = jobId)
    if (!handle.future.isDone) {
      return mediaJobPendingResult(toolName = "PollMediaJob", handle = handle)
    }
    val finalResult = try {
      handle.future.get()
    } catch (_: CancellationException) {
      cancelledMediaJobTerminalResult(handle)
    } catch (exception: Throwable) {
      failedMediaJobTerminalResult(
        toolName = handle.toolName,
        message = exception.cause?.message ?: exception.message ?: "Background media job failed.",
      )
    }
    return when (finalResult.status) {
      AgentToolResultStatus.SUCCESS -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.SUCCESS,
        content = buildString {
          appendLine("Media job completed.")
          appendLine("job_id=${handle.jobId}")
          appendLine()
          append(finalResult.content)
        }.trim(),
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = finalResult.metadata + mediaJobMetadata(
            handle = handle,
            status = OpenCrayMediaJobStatus.COMPLETED,
          ),
        ),
      )

      AgentToolResultStatus.CANCELLED -> mediaJobCancelledObservationResult(handle)
      else -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = finalResult.content,
        errorCode = finalResult.errorCode ?: "MEDIA_JOB_FAILED",
        errorMessage = finalResult.errorMessage ?: finalResult.content,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = finalResult.metadata + mediaJobMetadata(
            handle = handle,
            status = OpenCrayMediaJobStatus.FAILED,
          ),
        ),
      )
    }
  }

  private fun cancelMediaJob(arguments: JsonObject): AgentToolResult {
    val jobId = arguments.requiredText("job_id").trim()
    require(jobId.isNotBlank()) { "CancelMediaJob job_id must not be blank." }
    decodeProviderMediaJobId(jobId)?.let { providerSnapshot ->
      return cancelProviderMediaJob(
        externalJobId = jobId,
        snapshot = providerSnapshot,
      )
    }
    val handle = synchronized(mediaJobs) { mediaJobs[jobId] }
      ?: return missingMediaJobResult(toolName = "CancelMediaJob", jobId = jobId)
    val alreadyDone = handle.future.isDone
    if (!alreadyDone) {
      handle.cancelRequested.set(true)
      handle.future.cancel(true)
    }
    val status = if (handle.future.isDone) {
      if (alreadyDone) {
        OpenCrayMediaJobStatus.COMPLETED
      } else {
        OpenCrayMediaJobStatus.CANCELLED
      }
    } else {
      OpenCrayMediaJobStatus.PENDING
    }
    return AgentToolResult(
      toolName = "CancelMediaJob",
      status = AgentToolResultStatus.SUCCESS,
      content = when (status) {
        OpenCrayMediaJobStatus.CANCELLED ->
          "Cancellation requested for media job.\njob_id=${handle.jobId}"

        OpenCrayMediaJobStatus.COMPLETED ->
          "Media job already completed.\njob_id=${handle.jobId}"

        else ->
          "Media job cancellation is pending.\njob_id=${handle.jobId}"
      },
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "CancelMediaJob",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NETWORK,
          workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        ),
        metadata = handle.baseMetadata + mediaJobMetadata(
          handle = handle,
          status = status,
        ),
      ),
    )
  }

  private fun executeImageGeneration(
    client: OpenCrayImageGenerationClient,
    settings: OpenCrayImageGenerationSettings,
    prompt: String,
    count: Int,
    size: String?,
    format: String,
    modelOverride: String?,
    preferAsync: Boolean,
    outputDirectory: Path,
    plan: ToolPolicyPlan,
    endpoint: String,
    baseMetadata: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): AgentToolResult {
    val response = try {
      client.generate(
        request = OpenCrayImageGenerationRequest(
          prompt = prompt,
          count = count,
          size = size,
          format = format,
          modelOverride = modelOverride,
          preferAsync = preferAsync,
          settings = settings,
        ),
        cancellationRequested = cancellationRequested,
      )
    } catch (_: CancellationException) {
      return cancelledMediaToolResult(
        toolName = "GenerateImage",
        message = "Image generation was cancelled.",
        metadata = mapOf(
          "provider" to settings.provider,
          "endpoint" to endpoint,
        ),
      )
    }
    response.pendingJob?.let { pendingJob ->
      return providerPendingMediaJobResult(
        plan = plan,
        snapshot = pendingJob,
        metadata = response.metadata + baseMetadata,
      )
    }
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

  private fun executeVideoGeneration(
    client: OpenCrayVideoGenerationClient,
    settings: OpenCrayVideoGenerationSettings,
    prompt: String,
    durationSeconds: Int?,
    size: String?,
    format: String,
    modelOverride: String?,
    preferAsync: Boolean,
    outputDirectory: Path,
    plan: ToolPolicyPlan,
    endpoint: String,
    baseMetadata: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): AgentToolResult {
    val response = try {
      client.generateVideo(
        request = OpenCrayVideoGenerationRequest(
          prompt = prompt,
          durationSeconds = durationSeconds,
          size = size,
          format = format,
          modelOverride = modelOverride,
          preferAsync = preferAsync,
          settings = settings,
        ),
        cancellationRequested = cancellationRequested,
      )
    } catch (_: CancellationException) {
      return cancelledMediaToolResult(
        toolName = "GenerateVideo",
        message = "Video generation was cancelled.",
        metadata = mapOf(
          "provider" to settings.provider,
          "endpoint" to endpoint,
        ),
      )
    }
    response.pendingJob?.let { pendingJob ->
      return providerPendingMediaJobResult(
        plan = plan,
        snapshot = pendingJob,
        metadata = response.metadata + baseMetadata,
      )
    }
    require(response.videos.isNotEmpty()) { "Video provider returned no videos." }
    val batchId = UUID.randomUUID().toString().replace("-", "").take(12)
    val artifacts = response.videos.mapIndexed { index, asset ->
      writeGeneratedWorkspaceArtifact(
        directory = outputDirectory,
        stem = buildString {
          append("video-")
          append(batchId)
          if (response.videos.size > 1) {
            append("-")
            append(index + 1)
          }
        },
        requestedExtension = format,
        defaultExtension = DEFAULT_GENERATED_VIDEO_FORMAT,
        asset = asset,
        kindHint = "file",
      )
    }
    return AgentToolResult(
      toolName = "GenerateVideo",
      status = AgentToolResultStatus.SUCCESS,
      content = buildGeneratedVideoResultContent(artifacts = artifacts),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("provider", settings.provider)
          put("endpoint", endpoint)
          put("promptPreview", inlinePreview(prompt, maxChars = 240))
          put("videoCount", artifacts.size.toString())
          put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
          put("format", format)
          durationSeconds?.let { put("durationSeconds", it.toString()) }
          size?.let { put("size", it) }
          modelOverride?.let { put("modelOverride", it) }
          response.providerRequestId?.let { put("providerRequestId", it) }
          putAll(attachmentArtifactsMetadata(artifacts))
          putAll(response.metadata)
        },
      ),
    )
  }

  private fun executeSpeechSynthesis(
    client: OpenCraySpeechSynthesisClient,
    settings: OpenCraySpeechSynthesisSettings,
    text: String,
    format: String,
    voiceOverride: String?,
    modelOverride: String?,
    preferAsync: Boolean,
    outputDirectory: Path,
    plan: ToolPolicyPlan,
    endpoint: String,
    baseMetadata: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): AgentToolResult {
    val response = try {
      client.synthesize(
        request = OpenCraySpeechSynthesisRequest(
          text = text,
          format = format,
          voiceOverride = voiceOverride,
          modelOverride = modelOverride,
          preferAsync = preferAsync,
          settings = settings,
        ),
        cancellationRequested = cancellationRequested,
      )
    } catch (_: CancellationException) {
      return cancelledMediaToolResult(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis was cancelled.",
        metadata = mapOf(
          "provider" to settings.provider,
          "endpoint" to endpoint,
        ),
      )
    }
    response.pendingJob?.let { pendingJob ->
      return providerPendingMediaJobResult(
        plan = plan,
        snapshot = pendingJob,
        metadata = response.metadata + baseMetadata,
      )
    }
    val audio = requireNotNull(response.audio) { "Speech provider returned no audio payload." }
    val transcriptText = response.transcriptText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: text.takeIf(String::isNotBlank)
    val artifact = writeGeneratedWorkspaceArtifact(
      directory = outputDirectory,
      stem = "voice-${UUID.randomUUID().toString().replace("-", "").take(12)}",
      requestedExtension = format,
      defaultExtension = DEFAULT_GENERATED_AUDIO_FORMAT,
      asset = audio,
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

  private fun startBackgroundMediaJob(
    task: AgentTask,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
    toolName: String,
    plan: ToolPolicyPlan,
    summary: String,
    baseMetadata: Map<String, String>,
    work: ((() -> Boolean)) -> AgentToolResult,
  ): AgentToolResult {
    val jobId = nextMediaJobId(toolName)
    val cancelRequested = AtomicBoolean(false)
    val future = mediaJobExecutor.submit<AgentToolResult> {
      work {
        cancelRequested.get() || hooks.isCancellationRequested()
      }
    }
    val handle = MediaJobHandle(
      jobId = jobId,
      toolName = toolName,
      summary = summary,
      createdAtEpochMs = System.currentTimeMillis(),
      cancelRequested = cancelRequested,
      future = future,
      baseMetadata = baseMetadata,
    )
    synchronized(mediaJobs) {
      mediaJobs[jobId] = handle
    }
    val snapshot = OpenCrayMediaJobSnapshot(
      receipt = OpenCrayMediaJobReceipt(
        jobId = jobId,
        toolName = toolName,
        status = OpenCrayMediaJobStatus.PENDING,
      ),
      metadata = baseMetadata,
    )
    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = pendingMediaJobContent(snapshot),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = baseMetadata + mediaJobMetadata(
          handle = handle,
          status = OpenCrayMediaJobStatus.PENDING,
        ),
      ),
    )
  }

  private fun nextMediaJobId(toolName: String): String {
    val normalizedToolName = toolName.trim()
      .lowercase(Locale.US)
      .replace("[^a-z0-9]+".toRegex(), "-")
      .trim('-')
    val suffix = mediaJobIdCounter.incrementAndGet()
    return "media-$normalizedToolName-$suffix"
  }

  private fun mediaJobPendingResult(
    toolName: String,
    handle: MediaJobHandle,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.SUCCESS,
    content = pendingMediaJobContent(
      OpenCrayMediaJobSnapshot(
        receipt = OpenCrayMediaJobReceipt(
          jobId = handle.jobId,
          toolName = handle.toolName,
          status = OpenCrayMediaJobStatus.PENDING,
        ),
        metadata = handle.baseMetadata,
      ),
    ),
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = handle.baseMetadata + mediaJobMetadata(
        handle = handle,
        status = OpenCrayMediaJobStatus.PENDING,
      ),
    ),
  )

  private fun mediaJobCancelledObservationResult(
    handle: MediaJobHandle,
  ): AgentToolResult = AgentToolResult(
    toolName = "PollMediaJob",
    status = AgentToolResultStatus.SUCCESS,
    content = "Media job was cancelled.\njob_id=${handle.jobId}",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = "PollMediaJob",
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = handle.baseMetadata + mediaJobMetadata(
        handle = handle,
        status = OpenCrayMediaJobStatus.CANCELLED,
      ),
    ),
  )

  private fun missingMediaJobResult(
    toolName: String,
    jobId: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "Media job '$jobId' was not found.",
    errorCode = "MEDIA_JOB_NOT_FOUND",
    errorMessage = "Media job '$jobId' was not found.",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = mapOf(
        "jobId" to jobId,
        "jobStatus" to OpenCrayMediaJobStatus.FAILED.name.lowercase(Locale.US),
      ),
    ),
  )

  private fun cancelledMediaToolResult(
    toolName: String,
    message: String,
    metadata: Map<String, String>,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.CANCELLED,
    content = message,
    errorCode = "MEDIA_JOB_CANCELLED",
    errorMessage = message,
    metadata = metadata,
  )

  private fun cancelledMediaJobTerminalResult(handle: MediaJobHandle): AgentToolResult =
    AgentToolResult(
      toolName = handle.toolName,
      status = AgentToolResultStatus.CANCELLED,
      content = "Media job was cancelled.",
      errorCode = "MEDIA_JOB_CANCELLED",
      errorMessage = "Media job was cancelled.",
      metadata = handle.baseMetadata,
    )

  private fun failedMediaJobTerminalResult(
    toolName: String,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "MEDIA_JOB_FAILED",
    errorMessage = message,
  )

  private fun pendingMediaJobContent(
    snapshot: OpenCrayMediaJobSnapshot,
    externalJobId: String = snapshot.receipt.jobId,
  ): String = buildString {
    appendLine("Media job is pending.")
    appendLine("job_id=$externalJobId")
    appendLine("status=${snapshot.receipt.status.name.lowercase(Locale.US)}")
    appendLine("poll_tool=${snapshot.receipt.pollToolName}")
    appendLine("cancel_tool=${snapshot.receipt.cancelToolName}")
    append("Call ${snapshot.receipt.pollToolName} with this job_id to check completion.")
  }.trim()

  private fun mediaJobMetadata(
    handle: MediaJobHandle,
    status: OpenCrayMediaJobStatus,
  ): Map<String, String> = mapOf(
    "jobId" to handle.jobId,
    "jobStatus" to status.name.lowercase(Locale.US),
    "jobToolName" to handle.toolName,
    "jobCreatedAtEpochMs" to handle.createdAtEpochMs.toString(),
    "jobPollToolName" to "PollMediaJob",
    "jobCancelToolName" to "CancelMediaJob",
    "jobPending" to (status == OpenCrayMediaJobStatus.PENDING).toString(),
  )

  private fun providerPendingMediaJobResult(
    plan: ToolPolicyPlan,
    snapshot: OpenCrayMediaJobSnapshot,
    metadata: Map<String, String>,
  ): AgentToolResult {
    val externalJobId = encodeProviderMediaJobId(snapshot)
    return AgentToolResult(
      toolName = snapshot.receipt.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = pendingMediaJobContent(snapshot, externalJobId = externalJobId),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = metadata + providerMediaJobMetadata(
          externalJobId = externalJobId,
          snapshot = snapshot,
        ),
      ),
    )
  }

  private fun pollProviderMediaJob(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()
      ?: return unavailableMediaTool(
        toolName = "PollMediaJob",
        message = "Media job settings are unavailable on this runtime.",
      )
    val client = providerMediaJobClient
      ?: return unavailableMediaTool(
        toolName = "PollMediaJob",
        message = "Provider media job support is unavailable on this runtime.",
      )
    val polled = try {
      client.poll(
        job = snapshot,
        settings = settings,
      )
    } catch (_: CancellationException) {
      return providerCancelledMediaJobResult(
        externalJobId = externalJobId,
        snapshot = snapshot.copy(
          receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
        ),
      )
    } catch (exception: Throwable) {
      return AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = exception.message ?: "Media job polling failed.",
        errorCode = "MEDIA_JOB_FAILED",
        errorMessage = exception.message ?: "Media job polling failed.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = providerMediaJobMetadata(
            externalJobId = externalJobId,
            snapshot = snapshot.copy(
              receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.FAILED),
            ),
          ),
        ),
      )
    }
    val updatedExternalJobId = encodeProviderMediaJobId(polled.snapshot)
    return when (polled.snapshot.receipt.status) {
      OpenCrayMediaJobStatus.PENDING -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.SUCCESS,
        content = pendingMediaJobContent(polled.snapshot, externalJobId = updatedExternalJobId),
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = polled.snapshot.metadata +
            polled.metadata +
            providerMediaJobMetadata(
              externalJobId = updatedExternalJobId,
              snapshot = polled.snapshot,
            ),
        ),
      )

      OpenCrayMediaJobStatus.CANCELLED -> providerCancelledMediaJobResult(
        externalJobId = updatedExternalJobId,
        snapshot = polled.snapshot,
      )

      OpenCrayMediaJobStatus.FAILED -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = "Media job failed.",
        errorCode = "MEDIA_JOB_FAILED",
        errorMessage = "Media job failed.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = polled.snapshot.metadata +
            polled.metadata +
            providerMediaJobMetadata(
              externalJobId = updatedExternalJobId,
              snapshot = polled.snapshot,
            ),
        ),
      )

      OpenCrayMediaJobStatus.COMPLETED -> completedProviderMediaJobResult(
        externalJobId = updatedExternalJobId,
        snapshot = polled.snapshot,
        pollResult = polled,
      )
    }
  }

  private fun cancelProviderMediaJob(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()
      ?: return unavailableMediaTool(
        toolName = "CancelMediaJob",
        message = "Media job settings are unavailable on this runtime.",
      )
    val client = providerMediaJobClient
      ?: return unavailableMediaTool(
        toolName = "CancelMediaJob",
        message = "Provider media job support is unavailable on this runtime.",
      )
    val cancelledSnapshot = try {
      client.cancel(
        job = snapshot,
        settings = settings,
      )
    } catch (_: CancellationException) {
      snapshot.copy(
        receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
      )
    } catch (exception: Throwable) {
      return AgentToolResult(
        toolName = "CancelMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = exception.message ?: "Media job cancellation failed.",
        errorCode = "MEDIA_JOB_CANCEL_FAILED",
        errorMessage = exception.message ?: "Media job cancellation failed.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "CancelMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = providerMediaJobMetadata(
            externalJobId = externalJobId,
            snapshot = snapshot.copy(
              receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.FAILED),
            ),
          ),
        ),
      )
    }
    val updatedExternalJobId = encodeProviderMediaJobId(cancelledSnapshot)
    val status = cancelledSnapshot.receipt.status
    return AgentToolResult(
      toolName = "CancelMediaJob",
      status = AgentToolResultStatus.SUCCESS,
      content = when (status) {
        OpenCrayMediaJobStatus.CANCELLED ->
          "Cancellation requested for media job.\njob_id=$updatedExternalJobId"

        OpenCrayMediaJobStatus.COMPLETED ->
          "Media job already completed.\njob_id=$updatedExternalJobId"

        else ->
          "Media job cancellation is pending.\njob_id=$updatedExternalJobId"
      },
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "CancelMediaJob",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NETWORK,
          workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        ),
        metadata = cancelledSnapshot.metadata + providerMediaJobMetadata(
          externalJobId = updatedExternalJobId,
          snapshot = cancelledSnapshot,
        ),
      ),
    )
  }

  private fun completedProviderMediaJobResult(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
    pollResult: OpenCrayMediaJobPollResult,
  ): AgentToolResult {
    val request = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NETWORK,
      workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
    )
    return when (snapshot.receipt.toolName) {
      "GenerateImage" -> {
        require(pollResult.images.isNotEmpty()) { "Media job completed without image payloads." }
        val artifacts = pollResult.images.mapIndexed { index, asset ->
          writeGeneratedWorkspaceArtifact(
            directory = generatedMediaDirectory("images"),
            stem = buildString {
              append("image-")
              append(UUID.randomUUID().toString().replace("-", "").take(12))
              if (pollResult.images.size > 1) {
                append("-")
                append(index + 1)
              }
            },
            requestedExtension = snapshot.metadata["format"],
            defaultExtension = DEFAULT_GENERATED_IMAGE_FORMAT,
            asset = asset,
            kindHint = "image",
          )
        }
        AgentToolResult(
          toolName = "PollMediaJob",
          status = AgentToolResultStatus.SUCCESS,
          content = buildString {
            appendLine("Media job completed.")
            appendLine("job_id=$externalJobId")
            appendLine()
            append(buildGeneratedImageResultContent(artifacts))
          }.trim(),
          metadata = toolPolicyPipeline.resultMetadata(
            toolName = "PollMediaJob",
            request = request,
            metadata = snapshot.metadata +
              pollResult.metadata +
              mapOf("imageCount" to artifacts.size.toString()) +
              attachmentArtifactsMetadata(artifacts) +
              providerMediaJobMetadata(
                externalJobId = externalJobId,
                snapshot = snapshot,
              ),
          ),
        )
      }

      "GenerateVideo" -> {
        require(pollResult.videos.isNotEmpty()) { "Media job completed without video payloads." }
        val artifacts = pollResult.videos.mapIndexed { index, asset ->
          writeGeneratedWorkspaceArtifact(
            directory = generatedMediaDirectory("videos"),
            stem = buildString {
              append("video-")
              append(UUID.randomUUID().toString().replace("-", "").take(12))
              if (pollResult.videos.size > 1) {
                append("-")
                append(index + 1)
              }
            },
            requestedExtension = snapshot.metadata["format"],
            defaultExtension = DEFAULT_GENERATED_VIDEO_FORMAT,
            asset = asset,
            kindHint = "file",
          )
        }
        AgentToolResult(
          toolName = "PollMediaJob",
          status = AgentToolResultStatus.SUCCESS,
          content = buildString {
            appendLine("Media job completed.")
            appendLine("job_id=$externalJobId")
            appendLine()
            append(buildGeneratedVideoResultContent(artifacts))
          }.trim(),
          metadata = toolPolicyPipeline.resultMetadata(
            toolName = "PollMediaJob",
            request = request,
            metadata = snapshot.metadata +
              pollResult.metadata +
              mapOf("videoCount" to artifacts.size.toString()) +
              attachmentArtifactsMetadata(artifacts) +
              providerMediaJobMetadata(
                externalJobId = externalJobId,
                snapshot = snapshot,
              ),
          ),
        )
      }

      else -> {
        val audio = requireNotNull(pollResult.audio) { "Media job completed without audio payload." }
        val transcriptText = pollResult.transcriptText
          ?.trim()
          ?.takeIf(String::isNotBlank)
        val artifact = writeGeneratedWorkspaceArtifact(
          directory = generatedMediaDirectory("voices"),
          stem = "voice-${UUID.randomUUID().toString().replace("-", "").take(12)}",
          requestedExtension = snapshot.metadata["format"],
          defaultExtension = DEFAULT_GENERATED_AUDIO_FORMAT,
          asset = audio,
          kindHint = "voice",
          durationMs = pollResult.durationMs,
          transcriptText = transcriptText,
        )
        AgentToolResult(
          toolName = "PollMediaJob",
          status = AgentToolResultStatus.SUCCESS,
          content = buildString {
            appendLine("Media job completed.")
            appendLine("job_id=$externalJobId")
            appendLine()
            append(buildGeneratedSpeechResultContent(artifact))
          }.trim(),
          metadata = toolPolicyPipeline.resultMetadata(
            toolName = "PollMediaJob",
            request = request,
            metadata = snapshot.metadata +
              pollResult.metadata +
              attachmentArtifactsMetadata(listOf(artifact)) +
              providerMediaJobMetadata(
                externalJobId = externalJobId,
                snapshot = snapshot,
              ),
          ),
        )
      }
    }
  }

  private fun providerCancelledMediaJobResult(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): AgentToolResult = AgentToolResult(
    toolName = "PollMediaJob",
    status = AgentToolResultStatus.SUCCESS,
    content = "Media job was cancelled.\njob_id=$externalJobId",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = "PollMediaJob",
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = snapshot.metadata + providerMediaJobMetadata(
        externalJobId = externalJobId,
        snapshot = snapshot.copy(
          receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
        ),
      ),
    ),
  )

  private fun providerMediaJobMetadata(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): Map<String, String> = mapOf(
    "jobId" to externalJobId,
    "providerJobId" to snapshot.receipt.jobId,
    "jobStatus" to snapshot.receipt.status.name.lowercase(Locale.US),
    "jobToolName" to snapshot.receipt.toolName,
    "jobPollToolName" to "PollMediaJob",
    "jobCancelToolName" to "CancelMediaJob",
    "jobPending" to (snapshot.receipt.status == OpenCrayMediaJobStatus.PENDING).toString(),
    "jobPollAfterMs" to snapshot.receipt.pollAfterMs.toString(),
  ) + snapshot.providerRequestId?.let { mapOf("providerRequestId" to it) }.orEmpty()

  private fun encodeProviderMediaJobId(snapshot: OpenCrayMediaJobSnapshot): String {
    val payload = buildJsonObject {
      put("v", 1)
      put("toolName", snapshot.receipt.toolName)
      put("providerJobId", snapshot.receipt.jobId)
      put("status", snapshot.receipt.status.name.lowercase(Locale.US))
      put("pollAfterMs", snapshot.receipt.pollAfterMs)
      snapshot.providerRequestId?.let { put("providerRequestId", it) }
      put(
        "metadata",
        buildJsonObject {
          snapshot.metadata
            .filterKeys { key -> key in ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS }
            .toSortedMap()
            .forEach { (key, value) ->
            put(key, value)
          }
        },
      )
    }
    val encoded = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(config.json.encodeToString(JsonObject.serializer(), payload).toByteArray(StandardCharsets.UTF_8))
    return "$PROVIDER_MEDIA_JOB_ID_PREFIX$encoded"
  }

  private fun decodeProviderMediaJobId(jobId: String): OpenCrayMediaJobSnapshot? {
    if (!jobId.startsWith(PROVIDER_MEDIA_JOB_ID_PREFIX)) {
      return null
    }
    val encodedPayload = jobId.removePrefix(PROVIDER_MEDIA_JOB_ID_PREFIX)
    val decoded = runCatching {
      String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
    }.getOrNull() ?: return null
    val payload = config.json.parseToJsonElement(decoded) as? JsonObject ?: return null
    val toolName = (payload["toolName"] as? JsonPrimitive)?.content.orEmpty()
      .takeIf(String::isNotBlank)
      ?: return null
    val providerJobId = (payload["providerJobId"] as? JsonPrimitive)?.content.orEmpty()
      .takeIf(String::isNotBlank)
      ?: return null
    val status = (payload["status"] as? JsonPrimitive)?.content
      ?.trim()
      ?.uppercase(Locale.US)
      ?.let { raw -> OpenCrayMediaJobStatus.entries.firstOrNull { entry -> entry.name == raw } }
      ?: OpenCrayMediaJobStatus.PENDING
    val pollAfterMs = (payload["pollAfterMs"] as? JsonPrimitive)?.content
      ?.toLongOrNull()
      ?.takeIf { it > 0L }
      ?: 1_000L
    val providerRequestId = (payload["providerRequestId"] as? JsonPrimitive)?.content
      ?.takeIf(String::isNotBlank)
    val metadata = (payload["metadata"] as? JsonObject)
      ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
      .orEmpty()
    return OpenCrayMediaJobSnapshot(
      receipt = OpenCrayMediaJobReceipt(
        jobId = providerJobId,
        toolName = toolName,
        status = status,
        pollAfterMs = pollAfterMs,
      ),
      providerRequestId = providerRequestId,
      metadata = metadata,
    )
  }

  private fun editWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("file_path", "path"),
      label = "Edit",
      defaultToRoot = false,
    )
    val edit = TextEdit(
      oldString = arguments.requiredString("old_string"),
      newString = arguments.requiredText("new_string"),
      replaceAll = arguments.optionalBoolean("replace_all") ?: false,
    )
    return fileOpsService.withMutationLock {
      require(Files.isRegularFile(file)) { "Edit path is not a file: $file" }
      val source = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
      val outcome = applyTextEdits(source, listOf(edit))
      writeTextFile(
        task = task,
        toolName = "Edit",
        path = file,
        content = outcome.content,
        metadataPathKey = "filePath",
        successMessage = "Updated ${toolTargetResolver.displayModelPath(file)} with ${outcome.replacementCount} replacement(s).",
        extraMetadata = mapOf("replacementCount" to outcome.replacementCount.toString()),
      )
    }
  }

  private fun multiEditWorkspaceFile(task: AgentTask, arguments: JsonObject): AgentToolResult {
    val file = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("file_path", "path"),
      label = "MultiEdit",
      defaultToRoot = false,
    )
    val edits = arguments.requiredObjectArray("edits").mapIndexed { index, entry ->
      TextEdit(
        oldString = entry.requiredString("old_string"),
        newString = entry.requiredText("new_string"),
        replaceAll = entry.optionalBoolean("replace_all") ?: false,
      ).also {
        require(it.oldString.isNotEmpty()) { "MultiEdit edit ${index + 1} old_string must not be empty." }
      }
    }
    return fileOpsService.withMutationLock {
      require(Files.isRegularFile(file)) { "MultiEdit path is not a file: $file" }
      val source = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
      val outcome = applyTextEdits(source, edits)
      writeTextFile(
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
        ownerIdentity = managedProcessOwnerIdentity(task),
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
        ownerIdentity = managedProcessOwnerIdentity(task),
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

  private fun publishMediaArtifact(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val artifactId = arguments.requiredStringFrom("artifact_id", "artifactId")
    val destination = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("relative_path", "relativePath", "destination_path", "destinationPath"),
      label = "media artifact publish",
      defaultToRoot = false,
    )
    val registeredArtifact = config.mediaArtifactRegistry.resolve(artifactId)
      ?: return AgentToolResult(
        toolName = "PublishMediaArtifact",
        status = AgentToolResultStatus.FAILED,
        content = "Media artifact '$artifactId' was not found in the workspace media registry.",
        errorCode = "MEDIA_ARTIFACT_NOT_FOUND",
        errorMessage = "Media artifact '$artifactId' was not found in the workspace media registry.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PublishMediaArtifact",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.FILE,
            primaryPath = destination,
          ),
          metadata = mapOf(
            "artifactId" to artifactId,
            "path" to toolTargetResolver.displayWritablePath(destination),
          ),
        ),
      )
    val source = writeBoundary.defaultRoot
      .resolve(registeredArtifact.artifact.relativePath)
      .normalize()
    require(source.startsWith(writeBoundary.defaultRoot)) {
      "Registered media artifact '$artifactId' escapes the workspace root."
    }
    require(Files.isRegularFile(source)) {
      "Registered media artifact '$artifactId' no longer exists."
    }
    if (Files.exists(destination)) {
      val displayPath = toolTargetResolver.displayWritablePath(destination)
      return AgentToolResult(
        toolName = "PublishMediaArtifact",
        status = AgentToolResultStatus.FAILED,
        content = "PublishMediaArtifact destination already exists: $displayPath",
        errorCode = "ILLEGAL_ARGUMENT",
        errorMessage = "PublishMediaArtifact destination already exists: $displayPath",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PublishMediaArtifact",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.FILE,
            primaryPath = destination,
          ),
          metadata = mapOf(
            "artifactId" to artifactId,
            "path" to displayPath,
          ),
        ),
      )
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "PublishMediaArtifact",
      targetPath = destination,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = destination,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to toolTargetResolver.displayWritablePath(destination),
        "sourcePath" to toolTargetResolver.displayWritablePath(source),
      ),
    )?.let { return it }
    Files.createDirectories(destination.parent)
    Files.copy(source, destination)
    val artifact = OpenCrayGeneratedWorkspaceArtifact(
      path = destination,
      kindHint = registeredArtifact.artifact.kindHint,
      mimeType = registeredArtifact.artifact.mimeType,
      displayName = destination.fileName?.toString() ?: registeredArtifact.artifact.displayName,
      durationMs = registeredArtifact.artifact.durationMs,
      waveformBars = registeredArtifact.artifact.waveformBars,
      transcriptText = registeredArtifact.artifact.transcriptText,
    )
    val publishedMetadata = attachmentArtifactsMetadata(listOf(artifact))
    val publishedDescriptor = OpenCrayAttachmentArtifacts.decodeMetadata(config.json, publishedMetadata).firstOrNull()
    return AgentToolResult(
      toolName = "PublishMediaArtifact",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Published media artifact.")
        appendLine("source_artifact_id=$artifactId")
        publishedDescriptor?.let { descriptor ->
          appendLine("artifact_id=${descriptor.artifactId}")
          appendLine("relative_path=${descriptor.relativePath}")
        }
        append("Use the published relative_path in the final response attachment if the user requested a workspace file.")
      }.trim(),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "sourceArtifactId" to artifactId,
          "sourcePath" to toolTargetResolver.displayWritablePath(source),
          "path" to toolTargetResolver.displayWritablePath(destination),
        ) + publishedMetadata,
      ),
    )
  }

  private fun registerMediaArtifactsFromResult(
    task: AgentTask,
    result: AgentToolResult,
  ) {
    if (result.status != AgentToolResultStatus.SUCCESS) {
      return
    }
    val artifacts = OpenCrayAttachmentArtifacts.decodeMetadata(
      json = config.json,
      metadata = result.metadata,
    )
    if (artifacts.isEmpty()) {
      return
    }
    config.mediaArtifactRegistry.register(
      artifacts = artifacts,
      source = OpenCrayMediaArtifactSource(
        runId = task.metadata["runId"]
          ?: task.metadata["run_id"],
        toolName = result.toolName,
        source = when (result.toolName) {
          "GenerateImage",
          "GenerateVideo",
          "SynthesizeSpeech",
          "PollMediaJob",
          -> "generated"
          else -> "workspace"
        },
      ),
    )
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

  private fun buildGeneratedVideoResultContent(
    artifacts: List<OpenCrayGeneratedWorkspaceArtifact>,
  ): String = buildString {
    appendLine("Generated ${artifacts.size} video file(s).")
    artifacts.forEachIndexed { index, artifact ->
      val descriptor = attachmentArtifactDescriptor(artifact) ?: return@forEachIndexed
      appendLine("${index + 1}. artifact_id=${descriptor.artifactId}")
      appendLine("   relative_path=${descriptor.relativePath}")
    }
    append("Use kind=file when attaching these artifact_id values in the final response.")
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
    require(asset.bytes.isNotEmpty() || asset.sourcePath != null) { "Generated media asset was empty." }
    val resolvedExtension = resolveGeneratedAssetExtension(
      requestedExtension = requestedExtension,
      defaultExtension = defaultExtension,
      fileName = asset.fileName,
      mimeType = asset.mimeType,
    )
    Files.createDirectories(directory)
    val outputPath = directory.resolve("$stem.$resolvedExtension")
    asset.sourcePath?.let { sourcePath ->
      runCatching {
        Files.move(sourcePath, outputPath, StandardCopyOption.REPLACE_EXISTING)
      }.getOrElse {
        Files.copy(sourcePath, outputPath, StandardCopyOption.REPLACE_EXISTING)
        Files.deleteIfExists(sourcePath)
      }
    } ?: Files.write(outputPath, asset.bytes)
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
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    "video/webm" -> "webm"
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

  private fun normalizeGeneratedVideoFormat(rawValue: String?): String? = when (rawValue?.trim()?.lowercase(Locale.US)) {
    null,
    "",
    -> null
    "mp4" -> "mp4"
    "mov" -> "mov"
    "webm" -> "webm"
    else -> throw IllegalArgumentException("GenerateVideo format must be mp4, mov, or webm.")
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
      targetKind = ToolTargetKind.NETWORK,
      primaryPath = writeBoundary.defaultRoot,
      primaryTargetPath = toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot),
      workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      targetSummary = "sandbox session info",
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "sandbox_session_info",
      targetPath = writeBoundary.defaultRoot,
      metadataRequest = metadataRequest,
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf(
        "workspaceRoot" to toolTargetResolver.displayWritablePath(writeBoundary.defaultRoot),
      ),
      askDetail = "Approval is required before sandbox_session_info can inspect or refresh a cloud sandbox session.",
      denyDetail = "Policy denied sandbox_session_info.",
    )?.let { return it }
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
        plan = plan,
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
    val pinned = arguments.optionalBoolean("pin") ?: false
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
        "activationSource" to "skill_read",
        "pinned" to pinned.toString(),
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

  private fun searchProjectedSessionHistory(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "session_search",
        status = AgentToolResultStatus.FAILED,
        content = "Projected prior-session history search is not configured for this runtime.",
        errorCode = "SESSION_SEARCH_UNAVAILABLE",
      )
    val query = arguments.requiredString("query")
    val maxResults = (arguments.optionalInt("max_results") ?: arguments.optionalInt("maxResults")
      ?: config.maxSessionSearchResults).coerceIn(1, config.maxSessionSearchResults)
    val minScore = (arguments.optionalInt("min_score") ?: arguments.optionalInt("minScore")
      ?: 1).coerceAtLeast(1)
    val response = sessionSearchService.search(
      context = context,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    val content = if (response.matches.isEmpty()) {
      "No matching projected prior-session snippets were found."
    } else {
      buildString {
        appendLine("Found ${response.matches.size} projected session match(es).")
        response.matches.forEachIndexed { index, match ->
          append(index + 1)
          append(". ")
          append(renderSessionSearchHeader(match))
          appendLine()
          appendLine(match.snippet)
          if (index != response.matches.lastIndex) {
            appendLine()
          }
        }
      }.trim()
    }
    return AgentToolResult(
      toolName = "session_search",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicySupport.commonMetadata(
        toolName = "session_search",
        metadataContext = policyMetadataContext(
          toolName = "session_search",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = inlinePreview(query, maxChars = 256),
        ),
      ) + buildMap {
        put("query", query)
        put("surface", "session_history")
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "recordIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "sessionIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "paths",
            response.matches.joinToString(separator = ",") { match -> match.path },
          )
          put(
            "lineRanges",
            response.matches.joinToString(separator = ",") { match ->
              renderSessionLineRange(match.startLine, match.endLine)
            },
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

  private fun getProjectedSessionHistory(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "session_get",
        status = AgentToolResultStatus.FAILED,
        content = "Projected prior-session history reads are not configured for this runtime.",
        errorCode = "SESSION_GET_UNAVAILABLE",
      )
    val path = arguments.requiredString("path")
    val from = arguments.optionalInt("from")?.coerceAtLeast(1)
    val lines = (arguments.optionalInt("lines") ?: config.maxSessionGetLines)
      .coerceIn(1, config.maxSessionGetLines)
    val response = sessionSearchService.get(
      context = context,
      path = path,
      from = from,
      lines = lines,
    )
    return AgentToolResult(
      toolName = "session_get",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        append(response.path)
        append("#")
        append(renderSessionLineRange(response.startLine, response.endLine))
        appendLine()
        append(response.text)
      }.trim(),
      metadata = toolPolicySupport.commonMetadata(
        toolName = "session_get",
        metadataContext = policyMetadataContext(
          toolName = "session_get",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = response.path,
          targetSummary = response.path,
        ),
      ) + mapOf(
        "surface" to "session_history",
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
        "recordIds" to response.sessionIds.joinToString(separator = ","),
        "sessionIds" to response.sessionIds.joinToString(separator = ","),
      ),
    )
  }

  private fun searchPastSessionArchive(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "past_session_search",
        status = AgentToolResultStatus.FAILED,
        content = "Past-session archive search is not configured for this runtime.",
        errorCode = "PAST_SESSION_SEARCH_UNAVAILABLE",
      )
    val query = arguments.requiredString("query")
    val maxResults = (arguments.optionalInt("max_results") ?: arguments.optionalInt("maxResults")
      ?: config.maxSessionSearchResults).coerceIn(1, config.maxSessionSearchResults)
    val minScore = (arguments.optionalInt("min_score") ?: arguments.optionalInt("minScore")
      ?: 1).coerceAtLeast(1)
    val response = sessionSearchService.search(
      context = context,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    val content = renderPastSessionSearchContent(response.matches)
    return AgentToolResult(
      toolName = "past_session_search",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicySupport.commonMetadata(
        toolName = "past_session_search",
        metadataContext = policyMetadataContext(
          toolName = "past_session_search",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = inlinePreview(query, maxChars = 256),
        ),
      ) + buildMap {
        put("query", query)
        put("surface", "session_archive")
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "recordIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "sessionIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "paths",
            response.matches.joinToString(separator = ",") { match -> match.path },
          )
          put(
            "lineRanges",
            response.matches.joinToString(separator = ",") { match ->
              renderSessionLineRange(match.startLine, match.endLine)
            },
          )
        }
      },
    )
  }

  private fun getPastSessionArchive(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "past_session_get",
        status = AgentToolResultStatus.FAILED,
        content = "Past-session archive reads are not configured for this runtime.",
        errorCode = "PAST_SESSION_GET_UNAVAILABLE",
      )
    val path = arguments.requiredString("path")
    val from = arguments.optionalInt("from")?.coerceAtLeast(1)
    val lines = (arguments.optionalInt("lines") ?: config.maxSessionGetLines)
      .coerceIn(1, config.maxSessionGetLines)
    val response = sessionSearchService.get(
      context = context,
      path = path,
      from = from,
      lines = lines,
    )
    return AgentToolResult(
      toolName = "past_session_get",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        append(response.path)
        append("#")
        append(renderSessionLineRange(response.startLine, response.endLine))
        appendLine()
        append(response.text)
      }.trim(),
      metadata = toolPolicySupport.commonMetadata(
        toolName = "past_session_get",
        metadataContext = policyMetadataContext(
          toolName = "past_session_get",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = response.path,
          targetSummary = response.path,
        ),
      ) + mapOf(
        "surface" to "session_archive",
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
        "recordIds" to response.sessionIds.joinToString(separator = ","),
        "sessionIds" to response.sessionIds.joinToString(separator = ","),
      ),
    )
  }

  internal fun policyMetadataContext(
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

  internal fun gateReadOnlyTool(
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

  private data class MediaJobHandle(
    val jobId: String,
    val toolName: String,
    val summary: String,
    val createdAtEpochMs: Long,
    val cancelRequested: AtomicBoolean,
    val future: Future<AgentToolResult>,
    val baseMetadata: Map<String, String>,
  )

  companion object {
    internal const val HOST_SESSION_ID_METADATA_KEY: String = "_host.sessionId"
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
    private const val DEFAULT_GENERATED_VIDEO_FORMAT: String = "mp4"
    private const val DEFAULT_GENERATED_AUDIO_FORMAT: String = "mp3"
    private const val PROVIDER_MEDIA_JOB_ID_PREFIX: String = "provider_media_job:"
    private val ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS: Set<String> = setOf(
      "providerPollUrl",
      "providerCancelUrl",
    )
    private const val MAX_GENERATED_IMAGE_COUNT: Int = 9
    private const val MAX_GENERATED_VIDEO_DURATION_SECONDS: Int = 60
    private const val MAX_EXECUTION_ATTACHMENT_ARTIFACT_PREVIEW_COUNT: Int = 12
    private val WINDOWS_ABSOLUTE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/].+")
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

  internal fun inlinePreview(
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

  private fun renderSessionSearchHeader(match: SessionSearchMatch): String = buildString {
    append(match.path)
    append("#")
    append(renderSessionLineRange(match.startLine, match.endLine))
    append(" score=")
    append(match.score)
    append(" session_id=")
    append(match.sessionId)
    match.title
      ?.takeIf(String::isNotBlank)
      ?.let { title ->
        append(" title=")
        append(title)
      }
    if (match.matchedTerms.isNotEmpty()) {
      append(" matched_terms=")
      append(match.matchedTerms.joinToString(separator = "|"))
    }
  }

  private fun renderPastSessionSearchContent(matches: List<SessionSearchMatch>): String {
    if (matches.isEmpty()) {
      return "No matching past-session archive snippets were found."
    }
    return buildString {
      appendLine("Found ${matches.size} past-session archive match(es).")
      matches.forEachIndexed { index, match ->
        append(index + 1)
        append(". session_id=")
        append(match.sessionId)
        match.title
          ?.takeIf(String::isNotBlank)
          ?.let { title ->
            append(" title=")
            append(title)
          }
        append(" score=")
        append(match.score)
        appendLine()
        append("summary=")
        appendLine(match.snippet)
        append("reference=")
        append(match.path)
        append("#")
        append(renderSessionLineRange(match.startLine, match.endLine))
        if (match.matchedTerms.isNotEmpty()) {
          append(" matched_terms=")
          append(match.matchedTerms.joinToString(separator = "|"))
        }
        if (index != matches.lastIndex) {
          appendLine()
          appendLine()
        }
      }
    }.trim()
  }

  private fun renderMemoryLineRange(
    startLine: Int,
    endLine: Int,
  ): String = if (startLine == endLine) {
    "L$startLine"
  } else {
    "L$startLine-L$endLine"
  }

  private fun renderSessionLineRange(
    startLine: Int,
    endLine: Int,
  ): String = if (startLine == endLine) {
    "L$startLine"
  } else {
    "L$startLine-L$endLine"
  }

}
