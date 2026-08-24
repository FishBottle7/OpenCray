package com.opencray.app

import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeServiceHostServiceBackedShellGatewayTest : RuntimeServiceHostTestBase() {
  @Test
  fun serviceBackedShellGatewayPrefersBinderForLoadsAndFallsBackWhenUnavailable() {
    val binderGateway = RecordingShellGateway("binder")
    val fallbackGateway = RecordingShellGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = binderGateway,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )

    assertEquals("binder-shell", gateway.loadShellSnapshot()["source"])

    serviceClient.currentShellGateway = null

    assertEquals("fallback-shell", gateway.loadShellSnapshot()["source"])
    assertEquals(2, serviceClient.loadShellGatewayCallCount)
    assertEquals(0, serviceClient.peekShellGatewayCallCount)
  }

  @Test
  fun serviceBackedShellGatewayObserversSwitchBetweenFallbackAndBinderGateways() {
    val binderGateway = RecordingShellGateway("binder")
    val fallbackGateway = RecordingShellGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedShellSources = mutableListOf<String?>()

    gateway.observeShell { snapshot ->
      observedShellSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-shell"), observedShellSources)

    serviceClient.currentShellGateway = binderGateway
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-shell", "binder-shell"), observedShellSources)

    serviceClient.currentShellGateway = null
    serviceClient.emitConnectionStateChanged()

    assertEquals(listOf("fallback-shell", "binder-shell", "fallback-shell"), observedShellSources)
  }

  @Test
  fun serviceBackedShellGatewayDoesNotReemitFallbackSnapshotWhenGatewayDoesNotSwitch() {
    val fallbackGateway = RecordingShellGateway("fallback")
    val serviceClient = RecordingRuntimeServiceClient(
      currentShellGateway = null,
      currentChatGateway = null,
      currentSkillsGateway = null,
      currentSettingsGateway = null,
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = serviceClient,
      fallbackGateway = fallbackGateway,
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeShell { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-shell"), observedSources)

    serviceClient.emitConnectionStateChanged(RuntimeServiceConnectionState.bindingPending())

    assertEquals(listOf("fallback-shell"), observedSources)
  }

  @Test
  fun serviceBackedShellGatewayObservationDoesNotStartOrBindRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = client,
      fallbackGateway = RecordingShellGateway("fallback"),
    )
    val observedSources = mutableListOf<String?>()

    gateway.observeShell { snapshot ->
      observedSources += snapshot["source"] as String?
    }

    assertEquals(listOf("fallback-shell"), observedSources)
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
    assertEquals("fallback", client.loadConnectionState().phase)
  }

  @Test
  fun serviceBackedShellGatewayLoadStartsAndBindsRuntimeService() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val gateway = ServiceBackedOpenCrayShellGateway(
      serviceClient = client,
      fallbackGateway = RecordingShellGateway("fallback"),
    )

    assertEquals("fallback-shell", gateway.loadShellSnapshot()["source"])
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", client.loadConnectionState().phase)
  }
}
