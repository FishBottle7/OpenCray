package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.runtime.OpenCrayFinalAttachment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

internal class OpenCrayFlutterHostBridge(
  private val context: Context,
) {
  private val hostRuntime = OpenCrayHostRuntime.fromContext(context)
  private val permissionHost: ExternalAccessPermissionRequestHost? =
    context as? ExternalAccessPermissionRequestHost
  private val chatAttachmentPickerHost: ChatAttachmentPickerHost? =
    context as? ChatAttachmentPickerHost
  private val mainHandler = Handler(Looper.getMainLooper())
  private var shellObserverDisposer: (() -> Unit)? = null
  private var settingsObserverDisposer: (() -> Unit)? = null
  private var skillsObserverDisposer: (() -> Unit)? = null
  private var chatObserverDisposer: (() -> Unit)? = null
  private var chatRuntimeObserverDisposer: (() -> Unit)? = null

  fun attach(flutterEngine: FlutterEngine) {
    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler(::onMethodCall)
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SHELL_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = hostRuntime::observeShell,
        onDisposeChanged = { disposer -> shellObserverDisposer = disposer },
      ),
    )
    EventChannel(
      flutterEngine.dartExecutor.binaryMessenger,
      SETTINGS_OVERVIEW_CHANNEL,
    ).setStreamHandler(
      observerStreamHandler(
        observe = hostRuntime::observeSettingsOverview,
        onDisposeChanged = { disposer -> settingsObserverDisposer = disposer },
      ),
    )
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, SKILLS_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = hostRuntime::observeSkills,
        onDisposeChanged = { disposer -> skillsObserverDisposer = disposer },
      ),
    )
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, CHAT_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = hostRuntime::observeChat,
        onDisposeChanged = { disposer -> chatObserverDisposer = disposer },
      ),
    )
    EventChannel(flutterEngine.dartExecutor.binaryMessenger, CHAT_RUNTIME_SNAPSHOT_CHANNEL).setStreamHandler(
      observerStreamHandler(
        observe = hostRuntime::observeChatRuntime,
        onDisposeChanged = { disposer -> chatRuntimeObserverDisposer = disposer },
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
    shellObserverDisposer?.invoke()
    settingsObserverDisposer?.invoke()
    skillsObserverDisposer?.invoke()
    chatObserverDisposer?.invoke()
    chatRuntimeObserverDisposer?.invoke()
    shellObserverDisposer = null
    settingsObserverDisposer = null
    skillsObserverDisposer = null
    chatObserverDisposer = null
    chatRuntimeObserverDisposer = null
  }

  private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    if (call.method == "pickChatAttachments") {
      handlePickChatAttachments(call, result)
      return
    }
    runCatching {
      when (call.method) {
        "loadShellSnapshot" -> hostRuntime.loadShellSnapshot()
        "loadFilesSnapshot" -> hostRuntime.loadFilesSnapshot()
        "loadWorkspaceImagePreview" -> hostRuntime.loadWorkspaceImagePreview(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "loadWorkspaceTextPreview" -> hostRuntime.loadWorkspaceTextPreview(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "loadWorkspaceVoicePlaybackSource" -> hostRuntime.loadWorkspaceVoicePlaybackSource(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "loadWorkspaceTextDocument" -> hostRuntime.loadWorkspaceTextDocument(
          relativePath = call.argument<String>("relativePath").orEmpty(),
        )
        "openWorkspaceEntry" -> {
          hostRuntime.openWorkspaceEntry(
            relativePath = call.argument<String>("relativePath").orEmpty(),
          )
          null
        }
        "createWorkspaceFolder" -> hostRuntime.createWorkspaceFolder(
          parentRelativePath = call.argument<String>("parentRelativePath").orEmpty(),
          name = call.argument<String>("name").orEmpty(),
        )
        "createWorkspaceTextFile" -> hostRuntime.createWorkspaceTextFile(
          parentRelativePath = call.argument<String>("parentRelativePath").orEmpty(),
          name = call.argument<String>("name").orEmpty(),
        )
        "renameWorkspaceEntry" -> hostRuntime.renameWorkspaceEntry(
          targetRelativePath = call.argument<String>("targetRelativePath").orEmpty(),
          newName = call.argument<String>("newName").orEmpty(),
        )
        "deleteWorkspaceEntries" -> hostRuntime.deleteWorkspaceEntries(
          relativePaths = (call.argument<List<String>>("relativePaths") ?: emptyList()),
        )
        "saveWorkspaceTextDocument" -> hostRuntime.saveWorkspaceTextDocument(
          targetRelativePath = call.argument<String>("targetRelativePath").orEmpty(),
          content = call.argument<String>("content").orEmpty(),
        )
        "pasteWorkspaceEntries" -> hostRuntime.pasteWorkspaceEntries(
          sourceRelativePaths = (call.argument<List<String>>("sourceRelativePaths") ?: emptyList()),
          destinationRelativePath = call.argument<String>("destinationRelativePath").orEmpty(),
          move = call.argument<Boolean>("move") == true,
        )
        "shareWorkspaceEntries" -> {
          hostRuntime.shareWorkspaceEntries(
            relativePaths = (call.argument<List<String>>("relativePaths") ?: emptyList()),
          )
          null
        }
        "showNativeToast" -> {
          hostRuntime.showNativeToast(
            message = call.argument<String>("message").orEmpty(),
          )
          null
        }
        "loadSettingsOverview" -> hostRuntime.loadSettingsOverview()
        "loadSettingsDetail" -> hostRuntime.loadSettingsDetail(
          routeIdRaw = call.argument<String>("routeId").orEmpty(),
        )
        "loadNetworkSearchConfig" -> hostRuntime.loadNetworkSearchConfig()
        "saveNetworkSearchConfig" -> hostRuntime.saveNetworkSearchConfig(
          slots = (call.argument<List<*>>("slots") ?: emptyList<Any?>()).mapNotNull { slot ->
            @Suppress("UNCHECKED_CAST")
            slot as? Map<String, Any?>
          },
        )
        "loadMediaSpeechConfig" -> hostRuntime.loadMediaSpeechConfig()
        "saveMediaSpeechConfig" -> hostRuntime.saveMediaSpeechConfig(
          payload = call.arguments<Map<String, Any?>>() ?: emptyMap(),
        )
        "loadLlmConfig" -> hostRuntime.loadLlmConfig()
        "saveLlmConfig" -> hostRuntime.saveLlmConfig(
          enabled = call.argument<Boolean>("enabled") == true,
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
        )
        "saveCustomLlmProvider" -> hostRuntime.saveCustomLlmProvider(
          selectedProviderOptionId = call.argument<String>("selectedProviderOptionId").orEmpty(),
          protocol = call.argument<String>("protocol").orEmpty(),
          providerName = call.argument<String>("providerName").orEmpty(),
          providerNotes = call.argument<String>("providerNotes").orEmpty(),
          baseUrl = call.argument<String>("baseUrl").orEmpty(),
          apiKey = call.argument<String>("apiKey").orEmpty(),
          model = call.argument<String>("model").orEmpty(),
          reasoningEffort = call.argument<String>("reasoningEffort").orEmpty(),
          systemPrompt = call.argument<String>("systemPrompt").orEmpty(),
        )
        "validateLlmConfig" -> {
          runAsync(result) {
            hostRuntime.validateLlmConfig(
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
        "loadPersonalizationConfig" -> hostRuntime.loadPersonalizationConfig()
        "savePersonalizationConfig" -> hostRuntime.savePersonalizationConfig(
          presetId = call.argument<String>("presetId").orEmpty(),
          customLabel = call.argument<String>("customLabel").orEmpty(),
          customGuidance = call.argument<String>("customGuidance").orEmpty(),
        )
        "setAppLanguage" -> hostRuntime.setAppLanguage(
          languageId = call.argument<String>("languageId").orEmpty(),
        )
        "runPersonalizationReset" -> hostRuntime.runPersonalizationReset(
          scopeId = call.argument<String>("scopeId").orEmpty(),
        )
        "probeTwinImportSource" -> {
          runAsync(result) {
            hostRuntime.probeTwinImportSource(
              filePath = call.argument<String>("filePath").orEmpty(),
            )
          }
          return
        }
        "loadMcpSettings" -> hostRuntime.loadMcpSettings()
        "setMcpMasterEnabled" -> hostRuntime.setMcpMasterEnabled(
          enabled = call.argument<Boolean>("enabled") == true,
        )
        "setMcpServerEnabled" -> hostRuntime.setMcpServerEnabled(
          serverId = call.argument<String>("serverId").orEmpty(),
          enabled = call.argument<Boolean>("enabled") == true,
        )
        "loadSafetySettings" -> hostRuntime.loadSafetySettings()
        "saveSafetySettings" -> hostRuntime.saveSafetySettings(
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
        )
        "authorizeExternalAccessLocation" -> {
          authorizeExternalAccessLocation(
            locationId = call.argument<String>("locationId").orEmpty(),
            result = result,
          )
          return
        }

        "loadSkillsSnapshot" -> {
          runAsync(result) {
            hostRuntime.loadSkillsSnapshot(
              query = call.argument<String>("query").orEmpty(),
            )
          }
          return
        }
        "setSkillEnabled" -> {
          hostRuntime.setSkillEnabled(
            skillId = call.argument<String>("skillId").orEmpty(),
            enabled = call.argument<Boolean>("enabled") == true,
          )
          null
        }
        "installSkillSource" -> {
          runAsync(result) {
            hostRuntime.installSkillSource(
              sourceRef = call.argument<String>("sourceRef").orEmpty(),
              selectedSkillName = call.argument<String>("selectedSkillName").orEmpty(),
            )
          }
          return
        }
        "installSkillSourceBatch" -> {
          runAsync(result) {
            hostRuntime.installSkillSourceBatch(
              sourceRef = call.argument<String>("sourceRef").orEmpty(),
              selectedSkillNames = (call.argument<List<*>>("selectedSkillNames").orEmpty())
                .mapNotNull { value -> value as? String },
            )
          }
          return
        }
        "inspectSkillSource" -> {
          runAsync(result) {
            hostRuntime.inspectSkillSource(
              call.argument<String>("sourceRef").orEmpty(),
            )
          }
          return
        }
        "installSuggestedSkill" -> hostRuntime.installSuggestedSkill(
          call.argument<String>("skillId").orEmpty(),
        )
        "deleteInstalledSkill" -> hostRuntime.deleteInstalledSkill(
          call.argument<String>("skillId").orEmpty(),
        )
        "refreshSkills" -> hostRuntime.refreshSkills()
        "checkInstalledSkillUpdates" -> {
          runAsync(result) {
            hostRuntime.checkInstalledSkillUpdates(
              skillId = call.argument<String>("skillId").orEmpty(),
            )
          }
          return
        }
        "updateInstalledSkill" -> {
          runAsync(result) {
            hostRuntime.updateInstalledSkill(
              skillId = call.argument<String>("skillId").orEmpty(),
            )
          }
          return
        }
        "loadSkillInstructions" -> hostRuntime.loadSkillInstructions(
          call.argument<String>("skillId").orEmpty(),
        )
        "activateSkillsInstallSource" -> hostRuntime.activateSkillsInstallSource(
          call.argument<String>("sourceId").orEmpty(),
        )

        "loadChatSnapshot" -> hostRuntime.loadChatSnapshot()
        "loadChatRuntimeSnapshot" -> hostRuntime.loadChatRuntimeSnapshot()
        "loadChatRunSnapshot" -> hostRuntime.loadChatRunSnapshot(
          call.argument<String>("runId").orEmpty(),
        )
        "loadMemoryDebugSnapshot" -> hostRuntime.loadMemoryDebugSnapshot()
        "loadMemoryDebugLinksSnapshot" -> hostRuntime.loadMemoryDebugLinksSnapshot()
        "loadSoulDebugSnapshot" -> hostRuntime.loadSoulDebugSnapshot()
        "searchMemoryDebug" -> hostRuntime.searchMemoryDebug(
          query = call.argument<String>("query").orEmpty(),
          maxResults = call.argument<Number>("maxResults")?.toInt() ?: 4,
          minScore = call.argument<Number>("minScore")?.toInt() ?: 1,
        )
        "getMemoryDebugSlice" -> hostRuntime.getMemoryDebugSlice(
          path = call.argument<String>("path").orEmpty(),
          fromLine = call.argument<Number>("fromLine")?.toInt(),
          lines = call.argument<Number>("lines")?.toInt() ?: 12,
        )
        "applyMemoryDebugAction" -> hostRuntime.applyMemoryDebugAction(
          recordId = call.argument<String>("recordId").orEmpty(),
          actionId = call.argument<String>("actionId").orEmpty(),
        )
        "waitForChatRun" -> {
          runAsync(result) {
            hostRuntime.waitForChatRun(
              runId = call.argument<String>("runId").orEmpty(),
              timeoutMs = call.argument<Number>("timeoutMs")?.toLong() ?: 15_000L,
            )
          }
          return
        }
        "createChatSession" -> {
          hostRuntime.createChatSession()
          null
        }

        "copyChatSession" -> {
          hostRuntime.copyChatSession(call.argument<String>("sessionId").orEmpty())
          null
        }

        "deleteChatSession" -> {
          hostRuntime.deleteChatSession(call.argument<String>("sessionId").orEmpty())
          null
        }

        "selectChatSession" -> {
          hostRuntime.selectChatSession(call.argument<String>("sessionId").orEmpty())
          null
        }

        "branchChatSessionFromMessage" -> {
          hostRuntime.branchChatSessionFromMessage(
            sessionId = call.argument<String>("sessionId").orEmpty(),
            messageId = call.argument<String>("messageId").orEmpty(),
          )
          null
        }

        "deleteChatMessage" -> {
          hostRuntime.deleteChatMessage(
            sessionId = call.argument<String>("sessionId").orEmpty(),
            messageId = call.argument<String>("messageId").orEmpty(),
          )
          null
        }

        "recallChatMessage" -> {
          hostRuntime.recallChatMessage(
            sessionId = call.argument<String>("sessionId").orEmpty(),
            messageId = call.argument<String>("messageId").orEmpty(),
          )
          null
        }

        "submitChatMessage" -> hostRuntime.submitChatMessage(
          text = call.argument<String>("text").orEmpty(),
          attachments = parseDraftChatAttachments(call),
        )
        "approveChatApproval" -> {
          hostRuntime.approveChatApproval(
            call.argument<String>("runId")?.takeIf(String::isNotBlank)
              ?: call.argument<String>("taskId").orEmpty(),
          )
          null
        }
        "rejectChatApproval" -> {
          hostRuntime.rejectChatApproval(
            call.argument<String>("runId")?.takeIf(String::isNotBlank)
              ?: call.argument<String>("taskId").orEmpty(),
          )
          null
        }
        "cancelChatRun" -> {
          hostRuntime.cancelChatRun(
            call.argument<String>("runId")?.takeIf(String::isNotBlank)
              ?: call.argument<String>("taskId").orEmpty(),
          )
          null
        }
        "retryChatRun" -> {
          hostRuntime.retryChatRun(
            call.argument<String>("runId")?.takeIf(String::isNotBlank)
              ?: call.argument<String>("taskId").orEmpty(),
          )
          null
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
    val pickerHost = chatAttachmentPickerHost
    if (pickerHost == null) {
      result.error("HOST_BRIDGE_ERROR", "Chat attachment picker host is unavailable.", null)
      return
    }
    val requestedKind = call.argument<String>("kind").orEmpty()
    pickerHost.pickChatAttachments(requestedKind) { pickedUrisResult ->
      pickedUrisResult.onFailure { throwable ->
        mainHandler.post {
          result.error(
            "HOST_BRIDGE_ERROR",
            throwable.message ?: throwable::class.java.simpleName,
            null,
          )
        }
      }
      pickedUrisResult.onSuccess { pickedUris ->
        if (pickedUris.isEmpty()) {
          mainHandler.post { result.success(emptyList<Map<String, Any?>>()) }
          return@onSuccess
        }
        Thread {
          runCatching {
            hostRuntime.importDraftChatAttachments(
              requestedKind = requestedKind,
              uriStrings = pickedUris,
            )
          }.onSuccess { payload ->
            mainHandler.post { result.success(payload) }
          }.onFailure { throwable ->
            mainHandler.post {
              result.error(
                "HOST_BRIDGE_ERROR",
                throwable.message ?: throwable::class.java.simpleName,
                null,
              )
            }
          }
        }.start()
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
    Thread {
      runCatching(action)
        .onSuccess(result::success)
        .onFailure { throwable ->
          result.error(
            "HOST_BRIDGE_ERROR",
            throwable.message ?: throwable::class.java.simpleName,
            null,
          )
        }
    }.start()
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
    mainHandler.post {
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
  }
}
