package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.filesystem.FileMutationOperation
import com.opencray.filesystem.FileOpsService
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpRuntimeSupport
import com.opencray.mcp.McpToolExposure
import com.opencray.policy.ModePolicy
import com.opencray.runtime.memory.MemorySearchService
import com.opencray.runtime.memory.getProjectedMemory
import com.opencray.runtime.memory.searchProjectedMemory
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
import com.opencray.runtime.session.SessionSearchService
import com.opencray.runtime.session.getPastSessionArchive
import com.opencray.runtime.session.getProjectedSessionHistory
import com.opencray.runtime.session.searchPastSessionArchive
import com.opencray.runtime.session.searchProjectedSessionHistory
import com.opencray.runtime.media.MediaJobCoordinator
import com.opencray.runtime.media.cancelMediaJob
import com.opencray.runtime.media.generateImage
import com.opencray.runtime.media.generateVideo
import com.opencray.runtime.media.pollMediaJob
import com.opencray.runtime.media.publishMediaArtifact
import com.opencray.runtime.media.synthesizeSpeech
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.skills.checkInstalledSkillPackages
import com.opencray.runtime.skills.findSkillPackages
import com.opencray.runtime.skills.installSkillPackage
import com.opencray.runtime.skills.installSkillPackagesBatch
import com.opencray.runtime.skills.inspectSkillPackageSource
import com.opencray.runtime.skills.listInstalledSkillPackages
import com.opencray.runtime.skills.removeSkillPackage
import com.opencray.runtime.skills.updateInstalledSkillPackages
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
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
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
    approvedTaskGrantScopedToFirstRequest = config.approvedTaskGrantScopedToFirstRequest,
    rejectedTaskId = config.rejectedTaskId,
    rejectedToolName = config.rejectedToolName,
  )
  internal val writeBoundary = WorkspaceBoundary(config.workspaceRoots)
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
  internal val memorySearchService = MemorySearchService()
  internal val sessionSearchService = SessionSearchService()
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
  internal val videoGenerationClient: OpenCrayVideoGenerationClient? =
    config.imageGenerationClient as? OpenCrayVideoGenerationClient
      ?: config.speechSynthesisClient as? OpenCrayVideoGenerationClient
  internal val providerMediaJobClient: OpenCrayMediaJobClient? =
    config.imageGenerationClient as? OpenCrayMediaJobClient
      ?: config.speechSynthesisClient as? OpenCrayMediaJobClient
  internal val mediaJobCoordinator = MediaJobCoordinator()

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
    val managedProcessCancellationRegistration =
      com.opencray.runtime.process.ManagedProcessCancellationRegistry.register(task.id) {
        hooks.isCancellationRequested()
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
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw interrupted
    } catch (vmError: VirtualMachineError) {
      throw vmError
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
    } finally {
      managedProcessCancellationRegistration.close()
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
  ): ToolPolicyPlan =
    toolPolicyPipeline.plan(
      task = task,
      toolName = "WebSearch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = inlinePreview(query, maxChars = 256),
      ),
    )

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

  private val maxReadBudgetBytesPerChar: Long = 4

  internal data class WorkspaceReadHead(
    val text: String,
    val truncated: Boolean,
    val byteCount: Long,
  )

  internal fun readFileHeadWithinCharBudget(
    file: Path,
    maxChars: Int,
    sizeProbe: (Path) -> Long = { target -> Files.size(target) },
  ): WorkspaceReadHead {
    val totalBytes = sizeProbe(file).coerceAtLeast(0)
    val headByteLimit = maxChars * maxReadBudgetBytesPerChar + maxReadBudgetBytesPerChar
    val requestedBytes = minOf(totalBytes, headByteLimit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val buffer = ByteArray(requestedBytes)
    var filled = 0
    Files.newInputStream(file).use { input ->
      while (filled < requestedBytes) {
        val read = input.read(buffer, filled, requestedBytes - filled)
        if (read <= 0) {
          break
        }
        filled += read
      }
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    var usableBytes = filled
    var decodedText = ""
    while (true) {
      try {
        decodedText = decoder.decode(ByteBuffer.wrap(buffer, 0, usableBytes)).toString()
        break
      } catch (_: CharacterCodingException) {
        usableBytes -= 1
        if (usableBytes <= 0) {
          decodedText = ""
          break
        }
      }
    }
    val exceededCharBudget = decodedText.length > maxChars
    return WorkspaceReadHead(
      text = if (exceededCharBudget) decodedText.substring(0, maxChars) else decodedText,
      truncated = exceededCharBudget || usableBytes < totalBytes,
      byteCount = totalBytes,
    )
  }

  internal fun editableFileSizeLimitBytes(): Long =
    config.maxReadBytes * maxReadBudgetBytesPerChar

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
    val head = readFileHeadWithinCharBudget(file, config.maxReadBytes)
    val truncated = head.truncated
    val body = head.text.ifBlank { "<empty file>" }
    return AgentToolResult(
      toolName = "workspace_read_file",
      status = AgentToolResultStatus.SUCCESS,
      content = body,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to toolTargetResolver.displayModelPath(file),
          "byteCount" to head.byteCount.toString(),
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

    val head = readFileHeadWithinCharBudget(file, config.maxReadBytes)
    val fullBody = head.text
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
    val body = rawContent
    val truncated = head.truncated
    val returnedLineCount = if (fullBody.isEmpty()) 0 else selectedLines.size
    return AgentToolResult(
      toolName = "Read",
      status = AgentToolResultStatus.SUCCESS,
      content = body,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "filePath" to toolTargetResolver.displayModelPath(file),
          "byteCount" to head.byteCount.toString(),
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
        toolTargetResolver = toolTargetResolver,
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

    useWorkspaceSearchCandidates(root = searchRoot, fileOnly = true) { candidates ->
      for (file in candidates) {
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

    useWorkspaceSearchCandidates(root = searchRoot, fileOnly = false) { candidates ->
      for (candidate in candidates) {
        if (matcher.matches(toolTargetResolver.displayModelPath(candidate))) {
          matches.add(toolTargetResolver.displayModelPath(candidate))
        }
        if (matches.size >= maxResults) {
          truncated = true
          break
        }
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
      val fileSizeBytes = Files.size(file)
      val editableLimitBytes = editableFileSizeLimitBytes()
      require(fileSizeBytes <= editableLimitBytes) {
        "Edit rejected: ${toolTargetResolver.displayModelPath(file)} is $fileSizeBytes bytes, exceeding the editable file limit of $editableLimitBytes bytes."
      }
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
      val fileSizeBytes = Files.size(file)
      val editableLimitBytes = editableFileSizeLimitBytes()
      require(fileSizeBytes <= editableLimitBytes) {
        "MultiEdit rejected: ${toolTargetResolver.displayModelPath(file)} is $fileSizeBytes bytes, exceeding the editable file limit of $editableLimitBytes bytes."
      }
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
    val rawWaitTimeoutMs = arguments.optionalLong("wait_timeout_ms")
      ?: arguments.optionalLong("timeout_ms")
      ?: DEFAULT_BASH_WAIT_TIMEOUT_MS
    require(rawWaitTimeoutMs >= 0L) { "Bash wait timeout must be >= 0." }
    val waitTimeoutMs = rawWaitTimeoutMs
      .coerceAtMost(com.opencray.runtime.process.MAX_MANAGED_PROCESS_WAIT_TIMEOUT_MS)
    val rawProcessTimeoutMs = arguments.optionalLong("process_timeout_ms")
      ?: DEFAULT_MANAGED_PROCESS_TIMEOUT_MS
    require(rawProcessTimeoutMs > 0L) { "Bash process_timeout_ms must be > 0." }
    val processTimeoutMs = rawProcessTimeoutMs
      .coerceAtMost(com.opencray.runtime.process.MAX_MANAGED_PROCESS_TIMEOUT_MS)
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
    val timeoutMs = (
      arguments.optionalLong("timeout_ms")
        ?: DEFAULT_MANAGED_PROCESS_WAIT_TIMEOUT_MS
      )
      .coerceAtLeast(0L)
      .coerceAtMost(com.opencray.runtime.process.MAX_MANAGED_PROCESS_WAIT_TIMEOUT_MS)
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

  internal fun attachmentArtifactMetadata(path: Path): Map<String, String> {
    val artifact = attachmentArtifactFor(path) ?: return emptyMap()
    return attachmentArtifactsMetadata(listOf(artifact))
  }

  internal fun attachmentArtifactsMetadata(
    artifacts: List<OpenCrayGeneratedWorkspaceArtifact>,
  ): Map<String, String> {
    val descriptors = artifacts.mapNotNull(::attachmentArtifactDescriptor)
    return OpenCrayAttachmentArtifacts.encodeMetadata(config.json, descriptors)
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

  internal fun attachmentArtifactDescriptor(
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

  internal fun attachmentArtifactFor(path: Path): OpenCrayGeneratedWorkspaceArtifact? =
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

  internal fun workspaceImageMimeType(
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

  internal fun workspacePdfMimeType(
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

  internal fun copyIntoWorkspace(source: Path, destination: Path) {
    if (Files.isDirectory(source)) {
      copyDirectoryIntoWorkspace(source = source, destination = destination)
      return
    }
    Files.createDirectories(destination.parent)
    Files.copy(source, destination)
  }

  internal fun copyDirectoryIntoWorkspace(source: Path, destination: Path) {
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
    val toolResult = executionResult.toAgentToolResult(config = config, toolName = "command_exec")
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
    val toolResult = executionResult.toAgentToolResult(config = config, toolName = "python_exec")
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

  private data class ManagedProcessLaunch(
    val command: String,
    val args: List<String>,
    val workingDirectory: Path,
    val intent: ToolRuntimeIntent? = null,
    val metadata: Map<String, String> = emptyMap(),
    val affectedPaths: Map<String, String> = emptyMap(),
    val metadataRequest: ToolMetadataContextRequest = ToolMetadataContextRequest(),
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
    private const val MAX_VIEW_WORKSPACE_IMAGE_BYTES: Long = 20L * 1024L * 1024L
    private const val MAX_VIEW_WORKSPACE_PDF_BYTES: Long = 32L * 1024L * 1024L
  }
}
