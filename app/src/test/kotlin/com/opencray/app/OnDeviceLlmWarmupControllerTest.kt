package com.opencray.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceLlmWarmupControllerTest {
  @Test
  fun clearReleasesActiveModelAfterWarmupCompletes() {
    val runtime = RecordingWarmupRuntime()
    val stateChanged = CountDownLatch(1)
    val controller = AppOnDeviceLlmWarmupController(
      runtime = runtime,
      onStateChanged = { stateChanged.countDown() },
    )

    val warming = controller.ensureWarm(readyWarmupSpec())

    assertEquals(OnDeviceLlmWarmupPhase.WARMING, warming.phase)
    assertTrue(stateChanged.await(2, TimeUnit.SECONDS))

    val cleared = controller.clear()

    assertEquals(OnDeviceLlmWarmupPhase.IDLE, cleared.phase)
    assertEquals(1, runtime.prewarmCount)
    assertEquals(1, runtime.releaseCount)
  }

  private fun readyWarmupSpec(): OnDeviceLlmWarmupSpec = OnDeviceLlmWarmupSpec(
    modelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
    backend = OnDeviceLlmAccelerators.GPU,
    maxContextWindow = 32_768,
    maxTokens = 4_096,
    topK = 40,
    topP = 0.95,
    temperature = 0.7,
    thinkingEnabled = false,
  )

  private class RecordingWarmupRuntime : LiteRtOnDeviceRuntime(
    installStore = InMemoryLiteRtOnDeviceModelInstallStore(),
  ) {
    var prewarmCount: Int = 0
      private set
    var releaseCount: Int = 0
      private set

    override fun prewarm(request: LiteRtOnDeviceRuntimeRequest): LiteRtOnDevicePrewarmResult {
      prewarmCount += 1
      return LiteRtOnDevicePrewarmResult.Success()
    }

    override fun releaseActiveModel() {
      releaseCount += 1
    }
  }
}
