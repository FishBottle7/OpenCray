package com.opencray.app.agent

import android.content.Context
import com.opencray.app.ChatSessionLocalStore
import com.opencray.app.PersonalizationPreset
import com.opencray.app.WorkspaceSoulProfile
import com.opencray.persistence.model.ChatPromptTemplateEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal data class AgentBootstrapResult(
  val descriptor: AgentDescriptor,
  val config: AgentConfig,
  val soulProfile: WorkspaceSoulProfile,
  val storagePaths: AgentStoragePaths,
)

internal class AgentBootstrapService(
  private val pathResolver: AgentPathResolver,
  private val registryStore: AgentRegistryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile()),
  private val idFactory: AgentIdFactory = AgentIdFactory(),
  private val soulMapper: AgentDraftSoulMapper = AgentDraftSoulMapper(),
  private val configStore: AgentConfigStore = AgentConfigStore(pathResolver),
  private val soulProfileStore: AgentSoulProfileStore = AgentSoulProfileStore(pathResolver),
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  fun createAgent(request: AgentCreateRequest): AgentBootstrapResult {
    val now = nowEpochMs().coerceAtLeast(0L)
    val agentId = allocateAgentId()
    val sessionId = idFactory.newSessionId()
    val storagePaths = pathResolver.resolve(agentId)
    val descriptor = AgentDescriptor(
      agentId = agentId,
      displayName = normalizeRequired(request.displayName, fallback = "Untitled agent"),
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      presetName = normalizeRequired(request.presetName, fallback = PersonalizationPreset.STEADY.name),
      plasticity = normalizeRequired(request.plasticity, fallback = "medium"),
      activeSessionId = sessionId,
    )
    val config = AgentConfig(
      agentId = agentId,
      displayName = descriptor.displayName,
      presetName = descriptor.presetName,
      plasticity = descriptor.plasticity,
      callsYou = normalizeOptional(request.callsYou),
      addressStyle = normalizeOptional(request.addressStyle),
      mode = normalizeRequired(request.mode, fallback = "full"),
      voiceSummary = normalizeOptional(request.voiceSummary),
      verbosity = normalizeOptional(request.verbosity),
      relationshipStyle = normalizeOptional(request.relationshipStyle),
      riskTolerance = normalizeOptional(request.riskTolerance),
      toolUseBias = normalizeOptional(request.toolUseBias),
      baseDescription = normalizeOptional(request.baseDescription),
      collaborationGuidance = normalizeOptional(request.collaborationGuidance),
      escalationRules = normalizeOptional(request.escalationRules),
      forbiddenBehaviors = normalizeOptional(request.forbiddenBehaviors),
      llm = request.llm?.toNormalizedAgentLlmConfig(),
      avatar = request.avatar?.toNormalizedAgentAvatarConfig(),
      imageReferences = request.imageReferences.map { reference -> reference.toNormalizedAgentImageReferenceConfig() },
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
    )
    val soulProfile = soulMapper.toSoulProfile(request)

    val persistedDescriptor = try {
      pathResolver.ensureAgentDirectories(agentId)
      soulProfileStore.saveSoulProfile(agentId = agentId, profile = soulProfile)
      configStore.save(agentId = agentId, config = config)
      seedChatWorkspace(
        chatLocalStateRoot = storagePaths.chatLocalStateRoot,
        sessionId = sessionId,
        createdAtEpochMs = now,
      )
      val registryRecord = registryStore.create(
        descriptor = descriptor,
        makeActive = request.activateOnCreate || registryStore.activeAgentId() == null,
      )
      registryRecord.agents.firstOrNull { candidate -> candidate.agentId == agentId } ?: descriptor
    } catch (error: Throwable) {
      cleanupFailedBootstrap(storagePaths.agentRoot, error)
      throw error
    }

    return AgentBootstrapResult(
      descriptor = persistedDescriptor,
      config = config,
      soulProfile = soulProfile,
      storagePaths = storagePaths,
    )
  }

  private fun allocateAgentId(): String {
    repeat(MAX_ALLOCATION_ATTEMPTS) {
      val candidate = idFactory.newAgentId()
      val storagePaths = pathResolver.resolve(candidate)
      if (!Files.exists(storagePaths.agentRoot) && registryStore.loadAgent(candidate) == null) {
        return candidate
      }
    }
    throw IllegalStateException("Failed to allocate a unique agent id after $MAX_ALLOCATION_ATTEMPTS attempts.")
  }

  private fun seedChatWorkspace(
    chatLocalStateRoot: Path,
    sessionId: String,
    createdAtEpochMs: Long,
  ) {
    val workspaceStore = JsonFileChatWorkspaceStore(chatLocalStateRoot.toFile())
    if (workspaceStore.load() != null) {
      return
    }
    val workspace = ChatWorkspaceRecord(
      sessions = listOf(
        ChatTranscriptSessionEntry(
          sessionId = sessionId,
          title = ChatSessionLocalStore.DEFAULT_SESSION_TITLE,
          createdAtEpochMs = createdAtEpochMs,
          updatedAtEpochMs = createdAtEpochMs,
          messages = listOf(
            ChatTranscriptMessageEntry(
              messageId = "system-$sessionId-seed",
              role = ChatTranscriptRole.SYSTEM,
              promptTemplateRefId = ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_ID,
              createdAtEpochMs = createdAtEpochMs,
            ),
          ),
        ),
      ),
      promptTemplates = listOf(
        ChatPromptTemplateEntry(
          templateId = ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_ID,
          label = "Default system prompt",
          body = ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_VALUE,
          createdAtEpochMs = createdAtEpochMs,
        ),
      ),
      activeSessionId = sessionId,
      recordVersion = 2L,
      createdAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = createdAtEpochMs,
    )
    workspaceStore.save(workspace)
  }

  private fun cleanupFailedBootstrap(
    agentRoot: Path,
    cause: Throwable,
  ) {
    val agentsRoot = pathResolver.agentsRoot()
    val normalizedAgentRoot = agentRoot.toAbsolutePath().normalize()
    if (!normalizedAgentRoot.startsWith(agentsRoot) || !Files.exists(normalizedAgentRoot)) {
      return
    }
    runCatching {
      Files.walk(normalizedAgentRoot).use { stream ->
        stream
          .sorted(Comparator.reverseOrder())
          .forEach { path -> Files.deleteIfExists(path) }
      }
    }.exceptionOrNull()?.let { cleanupError ->
      cause.addSuppressed(cleanupError)
    }
  }

  private fun normalizeRequired(
    value: String,
    fallback: String,
  ): String = value.trim().ifBlank { fallback }

  private fun normalizeOptional(value: String?): String? =
    value?.trim()?.takeIf(String::isNotEmpty)

  private fun AgentLlmConfig.toNormalizedAgentLlmConfig(): AgentLlmConfig = copy(
    provider = provider.trim(),
    protocol = protocol.trim(),
    baseUrl = baseUrl?.trim()?.takeIf(String::isNotEmpty),
    apiKey = apiKey?.trim()?.takeIf(String::isNotEmpty),
    model = model.trim(),
    reasoningEffort = reasoningEffort?.trim()?.takeIf(String::isNotEmpty),
  )

  private fun AgentAvatarConfig.toNormalizedAgentAvatarConfig(): AgentAvatarConfig = copy(
    source = source.trim(),
    settingsAssetId = settingsAssetId?.trim()?.takeIf(String::isNotEmpty),
  )

  private fun AgentImageReferenceConfig.toNormalizedAgentImageReferenceConfig(): AgentImageReferenceConfig = copy(
    referenceId = referenceId.trim(),
    label = label.trim(),
    settingsAssetId = settingsAssetId?.trim()?.takeIf(String::isNotEmpty),
  )

  companion object {
    private const val MAX_ALLOCATION_ATTEMPTS = 32

    fun fromContext(context: Context): AgentBootstrapService {
      val pathResolver = AgentPathResolver.fromContext(context)
      return AgentBootstrapService(
        pathResolver = pathResolver,
        registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile()),
      )
    }
  }
}
