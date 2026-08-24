package com.opencray.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostServiceBackedChatGatewayTest : RuntimeServiceHostTestBase() {
  @Test
  fun serviceBackedChatGatewayObservationStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = client,
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )
    val observedChatSources = mutableListOf<String?>()
    val observedRuntimeSources = mutableListOf<String?>()

    gateway.observeChat { snapshot ->
      observedChatSources += snapshot["source"] as String?
    }
    gateway.observeChatRuntime { snapshot ->
      observedRuntimeSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime"), observedRuntimeSources)
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedChatGatewayLoadStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = client,
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )

    assertEquals("fallback-chat", gateway.loadChatSnapshot()["source"])
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = binderGateway,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("binder-chat", gateway.loadChatSnapshot()["source"])
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    gateway.submitChatMessage("through binder", emptyList())
    assertEquals("through binder", binderGateway.submittedText)
    assertEquals(null, fallbackGateway.submittedText)

    serviceClient.currentChatGateway = null
    serviceClient.dispatchChatWriteCommandHandler = null
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.inProcessFallback())

    assertEquals("fallback-chat", gateway.loadChatSnapshot()["source"])
    assertEquals(3, serviceClient.loadChatRuntimeGatewayCallCount)
    assertEquals(0, serviceClient.peekChatRuntimeGatewayCallCount)
    val failure = runCatching {
      gateway.submitChatMessage("through fallback", emptyList())
    }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("submitChatMessage") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertEquals(null, fallbackGateway.submittedText)
  }

  @Test
  fun serviceBackedGatewayObserversKeepRuntimeStickyAfterBinderGatewayAppears() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      dispatchChatWriteCommandHandler = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedChatSources = mutableListOf<String?>()
    val observedRuntimeSources = mutableListOf<String?>()

    gateway.observeChat { snapshot ->
      observedChatSources += snapshot["source"] as String?
    }
    gateway.observeChatRuntime { snapshot ->
      observedRuntimeSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime"), observedRuntimeSources)

    serviceClient.currentChatGateway = binderGateway
    serviceClient.dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-chat", "binder-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime", "binder-runtime"), observedRuntimeSources)

    serviceClient.currentChatGateway = null
    serviceClient.dispatchChatWriteCommandHandler = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-chat", "binder-chat", "fallback-chat"), observedChatSources)
    assertEquals(
      listOf("fallback-runtime", "binder-runtime"),
      observedRuntimeSources,
    )
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveChatRuntimeStaysOnBinderAcrossTransientPeekMiss() {
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-runtime",
        "revision" to 1,
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatRuntimePayload = mapOf(
        "source" to "fallback-runtime",
        "revision" to 0,
      )
    }
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = binderGateway,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedRevisions = mutableListOf<Int?>()

    val disposer = gateway.observeChatRuntime { snapshot ->
      observedSources += snapshot["source"] as String?
      observedRevisions += snapshot["revision"] as Int?
    }

    binderGateway.chatRuntimePayload = mapOf(
      "source" to "binder-runtime",
      "revision" to 2,
    )
    binderGateway.emitChatRuntimeSnapshot()
    serviceClient.currentChatGateway = null
    fallbackGateway.chatRuntimePayload = mapOf(
      "source" to "fallback-runtime",
      "revision" to 10,
    )
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.bindingPending())
    binderGateway.chatRuntimePayload = mapOf(
      "source" to "binder-runtime",
      "revision" to 3,
    )
    binderGateway.emitChatRuntimeSnapshot()
    disposer()

    assertEquals(
      listOf("fallback-runtime", "binder-runtime", "binder-runtime", "binder-runtime"),
      observedSources,
    )
    assertEquals(listOf(0, 1, 2, 3), observedRevisions)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitsForPendingBinderBeforeSubmittingMessage() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.submitChatMessage(
          text = "hello",
          attachments = emptyList(),
        )
      }.onSuccess { payload ->
        result = payload
      }.onFailure { throwable ->
        failure = throwable
      }
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun dispatchChatWriteCommand(
          command: OpenCrayChatWriteCommand,
        ): OpenCrayChatWriteDispatchResult = binderGateway.dispatchChatWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("hello", binderGateway.submittedText)
    assertEquals("binder-submit", result?.get("source"))
  }

  @Test
  fun serviceBackedChatRuntimeGatewayRejectsInProcessTransportWhenBinderIsUnavailable() {
    val bindingAdapter = RecordingBindingAdapter()
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    val failure = runCatching {
      gateway.submitChatMessage("through binder only", emptyList())
    }.exceptionOrNull()

    assertEquals(1, bindingAdapter.bindCount)
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("binder-backed runtime service gateway") == true)
    assertNull(fallbackGateway.submittedText)
    assertEquals("binding", serviceClient.loadConnectionState().phase)
    assertEquals("in_process", serviceClient.loadConnectionState().transport)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayFallsBackToWakeTransportForFireAndForgetWritesWhenBinderIsUnavailable() {
    val bindingAdapter = RecordingBindingAdapter()
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val wakeCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      wakeChatWriteRequester = { command ->
        wakeCommands += command
        true
      },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    gateway.approveChatApproval("run-alpha")

    assertEquals(1, bindingAdapter.bindCount)
    assertEquals(
      listOf(OpenCrayChatWriteCommand.ApproveChatApproval("run-alpha")),
      wakeCommands,
    )
    assertNull(fallbackGateway.approvedTaskIdOrRunId)
    assertEquals("binding", serviceClient.loadConnectionState().phase)
    assertEquals("in_process", serviceClient.loadConnectionState().transport)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayKeepsWakeFallbackWhenCommandFallbackTransportIsUnavailable() {
    val bindingAdapter = RecordingBindingAdapter()
    val wakeCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val commandFallbackTransport = object : RuntimeServiceCommandFallbackTransport {
      override fun dispatchChatWriteCommand(
        command: OpenCrayChatWriteCommand,
      ): OpenCrayChatWriteDispatchResult {
        throw IllegalStateException(
          "Loopback runtime transport is unavailable for 'v1/approve_chat_approval'.",
          java.net.ConnectException("refused"),
        )
      }
    }
    val serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      wakeChatWriteRequester = { command ->
        wakeCommands += command
        true
      },
      commandFallbackTransport = commandFallbackTransport,
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { false },
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )

    gateway.approveChatApproval("run-alpha")

    assertEquals(1, bindingAdapter.bindCount)
    assertEquals(
      listOf(OpenCrayChatWriteCommand.ApproveChatApproval("run-alpha")),
      wakeCommands,
    )
  }

  @Test
  fun serviceBackedGatewaysUseCommandFallbackTransportWhenBinderBindFailsOffMainThread() {
    val bindingAdapter = object : OpenCrayRuntimeServiceBindingAdapter {
      var bindCount = 0

      override fun bind(
        context: android.content.Context,
        intent: Intent,
        connection: ServiceConnection,
        flags: Int,
      ): Boolean {
        bindCount += 1
        return false
      }

      override fun unbind(
        context: android.content.Context,
        connection: ServiceConnection,
      ) = Unit
    }
    val chatCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val skillsCommands = mutableListOf<OpenCraySkillsWriteCommand>()
    val settingsCommands = mutableListOf<OpenCraySettingsWriteCommand>()
    val commandFallbackTransport = object : RuntimeServiceCommandFallbackTransport {
      override fun dispatchChatWriteCommand(
        command: OpenCrayChatWriteCommand,
      ): OpenCrayChatWriteDispatchResult {
        chatCommands += command
        return OpenCrayChatWriteDispatchResult.Payload(
          mapOf("source" to "command-fallback-chat"),
        )
      }

      override fun dispatchSkillsWriteCommand(
        command: OpenCraySkillsWriteCommand,
      ): OpenCraySkillsWriteDispatchResult {
        skillsCommands += command
        return OpenCraySkillsWriteDispatchResult.Message("command-fallback-skills")
      }

      override fun dispatchSettingsWriteCommand(
        command: OpenCraySettingsWriteCommand,
      ): OpenCraySettingsWriteDispatchResult {
        settingsCommands += command
        return OpenCraySettingsWriteDispatchResult.Payload(
          mapOf("source" to "command-fallback-settings"),
        )
      }
    }
    val fallbackChatGateway = RecordingChatRuntimeGateway("fallback")
    val fallbackSkillsGateway = RecordingSkillsGateway("fallback")
    val fallbackSettingsGateway = RecordingSettingsGateway("fallback")
    val serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      commandFallbackTransport = commandFallbackTransport,
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { false },
    )
    val chatGateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackChatGateway,
    )
    val skillsGateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackSkillsGateway,
    )
    val settingsGateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackSettingsGateway,
    )

    val chatResult = chatGateway.submitChatMessage("through command fallback", emptyList())
    val skillsResult = skillsGateway.installSkillSource("fallback-source", "selected")
    val settingsResult = settingsGateway.performStrongBackgroundAction("repair")

    assertEquals(3, bindingAdapter.bindCount)
    assertEquals("command-fallback-chat", chatResult?.get("source"))
    val submittedCommand = chatCommands.single() as OpenCrayChatWriteCommand.SubmitChatMessage
    assertEquals("through command fallback", submittedCommand.text)
    assertEquals("command-fallback-skills", skillsResult)
    assertEquals(
      OpenCraySkillsWriteCommand.InstallSkillSource("fallback-source", "selected"),
      skillsCommands.single(),
    )
    assertEquals("command-fallback-settings", settingsResult["source"])
    assertEquals(
      OpenCraySettingsWriteCommand.PerformStrongBackgroundAction("repair"),
      settingsCommands.single(),
    )
    assertNull(fallbackChatGateway.submittedText)
    assertNull(fallbackSkillsGateway.lastInstalledSourceRef)
    assertNull(fallbackSettingsGateway.lastStrongBackgroundActionId)
    assertEquals("fallback", serviceClient.loadConnectionState().phase)
    assertEquals("in_process", serviceClient.loadConnectionState().transport)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitsForPendingBinderBeforeLoadingRuntimeSnapshot() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-await-runtime-read"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream",
            "taskId" to "task-stream",
            "pendingMessageId" to "pending-stream",
            "text" to "Streaming answer",
            "updatedAtEpochMs" to 1_234L,
          ),
        ),
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatRuntimePayload = mapOf(
        "source" to "fallback-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.loadChatRuntimeSnapshot()
      }.onSuccess { payload ->
        result = payload
      }.onFailure { throwable ->
        failure = throwable
      }
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    worker.join(1_000L)

    @Suppress("UNCHECKED_CAST")
    val liveDrafts = result?.get("liveAssistantDrafts") as? List<Map<String, Any?>>

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("binder-runtime", result?.get("source"))
    assertEquals(1, liveDrafts?.size)
    assertEquals("Streaming answer", liveDrafts?.single()?.get("text"))
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWritesThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    gateway.approveChatApproval("run-alpha")

    assertEquals("run-alpha", binderGateway.approvedTaskIdOrRunId)
    assertNull(fallbackGateway.approvedTaskIdOrRunId)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayApprovesForSessionThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      dispatchChatWriteCommandHandler = binderGateway::dispatchChatWriteCommand,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    gateway.approveChatApprovalForSession("run-beta")

    assertEquals("run-beta", binderGateway.approvedForSessionTaskIdOrRunId)
    assertNull(fallbackGateway.approvedForSessionTaskIdOrRunId)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserversRecheckGatewayAfterConnectionObservationStarts() {
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = ChatGatewayAvailableOnObserveClient(binderGateway),
      fallbackGateway = fallbackGateway,
    )
    val observedChatSources = mutableListOf<String?>()
    val observedRuntimeSources = mutableListOf<String?>()

    gateway.observeChat { snapshot ->
      observedChatSources += snapshot["source"] as String?
    }
    gateway.observeChatRuntime { snapshot ->
      observedRuntimeSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-chat", "binder-chat"), observedChatSources)
    assertEquals(listOf("fallback-runtime", "binder-runtime"), observedRuntimeSources)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveChatFallsBackAfterBinderDisconnectAndRebinds() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-observe-chat-disconnect"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatPayload = mapOf(
        "source" to "binder-chat",
        "revision" to 1,
      )
    }
    val reboundGateway = RecordingChatRuntimeGateway("binder-rebound").apply {
      chatPayload = mapOf(
        "source" to "binder-rebound-chat",
        "revision" to 2,
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatPayload = mapOf(
        "source" to "fallback-chat",
        "revision" to 0,
      )
    }
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedRevisions = mutableListOf<Int?>()

    val disposer = gateway.observeChat { snapshot ->
      observedSources += snapshot["source"] as String?
      observedRevisions += snapshot["revision"] as Int?
    }

    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-chat"
    }
    fallbackGateway.chatPayload = mapOf(
      "source" to "fallback-chat",
      "revision" to 10,
    )
    bindingAdapter.disconnect()
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "fallback-chat" &&
        observedRevisions.lastOrNull() == 10
    }
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 2 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = reboundGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-rebound-chat" &&
        observedRevisions.lastOrNull() == 2
    }
    disposer()

    assertEquals(
      listOf("fallback-chat", "binder-chat", "fallback-chat", "binder-rebound-chat"),
      observedSources,
    )
    assertEquals(listOf(0, 1, 10, 2), observedRevisions)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveChatRuntimeStaysStickyAcrossDisconnectButRebindsOnReconnect() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-observe-runtime-disconnect"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream",
            "taskId" to "task-stream",
            "pendingMessageId" to "pending-stream",
            "text" to "Streaming answer",
            "updatedAtEpochMs" to 1_234L,
          ),
        ),
      )
    }
    val reboundGateway = RecordingChatRuntimeGateway("binder-rebound").apply {
      chatRuntimePayload = mapOf(
        "source" to "binder-rebound-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream-2",
            "taskId" to "task-stream-2",
            "pendingMessageId" to "pending-stream-2",
            "text" to "Streaming answer rebound",
            "updatedAtEpochMs" to 1_240L,
          ),
        ),
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback").apply {
      chatRuntimePayload = mapOf(
        "source" to "fallback-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedDraftCounts = mutableListOf<Int>()

    val disposer = gateway.observeChatRuntime { snapshot ->
      observedSources += snapshot["source"] as String?
      observedDraftCounts += ((snapshot["liveAssistantDrafts"] as? List<*>)?.size ?: -1)
    }

    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-runtime"
    }
    bindingAdapter.disconnect()
    Thread.sleep(100L)
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 2 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = reboundGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-rebound-runtime" &&
        observedDraftCounts.lastOrNull() == 1
    }
    disposer()

    assertEquals(
      listOf("fallback-runtime", "binder-runtime", "binder-rebound-runtime"),
      observedSources,
    )
    assertEquals(listOf(0, 1, 1), observedDraftCounts)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayObserveLiveDraftEventsStaysStickyAcrossDisconnectButRebindsOnReconnect() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-observe-draft-disconnect"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder").apply {
      liveAssistantDraftEventPayload = mapOf(
        "source" to "binder-draft",
        "sessionId" to "session-stream",
        "runId" to "run-stream",
        "taskId" to "task-stream",
        "pendingMessageId" to "pending-stream",
        "text" to "Streaming answer",
        "updatedAtEpochMs" to 1_234L,
        "cleared" to false,
      )
    }
    val reboundGateway = RecordingChatRuntimeGateway("binder-rebound").apply {
      liveAssistantDraftEventPayload = mapOf(
        "source" to "binder-rebound-draft",
        "sessionId" to "session-stream",
        "runId" to "run-stream",
        "taskId" to "task-stream",
        "pendingMessageId" to "pending-stream",
        "text" to "Streaming answer rebound",
        "updatedAtEpochMs" to 1_236L,
        "cleared" to false,
      )
    }
    val fallbackGateway = RecordingChatRuntimeGateway("fallback")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()
    val observedClears = mutableListOf<Boolean?>()

    val disposer = gateway.observeLiveAssistantDraftEvents { payload ->
      observedSources += payload["source"] as String?
      observedClears += payload["cleared"] as Boolean?
    }

    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-draft"
    }
    fallbackGateway.liveAssistantDraftEventPayload = mapOf(
      "source" to "fallback-draft",
      "sessionId" to "session-stream",
      "runId" to "run-stream",
      "taskId" to "task-stream",
      "pendingMessageId" to "pending-stream",
      "text" to "",
      "updatedAtEpochMs" to 1_235L,
      "cleared" to true,
    )
    bindingAdapter.disconnect()
    Thread.sleep(100L)
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 2 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = reboundGateway
      },
    )
    waitForCondition(timeoutMs = 1_000L) {
      observedSources.lastOrNull() == "binder-rebound-draft"
    }
    disposer()

    assertEquals(listOf("binder-draft", "binder-rebound-draft"), observedSources)
    assertEquals(listOf(false, false), observedClears)
  }

  @Test
  fun serviceBackedChatRuntimeGatewayWaitForRunUsesRemainingCallerTimeoutAfterBinderAwait() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("chat-gateway-wait-run-timeout-budget"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val gateway = ServiceBackedOpenCrayChatRuntimeGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingChatRuntimeGateway("fallback"),
    )
    var result: Map<String, Any?>? = null

    val worker = Thread {
      result = gateway.waitForChatRun("run-stream", 150L)
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    Thread.sleep(75L)
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )
    worker.join(1_000L)

    val remainingTimeoutMs = (result?.get("timeoutMs") as? Number)?.toLong() ?: -1L

    assertFalse(worker.isAlive)
    assertTrue(remainingTimeoutMs >= 0L && remainingTimeoutMs < 150L)
  }
}
