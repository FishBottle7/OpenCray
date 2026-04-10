package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

internal data class OnDeviceLlmWarmupSpec(
  val modelId: String,
  val backend: String,
  val maxContextWindow: Int,
  val maxTokens: Int,
  val topK: Int,
  val topP: Double,
  val temperature: Double,
  val thinkingEnabled: Boolean,
  val systemPrompt: String? = null,
  val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
  fun toRuntimeRequest(): LiteRtOnDeviceRuntimeRequest = LiteRtOnDeviceRuntimeRequest(
    requestId = "warmup-$modelId-$backend-$maxContextWindow",
    modelId = modelId,
    backend = backend,
    maxContextWindow = maxContextWindow,
    maxTokens = maxTokens,
    topK = topK,
    topP = topP,
    temperature = temperature,
    thinkingEnabled = thinkingEnabled,
    prompt = "",
    systemPrompt = systemPrompt,
    timeoutMs = timeoutMs,
  )

  companion object {
    private const val DEFAULT_TIMEOUT_MS: Long = 60_000L
  }
}

internal enum class OnDeviceLlmWarmupPhase {
  IDLE,
  WARMING,
  READY,
  FAILED,
}

internal data class OnDeviceLlmWarmupState(
  val phase: OnDeviceLlmWarmupPhase = OnDeviceLlmWarmupPhase.IDLE,
  val failureMessage: String? = null,
) {
  fun blocksChatInput(): Boolean = phase == OnDeviceLlmWarmupPhase.WARMING
}

internal interface OnDeviceLlmWarmupController {
  fun ensureWarm(spec: OnDeviceLlmWarmupSpec): OnDeviceLlmWarmupState

  fun clear(): OnDeviceLlmWarmupState
}

internal object NoOpOnDeviceLlmWarmupController : OnDeviceLlmWarmupController {
  private val idleState = OnDeviceLlmWarmupState()

  override fun ensureWarm(spec: OnDeviceLlmWarmupSpec): OnDeviceLlmWarmupState = idleState

  override fun clear(): OnDeviceLlmWarmupState = idleState
}

internal class AppOnDeviceLlmWarmupController(
  private val runtime: LiteRtOnDeviceRuntime,
  private val onStateChanged: () -> Unit,
) : OnDeviceLlmWarmupController {
  private val lock = Any()
  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor(OnDeviceLlmWarmupThreadFactory())

  private var desiredSpec: OnDeviceLlmWarmupSpec? = null
  private var workerScheduled: Boolean = false
  private var state: OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()

  override fun ensureWarm(spec: OnDeviceLlmWarmupSpec): OnDeviceLlmWarmupState {
    synchronized(lock) {
      if (desiredSpec == spec &&
        state.phase in setOf(
          OnDeviceLlmWarmupPhase.WARMING,
          OnDeviceLlmWarmupPhase.READY,
          OnDeviceLlmWarmupPhase.FAILED,
        )
      ) {
        return state
      }
      desiredSpec = spec
      state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.WARMING)
      if (!workerScheduled) {
        workerScheduled = true
        executor.execute(::drainWarmupQueue)
      }
      return state
    }
  }

  override fun clear(): OnDeviceLlmWarmupState = synchronized(lock) {
    desiredSpec = null
    state = OnDeviceLlmWarmupState()
    state
  }

  private fun drainWarmupQueue() {
    while (true) {
      val spec = synchronized(lock) {
        desiredSpec ?: run {
          workerScheduled = false
          return
        }
      }
      val result = runtime.prewarm(spec.toRuntimeRequest())
      val shouldContinue = synchronized(lock) {
        when {
          desiredSpec == null -> {
            state = OnDeviceLlmWarmupState()
            false
          }
          desiredSpec != spec -> {
            true
          }
          result is LiteRtOnDevicePrewarmResult.Success -> {
            state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.READY)
            workerScheduled = false
            false
          }
          result is LiteRtOnDevicePrewarmResult.Failure -> {
            state = OnDeviceLlmWarmupState(
              phase = OnDeviceLlmWarmupPhase.FAILED,
              failureMessage = result.errorMessage,
            )
            workerScheduled = false
            false
          }
          else -> {
            workerScheduled = false
            false
          }
        }
      }
      onStateChanged()
      if (!shouldContinue) {
        return
      }
    }
  }
}

internal fun LlmConfigSnapshot.onDeviceWarmupSpecOrNull(): OnDeviceLlmWarmupSpec? {
  if (!enabled || providerMode != LlmProviderModes.ON_DEVICE_MODEL) {
    return null
  }
  val normalizedModelId = selectedOnDeviceModelId.trim().takeIf(String::isNotBlank) ?: return null
  val selectedModel = onDeviceModels.firstOrNull { option ->
    option.id == normalizedModelId
  } ?: return null
  if (selectedModel.installState != OnDeviceLlmDownloadStates.READY || !selectedModel.sha256Verified) {
    return null
  }
  return OnDeviceLlmWarmupSpec(
    modelId = normalizedModelId,
    backend = onDeviceAccelerator,
    maxContextWindow = onDeviceMaxContextWindow,
    maxTokens = onDeviceMaxTokens,
    topK = onDeviceTopK,
    topP = onDeviceTopP,
    temperature = onDeviceTemperature,
    thinkingEnabled = onDeviceThinkingEnabled,
    systemPrompt = systemPrompt.trim().takeIf(String::isNotBlank),
  )
}

private class OnDeviceLlmWarmupThreadFactory : ThreadFactory {
  override fun newThread(runnable: Runnable): Thread = Thread(
    runnable,
    "opencray-litertlm-warmup",
  ).apply {
    isDaemon = true
  }
}
