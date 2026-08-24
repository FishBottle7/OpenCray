package com.opencray.app

import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostServiceBackedSkillsGatewayTest : RuntimeServiceHostTestBase() {
  @Test
  fun serviceBackedSkillsGatewayObservationStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = client,
      fallbackGateway = RecordingSkillsGateway("fallback"),
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSkills { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-skills"), observedSources)
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedSkillsGatewayLoadStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = client,
      fallbackGateway = RecordingSkillsGateway("fallback"),
    )

    assertEquals(
      "fallback-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedSkillsGatewayAllowsFallbackLoadsButRequiresBinderForCommands() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = binderGateway,
      dispatchSkillsWriteCommandHandler = binderGateway::dispatchSkillsWriteCommand,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals(
      "binder-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    assertEquals(
      "Installed roin-orca/skills via binder.",
      gateway.installSkillSource(sourceRef = "roin-orca/skills", selectedSkillName = ""),
    )
    assertEquals("roin-orca/skills", binderGateway.lastInstalledSourceRef)
    assertEquals(null, fallbackGateway.lastInstalledSourceRef)

    serviceClient.currentSkillsGateway = null
    serviceClient.dispatchSkillsWriteCommandHandler = null
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.inProcessFallback())

    assertEquals(
      "fallback-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    assertEquals(2, serviceClient.loadSkillsGatewayCallCount)
    assertEquals(0, serviceClient.peekSkillsGatewayCallCount)
    val failure = runCatching {
      gateway.installSkillSource(sourceRef = "fallback/skills", selectedSkillName = "")
    }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("installSkillSource") == true)
    assertTrue(failure?.message?.contains("phase=fallback") == true)
    assertEquals(null, fallbackGateway.lastInstalledSourceRef)
  }

  @Test
  fun serviceBackedSkillsGatewayWritesThroughDispatchWithoutBinderReadGateway() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
      dispatchSkillsWriteCommandHandler = binderGateway::dispatchSkillsWriteCommand,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals(
      "fallback-skills",
      gateway.loadSkillsSnapshot(query = "", suggestedLimit = 0)["source"],
    )
    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.binderConnected())
    assertEquals(
      "Installed roin-orca/skills via binder.",
      gateway.installSkillSource(sourceRef = "roin-orca/skills", selectedSkillName = ""),
    )
    assertEquals("roin-orca/skills", binderGateway.lastInstalledSourceRef)
    assertNull(fallbackGateway.lastInstalledSourceRef)
  }

  @Test
  fun serviceBackedSkillsGatewayUsesPassiveConnectionStateLookupForWriteDiagnostics() {
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    val failure = runCatching {
      gateway.installSkillSource(sourceRef = "fallback/skills", selectedSkillName = "")
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals(0, serviceClient.loadConnectionStateCallCount)
    assertEquals(1, serviceClient.peekConnectionStateCallCount)
    assertNull(fallbackGateway.lastInstalledSourceRef)
  }

  @Test
  fun serviceBackedSkillsGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSkills { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-skills"), observedSources)

    serviceClient.currentSkillsGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-skills", "binder-skills"), observedSources)

    serviceClient.currentSkillsGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-skills", "binder-skills", "fallback-skills"), observedSources)
  }

  @Test
  fun serviceBackedSkillsGatewayObserverRechecksGatewayAfterConnectionObservationStarts() {
    val binderGateway = RecordingSkillsGateway("binder")
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = SkillsGatewayAvailableOnObserveClient(binderGateway),
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeSkills { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-skills", "binder-skills"), observedSources)
  }

  @Test
  fun serviceBackedSkillsGatewayWaitsForPendingBinderBeforeInstallingSource() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("skills-gateway-await-binder"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSkillsGateway("binder")
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = ContextWrapper(null),
        bindingAdapter = bindingAdapter,
        startRequester = { },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { Intent() },
        isMainThread = { false },
      ),
      fallbackGateway = RecordingSkillsGateway("fallback"),
    )
    var result: String? = null
    var failure: Throwable? = null

    val worker = Thread {
      runCatching {
        gateway.installSkillSource(sourceRef = "roin-orca/skills", selectedSkillName = "")
      }.onSuccess { message ->
        result = message
      }.onFailure { throwable ->
        failure = throwable
      }
    }

    worker.start()
    waitForCondition(timeoutMs = 1_000L) { bindingAdapter.bindCount == 1 }
    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun dispatchSkillsWriteCommand(
          command: OpenCraySkillsWriteCommand,
        ): OpenCraySkillsWriteDispatchResult = binderGateway.dispatchSkillsWriteCommand(command)
      },
    )
    worker.join(1_000L)

    assertFalse(worker.isAlive)
    assertEquals(null, failure)
    assertEquals("roin-orca/skills", binderGateway.lastInstalledSourceRef)
    assertEquals("Installed roin-orca/skills via binder.", result)
  }

  @Test
  fun serviceBackedSkillsGatewayRejectsInProcessTransportWhenBinderIsUnavailable() {
    val bindingAdapter = RecordingBindingAdapter()
    val fallbackGateway = RecordingSkillsGateway("fallback")
    val serviceClient = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )
    val gateway = ServiceBackedOpenCraySkillsGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    val installFailure = runCatching {
      gateway.installSkillSourceBatch(
        sourceRef = "roin-orca/skills",
        selectedSkillNames = listOf("find-skills", "skill-creator"),
      )
    }.exceptionOrNull()
    val activationFailure = runCatching {
      gateway.activateSkillsInstallSource("github-url")
    }.exceptionOrNull()

    assertEquals(1, bindingAdapter.bindCount)
    assertTrue(installFailure is IllegalStateException)
    assertTrue(activationFailure is IllegalStateException)
    assertTrue(installFailure?.message?.contains("binder-backed runtime service gateway") == true)
    assertNull(fallbackGateway.lastActivatedSourceId)
    assertEquals("binding", serviceClient.loadConnectionState().phase)
    assertEquals("in_process", serviceClient.loadConnectionState().transport)
  }
}
