package com.opencray.app

import android.content.Context
import android.content.ContextWrapper
import com.opencray.runtime.OpenCrayFinalAttachment
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCrayFlutterHostBridgeTest {

  @Test
  fun refreshSandboxSessionInfoMethodCallRoutesThroughChatRuntimeGateway() {
    val chatGateway = RecordingChatRuntimeGateway()
    val result = RecordingMethodResult()
    val bridge = hostBridge(chatGateway = chatGateway)

    bridge.onMethodCall(MethodCall("refreshSandboxSessionInfo", null), result)

    assertEquals(1, chatGateway.refreshSandboxSessionInfoCallCount)
    assertTrue(result.successCalled)
    assertNull(result.successPayload)
    assertNull(result.errorCode)
    assertNull(result.errorMessage)
  }

  @Test
  fun refreshSandboxSessionInfoMethodCallReturnsBridgeErrorWhenGatewayFails() {
    val chatGateway = RecordingChatRuntimeGateway().apply {
      refreshSandboxSessionInfoError = IllegalStateException("refresh failed")
    }
    val result = RecordingMethodResult()
    val bridge = hostBridge(chatGateway = chatGateway)

    bridge.onMethodCall(MethodCall("refreshSandboxSessionInfo", null), result)

    assertEquals(1, chatGateway.refreshSandboxSessionInfoCallCount)
    assertFalse(result.successCalled)
    assertEquals("HOST_BRIDGE_ERROR", result.errorCode)
    assertEquals("refresh failed", result.errorMessage)
  }

  @Test
  fun selectChatSessionRoutesThroughChatRuntimeGateway() {
    val chatGateway = RecordingChatRuntimeGateway()
    val bridge = hostBridge(chatGateway = chatGateway)

    val selected = bridge.selectChatSession("session-1")

    assertTrue(selected)
    assertEquals("session-1", chatGateway.lastSelectedSessionId)
  }

  @Test
  fun selectChatSessionAsyncRoutesThroughChatRuntimeGatewayAndReportsCallback() {
    val chatGateway = RecordingChatRuntimeGateway()
    val callbackValues = mutableListOf<Boolean>()
    val bridge = hostBridge(chatGateway = chatGateway)

    bridge.selectChatSessionAsync("session-async") { selected ->
      callbackValues += selected
    }

    assertEquals("session-async", chatGateway.lastSelectedSessionId)
    assertEquals(listOf(true), callbackValues)
  }

  @Test
  fun loadShellSnapshotMethodCallAddsStableBridgeInstanceId() {
    val result = RecordingMethodResult()
    val bridge = OpenCrayFlutterHostBridge(
      context = MinimalContext(),
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      localHostGateway = UnsupportedLocalGateway(),
      shellGateway = object : UnsupportedShellGateway() {
        override fun loadShellSnapshot(): Map<String, Any?> = mapOf(
          "initialTab" to "chat",
          "hostLabel" to "HOST READY",
          "hostSummary" to "Detached runtime service active.",
          "isHostConnected" to true,
        )
      },
      chatRuntimeGateway = RecordingChatRuntimeGateway(),
      skillsGateway = UnsupportedSkillsGateway(),
      settingsGateway = UnsupportedSettingsGateway(),
      debugPythonScriptRunnerFactory = {
        throw UnsupportedOperationException("Debug Python runner should not be used.")
      },
      backgroundRunner = { action -> action() },
      mainThreadPoster = { action -> action() },
    )

    bridge.onMethodCall(MethodCall("loadShellSnapshot", null), result)
    val firstSnapshot = result.successPayload as Map<*, *>
    val firstBridgeId = firstSnapshot["bridgeInstanceId"] as String?

    val secondResult = RecordingMethodResult()
    bridge.onMethodCall(MethodCall("loadShellSnapshot", null), secondResult)
    val secondSnapshot = secondResult.successPayload as Map<*, *>

    assertTrue(result.successCalled)
    assertTrue(firstBridgeId?.isNotBlank() == true)
    assertEquals(firstBridgeId, secondSnapshot["bridgeInstanceId"])
  }

  @Test
  fun loadChatSnapshotMethodCallAddsBridgeInstanceIdToEmbeddedRuntimeActivity() {
    val result = RecordingMethodResult()
    val bridge = OpenCrayFlutterHostBridge(
      context = MinimalContext(),
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      localHostGateway = UnsupportedLocalGateway(),
      shellGateway = UnsupportedShellGateway(),
      chatRuntimeGateway = object : UnsupportedChatRuntimeGateway() {
        override fun loadChatSnapshot(): Map<String, Any?> = mapOf(
          "screenTitle" to "Chat",
          "modeLabel" to "AUTO",
          "sessionButtonLabel" to "Sessions",
          "composerPlaceholder" to "Message OpenCray",
          "summary" to emptyMap<String, Any?>(),
          "messages" to emptyList<Map<String, Any?>>(),
          "drawer" to emptyMap<String, Any?>(),
          "isInputEnabled" to true,
          "runtimeActivity" to mapOf(
            "sessionId" to "session-1",
            "activeRuns" to emptyList<Map<String, Any?>>(),
            "retainedRuns" to emptyList<Map<String, Any?>>(),
            "subAgents" to emptyList<Map<String, Any?>>(),
            "events" to emptyList<Map<String, Any?>>(),
            "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
          ),
        )
      },
      skillsGateway = UnsupportedSkillsGateway(),
      settingsGateway = UnsupportedSettingsGateway(),
      debugPythonScriptRunnerFactory = {
        throw UnsupportedOperationException("Debug Python runner should not be used.")
      },
      backgroundRunner = { action -> action() },
      mainThreadPoster = { action -> action() },
    )

    bridge.onMethodCall(MethodCall("loadChatSnapshot", null), result)

    val chatSnapshot = result.successPayload as Map<*, *>
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val bridgeId = runtimeActivity["bridgeInstanceId"] as String?

    assertTrue(result.successCalled)
    assertTrue(bridgeId?.isNotBlank() == true)
  }

  @Test
  fun openCrayFlutterHostBridgeUsesInjectedGatewayBundleFactory() {
    val localGateway = RecordingLocalGateway()
    val chatGateway = RecordingChatRuntimeGateway()
    var capturedTarget: RuntimeServiceTarget? = null
    val bridge = openCrayFlutterHostBridge(
      context = MinimalContext(),
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      gatewayBundleFactory = OpenCrayClientGatewayBundleFactory { _, target ->
        capturedTarget = target
        OpenCrayClientGatewayBundle(
          localHostGateway = localGateway,
          shellGateway = UnsupportedShellGateway(),
          chatRuntimeGateway = chatGateway,
          skillsGateway = UnsupportedSkillsGateway(),
          settingsGateway = UnsupportedSettingsGateway(),
        )
      },
    )

    val selected = bridge.selectChatSession("session-2")

    assertTrue(selected)
    assertEquals(RuntimeServiceTarget.INTERACTIVE, capturedTarget)
    assertEquals("session-2", chatGateway.lastSelectedSessionId)
  }

  @Test
  fun openCrayFlutterHostBridgeUsesEnvironmentDefaultsWhenFactoryAndTargetAreOmitted() {
    val localGateway = RecordingLocalGateway()
    val chatGateway = RecordingChatRuntimeGateway()
    val expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND
    var capturedTarget: RuntimeServiceTarget? = null
    val context = RuntimeEnvironmentContext(
      OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        defaultClientRuntimeServiceTarget = expectedTarget,
        clientGatewayBundleFactoryProvider = {
          OpenCrayClientGatewayBundleFactory { _, target ->
            capturedTarget = target
            OpenCrayClientGatewayBundle(
              localHostGateway = localGateway,
              shellGateway = UnsupportedShellGateway(),
              chatRuntimeGateway = chatGateway,
              skillsGateway = UnsupportedSkillsGateway(),
              settingsGateway = UnsupportedSettingsGateway(),
            )
          }
        },
      ),
    )

    val bridge = openCrayFlutterHostBridge(
      context = context,
      runtimeTarget = expectedTarget,
      gatewayBundleFactory = context.openCrayRuntimeServiceEnvironment.clientGatewayBundleFactory,
    )
    val selected = bridge.selectChatSession("session-detached")

    assertEquals(expectedTarget, capturedTarget)
    assertEquals(expectedTarget, bridge.runtimeTarget)
    assertTrue(selected)
    assertEquals("session-detached", chatGateway.lastSelectedSessionId)
  }

  @Test
  fun listAgentsMethodCallRoutesThroughLocalHostGateway() {
    val localGateway = RecordingLocalGateway().apply {
      listAgentsResult = listOf(
        mapOf(
          "agentId" to "agent-1",
          "displayName" to "Aster",
        ),
      )
    }
    val result = RecordingMethodResult()
    val bridge = hostBridge(
      chatGateway = RecordingChatRuntimeGateway(),
      localHostGateway = localGateway,
    )

    bridge.onMethodCall(MethodCall("listAgents", null), result)

    assertEquals(1, localGateway.listAgentsCallCount)
    assertTrue(result.successCalled)
    assertEquals(localGateway.listAgentsResult, result.successPayload)
  }

  @Test
  fun resolveSandboxPreviewEmbedConfigMethodCallRoutesThroughLocalHostGateway() {
    val localGateway = RecordingLocalGateway().apply {
      resolveSandboxPreviewEmbedConfigResult = mapOf(
        "previewUrl" to "https://3000-sb-1.e2b.app/",
        "providerId" to "e2b",
        "headers" to mapOf(
          "E2B-Traffic-Access-Token" to "traffic-1",
        ),
        "sessionMatched" to true,
        "accessTokenConfigured" to true,
      )
    }
    val result = RecordingMethodResult()
    val bridge = hostBridge(
      chatGateway = RecordingChatRuntimeGateway(),
      localHostGateway = localGateway,
    )

    bridge.onMethodCall(
      MethodCall(
        "resolveSandboxPreviewEmbedConfig",
        mapOf("previewUrl" to "https://3000-sb-1.e2b.app/"),
      ),
      result,
    )

    assertEquals(
      "https://3000-sb-1.e2b.app/",
      localGateway.lastResolvedSandboxPreviewUrl,
    )
    assertTrue(result.successCalled)
    assertEquals(
      localGateway.resolveSandboxPreviewEmbedConfigResult,
      result.successPayload,
    )
  }

  @Test
  fun createAgentMethodCallRoutesThroughLocalHostGateway() {
    val localGateway = RecordingLocalGateway().apply {
      createAgentResult = mapOf(
        "agentId" to "agent-2",
        "displayName" to "Nova",
      )
    }
    val result = RecordingMethodResult()
    val bridge = hostBridge(
      chatGateway = RecordingChatRuntimeGateway(),
      localHostGateway = localGateway,
    )
    val payload = mapOf(
      "displayName" to "Nova",
      "presetName" to "builder",
      "plasticity" to "medium",
    )

    bridge.onMethodCall(MethodCall("createAgent", payload), result)

    assertEquals(payload, localGateway.lastCreateAgentPayload)
    assertTrue(result.successCalled)
    assertEquals(localGateway.createAgentResult, result.successPayload)
  }

  @Test
  fun performStrongBackgroundActionMethodCallRoutesThroughSettingsGatewayAsync() {
    val settingsGateway = object : UnsupportedSettingsGateway() {
      var lastActionId: String? = null

      override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> {
        lastActionId = actionId
        return mapOf("actionId" to actionId)
      }
    }
    val result = RecordingMethodResult()
    val bridge = OpenCrayFlutterHostBridge(
      context = MinimalContext(),
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      localHostGateway = UnsupportedLocalGateway(),
      shellGateway = UnsupportedShellGateway(),
      chatRuntimeGateway = RecordingChatRuntimeGateway(),
      skillsGateway = UnsupportedSkillsGateway(),
      settingsGateway = settingsGateway,
      debugPythonScriptRunnerFactory = {
        throw UnsupportedOperationException("Debug Python runner should not be used.")
      },
      backgroundRunner = { action -> action() },
      mainThreadPoster = { action -> action() },
    )

    bridge.onMethodCall(
      MethodCall(
        "performStrongBackgroundAction",
        mapOf("actionId" to StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS),
      ),
      result,
    )

    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      settingsGateway.lastActionId,
    )
    assertTrue(result.successCalled)
    assertEquals(
      mapOf("actionId" to StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS),
      result.successPayload,
    )
  }

  @Test
  fun selectAgentMethodCallRoutesThroughLocalHostGateway() {
    val localGateway = RecordingLocalGateway().apply {
      selectAgentResult = mapOf(
        "agentId" to "agent-3",
        "displayName" to "Quarry",
      )
    }
    val result = RecordingMethodResult()
    val bridge = hostBridge(
      chatGateway = RecordingChatRuntimeGateway(),
      localHostGateway = localGateway,
    )

    bridge.onMethodCall(
      MethodCall("selectAgent", mapOf("agentId" to "agent-3")),
      result,
    )

    assertEquals("agent-3", localGateway.lastSelectedAgentId)
    assertTrue(result.successCalled)
    assertEquals(localGateway.selectAgentResult, result.successPayload)
  }

  private fun hostBridge(
    chatGateway: RecordingChatRuntimeGateway,
    localHostGateway: OpenCrayLocalHostGateway = UnsupportedLocalGateway(),
  ): OpenCrayFlutterHostBridge = OpenCrayFlutterHostBridge(
    context = MinimalContext(),
    runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
    localHostGateway = localHostGateway,
    shellGateway = UnsupportedShellGateway(),
    chatRuntimeGateway = chatGateway,
    skillsGateway = UnsupportedSkillsGateway(),
    settingsGateway = UnsupportedSettingsGateway(),
    debugPythonScriptRunnerFactory = {
      throw UnsupportedOperationException("Debug Python runner should not be used.")
    },
    backgroundRunner = { action -> action() },
    mainThreadPoster = { action -> action() },
  )

  private class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  private class RuntimeEnvironmentContext(
    override val openCrayRuntimeServiceEnvironment: OpenCrayRuntimeServiceEnvironment,
  ) : ContextWrapper(null), OpenCrayRuntimeServiceEnvironmentOwner {
    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  private class RecordingMethodResult : MethodChannel.Result {
    var successCalled: Boolean = false
      private set

    var successPayload: Any? = null
      private set

    var errorCode: String? = null
      private set

    var errorMessage: String? = null
      private set

    override fun success(result: Any?) {
      successCalled = true
      successPayload = result
    }

    override fun error(
      errorCode: String,
      errorMessage: String?,
      errorDetails: Any?,
    ) {
      this.errorCode = errorCode
      this.errorMessage = errorMessage
    }

    override fun notImplemented() {
      error("Method should have been implemented.")
    }
  }

  private open class UnsupportedLocalGateway : OpenCrayLocalHostGateway {
    override fun loadFilesSnapshot(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun openWorkspaceEntry(relativePath: String) {
      throw UnsupportedOperationException()
    }

    override fun openExternalUri(uri: String) {
      throw UnsupportedOperationException()
    }

    override fun copyRichTextToClipboard(plainText: String, htmlText: String?) {
      throw UnsupportedOperationException()
    }

    override fun createWorkspaceFolder(parentRelativePath: String, name: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun createWorkspaceTextFile(parentRelativePath: String, name: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun renameWorkspaceEntry(targetRelativePath: String, newName: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun saveWorkspaceTextDocument(targetRelativePath: String, content: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun pasteWorkspaceEntries(
      sourceRelativePaths: List<String>,
      destinationRelativePath: String,
      move: Boolean,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun shareWorkspaceEntries(relativePaths: List<String>) {
      throw UnsupportedOperationException()
    }

    override fun showNativeToast(message: String) {
      throw UnsupportedOperationException()
    }

    override fun importDraftChatAttachments(
      requestedKind: String,
      uriStrings: List<String>,
    ): List<Map<String, Any?>> = throw UnsupportedOperationException()

    override fun probeTwinImportSource(filePath: String): Map<String, Any?> =
      throw UnsupportedOperationException()
  }

  private open class UnsupportedShellGateway : OpenCrayShellGateway {
    override fun loadShellSnapshot(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
      throw UnsupportedOperationException()
  }

  private open class UnsupportedSettingsGateway : OpenCraySettingsGateway {
    override fun loadSettingsOverview(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
      throw UnsupportedOperationException()

    override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadNotificationSettings(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadNetworkSearchConfig(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadMediaSpeechConfig(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadSandboxSettings(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadLlmConfig(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun saveLlmConfig(
      enabled: Boolean,
      streamingEnabled: Boolean?,
      providerId: String,
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun saveCustomLlmProvider(
      selectedProviderOptionId: String,
      streamingEnabled: Boolean?,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun validateLlmConfig(
      providerId: String,
      protocol: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun loadPersonalizationConfig(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun savePersonalizationConfig(
      presetId: String,
      customLabel: String,
      customGuidance: String,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun setAppLanguage(languageId: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadMcpSettings(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun setMcpServerEnabled(serverId: String, enabled: Boolean): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadSafetySettings(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun saveSafetySettings(
      automationModeId: String,
      rollbackJournalEnabled: Boolean,
      maxFilesPerBatch: Int,
      maxAgentTurns: Int,
      maxToolCalls: Int,
      undoWindowHours: Int,
      fileChangesPolicyId: String,
      fileDeletesPolicyId: String,
      shellCommandsPolicyId: String,
      externalAccessModeId: String,
      photoLibraryEnabled: Boolean,
      downloadsEnabled: Boolean,
      documentsEnabled: Boolean,
      recordingsEnabled: Boolean,
      workspaceAccessProfileId: String,
      readOnlyOutsideWorkspace: Boolean,
      liveContextModeId: String,
      memoryToolsEnabled: Boolean,
      subAgentContextDefaultModeId: String?,
      subAgentContextProfileOverrides: Map<String, String>,
    ): Map<String, Any?> = throw UnsupportedOperationException()
  }

  private open class UnsupportedSkillsGateway : OpenCraySkillsGateway {
    override fun loadSkillsSnapshot(query: String, suggestedLimit: Int): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
      throw UnsupportedOperationException()

    override fun setSkillEnabled(skillId: String, enabled: Boolean) {
      throw UnsupportedOperationException()
    }

    override fun installSuggestedSkill(skillId: String): String =
      throw UnsupportedOperationException()

    override fun installSkillSource(sourceRef: String, selectedSkillName: String): String =
      throw UnsupportedOperationException()

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): String = throw UnsupportedOperationException()

    override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun deleteInstalledSkill(skillId: String): String =
      throw UnsupportedOperationException()

    override fun refreshSkills(): String = throw UnsupportedOperationException()

    override fun checkInstalledSkillUpdates(skillId: String): String =
      throw UnsupportedOperationException()

    override fun updateInstalledSkill(skillId: String): String =
      throw UnsupportedOperationException()

    override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadSuggestedSkillInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun activateSkillsInstallSource(sourceId: String): String =
      throw UnsupportedOperationException()
  }

  private open class UnsupportedChatRuntimeGateway : OpenCrayChatRuntimeGateway {
    override fun loadChatSnapshot(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
      throw UnsupportedOperationException()

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
      throw UnsupportedOperationException()

    override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? =
      throw UnsupportedOperationException()

    override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
      throw UnsupportedOperationException()

    override fun refreshSandboxSessionInfo() {
      throw UnsupportedOperationException()
    }

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
      throw UnsupportedOperationException()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = throw UnsupportedOperationException()

    override fun searchMemoryDebug(
      query: String,
      maxResults: Int,
      minScore: Int,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun getMemoryDebugSlice(
      path: String,
      fromLine: Int?,
      lines: Int,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun applyMemoryDebugAction(
      recordId: String,
      actionId: String,
    ): Map<String, Any?> = throw UnsupportedOperationException()

    override fun createChatSession() {
      throw UnsupportedOperationException()
    }

    override fun copyChatSession(sessionId: String) {
      throw UnsupportedOperationException()
    }

    override fun deleteChatSession(sessionId: String) {
      throw UnsupportedOperationException()
    }

    override fun selectChatSession(sessionId: String) {
      throw UnsupportedOperationException()
    }

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) {
      throw UnsupportedOperationException()
    }

    override fun deleteChatMessage(sessionId: String, messageId: String) {
      throw UnsupportedOperationException()
    }

    override fun recallChatMessage(sessionId: String, messageId: String) {
      throw UnsupportedOperationException()
    }

    override fun submitChatMessage(
      text: String,
      attachments: List<OpenCrayFinalAttachment>,
    ): Map<String, Any?>? = throw UnsupportedOperationException()

    override fun approveChatApproval(taskIdOrRunId: String) {
      throw UnsupportedOperationException()
    }

    override fun approveChatApprovalForSession(taskIdOrRunId: String) {
      throw UnsupportedOperationException()
    }

    override fun rejectChatApproval(taskIdOrRunId: String) {
      throw UnsupportedOperationException()
    }

    override fun interruptChatRun(taskIdOrRunId: String) {
      throw UnsupportedOperationException()
    }

    override fun retryChatRun(taskIdOrRunId: String) {
      throw UnsupportedOperationException()
    }
  }

  private class RecordingChatRuntimeGateway : UnsupportedChatRuntimeGateway() {
    var refreshSandboxSessionInfoCallCount: Int = 0
      private set

    var refreshSandboxSessionInfoError: Throwable? = null
    var lastSelectedSessionId: String? = null
      private set

    override fun selectChatSession(sessionId: String) {
      lastSelectedSessionId = sessionId
    }

    override fun refreshSandboxSessionInfo() {
      refreshSandboxSessionInfoCallCount += 1
      refreshSandboxSessionInfoError?.let { throwable -> throw throwable }
    }
  }

  private class RecordingLocalGateway : UnsupportedLocalGateway() {
    var listAgentsCallCount: Int = 0
      private set

    var listAgentsResult: List<Map<String, Any?>> = emptyList()
    var lastResolvedSandboxPreviewUrl: String? = null
      private set

    var resolveSandboxPreviewEmbedConfigResult: Map<String, Any?> = emptyMap()
    var lastCreateAgentPayload: Map<String, Any?>? = null
      private set

    var createAgentResult: Map<String, Any?> = emptyMap()
    var lastSelectedAgentId: String? = null
      private set

    var selectAgentResult: Map<String, Any?>? = null

    override fun listAgents(): List<Map<String, Any?>> {
      listAgentsCallCount += 1
      return listAgentsResult
    }

    override fun resolveSandboxPreviewEmbedConfig(previewUrl: String): Map<String, Any?> {
      lastResolvedSandboxPreviewUrl = previewUrl
      return resolveSandboxPreviewEmbedConfigResult
    }

    override fun createAgent(payload: Map<String, Any?>): Map<String, Any?> {
      lastCreateAgentPayload = payload
      return createAgentResult
    }

    override fun selectAgent(agentId: String): Map<String, Any?>? {
      lastSelectedAgentId = agentId
      return selectAgentResult
    }
  }
}
