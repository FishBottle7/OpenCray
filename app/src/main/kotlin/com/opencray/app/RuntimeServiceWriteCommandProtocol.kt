package com.opencray.app

import com.opencray.persistence.PersistenceJson
import com.opencray.runtime.OpenCrayFinalAttachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val RUNTIME_SERVICE_WRITE_COMMAND_SCHEMA_VERSION: Int = 1
internal const val RUNTIME_SERVICE_WRITE_COMMAND_MAX_CHARS: Int = 256_000
internal const val RUNTIME_SERVICE_WRITE_RESULT_MAX_CHARS: Int = 256_000

internal object RuntimeServiceWriteCommandDomains {
  const val CHAT: String = "chat"
  const val SKILLS: String = "skills"
  const val SETTINGS: String = "settings"
}

@Serializable
internal data class RuntimeServiceWriteCommandEnvelope(
  val schemaVersion: Int = RUNTIME_SERVICE_WRITE_COMMAND_SCHEMA_VERSION,
  val domain: String,
  val method: String,
  val route: String,
  val queryParameters: Map<String, String> = emptyMap(),
  val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class RuntimeServiceWriteResultEnvelope(
  val schemaVersion: Int = RUNTIME_SERVICE_WRITE_COMMAND_SCHEMA_VERSION,
  val domain: String,
  val resultType: String,
  val payload: JsonElement = JsonNull,
)

internal sealed interface DecodedRuntimeServiceWriteCommand {
  data class Chat(
    val command: OpenCrayChatWriteCommand,
  ) : DecodedRuntimeServiceWriteCommand

  data class Skills(
    val command: OpenCraySkillsWriteCommand,
  ) : DecodedRuntimeServiceWriteCommand

  data class Settings(
    val command: OpenCraySettingsWriteCommand,
  ) : DecodedRuntimeServiceWriteCommand
}

internal fun runtimeServiceWriteCommandEnvelope(
  command: OpenCrayChatWriteCommand,
): RuntimeServiceWriteCommandEnvelope = when (command) {
  OpenCrayChatWriteCommand.RefreshSandboxSessionInfo -> chatCommandEnvelope(
    route = "v1/refresh_sandbox_session_info",
  )

  is OpenCrayChatWriteCommand.ApplyMemoryDebugAction -> chatCommandEnvelope(
    route = "v1/memory_debug_action",
    payload = buildJsonObject {
      put("recordId", command.recordId)
      put("actionId", command.actionId)
    },
  )

  OpenCrayChatWriteCommand.CreateChatSession -> chatCommandEnvelope(
    route = "v1/create_chat_session",
  )

  is OpenCrayChatWriteCommand.CopyChatSession -> chatCommandEnvelope(
    route = "v1/copy_chat_session",
    payload = stringPayload("sessionId", command.sessionId),
  )

  is OpenCrayChatWriteCommand.DeleteChatSession -> chatCommandEnvelope(
    route = "v1/delete_chat_session",
    payload = stringPayload("sessionId", command.sessionId),
  )

  is OpenCrayChatWriteCommand.SelectChatSession -> chatCommandEnvelope(
    route = "v1/select_chat_session",
    payload = stringPayload("sessionId", command.sessionId),
  )

  is OpenCrayChatWriteCommand.BranchChatSessionFromMessage -> chatCommandEnvelope(
    route = "v1/branch_chat_session_from_message",
    payload = sessionMessagePayload(command.sessionId, command.messageId),
  )

  is OpenCrayChatWriteCommand.DeleteChatMessage -> chatCommandEnvelope(
    route = "v1/delete_chat_message",
    payload = sessionMessagePayload(command.sessionId, command.messageId),
  )

  is OpenCrayChatWriteCommand.RecallChatMessage -> chatCommandEnvelope(
    route = "v1/recall_chat_message",
    payload = sessionMessagePayload(command.sessionId, command.messageId),
  )

  is OpenCrayChatWriteCommand.SubmitChatMessage -> chatCommandEnvelope(
    route = "v1/submit_chat_message",
    payload = buildJsonObject {
      put("text", command.text)
      put("attachments", buildJsonArray {
        command.attachments.forEach { attachment ->
          add(attachment.toWireJsonObject())
        }
      })
    },
  )

  is OpenCrayChatWriteCommand.ApproveChatApproval -> chatCommandEnvelope(
    route = "v1/approve_chat_approval",
    payload = approvalPayload(command.taskIdOrRunId),
  )

  is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> chatCommandEnvelope(
    route = "v1/approve_chat_approval_for_session",
    payload = approvalPayload(command.taskIdOrRunId),
  )

  is OpenCrayChatWriteCommand.RejectChatApproval -> chatCommandEnvelope(
    route = "v1/reject_chat_approval",
    payload = approvalPayload(command.taskIdOrRunId),
  )

  is OpenCrayChatWriteCommand.InterruptChatRun -> chatCommandEnvelope(
    route = "v1/interrupt_chat_run",
    payload = approvalPayload(command.taskIdOrRunId),
  )

  is OpenCrayChatWriteCommand.RetryChatRun -> chatCommandEnvelope(
    route = "v1/retry_chat_run",
    payload = approvalPayload(command.taskIdOrRunId),
  )
}

internal fun runtimeServiceWriteCommandEnvelope(
  command: OpenCraySkillsWriteCommand,
): RuntimeServiceWriteCommandEnvelope = when (command) {
  is OpenCraySkillsWriteCommand.SetSkillEnabled -> skillsCommandEnvelope(
    route = "v1/set_skill_enabled",
    payload = buildJsonObject {
      put("skillId", command.skillId)
      put("enabled", command.enabled)
    },
  )

  is OpenCraySkillsWriteCommand.InstallSuggestedSkill -> skillsCommandEnvelope(
    route = "v1/install_suggested_skill",
    payload = stringPayload("skillId", command.skillId),
  )

  is OpenCraySkillsWriteCommand.InstallSkillSource -> skillsCommandEnvelope(
    route = "v1/install_skill_source",
    payload = buildJsonObject {
      put("sourceRef", command.sourceRef)
      put("selectedSkillName", command.selectedSkillName)
    },
  )

  is OpenCraySkillsWriteCommand.InstallSkillSourceBatch -> skillsCommandEnvelope(
    route = "v1/install_skill_source_batch",
    payload = buildJsonObject {
      put("sourceRef", command.sourceRef)
      put("selectedSkillNames", buildJsonArray {
        command.selectedSkillNames.forEach { skillName ->
          add(JsonPrimitive(skillName))
        }
      })
    },
  )

  is OpenCraySkillsWriteCommand.InspectSkillSource -> skillsCommandEnvelope(
    route = "v1/inspect_skill_source",
    payload = stringPayload("sourceRef", command.sourceRef),
  )

  is OpenCraySkillsWriteCommand.DeleteInstalledSkill -> skillsCommandEnvelope(
    route = "v1/delete_installed_skill",
    payload = stringPayload("skillId", command.skillId),
  )

  OpenCraySkillsWriteCommand.RefreshSkills -> skillsCommandEnvelope(
    route = "v1/refresh_skills",
  )

  is OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates -> skillsCommandEnvelope(
    method = "GET",
    route = "v1/check_installed_skill_updates",
    queryParameters = mapOf("skillId" to command.skillId),
  )

  is OpenCraySkillsWriteCommand.UpdateInstalledSkill -> skillsCommandEnvelope(
    route = "v1/update_installed_skill",
    payload = stringPayload("skillId", command.skillId),
  )

  is OpenCraySkillsWriteCommand.ActivateSkillsInstallSource -> skillsCommandEnvelope(
    route = "v1/activate_skills_install_source",
    payload = stringPayload("sourceId", command.sourceId),
  )
}

internal fun runtimeServiceWriteCommandEnvelope(
  command: OpenCraySettingsWriteCommand,
): RuntimeServiceWriteCommandEnvelope = when (command) {
  is OpenCraySettingsWriteCommand.SaveNotificationSettings -> settingsCommandEnvelope(
    route = "v1/save_notification_settings",
    payload = command.payload.toWireJsonObject(),
  )

  is OpenCraySettingsWriteCommand.UpdateScheduledTaskEnabled -> settingsCommandEnvelope(
    route = "v1/update_scheduled_task_enabled",
    payload = buildJsonObject {
      put("scheduleId", command.scheduleId)
      put("enabled", command.enabled)
    },
  )

  is OpenCraySettingsWriteCommand.RunScheduledTaskNow -> settingsCommandEnvelope(
    route = "v1/run_scheduled_task_now",
    payload = stringPayload("scheduleId", command.scheduleId),
  )

  is OpenCraySettingsWriteCommand.SnoozeScheduledTask -> settingsCommandEnvelope(
    route = "v1/snooze_scheduled_task",
    payload = buildJsonObject {
      put("scheduleId", command.scheduleId)
      put("durationMinutes", command.durationMinutes)
    },
  )

  is OpenCraySettingsWriteCommand.PerformStrongBackgroundAction -> settingsCommandEnvelope(
    route = "v1/perform_strong_background_action",
    payload = stringPayload("actionId", command.actionId),
  )

  is OpenCraySettingsWriteCommand.SaveNetworkSearchConfig -> settingsCommandEnvelope(
    route = "v1/save_network_search_config",
    payload = buildJsonObject {
      put("slots", command.slots.toWireJsonElement())
    },
  )

  is OpenCraySettingsWriteCommand.SaveMediaSpeechConfig -> settingsCommandEnvelope(
    route = "v1/save_media_speech_config",
    payload = command.payload.toWireJsonObject(),
  )

  is OpenCraySettingsWriteCommand.SaveSandboxSettings -> settingsCommandEnvelope(
    route = "v1/save_sandbox_settings",
    payload = command.payload.toWireJsonObject(),
  )

  is OpenCraySettingsWriteCommand.SaveLlmConfig -> settingsCommandEnvelope(
    route = "v1/save_llm_config",
    payload = buildJsonObject {
      put("enabled", command.enabled)
      putIfNotNull("streamingEnabled", command.streamingEnabled)
      put("providerMode", command.providerMode)
      put("providerId", command.providerId)
      put("selectedProviderOptionId", command.selectedProviderOptionId)
      put("protocol", command.protocol)
      put("providerName", command.providerName)
      put("providerNotes", command.providerNotes)
      put("baseUrl", command.baseUrl)
      put("apiKey", command.apiKey)
      put("model", command.model)
      put("reasoningEffort", command.reasoningEffort)
      put("systemPrompt", command.systemPrompt)
      putIfNotNull("openAiPromptCacheKeyStrategy", command.openAiPromptCacheKeyStrategy)
      putIfNotNull("openAiPromptCacheRetention", command.openAiPromptCacheRetention)
      putIfNotNull("anthropicPromptCachingEnabled", command.anthropicPromptCachingEnabled)
      putIfNotNull("anthropicPromptCacheTtl", command.anthropicPromptCacheTtl)
      putIfNotNull("contextBudgetPreset", command.contextBudgetPreset)
      putIfNotNull("contextBudgetReservedOutputTokens", command.contextBudgetReservedOutputTokens)
      putIfNotNull("contextBudgetSafetyMarginTokens", command.contextBudgetSafetyMarginTokens)
      putIfNotNull("contextBudgetEffectiveInputPercent", command.contextBudgetEffectiveInputPercent)
      putIfNotNull("contextWindowTokensOverride", command.contextWindowTokensOverride)
      put("selectedOnDeviceModelId", command.selectedOnDeviceModelId)
      put("onDeviceMaxContextWindow", command.onDeviceMaxContextWindow)
      put("onDeviceMaxTokens", command.onDeviceMaxTokens)
      put("onDeviceTopK", command.onDeviceTopK)
      put("onDeviceTopP", command.onDeviceTopP)
      put("onDeviceTemperature", command.onDeviceTemperature)
      put("onDeviceAccelerator", command.onDeviceAccelerator)
      put("onDeviceThinkingEnabled", command.onDeviceThinkingEnabled)
      put("onDeviceLiteModeEnabled", command.onDeviceLiteModeEnabled)
    },
  )

  is OpenCraySettingsWriteCommand.SaveCustomLlmProvider -> settingsCommandEnvelope(
    route = "v1/save_custom_llm_provider",
    payload = buildJsonObject {
      put("selectedProviderOptionId", command.selectedProviderOptionId)
      putIfNotNull("streamingEnabled", command.streamingEnabled)
      put("protocol", command.protocol)
      put("providerName", command.providerName)
      put("providerNotes", command.providerNotes)
      put("baseUrl", command.baseUrl)
      put("apiKey", command.apiKey)
      put("model", command.model)
      put("reasoningEffort", command.reasoningEffort)
      put("systemPrompt", command.systemPrompt)
      putIfNotNull("openAiPromptCacheKeyStrategy", command.openAiPromptCacheKeyStrategy)
      putIfNotNull("openAiPromptCacheRetention", command.openAiPromptCacheRetention)
      putIfNotNull("anthropicPromptCachingEnabled", command.anthropicPromptCachingEnabled)
      putIfNotNull("anthropicPromptCacheTtl", command.anthropicPromptCacheTtl)
      putIfNotNull("contextBudgetPreset", command.contextBudgetPreset)
      putIfNotNull("contextBudgetReservedOutputTokens", command.contextBudgetReservedOutputTokens)
      putIfNotNull("contextBudgetSafetyMarginTokens", command.contextBudgetSafetyMarginTokens)
      putIfNotNull("contextBudgetEffectiveInputPercent", command.contextBudgetEffectiveInputPercent)
      putIfNotNull("contextWindowTokensOverride", command.contextWindowTokensOverride)
    },
  )

  is OpenCraySettingsWriteCommand.ValidateLlmConfig -> settingsCommandEnvelope(
    route = "v1/validate_llm_config",
    payload = buildJsonObject {
      put("providerId", command.providerId)
      put("protocol", command.protocol)
      put("baseUrl", command.baseUrl)
      put("apiKey", command.apiKey)
      put("model", command.model)
      put("reasoningEffort", command.reasoningEffort)
      putIfNotNull("contextWindowTokensOverride", command.contextWindowTokensOverride)
    },
  )

  is OpenCraySettingsWriteCommand.DownloadOnDeviceLlmModel -> settingsCommandEnvelope(
    route = "v1/download_on_device_llm_model",
    payload = stringPayload("modelId", command.modelId),
  )

  is OpenCraySettingsWriteCommand.CancelOnDeviceLlmModelDownload -> settingsCommandEnvelope(
    route = "v1/cancel_on_device_llm_model_download",
    payload = stringPayload("modelId", command.modelId),
  )

  is OpenCraySettingsWriteCommand.DeleteOnDeviceLlmModel -> settingsCommandEnvelope(
    route = "v1/delete_on_device_llm_model",
    payload = stringPayload("modelId", command.modelId),
  )

  is OpenCraySettingsWriteCommand.SavePersonalizationConfig -> settingsCommandEnvelope(
    route = "v1/save_personalization_config",
    payload = buildJsonObject {
      put("presetId", command.presetId)
      put("customLabel", command.customLabel)
      put("customGuidance", command.customGuidance)
    },
  )

  is OpenCraySettingsWriteCommand.SetAppLanguage -> settingsCommandEnvelope(
    route = "v1/set_app_language",
    payload = stringPayload("languageId", command.languageId),
  )

  is OpenCraySettingsWriteCommand.RunPersonalizationReset -> settingsCommandEnvelope(
    route = "v1/run_personalization_reset",
    payload = stringPayload("scopeId", command.scopeId),
  )

  is OpenCraySettingsWriteCommand.SetMcpMasterEnabled -> settingsCommandEnvelope(
    route = "v1/set_mcp_master_enabled",
    payload = buildJsonObject { put("enabled", command.enabled) },
  )

  is OpenCraySettingsWriteCommand.SetMcpServerEnabled -> settingsCommandEnvelope(
    route = "v1/set_mcp_server_enabled",
    payload = buildJsonObject {
      put("serverId", command.serverId)
      put("enabled", command.enabled)
    },
  )

  is OpenCraySettingsWriteCommand.SaveSafetySettings -> settingsCommandEnvelope(
    route = "v1/save_safety_settings",
    payload = buildJsonObject {
      put("automationModeId", command.automationModeId)
      put("rollbackJournalEnabled", command.rollbackJournalEnabled)
      put("maxFilesPerBatch", command.maxFilesPerBatch)
      put("maxAgentTurns", command.maxAgentTurns)
      put("maxToolCalls", command.maxToolCalls)
      put("undoWindowHours", command.undoWindowHours)
      put("fileChangesPolicyId", command.fileChangesPolicyId)
      put("fileDeletesPolicyId", command.fileDeletesPolicyId)
      put("shellCommandsPolicyId", command.shellCommandsPolicyId)
      put("externalAccessModeId", command.externalAccessModeId)
      put("photoLibraryEnabled", command.photoLibraryEnabled)
      put("downloadsEnabled", command.downloadsEnabled)
      put("documentsEnabled", command.documentsEnabled)
      put("recordingsEnabled", command.recordingsEnabled)
      put("workspaceAccessProfileId", command.workspaceAccessProfileId)
      put("readOnlyOutsideWorkspace", command.readOnlyOutsideWorkspace)
      put("liveContextModeId", command.liveContextModeId)
      put("memoryToolsEnabled", command.memoryToolsEnabled)
      putIfNotNull("subAgentContextDefaultModeId", command.subAgentContextDefaultModeId)
      put("subAgentContextProfileOverrides", command.subAgentContextProfileOverrides.toWireJsonElement())
    },
  )
}

internal fun encodeRuntimeServiceWriteCommand(
  envelope: RuntimeServiceWriteCommandEnvelope,
): String = PersistenceJson.instance.encodeToString(
  serializer = RuntimeServiceWriteCommandEnvelope.serializer(),
  value = envelope,
)

internal fun decodeRuntimeServiceWriteCommand(
  encoded: String?,
): DecodedRuntimeServiceWriteCommand? = runCatching {
  val envelope = PersistenceJson.instance.decodeFromString(
    deserializer = RuntimeServiceWriteCommandEnvelope.serializer(),
    string = encoded.orEmpty(),
  )
  require(envelope.schemaVersion == RUNTIME_SERVICE_WRITE_COMMAND_SCHEMA_VERSION) {
    "Unsupported runtime service write command schema '${envelope.schemaVersion}'."
  }
  when (envelope.domain) {
    RuntimeServiceWriteCommandDomains.CHAT -> DecodedRuntimeServiceWriteCommand.Chat(
      envelope.decodeChatCommand(),
    )

    RuntimeServiceWriteCommandDomains.SKILLS -> DecodedRuntimeServiceWriteCommand.Skills(
      envelope.decodeSkillsCommand(),
    )

    RuntimeServiceWriteCommandDomains.SETTINGS -> DecodedRuntimeServiceWriteCommand.Settings(
      envelope.decodeSettingsCommand(),
    )

    else -> error("Unsupported runtime service write command domain '${envelope.domain}'.")
  }
}.getOrNull()

internal fun encodeRuntimeServiceWriteResult(
  result: OpenCrayChatWriteDispatchResult,
): String = encodeResultEnvelope(
  when (result) {
    OpenCrayChatWriteDispatchResult.Completed -> RuntimeServiceWriteResultEnvelope(
      domain = RuntimeServiceWriteCommandDomains.CHAT,
      resultType = "completed",
    )

    is OpenCrayChatWriteDispatchResult.Payload -> RuntimeServiceWriteResultEnvelope(
      domain = RuntimeServiceWriteCommandDomains.CHAT,
      resultType = "payload",
      payload = result.value.toWireJsonElement(),
    )
  },
)

internal fun encodeRuntimeServiceWriteResult(
  result: OpenCraySkillsWriteDispatchResult,
): String = encodeResultEnvelope(
  when (result) {
    OpenCraySkillsWriteDispatchResult.Completed -> RuntimeServiceWriteResultEnvelope(
      domain = RuntimeServiceWriteCommandDomains.SKILLS,
      resultType = "completed",
    )

    is OpenCraySkillsWriteDispatchResult.Message -> RuntimeServiceWriteResultEnvelope(
      domain = RuntimeServiceWriteCommandDomains.SKILLS,
      resultType = "message",
      payload = JsonPrimitive(result.value),
    )

    is OpenCraySkillsWriteDispatchResult.Payload -> RuntimeServiceWriteResultEnvelope(
      domain = RuntimeServiceWriteCommandDomains.SKILLS,
      resultType = "payload",
      payload = result.value.toWireJsonElement(),
    )
  },
)

internal fun encodeRuntimeServiceWriteResult(
  result: OpenCraySettingsWriteDispatchResult,
): String = encodeResultEnvelope(
  when (result) {
    is OpenCraySettingsWriteDispatchResult.Payload -> RuntimeServiceWriteResultEnvelope(
      domain = RuntimeServiceWriteCommandDomains.SETTINGS,
      resultType = "payload",
      payload = result.value.toWireJsonElement(),
    )
  },
)

internal fun decodeRuntimeServiceChatWriteResult(
  encoded: String?,
): OpenCrayChatWriteDispatchResult? = decodeResultEnvelope(encoded) { envelope ->
  require(envelope.domain == RuntimeServiceWriteCommandDomains.CHAT)
  when (envelope.resultType) {
    "completed" -> OpenCrayChatWriteDispatchResult.Completed
    "payload" -> OpenCrayChatWriteDispatchResult.Payload(envelope.payload.toNullableMap())
    else -> error("Unsupported chat write result type '${envelope.resultType}'.")
  }
}

internal fun decodeRuntimeServiceSkillsWriteResult(
  encoded: String?,
): OpenCraySkillsWriteDispatchResult? = decodeResultEnvelope(encoded) { envelope ->
  require(envelope.domain == RuntimeServiceWriteCommandDomains.SKILLS)
  when (envelope.resultType) {
    "completed" -> OpenCraySkillsWriteDispatchResult.Completed
    "message" -> OpenCraySkillsWriteDispatchResult.Message(envelope.payload.requireStringValue())
    "payload" -> OpenCraySkillsWriteDispatchResult.Payload(envelope.payload.requireMapValue())
    else -> error("Unsupported skills write result type '${envelope.resultType}'.")
  }
}

internal fun decodeRuntimeServiceSettingsWriteResult(
  encoded: String?,
): OpenCraySettingsWriteDispatchResult? = decodeResultEnvelope(encoded) { envelope ->
  require(envelope.domain == RuntimeServiceWriteCommandDomains.SETTINGS)
  when (envelope.resultType) {
    "payload" -> OpenCraySettingsWriteDispatchResult.Payload(envelope.payload.requireMapValue())
    else -> error("Unsupported settings write result type '${envelope.resultType}'.")
  }
}

internal fun decodeLoopbackRuntimeServiceWriteResult(
  command: OpenCrayChatWriteCommand,
  payload: Any?,
): OpenCrayChatWriteDispatchResult = when (command) {
  is OpenCrayChatWriteCommand.ApplyMemoryDebugAction ->
    OpenCrayChatWriteDispatchResult.Payload(payload.requireMapPayload())

  is OpenCrayChatWriteCommand.SubmitChatMessage ->
    OpenCrayChatWriteDispatchResult.Payload(payload?.requireMapPayload())

  else -> OpenCrayChatWriteDispatchResult.Completed
}

internal fun decodeLoopbackRuntimeServiceWriteResult(
  command: OpenCraySkillsWriteCommand,
  payload: Any?,
): OpenCraySkillsWriteDispatchResult = when (command) {
  is OpenCraySkillsWriteCommand.SetSkillEnabled -> OpenCraySkillsWriteDispatchResult.Completed
  is OpenCraySkillsWriteCommand.InspectSkillSource ->
    OpenCraySkillsWriteDispatchResult.Payload(payload.requireMapPayload())

  else -> OpenCraySkillsWriteDispatchResult.Message(payload.requireStringPayload())
}

internal fun decodeLoopbackRuntimeServiceWriteResult(
  command: OpenCraySettingsWriteCommand,
  payload: Any?,
): OpenCraySettingsWriteDispatchResult = OpenCraySettingsWriteDispatchResult.Payload(
  payload.requireMapPayload(),
)

private fun RuntimeServiceWriteCommandEnvelope.decodeChatCommand(): OpenCrayChatWriteCommand =
  when (normalizedMethodAndRoute()) {
    "POST" to "v1/refresh_sandbox_session_info" ->
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo

    "POST" to "v1/memory_debug_action" -> OpenCrayChatWriteCommand.ApplyMemoryDebugAction(
      recordId = payload.requireString("recordId"),
      actionId = payload.requireString("actionId"),
    )

    "POST" to "v1/create_chat_session" -> OpenCrayChatWriteCommand.CreateChatSession
    "POST" to "v1/copy_chat_session" -> OpenCrayChatWriteCommand.CopyChatSession(
      payload.requireString("sessionId"),
    )

    "POST" to "v1/delete_chat_session" -> OpenCrayChatWriteCommand.DeleteChatSession(
      payload.requireString("sessionId"),
    )

    "POST" to "v1/select_chat_session" -> OpenCrayChatWriteCommand.SelectChatSession(
      payload.requireString("sessionId"),
    )

    "POST" to "v1/branch_chat_session_from_message" ->
      OpenCrayChatWriteCommand.BranchChatSessionFromMessage(
        sessionId = payload.requireString("sessionId"),
        messageId = payload.requireString("messageId"),
      )

    "POST" to "v1/delete_chat_message" -> OpenCrayChatWriteCommand.DeleteChatMessage(
      sessionId = payload.requireString("sessionId"),
      messageId = payload.requireString("messageId"),
    )

    "POST" to "v1/recall_chat_message" -> OpenCrayChatWriteCommand.RecallChatMessage(
      sessionId = payload.requireString("sessionId"),
      messageId = payload.requireString("messageId"),
    )

    "POST" to "v1/submit_chat_message" -> OpenCrayChatWriteCommand.SubmitChatMessage(
      text = payload.requireString("text"),
      attachments = payload.requireArray("attachments").map { element ->
        (element as? JsonObject)?.toFinalAttachment()
          ?: error("Runtime service chat attachment must be an object.")
      },
    )

    "POST" to "v1/approve_chat_approval" -> OpenCrayChatWriteCommand.ApproveChatApproval(
      payload.requireTaskOrRunId(),
    )

    "POST" to "v1/approve_chat_approval_for_session" ->
      OpenCrayChatWriteCommand.ApproveChatApprovalForSession(payload.requireTaskOrRunId())

    "POST" to "v1/reject_chat_approval" -> OpenCrayChatWriteCommand.RejectChatApproval(
      payload.requireTaskOrRunId(),
    )

    "POST" to "v1/interrupt_chat_run" -> OpenCrayChatWriteCommand.InterruptChatRun(
      payload.requireTaskOrRunId(),
    )

    "POST" to "v1/retry_chat_run" -> OpenCrayChatWriteCommand.RetryChatRun(
      payload.requireTaskOrRunId(),
    )

    else -> error("Unsupported chat write command route '$method $route'.")
  }

private fun RuntimeServiceWriteCommandEnvelope.decodeSkillsCommand(): OpenCraySkillsWriteCommand =
  when (normalizedMethodAndRoute()) {
    "POST" to "v1/set_skill_enabled" -> OpenCraySkillsWriteCommand.SetSkillEnabled(
      skillId = payload.requireString("skillId"),
      enabled = payload.requireBoolean("enabled"),
    )

    "POST" to "v1/install_suggested_skill" -> OpenCraySkillsWriteCommand.InstallSuggestedSkill(
      payload.requireString("skillId"),
    )

    "POST" to "v1/install_skill_source" -> OpenCraySkillsWriteCommand.InstallSkillSource(
      sourceRef = payload.requireString("sourceRef"),
      selectedSkillName = payload.requireString("selectedSkillName"),
    )

    "POST" to "v1/install_skill_source_batch" ->
      OpenCraySkillsWriteCommand.InstallSkillSourceBatch(
        sourceRef = payload.requireString("sourceRef"),
        selectedSkillNames = payload.requireStringList("selectedSkillNames"),
      )

    "POST" to "v1/inspect_skill_source" -> OpenCraySkillsWriteCommand.InspectSkillSource(
      payload.requireString("sourceRef"),
    )

    "POST" to "v1/delete_installed_skill" -> OpenCraySkillsWriteCommand.DeleteInstalledSkill(
      payload.requireString("skillId"),
    )

    "POST" to "v1/refresh_skills" -> OpenCraySkillsWriteCommand.RefreshSkills
    "GET" to "v1/check_installed_skill_updates" ->
      OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates(
        queryParameters.requireQueryParameter("skillId"),
      )

    "POST" to "v1/update_installed_skill" -> OpenCraySkillsWriteCommand.UpdateInstalledSkill(
      payload.requireString("skillId"),
    )

    "POST" to "v1/activate_skills_install_source" ->
      OpenCraySkillsWriteCommand.ActivateSkillsInstallSource(payload.requireString("sourceId"))

    else -> error("Unsupported skills write command route '$method $route'.")
  }

private fun RuntimeServiceWriteCommandEnvelope.decodeSettingsCommand(): OpenCraySettingsWriteCommand =
  when (normalizedMethodAndRoute()) {
    "POST" to "v1/save_notification_settings" ->
      OpenCraySettingsWriteCommand.SaveNotificationSettings(payload.toKotlinMap())

    "POST" to "v1/update_scheduled_task_enabled" ->
      OpenCraySettingsWriteCommand.UpdateScheduledTaskEnabled(
        scheduleId = payload.requireString("scheduleId"),
        enabled = payload.requireBoolean("enabled"),
      )

    "POST" to "v1/run_scheduled_task_now" ->
      OpenCraySettingsWriteCommand.RunScheduledTaskNow(payload.requireString("scheduleId"))

    "POST" to "v1/snooze_scheduled_task" ->
      OpenCraySettingsWriteCommand.SnoozeScheduledTask(
        scheduleId = payload.requireString("scheduleId"),
        durationMinutes = payload.requireInt("durationMinutes"),
      )

    "POST" to "v1/perform_strong_background_action" ->
      OpenCraySettingsWriteCommand.PerformStrongBackgroundAction(payload.requireString("actionId"))

    "POST" to "v1/save_network_search_config" ->
      OpenCraySettingsWriteCommand.SaveNetworkSearchConfig(
        payload.requireMapList("slots"),
      )

    "POST" to "v1/save_media_speech_config" ->
      OpenCraySettingsWriteCommand.SaveMediaSpeechConfig(payload.toKotlinMap())

    "POST" to "v1/save_sandbox_settings" ->
      OpenCraySettingsWriteCommand.SaveSandboxSettings(payload.toKotlinMap())

    "POST" to "v1/save_llm_config" -> OpenCraySettingsWriteCommand.SaveLlmConfig(
      enabled = payload.requireBoolean("enabled"),
      streamingEnabled = payload.optionalBoolean("streamingEnabled"),
      providerMode = payload.requireString("providerMode"),
      providerId = payload.requireString("providerId"),
      selectedProviderOptionId = payload.requireString("selectedProviderOptionId"),
      protocol = payload.requireString("protocol"),
      providerName = payload.requireString("providerName"),
      providerNotes = payload.requireString("providerNotes"),
      baseUrl = payload.requireString("baseUrl"),
      apiKey = payload.requireString("apiKey"),
      model = payload.requireString("model"),
      reasoningEffort = payload.requireString("reasoningEffort"),
      systemPrompt = payload.requireString("systemPrompt"),
      openAiPromptCacheKeyStrategy = payload.optionalString("openAiPromptCacheKeyStrategy"),
      openAiPromptCacheRetention = payload.optionalString("openAiPromptCacheRetention"),
      anthropicPromptCachingEnabled = payload.optionalBoolean("anthropicPromptCachingEnabled"),
      anthropicPromptCacheTtl = payload.optionalString("anthropicPromptCacheTtl"),
      contextBudgetPreset = payload.optionalString("contextBudgetPreset"),
      contextBudgetReservedOutputTokens = payload.optionalInt("contextBudgetReservedOutputTokens"),
      contextBudgetSafetyMarginTokens = payload.optionalInt("contextBudgetSafetyMarginTokens"),
      contextBudgetEffectiveInputPercent = payload.optionalDouble("contextBudgetEffectiveInputPercent"),
      contextWindowTokensOverride = payload.optionalInt("contextWindowTokensOverride"),
      selectedOnDeviceModelId = payload.requireString("selectedOnDeviceModelId"),
      onDeviceMaxContextWindow = payload.requireInt("onDeviceMaxContextWindow"),
      onDeviceMaxTokens = payload.requireInt("onDeviceMaxTokens"),
      onDeviceTopK = payload.requireInt("onDeviceTopK"),
      onDeviceTopP = payload.requireDouble("onDeviceTopP"),
      onDeviceTemperature = payload.requireDouble("onDeviceTemperature"),
      onDeviceAccelerator = payload.requireString("onDeviceAccelerator"),
      onDeviceThinkingEnabled = payload.requireBoolean("onDeviceThinkingEnabled"),
      onDeviceLiteModeEnabled = payload.requireBoolean("onDeviceLiteModeEnabled"),
    )

    "POST" to "v1/save_custom_llm_provider" ->
      OpenCraySettingsWriteCommand.SaveCustomLlmProvider(
        selectedProviderOptionId = payload.requireString("selectedProviderOptionId"),
        streamingEnabled = payload.optionalBoolean("streamingEnabled"),
        protocol = payload.requireString("protocol"),
        providerName = payload.requireString("providerName"),
        providerNotes = payload.requireString("providerNotes"),
        baseUrl = payload.requireString("baseUrl"),
        apiKey = payload.requireString("apiKey"),
        model = payload.requireString("model"),
        reasoningEffort = payload.requireString("reasoningEffort"),
        systemPrompt = payload.requireString("systemPrompt"),
        openAiPromptCacheKeyStrategy = payload.optionalString("openAiPromptCacheKeyStrategy"),
        openAiPromptCacheRetention = payload.optionalString("openAiPromptCacheRetention"),
        anthropicPromptCachingEnabled = payload.optionalBoolean("anthropicPromptCachingEnabled"),
        anthropicPromptCacheTtl = payload.optionalString("anthropicPromptCacheTtl"),
        contextBudgetPreset = payload.optionalString("contextBudgetPreset"),
        contextBudgetReservedOutputTokens = payload.optionalInt("contextBudgetReservedOutputTokens"),
        contextBudgetSafetyMarginTokens = payload.optionalInt("contextBudgetSafetyMarginTokens"),
        contextBudgetEffectiveInputPercent = payload.optionalDouble("contextBudgetEffectiveInputPercent"),
        contextWindowTokensOverride = payload.optionalInt("contextWindowTokensOverride"),
      )

    "POST" to "v1/validate_llm_config" -> OpenCraySettingsWriteCommand.ValidateLlmConfig(
      providerId = payload.requireString("providerId"),
      protocol = payload.requireString("protocol"),
      baseUrl = payload.requireString("baseUrl"),
      apiKey = payload.requireString("apiKey"),
      model = payload.requireString("model"),
      reasoningEffort = payload.requireString("reasoningEffort"),
      contextWindowTokensOverride = payload.optionalInt("contextWindowTokensOverride"),
    )

    "POST" to "v1/download_on_device_llm_model" ->
      OpenCraySettingsWriteCommand.DownloadOnDeviceLlmModel(payload.requireString("modelId"))

    "POST" to "v1/cancel_on_device_llm_model_download" ->
      OpenCraySettingsWriteCommand.CancelOnDeviceLlmModelDownload(payload.requireString("modelId"))

    "POST" to "v1/delete_on_device_llm_model" ->
      OpenCraySettingsWriteCommand.DeleteOnDeviceLlmModel(payload.requireString("modelId"))

    "POST" to "v1/save_personalization_config" ->
      OpenCraySettingsWriteCommand.SavePersonalizationConfig(
        presetId = payload.requireString("presetId"),
        customLabel = payload.requireString("customLabel"),
        customGuidance = payload.requireString("customGuidance"),
      )

    "POST" to "v1/set_app_language" ->
      OpenCraySettingsWriteCommand.SetAppLanguage(payload.requireString("languageId"))

    "POST" to "v1/run_personalization_reset" ->
      OpenCraySettingsWriteCommand.RunPersonalizationReset(payload.requireString("scopeId"))

    "POST" to "v1/set_mcp_master_enabled" ->
      OpenCraySettingsWriteCommand.SetMcpMasterEnabled(payload.requireBoolean("enabled"))

    "POST" to "v1/set_mcp_server_enabled" ->
      OpenCraySettingsWriteCommand.SetMcpServerEnabled(
        serverId = payload.requireString("serverId"),
        enabled = payload.requireBoolean("enabled"),
      )

    "POST" to "v1/save_safety_settings" -> OpenCraySettingsWriteCommand.SaveSafetySettings(
      automationModeId = payload.requireString("automationModeId"),
      rollbackJournalEnabled = payload.requireBoolean("rollbackJournalEnabled"),
      maxFilesPerBatch = payload.requireInt("maxFilesPerBatch"),
      maxAgentTurns = payload.requireInt("maxAgentTurns"),
      maxToolCalls = payload.requireInt("maxToolCalls"),
      undoWindowHours = payload.requireInt("undoWindowHours"),
      fileChangesPolicyId = payload.requireString("fileChangesPolicyId"),
      fileDeletesPolicyId = payload.requireString("fileDeletesPolicyId"),
      shellCommandsPolicyId = payload.requireString("shellCommandsPolicyId"),
      externalAccessModeId = payload.requireString("externalAccessModeId"),
      photoLibraryEnabled = payload.requireBoolean("photoLibraryEnabled"),
      downloadsEnabled = payload.requireBoolean("downloadsEnabled"),
      documentsEnabled = payload.requireBoolean("documentsEnabled"),
      recordingsEnabled = payload.requireBoolean("recordingsEnabled"),
      workspaceAccessProfileId = payload.requireString("workspaceAccessProfileId"),
      readOnlyOutsideWorkspace = payload.requireBoolean("readOnlyOutsideWorkspace"),
      liveContextModeId = payload.requireString("liveContextModeId"),
      memoryToolsEnabled = payload.requireBoolean("memoryToolsEnabled"),
      subAgentContextDefaultModeId = payload.optionalString("subAgentContextDefaultModeId"),
      subAgentContextProfileOverrides = payload.requireStringMap("subAgentContextProfileOverrides"),
    )

    else -> error("Unsupported settings write command route '$method $route'.")
  }

private fun chatCommandEnvelope(
  route: String,
  payload: JsonObject = JsonObject(emptyMap()),
): RuntimeServiceWriteCommandEnvelope = RuntimeServiceWriteCommandEnvelope(
  domain = RuntimeServiceWriteCommandDomains.CHAT,
  method = "POST",
  route = route,
  payload = payload,
)

private fun skillsCommandEnvelope(
  route: String,
  method: String = "POST",
  queryParameters: Map<String, String> = emptyMap(),
  payload: JsonObject = JsonObject(emptyMap()),
): RuntimeServiceWriteCommandEnvelope = RuntimeServiceWriteCommandEnvelope(
  domain = RuntimeServiceWriteCommandDomains.SKILLS,
  method = method,
  route = route,
  queryParameters = queryParameters,
  payload = payload,
)

private fun settingsCommandEnvelope(
  route: String,
  payload: JsonObject = JsonObject(emptyMap()),
): RuntimeServiceWriteCommandEnvelope = RuntimeServiceWriteCommandEnvelope(
  domain = RuntimeServiceWriteCommandDomains.SETTINGS,
  method = "POST",
  route = route,
  payload = payload,
)

private fun stringPayload(key: String, value: String): JsonObject = buildJsonObject {
  put(key, value)
}

private fun sessionMessagePayload(sessionId: String, messageId: String): JsonObject =
  buildJsonObject {
    put("sessionId", sessionId)
    put("messageId", messageId)
  }

private fun approvalPayload(taskIdOrRunId: String): JsonObject = buildJsonObject {
  put("runId", taskIdOrRunId)
  put("taskId", taskIdOrRunId)
}

private fun RuntimeServiceWriteCommandEnvelope.normalizedMethodAndRoute(): Pair<String, String> =
  method.trim().uppercase() to route.trim().trimStart('/')

private fun JsonObject.requireString(key: String): String =
  (get(key) as? JsonPrimitive)?.contentOrNull
    ?: error("Runtime service write command field '$key' must be a string.")

private fun JsonObject.optionalString(key: String): String? = when (val value = get(key)) {
  null,
  JsonNull,
  -> null
  is JsonPrimitive -> value.contentOrNull
  else -> error("Runtime service write command field '$key' must be a string or null.")
}

private fun JsonObject.requireBoolean(key: String): Boolean =
  (get(key) as? JsonPrimitive)?.booleanOrNull
    ?: error("Runtime service write command field '$key' must be a boolean.")

private fun JsonObject.optionalBoolean(key: String): Boolean? = when (val value = get(key)) {
  null,
  JsonNull,
  -> null
  is JsonPrimitive -> value.booleanOrNull
    ?: error("Runtime service write command field '$key' must be a boolean or null.")
  else -> error("Runtime service write command field '$key' must be a boolean or null.")
}

private fun JsonObject.requireInt(key: String): Int =
  (get(key) as? JsonPrimitive)?.intOrNull
    ?: error("Runtime service write command field '$key' must be an integer.")

private fun JsonObject.optionalInt(key: String): Int? = when (val value = get(key)) {
  null,
  JsonNull,
  -> null
  is JsonPrimitive -> value.intOrNull
    ?: error("Runtime service write command field '$key' must be an integer or null.")
  else -> error("Runtime service write command field '$key' must be an integer or null.")
}

private fun JsonObject.requireDouble(key: String): Double =
  (get(key) as? JsonPrimitive)?.doubleOrNull
    ?: error("Runtime service write command field '$key' must be numeric.")

private fun JsonObject.optionalDouble(key: String): Double? = when (val value = get(key)) {
  null,
  JsonNull,
  -> null
  is JsonPrimitive -> value.doubleOrNull
    ?: error("Runtime service write command field '$key' must be numeric or null.")
  else -> error("Runtime service write command field '$key' must be numeric or null.")
}

private fun JsonObject.requireArray(key: String): JsonArray = get(key) as? JsonArray
  ?: error("Runtime service write command field '$key' must be an array.")

private fun JsonObject.requireStringList(key: String): List<String> = requireArray(key).map { item ->
  (item as? JsonPrimitive)?.contentOrNull
    ?: error("Runtime service write command field '$key' must contain only strings.")
}

private fun JsonObject.requireMapList(key: String): List<Map<String, Any?>> = requireArray(key).map { item ->
  (item as? JsonObject)?.toKotlinMap()
    ?: error("Runtime service write command field '$key' must contain only objects.")
}

private fun JsonObject.requireStringMap(key: String): Map<String, String> =
  (get(key) as? JsonObject)?.mapValues { (nestedKey, value) ->
    (value as? JsonPrimitive)?.contentOrNull
      ?: error("Runtime service write command field '$key.$nestedKey' must be a string.")
  } ?: error("Runtime service write command field '$key' must be an object.")

private fun JsonObject.requireTaskOrRunId(): String =
  optionalString("runId")?.takeIf(String::isNotBlank)
    ?: requireString("taskId")

private fun Map<String, String>.requireQueryParameter(key: String): String =
  get(key) ?: error("Runtime service write command query parameter '$key' is required.")

private fun OpenCrayFinalAttachment.toWireJsonObject(): JsonObject = buildJsonObject {
  putIfNotNull("kind", kind)
  putIfNotNull("relativePath", relativePath)
  putIfNotNull("path", path)
  putIfNotNull("artifactId", artifactId)
  putIfNotNull("chatAttachmentId", chatAttachmentId)
  putIfNotNull("displayName", displayName)
  putIfNotNull("mimeType", mimeType)
  putIfNotNull("durationMs", durationMs)
  if (waveformBars.isNotEmpty()) {
    put("waveformBars", waveformBars.toWireJsonElement())
  }
  putIfNotNull("transcriptText", transcriptText)
}

private fun JsonObject.toFinalAttachment(): OpenCrayFinalAttachment = OpenCrayFinalAttachment(
  kind = optionalString("kind"),
  relativePath = optionalString("relativePath"),
  path = optionalString("path"),
  artifactId = optionalString("artifactId"),
  chatAttachmentId = optionalString("chatAttachmentId"),
  displayName = optionalString("displayName"),
  mimeType = optionalString("mimeType"),
  durationMs = when (val value = get("durationMs")) {
    null,
    JsonNull,
    -> null
    is JsonPrimitive -> value.longOrNull
      ?: error("Runtime service attachment durationMs must be an integer or null.")
    else -> error("Runtime service attachment durationMs must be an integer or null.")
  },
  waveformBars = when (val value = get("waveformBars")) {
    null,
    JsonNull,
    -> emptyList()
    is JsonArray -> value.map { item ->
      (item as? JsonPrimitive)?.intOrNull
        ?: error("Runtime service attachment waveformBars must contain integers.")
    }
    else -> error("Runtime service attachment waveformBars must be an array.")
  },
  transcriptText = optionalString("transcriptText"),
)

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfNotNull(
  key: String,
  value: Any?,
) {
  if (value != null) {
    put(key, value.toWireJsonElement())
  }
}

private fun Map<String, *>.toWireJsonObject(): JsonObject = JsonObject(
  entries.associate { (key, value) -> key to value.toWireJsonElement() },
)

private fun Any?.toWireJsonElement(): JsonElement = when (this) {
  null -> JsonNull
  is JsonElement -> this
  is String -> JsonPrimitive(this)
  is Boolean -> JsonPrimitive(this)
  is Number -> JsonPrimitive(this)
  is Map<*, *> -> JsonObject(
    entries.associate { (key, value) ->
      requireNotNull(key) { "Runtime service wire map keys must not be null." }.toString() to
        value.toWireJsonElement()
    },
  )
  is Iterable<*> -> JsonArray(map { value -> value.toWireJsonElement() })
  is Array<*> -> JsonArray(map { value -> value.toWireJsonElement() })
  else -> error("Unsupported runtime service wire value '${this::class.java.name}'.")
}

private fun JsonObject.toKotlinMap(): Map<String, Any?> =
  entries.associate { (key, value) -> key to value.toKotlinValue() }

private fun JsonElement.toKotlinValue(): Any? = when (this) {
  JsonNull -> null
  is JsonObject -> toKotlinMap()
  is JsonArray -> map(JsonElement::toKotlinValue)
  is JsonPrimitive -> when {
    isString -> content
    booleanOrNull != null -> booleanOrNull
    longOrNull != null -> longOrNull?.let { value ->
      if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
    }
    doubleOrNull != null -> doubleOrNull
    else -> content
  }
}

private fun encodeResultEnvelope(envelope: RuntimeServiceWriteResultEnvelope): String =
  PersistenceJson.instance.encodeToString(
    serializer = RuntimeServiceWriteResultEnvelope.serializer(),
    value = envelope,
  )

private fun <T> decodeResultEnvelope(
  encoded: String?,
  transform: (RuntimeServiceWriteResultEnvelope) -> T,
): T? = runCatching {
  val envelope = PersistenceJson.instance.decodeFromString(
    deserializer = RuntimeServiceWriteResultEnvelope.serializer(),
    string = encoded.orEmpty(),
  )
  require(envelope.schemaVersion == RUNTIME_SERVICE_WRITE_COMMAND_SCHEMA_VERSION) {
    "Unsupported runtime service write result schema '${envelope.schemaVersion}'."
  }
  transform(envelope)
}.getOrNull()

private fun JsonElement.toNullableMap(): Map<String, Any?>? = when (this) {
  JsonNull -> null
  is JsonObject -> toKotlinMap()
  else -> error("Runtime service write result payload must be an object or null.")
}

private fun JsonElement.requireMapValue(): Map<String, Any?> =
  (this as? JsonObject)?.toKotlinMap()
    ?: error("Runtime service write result payload must be an object.")

private fun JsonElement.requireStringValue(): String =
  (this as? JsonPrimitive)?.contentOrNull
    ?: error("Runtime service write result payload must be a string.")

@Suppress("UNCHECKED_CAST")
private fun Any?.requireMapPayload(): Map<String, Any?> = this as? Map<String, Any?>
  ?: error("Runtime service loopback write result payload must be an object.")

private fun Any?.requireStringPayload(): String = this as? String
  ?: error("Runtime service loopback write result payload must be a string.")
