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
