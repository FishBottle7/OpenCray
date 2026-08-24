package com.opencray.app

import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostServiceBackedSettingsGatewayTest : RuntimeServiceHostTestBase() {
  @Test
  fun serviceBackedSettingsGatewayObservationStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = client,
      fallbackGateway = RecordingSettingsGateway("fallback"),
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSettingsOverview { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-settings"), observedSources)
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedSettingsGatewayLoadStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = client,
      fallbackGateway = RecordingSettingsGateway("fallback"),
    )

    assertEquals("fallback-settings", gateway.loadSettingsOverview()["source"])
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedSettingsGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = binderGateway,
      dispatchSettingsWriteCommandHandler = binderGateway::dispatchSettingsWriteCommand,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("binder-settings", gateway.loadSettingsOverview()["source"])
    assertEquals("binder-notification-settings", gateway.loadNotificationSettings()["source"])
    assertEquals("binder-sandbox-settings", gateway.loadSandboxSettings()["source"])
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    assertEquals(
      "binder-notification-settings-save",
      gateway.saveNotificationSettings(
        mapOf(
          "masterEnabled" to false,
          "defaultDeliveryModeId" to "all",
        ),
      )["source"],
    )
    assertEquals(
      "binder-sandbox-settings-save",
      gateway.saveSandboxSettings(
        mapOf(
          "enabled" to true,
          "defaultBackend" to "sandbox",
        ),
      )["source"],
    )
    assertEquals(true, gateway.setMcpMasterEnabled(true)["enabled"])
    assertEquals(true, binderGateway.lastMcpMasterEnabled)
    assertEquals("all", binderGateway.lastNotificationSettingsPayload?.get("defaultDeliveryModeId"))
    assertEquals("sandbox", binderGateway.lastSandboxSettingsPayload?.get("defaultBackend"))
    assertEquals(null, fallbackGateway.lastMcpMasterEnabled)

    serviceClient.currentSettingsGateway = null
    serviceClient.dispatchSettingsWriteCommandHandler = null
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.inProcessFallback())

    assertEquals("fallback-settings", gateway.loadSettingsOverview()["source"])
    assertEquals("fallback-notification-settings", gateway.loadNotificationSettings()["source"])
    assertEquals("fallback-sandbox-settings", gateway.loadSandboxSettings()["source"])
    assertEquals(6, serviceClient.loadSettingsGatewayCallCount)
    assertEquals(0, serviceClient.peekSettingsGatewayCallCount)
    val notificationFailure = runCatching {
      gateway.saveNotificationSettings(mapOf("masterEnabled" to true))
    }.exceptionOrNull()
    val sandboxFailure = runCatching {
      gateway.saveSandboxSettings(mapOf("enabled" to true))
    }.exceptionOrNull()
    val failure = runCatching {
      gateway.setMcpMasterEnabled(false)
    }.exceptionOrNull()
    assertTrue(notificationFailure is IllegalStateException)
    assertTrue(notificationFailure?.message?.contains("saveNotificationSettings") == true)
    assertTrue(sandboxFailure is IllegalStateException)
    assertTrue(sandboxFailure?.message?.contains("saveSandboxSettings") == true)
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("setMcpMasterEnabled") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertEquals(null, fallbackGateway.lastNotificationSettingsPayload)
    assertEquals(null, fallbackGateway.lastSandboxSettingsPayload)
    assertEquals(null, fallbackGateway.lastMcpMasterEnabled)
  }

  @Test
  fun serviceBackedSettingsGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSettingsOverview { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-settings"), observedSources)

    serviceClient.currentSettingsGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-settings", "binder-settings"), observedSources)

    serviceClient.currentSettingsGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-settings", "binder-settings", "fallback-settings"), observedSources)
  }

  @Test
  fun serviceBackedSettingsGatewayObserverRechecksGatewayAfterConnectionObservationStarts() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = SettingsGatewayAvailableOnObserveClient(binderGateway),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSettingsOverview { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-settings", "binder-settings"), observedSources)
  }

  @Test
  fun serviceBackedSettingsGatewayAllowsFallbackStrongBackgroundLoadsButRequiresBinderForActions() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("fallback-strong-background", gateway.loadStrongBackgroundSnapshot()["source"])
    val failure = runCatching {
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      )
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("performStrongBackgroundAction") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertNull(fallbackGateway.lastStrongBackgroundActionId)

    serviceClient.currentSettingsGateway = binderGateway
    serviceClient.dispatchSettingsWriteCommandHandler = binderGateway::dispatchSettingsWriteCommand
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())

    assertEquals("binder-strong-background", gateway.loadStrongBackgroundSnapshot()["source"])
    assertEquals(
      "binder-strong-background-action",
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS,
      )["source"],
    )
    assertEquals(
      StrongBackgroundActionIds.OPEN_EXACT_ALARM_SETTINGS,
      binderGateway.lastStrongBackgroundActionId,
    )
  }

  @Test
  fun serviceBackedSettingsGatewayWritesStrongBackgroundActionThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingSettingsGateway("binder")
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
      dispatchSettingsWriteCommandHandler = binderGateway::dispatchSettingsWriteCommand,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("fallback-strong-background", gateway.loadStrongBackgroundSnapshot()["source"])
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    assertEquals(
      "binder-strong-background-action",
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      )["source"],
    )
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      binderGateway.lastStrongBackgroundActionId,
    )
    assertNull(fallbackGateway.lastStrongBackgroundActionId)
  }

  @Test
  fun serviceBackedSettingsGatewayUsesPassiveConnectionStateLookupForWriteDiagnostics() {
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    val failure = runCatching {
      gateway.saveNotificationSettings(mapOf("masterEnabled" to true))
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals(0, serviceClient.loadConnectionStateCallCount)
    assertEquals(1, serviceClient.peekConnectionStateCallCount)
    assertNull(fallbackGateway.lastNotificationSettingsPayload)
  }

  @Test
  fun serviceBackedSettingsGatewayWaitsForPendingBinderBeforeSavingCustomProvider() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("settings-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingSettingsGateway("fallback"),
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.saveCustomLlmProvider(
          selectedProviderOptionId = "provider-option",
          protocol = "responses",
          providerName = "Test Provider",
          providerNotes = "notes",
          baseUrl = "https://example.com",
          apiKey = "sk-test",
          model = "gpt-test",
          reasoningEffort = "medium",
          systemPrompt = "prompt",
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

        override fun dispatchSettingsWriteCommand(
          command: OpenCraySettingsWriteCommand,
        ): OpenCraySettingsWriteDispatchResult = binderGateway.dispatchSettingsWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("binder-custom-llm", result?.get("source"))
  }

  @Test
  fun serviceBackedSettingsGatewayRejectsInProcessTransportWhenBinderIsUnavailable() {
    val bindingAdapter = RecordingBindingAdapter()
    val fallbackGateway = RecordingSettingsGateway("fallback")
    val serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    val customProviderFailure = runCatching {
      gateway.saveCustomLlmProvider(
        selectedProviderOptionId = "provider-option",
        protocol = "anthropic",
        providerName = "Third-party Anthropic",
        providerNotes = "loopback",
        baseUrl = "https://example.com",
        apiKey = "sk-loopback",
        model = "kimi-k2.5",
        reasoningEffort = "medium",
        systemPrompt = "prompt",
      )
    }.exceptionOrNull()
    val strongBackgroundFailure = runCatching {
      gateway.performStrongBackgroundAction(
        StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      )
    }.exceptionOrNull()

    assertEquals(1, bindingAdapter.bindCount)
    assertTrue(customProviderFailure is IllegalStateException)
    assertTrue(strongBackgroundFailure is IllegalStateException)
    assertTrue(customProviderFailure?.message?.contains("binder-backed runtime service gateway") == true)
    assertNull(fallbackGateway.lastStrongBackgroundActionId)
    assertEquals("binding", serviceClient.loadConnectionState().phase)
    assertEquals("in_process", serviceClient.loadConnectionState().transport)
  }

  @Test
  fun serviceBackedSettingsGatewayWaitsForPendingBinderBeforePerformingStrongBackgroundAction() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("settings-gateway-await-strong-background"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val gateway = ServiceBackedOpenCraySettingsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingSettingsGateway("fallback"),
    )
    var result: Map<String, Any?>? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.performStrongBackgroundAction(
          StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
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

        override fun dispatchSettingsWriteCommand(
          command: OpenCraySettingsWriteCommand,
        ): OpenCraySettingsWriteDispatchResult = binderGateway.dispatchSettingsWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      binderGateway.lastStrongBackgroundActionId,
    )
    assertEquals("binder-strong-background-action", result?.get("source"))
  }
}
