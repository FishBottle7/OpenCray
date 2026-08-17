package com.opencray.runtime

import com.opencray.mcp.McpClientExposureReport
import com.opencray.policy.ModePolicy
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.session.SessionSearchToolContext
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.web.HttpUrlWebContentFetcher
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import com.opencray.runtime.web.WebContentFetcher
import com.opencray.runtime.web.WebSearchProvider
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
      val normalizedMetadata = OpenCrayPromptResumeMetadata.sanitizeToolResultMetadata(metadata)
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
          normalizedMetadata.toSortedMap().forEach { (key, value) -> put(key, value) }
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
  val fileMutationLockDirectory: Path? = null,
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
  val mediaArtifactRegistry: OpenCrayMediaArtifactRegistry = NoOpOpenCrayMediaArtifactRegistry,
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
  val sessionSearchToolContext: SessionSearchToolContext? = null,
  val maxSessionSearchResults: Int = 5,
  val maxSessionGetLines: Int = 20,
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
    require(maxSessionSearchResults > 0) { "OpenCrayToolDispatcherConfig maxSessionSearchResults must be > 0." }
    require(maxSessionGetLines > 0) { "OpenCrayToolDispatcherConfig maxSessionGetLines must be > 0." }
  }
}
