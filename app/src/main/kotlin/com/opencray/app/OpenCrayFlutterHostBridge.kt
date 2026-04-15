package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.runtime.OpenCrayFinalAttachment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

internal fun openCrayFlutterHostBridge(
  context: Context,
  gatewayBundleFactory: OpenCrayClientGatewayBundleFactory =
    DefaultOpenCrayClientGatewayBundleFactory,
): OpenCrayFlutterHostBridge {
  val gatewayBundle = gatewayBundleFactory.create(context.applicationContext)
  return OpenCrayFlutterHostBridge(
    context = context,
    localHostGateway = gatewayBundle.localHostGateway,
    shellGateway = gatewayBundle.shellGateway,
    chatRuntimeGateway = gatewayBundle.chatRuntimeGateway,
    skillsGateway = gatewayBundle.skillsGateway,
    settingsGateway = gatewayBundle.settingsGateway,
  )
}

internal class OpenCrayFlutterHostBridge(
  private val context: Context,
  private val localHostGateway: OpenCrayLocalHostGateway,
  private val shellGateway: OpenCrayShellGateway,
  private val chatRuntimeGateway: OpenCrayChatRuntimeGateway,
  private val skillsGateway: OpenCraySkillsGateway,
  private val settingsGateway: OpenCraySettingsGateway,
  private val debugPythonScriptRunnerFactory: () -> DebugPythonScriptRunner = {
    DebugPythonScriptRunner(context.applicationContext)
  },
  private val permissionHost: ExternalAccessPermissionRequestHost? =
    context as? ExternalAccessPermissionRequestHost,
  private val chatAttachmentPickerHost: ChatAttachmentPickerHost? =
    context as? ChatAttachmentPickerHost,
  private val backgroundRunner: ((() -> Unit) -> Unit) = { action ->
    Thread { action() }.start()
  },
  private val mainThreadPoster: ((() -> Unit) -> Unit) = { action ->
    Handler(Looper.getMainLooper()).post { action() }
  },
) {
  private val debugPythonScriptRunner: DebugPythonScriptRunner by lazy(LazyThreadSafetyMode.NONE) {
    debugPythonScriptRunnerFactory()
  }
  private var shellObserverDisposer: (() -> Unit)? = null
  private var settingsObserverDisposer: (() -> Unit)? = null
  private var skillsObserverDisposer: (() -> Unit)? = null
  private var chatObserverDisposer: (() -> Unit)? = null
  private var chatRuntimeObserverDisposer: (() -> Unit)? = null
  private var liveAssistantDraftObserverDisposer: (() -> Unit)? = null

  fun attach(flutterEngine: FlutterEngine) {
    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler(::onMethodCall)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SHELL_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = shellGateway::observeShell,
        onDisposeChanged = { disposer -> shellObserverDisposer = disposer },
      ),
    )
    EventChannel(
      flutterEngine.dartExecutor.binaryMessenger,
      SETTINGS_OVERVIEW_CHANNEL,
    ).setStreamHandler(
      observerStreamHandler(
        observe = settingsGateway::observeSettingsOverview,
        onDisposeChanged = { disposer -> settingsObserverDisposer = disposer },
      ),
    )
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SKILLS_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = skillsGateway::observeSkills,
        onDisposeChanged = { disposer -> skillsObserverDisposer = disposer },
      ),
    )
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, CHAT_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = chatRuntimeGateway::observeChat,
        onDisposeChanged = { disposer -> chatObserverDisposer = disposer },
      ),
    )
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, CHAT_RUNTIME_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = chatRuntimeGateway::observeChatRuntime,
        onDisposeChanged = { disposer -> chatRuntimeObserverDisposer = disposer },
      ),
    )
    EventChannel(
      flutterEngine.dartExecutor.binaryMessenger,
      LIVE_ASSISTANT_DRAFT_CHANNEL,
    ).setStreamHandler(
      observerStreamHandler(
        observe = chatRuntimeGateway::observeLiveAssistantDraftEvents,
        onDisposeChanged = { disposer -> liveAssistantDraftObserverDisposer = disposer },
      ),
    )
  }

  fun detach(flutterEngine: FlutterEngine) {
    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler(null)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SHELL_SNAPSHOT_CHANNEL).setStreamHandler(null)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SETTINGS_OVERVIEW_CHANNEL).setStreamHandler(null)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SKILLS_SNAPSHOT_CHANNEL).setStreamHandler(null)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, CHAT_SNAPSHOT_CHANNEL).setStreamHandler(null)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, CHAT_RUNTIME_SNAPSHOT_CHANNEL).setStreamHandler(null)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, LIVE_ASSISTANT_DRAFT_CHANNEL).setStreamHandler(null)
    shellObserverDisposer?.invoke()
    settingsObserverDisposer?.invoke()
    skillsObserverDisposer?.invoke()
    chatObserverDisposer?.invoke()
    chatRuntimeObserverDisposer?.invoke()
    liveAssistantDraftObserverDisposer?.invoke()
    shellObserverDisposer = null
    settingsObserverDisposer = null
    skillsObserverDisposer = null
    chatObserverDisposer = null
    chatRuntimeObserverDisposer = null
    liveAssistantDraftObserverDisposer = null
  }

  fun selectChatSession(sessionId: String): Boolean =
    runCatching {
      chatRuntimeGateway.selectChatSession(sessionId)
    }.isSuccess

  internal fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    if (call.method == "pickChatAttachments") {
      handlePickChatAttachments(call, result)
      return
    }
    if (call.method == "pickSettingsImageAssets") {
      handlePickSettingsImageAssets(result)
      return
    }
    runCatching {
      when (call.method) {
        "loadShellSnapshot" -> shellGateway.loadShellSnapshot()
        "loadFilesSnapshot" -> localHostGateway.loadFilesSnapshot()
        "resolveSandboxPreviewEmbedConfig" -> localHostGateway.resolveSandboxPreviewEmbedConfig(
          previewUrl = call.argument<String>("previewUrl").orEmpty(),
        )
        "listSettingsImageAssets" -> localHostGateway.listSettingsImageAssets()
        "listAgents" -> {
          runAsync(result) {
            localHostGateway.listAgents()
          }
          return
        }
        "loadActiveAgent" -> {
          runAsync(result) {
            localHostGateway.loadActiveAgent()
          }
          return
        }
        "createAgent" -> {
          runAsync(result) {
            localHostGateway.createAgent(
              payload = call.arguments<Map<String, Any?>>() ?: emptyMap(),
            )
          }
          return
        }
        "selectAgent" -> {
          runAsync(result) {
            localHostGateway.selectAgent(
              agentId = call.argument<String>("agentId").orEmpty(),
            )
          }
          return
        }
        "loadSoulVisualIdentity" -> localHostGateway.loadSoulVisualIdentity()
        "loadWorkspaceImagePreview" -> localHostGateway.loadWorkspaceImagePreview(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "loadWorkspaceTextPreview" -> localHostGateway.loadWorkspaceTextPreview(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "loadWorkspaceVoicePlaybackSource" -> localHostGateway.loadWorkspaceVoicePlaybackSource(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "loadWorkspaceTextDocument" -> localHostGateway.loadWorkspaceTextDocument(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "openWorkspaceEntry" -> {
          localHostGateway.openWorkspaceEntry(
            relativePath = call.argument<String>("relativePath").orEmpty(),
          )
          null
        }
        "openExternalUri" -> {
          localHostGateway.openExternalUri(
            uri = call.argument<String>("uri").orEmpty(),
          )
          null
        }
        "copyRichTextToClipboard" -> {
          localHostGateway.copyRichTextToClipboard(
            plainText = call.argument<String>("plainText").orEmpty(),
            htmlText = call.argument<String>("htmlText"),
          )
          null
        }
        "createWorkspaceFolder" -> localHostGateway.createWorkspaceFolder(
          parentRelativePath = call.argument<String>("parentRelativePath").orEmpty(),
          name = call.argument<String>("name").orEmpty(),
        )
        "createWorkspaceTextFile" -> localHostGateway.createWorkspaceTextFile(
          parentRelativePath = call.argument<String>("parentRelativePath").orEmpty(),
          name = call.argument<String>("name").orEmpty(),
        )
        "renameWorkspaceEntry" -> localHostGateway.renameWorkspaceEntry(
          targetRelativePath = call.argument<String>("targetRelativePath").orEmpty(),
          newName = call.argument<String>("newName").orEmpty(),
        )
        "deleteWorkspaceEntries" -> localHostGateway.deleteWorkspaceEntries(
          relativePaths = (call.argument<List<String>>("relativePaths") ?: emptyList()),
        )
        "saveWorkspaceTextDocument" -> localHostGateway.saveWorkspaceTextDocument(
          targetRelativePath = call.argument<String>("targetRelativePath").orEmpty(),
          content = call.argument<String>("content").orEmpty(),
        )
        "pasteWorkspaceEntries" -> localHostGateway.pasteWorkspaceEntries(
          sourceRelativePaths = (call.argument<List<String>>("sourceRelativePaths") ?: emptyList()),
          destinationRelativePath = call.argument<String>("destinationRelativePath").orEmpty(),
          move = call.argument<Boolean>("move") == true,
        )
        "shareWorkspaceEntries" -> {
          localHostGateway.shareWorkspaceEntries(
            relativePaths = (call.argument<List<String>>("relativePaths") ?: emptyList()),
          )
          null
        }
        "showNativeToast" -> {
          localHostGateway.showNativeToast(
            message = call.argument<String>("message").orEmpty(),
          )
          null
        }
        "loadSettingsOverview" -> settingsGateway.loadSettingsOverview()
        "loadSettingsDetail" -> settingsGateway.loadSettingsDetail(
          routeIdRaw = call.argument<String>("routeId").orEmpty(),
        )
        "loadNotificationSettings" -> settingsGateway.loadNotificationSettings()
        "saveNotificationSettings" -> {
          runAsync(result) {
            settingsGateway.saveNotificationSettings(
              payload = call.arguments<Map<String, Any?>>() ?: emptyMap(),
            )
          }
          return
        }
        "loadStrongBackgroundSnapshot" -> settingsGateway.loadStrongBackgroundSnapshot()
        "performStrongBackgroundAction" -> settingsGateway.performStrongBackgroundAction(
          actionId = call.argument<String>("actionId").orEmpty(),
        )
        "loadNetworkSearchConfig" -> settingsGateway.loadNetworkSearchConfig()
        "saveNetworkSearchConfig" -> {
          runAsync(result) {
            settingsGateway.saveNetworkSearchConfig(
              slots = (call.argument<List<*>>("slots") ?: emptyList<Any?>()).mapNotNull { slot ->
                @Suppress("UNCHECKED_CAST")
                slot as? Map<String, Any?>
              },
            )
          }
          return
        }
        "loadMediaSpeechConfig" -> settingsGateway.loadMediaSpeechConfig()
        "saveMediaSpeechConfig" -> {
          runAsync(result) {
            settingsGateway.saveMediaSpeechConfig(
              payload = call.arguments<Map<String, Any?>>() ?: emptyMap(),
            )
          }
          return
        }
        "loadSandboxSettings" -> settingsGateway.loadSandboxSettings()
        "saveSandboxSettings" -> {
          runAsync(result) {
            settingsGateway.saveSandboxSettings(
              payload = call.arguments<Map<String, Any?>>() ?: emptyMap(),
            )
          }
          return
        }
        "loadLlmConfig" -> settingsGateway.loadLlmConfig()
        "saveLlmConfig" -> {
          runAsync(result) {
            settingsGateway.saveLlmConfig(
              enabled = call.argument<Boolean>("enabled") == true,
              streamingEnabled = call.argument<Boolean>("streamingEnabled"),
              providerMode = call.argument<String>("providerMode") ?: LlmProviderModes.CLOUD,
              providerId = call.argument<String>("providerId").orEmpty(),
              selectedProviderOptionId = call.argument<String>("selectedProviderOptionId").orEmpty(),
              protocol = call.argument<String>("protocol").orEmpty(),
              providerName = call.argument<String>("providerName").orEmpty(),
              providerNotes = call.argument<String>("providerNotes").orEmpty(),
              baseUrl = call.argument<String>("baseUrl").orEmpty(),
              apiKey = call.argument<String>("apiKey").orEmpty(),
              model = call.argument<String>("model").orEmpty(),
              reasoningEffort = call.argument<String>("reasoningEffort").orEmpty(),
              systemPrompt = call.argument<String>("systemPrompt").orEmpty(),
              openAiPromptCacheKeyStrategy = call.argument<String>("openAiPromptCacheKeyStrategy"),
              openAiPromptCacheRetention = call.argument<String>("openAiPromptCacheRetention"),
              anthropicPromptCachingEnabled =
                call.argument<Boolean>("anthropicPromptCachingEnabled"),
              anthropicPromptCacheTtl = call.argument<String>("anthropicPromptCacheTtl"),
              contextBudgetPreset = call.argument<String>("contextBudgetPreset"),
              contextBudgetReservedOutputTokens =
                call.argument<Number>("contextBudgetReservedOutputTokens")?.toInt(),
              contextBudgetSafetyMarginTokens =
                call.argument<Number>("contextBudgetSafetyMarginTokens")?.toInt(),
              contextBudgetEffectiveInputPercent =
                call.argument<Number>("contextBudgetEffectiveInputPercent")?.toDouble(),
              selectedOnDeviceModelId =
                call.argument<String>("selectedOnDeviceModelId")
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_MODEL_ID,
              onDeviceMaxContextWindow =
                call.argument<Number>("onDeviceMaxContextWindow")?.toInt()
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_MAX_CONTEXT_WINDOW,
              onDeviceMaxTokens =
                call.argument<Number>("onDeviceMaxTokens")?.toInt()
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_MAX_TOKENS,
              onDeviceTopK =
                call.argument<Number>("onDeviceTopK")?.toInt()
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_TOP_K,
              onDeviceTopP =
                call.argument<Number>("onDeviceTopP")?.toDouble()
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_TOP_P,
              onDeviceTemperature =
                call.argument<Number>("onDeviceTemperature")?.toDouble()
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_TEMPERATURE,
              onDeviceAccelerator =
                call.argument<String>("onDeviceAccelerator")
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_ACCELERATOR,
              onDeviceThinkingEnabled =
                call.argument<Boolean>("onDeviceThinkingEnabled")
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_THINKING_ENABLED,
              onDeviceLiteModeEnabled =
                call.argument<Boolean>("onDeviceLiteModeEnabled")
                  ?: LlmSettingsState.DEFAULT_ON_DEVICE_LITE_MODE_ENABLED,
            )
          }
          return
        }
        "saveCustomLlmProvider" -> {
          runAsync(result) {
            settingsGateway.saveCustomLlmProvider(
              selectedProviderOptionId = call.argument<String>("selectedProviderOptionId").orEmpty(),
              streamingEnabled = call.argument<Boolean>("streamingEnabled"),
              protocol = call.argument<String>("protocol").orEmpty(),
              providerName = call.argument<String>("providerName").orEmpty(),
              providerNotes = call.argument<String>("providerNotes").orEmpty(),
              baseUrl = call.argument<String>("baseUrl").orEmpty(),
              apiKey = call.argument<String>("apiKey").orEmpty(),
              model = call.argument<String>("model").orEmpty(),
              reasoningEffort = call.argument<String>("reasoningEffort").orEmpty(),
              systemPrompt = call.argument<String>("systemPrompt").orEmpty(),
              openAiPromptCacheKeyStrategy = call.argument<String>("openAiPromptCacheKeyStrategy"),
              openAiPromptCacheRetention = call.argument<String>("openAiPromptCacheRetention"),
              anthropicPromptCachingEnabled =
                call.argument<Boolean>("anthropicPromptCachingEnabled"),
              anthropicPromptCacheTtl = call.argument<String>("anthropicPromptCacheTtl"),
              contextBudgetPreset = call.argument<String>("contextBudgetPreset"),
              contextBudgetReservedOutputTokens =
                call.argument<Number>("contextBudgetReservedOutputTokens")?.toInt(),
              contextBudgetSafetyMarginTokens =
                call.argument<Number>("contextBudgetSafetyMarginTokens")?.toInt(),
              contextBudgetEffectiveInputPercent =
                call.argument<Number>("contextBudgetEffectiveInputPercent")?.toDouble(),
            )
          }
          return
        }
        "validateLlmConfig" -> {
          runAsync(result) {
            settingsGateway.validateLlmConfig(
              providerId = call.argument<String>("providerId").orEmpty(),
              protocol = call.argument<String>("protocol").orEmpty(),
              baseUrl = call.argument<String>("baseUrl").orEmpty(),
              apiKey = call.argument<String>("apiKey").orEmpty(),
              model = call.argument<String>("model").orEmpty(),
              reasoningEffort = call.argument<String>("reasoningEffort").orEmpty(),
            )
          }
          return
        }
        "downloadOnDeviceLlmModel" -> {
          runAsync(result) {
            settingsGateway.downloadOnDeviceLlmModel(
              modelId = call.argument<String>("modelId").orEmpty(),
            )
          }
          return
        }
        "cancelOnDeviceLlmModelDownload" -> {
          runAsync(result) {
            settingsGateway.cancelOnDeviceLlmModelDownload(
              modelId = call.argument<String>("modelId").orEmpty(),
            )
          }
          return
        }
        "deleteOnDeviceLlmModel" -> {
          runAsync(result) {
            settingsGateway.deleteOnDeviceLlmModel(
              modelId = call.argument<String>("modelId").orEmpty(),
            )
          }
          return
        }
        "loadPersonalizationConfig" -> settingsGateway.loadPersonalizationConfig()
        "savePersonalizationConfig" -> {
          runAsync(result) {
            settingsGateway.savePersonalizationConfig(
              presetId = call.argument<String>("presetId").orEmpty(),
              customLabel = call.argument<String>("customLabel").orEmpty(),
              customGuidance = call.argument<String>("customGuidance").orEmpty(),
            )
          }
          return
        }
        "setAppLanguage" -> {
          runAsync(result) {
            settingsGateway.setAppLanguage(
              languageId = call.argument<String>("languageId").orEmpty(),
            )
          }
          return
        }
        "runPersonalizationReset" -> {
          runAsync(result) {
            settingsGateway.runPersonalizationReset(
              scopeId = call.argument<String>("scopeId").orEmpty(),
            )
          }
          return
        }
        "probeTwinImportSource" -> {
          runAsync(result) {
            localHostGateway.probeTwinImportSource(
              filePath = call.argument<String>("filePath").orEmpty(),
            )
          }
          return
        }
        "importSettingsImageAssets" -> {
          runAsync(result) {
            localHostGateway.importSettingsImageAssets(
              uriStrings = (call.argument<List<String>>("uriStrings") ?: emptyList()),
            )
          }
          return
        }
        "saveSoulPrimaryPortrait" -> {
          runAsync(result) {
            localHostGateway.saveSoulPrimaryPortrait(
              source = call.argument<Map<String, Any?>>("source") ?: emptyMap(),
            )
          }
          return
        }
        "saveSoulReferenceImage" -> {
          runAsync(result) {
            localHostGateway.saveSoulReferenceImage(
              refId = call.argument<String>("refId").orEmpty(),
              source = call.argument<Map<String, Any?>>("source") ?: emptyMap(),
            )
          }
          return
        }
        "listMemoryImageReferences" -> {
          runAsync(result) {
            localHostGateway.listMemoryImageReferences(
              memoryId = call.argument<String>("memoryId").orEmpty(),
            )
          }
          return
        }
        "attachMemoryImageReference" -> {
          runAsync(result) {
            localHostGateway.attachMemoryImageReference(
              memoryId = call.argument<String>("memoryId").orEmpty(),
              source = call.argument<Map<String, Any?>>("source") ?: emptyMap(),
              preferredMode = call.argument<String>("preferredMode"),
            )
          }
          return
        }
        "loadMcpSettings" -> settingsGateway.loadMcpSettings()
        "setMcpMasterEnabled" -> {
          runAsync(result) {
            settingsGateway.setMcpMasterEnabled(
              enabled = call.argument<Boolean>("enabled") == true,
            )
          }
          return
        }
        "setMcpServerEnabled" -> {
          runAsync(result) {
            settingsGateway.setMcpServerEnabled(
              serverId = call.argument<String>("serverId").orEmpty(),
              enabled = call.argument<Boolean>("enabled") == true,
            )
          }
          return
        }
        "loadSafetySettings" -> settingsGateway.loadSafetySettings()
        "saveSafetySettings" -> {
          runAsync(result) {
            settingsGateway.saveSafetySettings(
              automationModeId = call.argument<String>("automationModeId").orEmpty(),
              rollbackJournalEnabled = call.argument<Boolean>("rollbackJournalEnabled") != false,
              maxFilesPerBatch = call.argument<Int>("maxFilesPerBatch") ?: 20,
              maxAgentTurns = call.argument<Int>("maxAgentTurns")
                ?: SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
              maxToolCalls = call.argument<Int>("maxToolCalls")
                ?: SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
              undoWindowHours = call.argument<Int>("undoWindowHours") ?: 24,
              fileChangesPolicyId = call.argument<String>("fileChangesPolicyId").orEmpty(),
              fileDeletesPolicyId = call.argument<String>("fileDeletesPolicyId").orEmpty(),
              shellCommandsPolicyId = call.argument<String>("shellCommandsPolicyId").orEmpty(),
              externalAccessModeId = call.argument<String>("externalAccessModeId").orEmpty(),
              photoLibraryEnabled = call.argument<Boolean>("photoLibraryEnabled") != false,
              downloadsEnabled = call.argument<Boolean>("downloadsEnabled") != false,
              documentsEnabled = call.argument<Boolean>("documentsEnabled") == true,
              recordingsEnabled = call.argument<Boolean>("recordingsEnabled") == true,
              workspaceAccessProfileId = call.argument<String>("workspaceAccessProfileId").orEmpty(),
              readOnlyOutsideWorkspace = call.argument<Boolean>("readOnlyOutsideWorkspace") != false,
              liveContextModeId =
                call.argument<String>("liveContextModeId") ?: LiveContextMode.FULL.wireValue,
              memoryToolsEnabled = call.argument<Boolean>("memoryToolsEnabled") != false,
              subAgentContextDefaultModeId = call.argument<String>("subAgentContextDefaultModeId"),
              subAgentContextProfileOverrides = call.argument<Map<*, *>>(
                "subAgentContextProfileOverrides",
              )?.entries
                ?.mapNotNull { (key, value) ->
                  val profileId = (key as? String)?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                  val modeId = (value as? String)?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                  profileId to modeId
                }
                ?.toMap()
                ?: emptyMap(),
            )
          }
          return
        }
        "authorizeExternalAccessLocation" -> {
          authorizeExternalAccessLocation(
            locationId = call.argument<String>("locationId").orEmpty(),
            result = result,
          )
          return
        }

        "loadSkillsSnapshot" -> {
          runAsync(result) {
            skillsGateway.loadSkillsSnapshot(
              query = call.argument<String>("query").orEmpty(),
              suggestedLimit = call.argument<Int>("suggestedLimit") ?: 0,
            )
          }
          return
        }
        "setSkillEnabled" -> {
          runAsync(result) {
            skillsGateway.setSkillEnabled(
              skillId = call.argument<String>("skillId").orEmpty(),
              enabled = call.argument<Boolean>("enabled") == true,
            )
            null
          }
          return
        }
        "installSkillSource" -> {
          runAsync(result) {
            skillsGateway.installSkillSource(
              sourceRef = call.argument<String>("sourceRef").orEmpty(),
              selectedSkillName = call.argument<String>("selectedSkillName").orEmpty(),
            )
          }
          return
        }
        "installSkillSourceBatch" -> {
          runAsync(result) {
            skillsGateway.installSkillSourceBatch(
              sourceRef = call.argument<String>("sourceRef").orEmpty(),
              selectedSkillNames = (call.argument<List<*>>("selectedSkillNames").orEmpty())
                .mapNotNull { value -> value as? String },
            )
          }
          return
        }
        "inspectSkillSource" -> {
          runAsync(result) {
            skillsGateway.inspectSkillSource(
              call.argument<String>("sourceRef").orEmpty(),
            )
          }
          return
        }
        "installSuggestedSkill" -> {
          runAsync(result) {
            skillsGateway.installSuggestedSkill(
              call.argument<String>("skillId").orEmpty(),
            )
          }
          return
        }
        "deleteInstalledSkill" -> {
          runAsync(result) {
            skillsGateway.deleteInstalledSkill(
              call.argument<String>("skillId").orEmpty(),
            )
          }
          return
        }
        "refreshSkills" -> {
          runAsync(result) {
            skillsGateway.refreshSkills()
          }
          return
        }
        "checkInstalledSkillUpdates" -> {
          runAsync(result) {
            skillsGateway.checkInstalledSkillUpdates(
              skillId = call.argument<String>("skillId").orEmpty(),
            )
          }
          return
        }
        "updateInstalledSkill" -> {
          runAsync(result) {
            skillsGateway.updateInstalledSkill(
              skillId = call.argument<String>("skillId").orEmpty(),
            )
          }
          return
        }
        "loadSkillInstructions" -> skillsGateway.loadSkillInstructions(
          call.argument<String>("skillId").orEmpty(),
        )
        "loadSuggestedSkillInstructions" -> {
          runAsync(result) {
            skillsGateway.loadSuggestedSkillInstructions(
              sourceRef = call.argument<String>("sourceRef").orEmpty(),
              selectedSkillName = call.argument<String>("selectedSkillName").orEmpty(),
            )
          }
          return
        }
        "activateSkillsInstallSource" -> {
          runAsync(result) {
            skillsGateway.activateSkillsInstallSource(
              call.argument<String>("sourceId").orEmpty(),
            )
          }
          return
        }

        "loadChatSnapshot" -> chatRuntimeGateway.loadChatSnapshot()
        "loadChatRuntimeSnapshot" -> chatRuntimeGateway.loadChatRuntimeSnapshot()
        "loadChatRunSnapshot" -> chatRuntimeGateway.loadChatRunSnapshot(
          call.argument<String>("runId").orEmpty(),
        )
        "loadMemoryDebugSnapshot" -> chatRuntimeGateway.loadMemoryDebugSnapshot()
        "loadMemoryDebugLinksSnapshot" -> chatRuntimeGateway.loadMemoryDebugLinksSnapshot()
        "loadSoulDebugSnapshot" -> chatRuntimeGateway.loadSoulDebugSnapshot()
        "runDebugPythonScript" -> {
          runAsync(result) {
            debugPythonScriptRunner.runScript(
              fileName = call.argument<String>("fileName").orEmpty(),
              scriptText = call.argument<String>("scriptText").orEmpty(),
            )
          }
          return
        }
        "searchMemoryDebug" -> chatRuntimeGateway.searchMemoryDebug(
          query = call.argument<String>("query").orEmpty(),
          maxResults = call.argument<Number>("maxResults")?.toInt() ?: 4,
          minScore = call.argument<Number>("minScore")?.toInt() ?: 1,
        )
        "getMemoryDebugSlice" -> chatRuntimeGateway.getMemoryDebugSlice(
          path = call.argument<String>("path").orEmpty(),
          fromLine = call.argument<Number>("fromLine")?.toInt(),
          lines = call.argument<Number>("lines")?.toInt() ?: 12,
        )
        "applyMemoryDebugAction" -> {
          runAsync(result) {
            chatRuntimeGateway.applyMemoryDebugAction(
              recordId = call.argument<String>("recordId").orEmpty(),
              actionId = call.argument<String>("actionId").orEmpty(),
            )
          }
          return
        }
        "waitForChatRun" -> {
          runAsync(result) {
            chatRuntimeGateway.waitForChatRun(
              runId = call.argument<String>("runId").orEmpty(),
              timeoutMs = call.argument<Number>("timeoutMs")?.toLong() ?: 15_000L,
            )
          }
          return
        }
        "refreshSandboxSessionInfo" -> {
          runAsync(result) {
            chatRuntimeGateway.refreshSandboxSessionInfo()
            null
          }
          return
        }
        "createChatSession" -> {
          runAsync(result) {
            chatRuntimeGateway.createChatSession()
            null
          }
          return
        }

        "copyChatSession" -> {
          runAsync(result) {
            chatRuntimeGateway.copyChatSession(call.argument<String>("sessionId").orEmpty())
            null
          }
          return
        }

        "deleteChatSession" -> {
          runAsync(result) {
            chatRuntimeGateway.deleteChatSession(call.argument<String>("sessionId").orEmpty())
            null
          }
          return
        }

        "selectChatSession" -> {
          runAsync(result) {
            chatRuntimeGateway.selectChatSession(call.argument<String>("sessionId").orEmpty())
            null
          }
          return
        }

        "branchChatSessionFromMessage" -> {
          runAsync(result) {
            chatRuntimeGateway.branchChatSessionFromMessage(
              sessionId = call.argument<String>("sessionId").orEmpty(),
              messageId = call.argument<String>("messageId").orEmpty(),
            )
            null
          }
          return
        }

        "deleteChatMessage" -> {
          runAsync(result) {
            chatRuntimeGateway.deleteChatMessage(
              sessionId = call.argument<String>("sessionId").orEmpty(),
              messageId = call.argument<String>("messageId").orEmpty(),
            )
            null
          }
          return
        }

        "recallChatMessage" -> {
          runAsync(result) {
            chatRuntimeGateway.recallChatMessage(
              sessionId = call.argument<String>("sessionId").orEmpty(),
              messageId = call.argument<String>("messageId").orEmpty(),
            )
            null
          }
          return
        }

        "submitChatMessage" -> {
          runAsync(result) {
            chatRuntimeGateway.submitChatMessage(
              text = call.argument<String>("text").orEmpty(),
              attachments = parseDraftChatAttachments(call),
            )
          }
          return
        }
        "approveChatApproval" -> {
          runAsync(result) {
            chatRuntimeGateway.approveChatApproval(
              call.argument<String>("runId")?.takeIf(String::isNotBlank)
                ?: call.argument<String>("taskId").orEmpty(),
            )
            null
          }
          return
        }
        "approveChatApprovalForSession" -> {
          runAsync(result) {
            chatRuntimeGateway.approveChatApprovalForSession(
              call.argument<String>("runId")?.takeIf(String::isNotBlank)
                ?: call.argument<String>("taskId").orEmpty(),
            )
            null
          }
          return
        }
        "rejectChatApproval" -> {
          runAsync(result) {
            chatRuntimeGateway.rejectChatApproval(
              call.argument<String>("runId")?.takeIf(String::isNotBlank)
                ?: call.argument<String>("taskId").orEmpty(),
            )
            null
          }
          return
        }
        "interruptChatRun" -> {
          runAsync(result) {
            chatRuntimeGateway.interruptChatRun(
              call.argument<String>("runId")?.takeIf(String::isNotBlank)
                ?: call.argument<String>("taskId").orEmpty(),
            )
            null
          }
          return
        }
        "retryChatRun" -> {
          runAsync(result) {
            chatRuntimeGateway.retryChatRun(
              call.argument<String>("runId")?.takeIf(String::isNotBlank)
                ?: call.argument<String>("taskId").orEmpty(),
            )
            null
          }
          return
        }

        else -> {
          result.notImplemented()
          return
        }
      }
    }.onSuccess { payload ->
      result.success(payload)
    }.onFailure { throwable ->
      result.error(
        "HOST_BRIDGE_ERROR",
        throwable.message ?: throwable::class.java.simpleName,
        null,
      )
    }
  }

  private fun handlePickChatAttachments(call: MethodCall, result: MethodChannel.Result) {
    val requestedKind = call.argument<String>("kind").orEmpty()
    handlePickedImports(
      requestedKind = requestedKind,
      result = result,
    ) { pickedUris ->
      localHostGateway.importDraftChatAttachments(
        requestedKind = requestedKind,
        uriStrings = pickedUris,
      )
    }
  }

  private fun handlePickSettingsImageAssets(result: MethodChannel.Result) {
    handlePickedImports(
      requestedKind = "image",
      result = result,
    ) { pickedUris ->
      localHostGateway.importSettingsImageAssets(pickedUris)
    }
  }

  private fun handlePickedImports(
    requestedKind: String,
    result: MethodChannel.Result,
    importAction: (List<String>) -> Any?,
  ) {
    val pickerHost = chatAttachmentPickerHost
    if (pickerHost == null) {
      result.error("HOST_BRIDGE_ERROR", "Chat attachment picker host is unavailable.", null)
      return
    }
    pickerHost.pickChatAttachments(requestedKind) { pickedUrisResult ->
      pickedUrisResult.onFailure { throwable ->
        mainThreadPoster {
          result.error(
            "HOST_BRIDGE_ERROR",
            throwable.message ?: throwable::class.java.simpleName,
            null,
          )
        }
      }
      pickedUrisResult.onSuccess { pickedUris ->
        if (pickedUris.isEmpty()) {
          mainThreadPoster { result.success(emptyList<Map<String, Any?>>()) }
          return@onSuccess
        }
        backgroundRunner {
          runCatching { importAction(pickedUris) }
            .onSuccess { payload ->
              mainThreadPoster { result.success(payload) }
            }
            .onFailure { throwable ->
              mainThreadPoster {
                result.error(
                  "HOST_BRIDGE_ERROR",
                  throwable.message ?: throwable::class.java.simpleName,
                  null,
                )
              }
            }
        }
      }
    }
  }

  private fun parseDraftChatAttachments(call: MethodCall): List<OpenCrayFinalAttachment> {
    return (call.argument<List<*>>("attachments") ?: emptyList<Any>())
      .mapNotNull { rawEntry ->
        val payload = rawEntry as? Map<*, *> ?: return@mapNotNull null
        val relativePath = payload["relativePath"] as String?
        val path = payload["path"] as String?
        val artifactId = payload["artifactId"] as String?
        if (relativePath.isNullOrBlank() && path.isNullOrBlank() && artifactId.isNullOrBlank()) {
          return@mapNotNull null
        }
        OpenCrayFinalAttachment(
          kind = payload["kind"] as String?,
          relativePath = relativePath,
          path = path,
          artifactId = artifactId,
          displayName = payload["displayName"] as String?,
          mimeType = payload["mimeType"] as String?,
          durationMs = (payload["durationMs"] as Number?)?.toLong(),
          waveformBars = (payload["waveformBars"] as? List<*>)?.mapNotNull { value ->
            (value as? Number)?.toInt()
          }.orEmpty(),
          transcriptText = payload["transcriptText"] as String?,
        )
      }
  }

  private fun runAsync(
    result: MethodChannel.Result,
    action: () -> Any?,
  ) {
    backgroundRunner {
      runCatching(action)
        .onSuccess { payload ->
          mainThreadPoster { result.success(payload) }
        }
        .onFailure { throwable ->
          mainThreadPoster {
            result.error(
              "HOST_BRIDGE_ERROR",
              throwable.message ?: throwable::class.java.simpleName,
              null,
            )
          }
        }
    }
  }

  private fun authorizeExternalAccessLocation(
    locationId: String,
    result: MethodChannel.Result,
  ) {
    val permissions = ApprovedReadRootsResolver
      .permissionsToRequest(context = context, locationId = locationId)
      .toTypedArray()
    if (permissions.isEmpty()) {
      result.success(
        ApprovedReadRootsResolver.hasAccessibleLocation(
          context = context,
          locationId = locationId,
        ),
      )
      return
    }
    val host = permissionHost
    if (host == null) {
      result.error(
        "HOST_BRIDGE_ERROR",
        "External access permission host is unavailable.",
        null,
      )
      return
    }
    mainThreadPoster {
      host.requestExternalAccessPermissions(permissions) { granted ->
        result.success(
          granted && ApprovedReadRootsResolver.hasAccessibleLocation(
            context = context,
            locationId = locationId,
          ),
        )
      }
    }
  }

  private fun observerStreamHandler(
    observe: ((Map<String, Any?>) -> Unit) -> (() -> Unit),
    onDisposeChanged: ((() -> Unit)?) -> Unit,
  ): EventChannel.StreamHandler = object : EventChannel.StreamHandler {
    private var currentDispose: (() -> Unit)? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
      currentDispose?.invoke()
      currentDispose = observe { payload ->
        events.success(payload)
      }
      onDisposeChanged(currentDispose)
    }

    override fun onCancel(arguments: Any?) {
      currentDispose?.invoke()
      currentDispose = null
      onDisposeChanged(null)
    }
  }

  companion object {
    private const val METHOD_CHANNEL = "com.opencray.host/methods"
    private const val SHELL_SNAPSHOT_CHANNEL = "com.opencray.host/shell_snapshot"
    private const val SETTINGS_OVERVIEW_CHANNEL = "com.opencray.host/settings_overview"
    private const val SKILLS_SNAPSHOT_CHANNEL = "com.opencray.host/skills_snapshot"
    private const val CHAT_SNAPSHOT_CHANNEL = "com.opencray.host/chat_snapshot"
    private const val CHAT_RUNTIME_SNAPSHOT_CHANNEL = "com.opencray.host/chat_runtime_snapshot"
    private const val LIVE_ASSISTANT_DRAFT_CHANNEL = "com.opencray.host/live_assistant_draft"
  }
}
