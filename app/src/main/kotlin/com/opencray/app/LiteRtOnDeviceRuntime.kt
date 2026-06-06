package com.opencray.app

import android.content.Context
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmToolDefinition
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.opencray.litertlmbridge.LiteRtLmBridge

internal object LiteRtOnDeviceFailureCodes {
  const val MODEL_NOT_INSTALLED: String = "MODEL_NOT_INSTALLED"
  const val MODEL_FILE_MISSING: String = "MODEL_FILE_MISSING"
  const val MODEL_HASH_MISMATCH: String = "MODEL_HASH_MISMATCH"
  const val BACKEND_UNAVAILABLE: String = "BACKEND_UNAVAILABLE"
  const val MODEL_LOAD_FAILED: String = "MODEL_LOAD_FAILED"
  const val LOCAL_INFERENCE_TIMEOUT: String = "LOCAL_INFERENCE_TIMEOUT"
  const val LOCAL_INFERENCE_CANCELLED: String = "LOCAL_INFERENCE_CANCELLED"
  const val REQUEST_NOT_SUPPORTED: String = "REQUEST_NOT_SUPPORTED"
}

internal object LiteRtOnDeviceMetadataKeys {
  const val PROVIDER_MODE: String = "providerMode"
  const val RUNTIME: String = "onDeviceRuntime"
  const val MODEL_ID: String = "onDeviceModelId"
  const val BACKEND: String = "onDeviceBackend"
  const val MAX_CONTEXT_WINDOW: String = "onDeviceMaxContextWindow"
  const val THINKING_ENABLED: String = "onDeviceThinkingEnabled"
  const val THINKING_LABEL: String = "onDeviceThinkingLabel"
  const val LITE_MODE_ENABLED: String = "onDeviceLiteModeEnabled"
  const val INSTALLED: String = "onDeviceInstalled"
  const val SHA256_VERIFIED: String = "onDeviceSha256Verified"
  const val CONTEXT_WINDOW_TOKENS: String = "onDeviceContextWindowTokens"
}

internal data class LiteRtOnDeviceRuntimeRequest(
  val requestId: String,
  val modelId: String,
  val backend: String,
  val maxContextWindow: Int,
  val maxTokens: Int,
  val topK: Int,
  val topP: Double,
  val temperature: Double,
  val streamingEnabled: Boolean = LlmSettingsState.DEFAULT_STREAMING_ENABLED,
  val thinkingEnabled: Boolean,
  val prompt: String,
  val systemPrompt: String? = null,
  val messages: List<LiteLlmGatewayMessage> = emptyList(),
  val tools: List<LiteLlmToolDefinition> = emptyList(),
  val builtinTools: List<LiteLlmBuiltinToolDefinition> = emptyList(),
  val toolChoice: LiteLlmToolChoice? = null,
  val parallelToolCalls: Boolean? = null,
  val timeoutMs: Long,
  val automaticToolExecutionContext: LiteRtAutomaticToolExecutionContext? = null,
)

internal sealed interface LiteRtOnDeviceRuntimeResult {
  data class Success(
    val outputText: String,
    val completion: LiteLlmStructuredCompletion? = null,
    val finishReason: String? = null,
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteRtOnDeviceRuntimeResult

  data class Timeout(
    val errorMessage: String,
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteRtOnDeviceRuntimeResult

  data class Failure(
    val errorCode: String,
    val errorMessage: String,
    val completion: LiteLlmStructuredCompletion? = null,
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteRtOnDeviceRuntimeResult
}

internal sealed interface LiteRtOnDevicePrewarmResult {
  data class Success(
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteRtOnDevicePrewarmResult

  data class Failure(
    val errorCode: String,
    val errorMessage: String,
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteRtOnDevicePrewarmResult
}

internal interface LiteRtOnDeviceEngineHandle : AutoCloseable {
  fun generate(request: LiteRtOnDeviceRuntimeRequest): LiteRtOnDeviceRuntimeResult

  fun prewarm(request: LiteRtOnDeviceRuntimeRequest)

  fun cancelActiveGeneration()
}

internal fun interface LiteRtOnDeviceEngineFactory {
  fun create(
    modelFile: File,
    backend: String,
    maxContextWindow: Int,
  ): LiteRtOnDeviceEngineHandle
}

private data class LiteRtOnDeviceActiveModelKey(
  val modelPath: String,
  val backend: String,
  val maxContextWindow: Int,
)

internal object LiteRtLmEngineFactory : LiteRtOnDeviceEngineFactory {
  override fun create(
    modelFile: File,
    backend: String,
    maxContextWindow: Int,
  ): LiteRtOnDeviceEngineHandle = LiteRtLmBridgeEngineHandle(
    delegate = LiteRtLmBridge.createEngineHandle(
      modelFile.absolutePath,
      backend,
      maxContextWindow,
    ),
  )
}

internal open class LiteRtOnDeviceRuntime(
  private val installStore: LiteRtOnDeviceModelInstallStore,
  private val engineFactory: LiteRtOnDeviceEngineFactory = LiteRtLmEngineFactory,
) {
  private val inferenceExecutor: ExecutorService =
    Executors.newSingleThreadExecutor(LiteRtOnDeviceThreadFactory())

  private var activeModelKey: LiteRtOnDeviceActiveModelKey? = null
  private var activeEngineHandle: LiteRtOnDeviceEngineHandle? = null

  @Synchronized
  open fun execute(
    request: LiteRtOnDeviceRuntimeRequest,
  ): LiteRtOnDeviceRuntimeResult {
    return when (val prepared = prepareExecution(request)) {
      is LiteRtOnDevicePreparedExecution.Ready -> executeWithTimeout(
        engineHandle = prepared.engineHandle,
        request = prepared.request,
      )
      is LiteRtOnDevicePreparedExecution.Failure -> prepared.result
    }
  }

  @Synchronized
  open fun prewarm(
    request: LiteRtOnDeviceRuntimeRequest,
  ): LiteRtOnDevicePrewarmResult = when (val prepared = prepareExecution(request)) {
    is LiteRtOnDevicePreparedExecution.Ready -> prewarmWithTimeout(
      engineHandle = prepared.engineHandle,
      request = prepared.request,
    )
    is LiteRtOnDevicePreparedExecution.Failure -> LiteRtOnDevicePrewarmResult.Failure(
      errorCode = prepared.result.errorCode,
      errorMessage = prepared.result.errorMessage,
      metadata = prepared.result.metadata,
    )
  }

  @Synchronized
  open fun releaseActiveModel() {
    activeEngineHandle?.runCatching { cancelActiveGeneration() }
    activeEngineHandle?.runCatching { close() }
    activeEngineHandle = null
    activeModelKey = null
  }

  @Synchronized
  open fun cancelActiveGeneration() {
    activeEngineHandle?.runCatching { cancelActiveGeneration() }
  }

  protected fun failure(
    request: LiteRtOnDeviceRuntimeRequest,
    errorCode: String,
    errorMessage: String,
    installed: Boolean,
    sha256Verified: Boolean,
  ): LiteRtOnDeviceRuntimeResult.Failure = LiteRtOnDeviceRuntimeResult.Failure(
    errorCode = errorCode,
    errorMessage = errorMessage,
    metadata = runtimeMetadata(
      request = request,
      installed = installed,
      sha256Verified = sha256Verified,
    ),
  )

  protected fun runtimeMetadata(
    request: LiteRtOnDeviceRuntimeRequest,
    installed: Boolean,
    sha256Verified: Boolean,
  ): Map<String, String> = mapOf(
    LiteRtOnDeviceMetadataKeys.PROVIDER_MODE to LlmProviderModes.ON_DEVICE_MODEL,
    LiteRtOnDeviceMetadataKeys.RUNTIME to OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM,
    LiteRtOnDeviceMetadataKeys.MODEL_ID to request.modelId,
    LiteRtOnDeviceMetadataKeys.BACKEND to request.backend,
    "stream" to request.streamingEnabled.toString(),
    LiteRtOnDeviceMetadataKeys.MAX_CONTEXT_WINDOW to request.maxContextWindow.toString(),
    LiteRtOnDeviceMetadataKeys.THINKING_ENABLED to request.thinkingEnabled.toString(),
    LiteRtOnDeviceMetadataKeys.INSTALLED to installed.toString(),
    LiteRtOnDeviceMetadataKeys.SHA256_VERIFIED to sha256Verified.toString(),
    LiteRtOnDeviceMetadataKeys.CONTEXT_WINDOW_TOKENS to request.maxContextWindow.toString(),
  )

  private fun unsupportedRequestFailure(
    request: LiteRtOnDeviceRuntimeRequest,
  ): LiteRtOnDeviceRuntimeResult.Failure? {
    if (request.toolChoice?.mode == LiteLlmToolChoiceMode.TOOL &&
      request.availableTools().isEmpty()
    ) {
      return failure(
        request = request,
        errorCode = LiteRtOnDeviceFailureCodes.REQUEST_NOT_SUPPORTED,
        errorMessage = "LiteRT-LM on-device mode was asked to select a named tool that is not available.",
        installed = true,
        sha256Verified = true,
      )
    }
    return null
  }

  private fun activeEngineHandleFor(
    modelFile: File,
    backend: String,
    maxContextWindow: Int,
  ): LiteRtOnDeviceEngineHandle {
    val nextKey = LiteRtOnDeviceActiveModelKey(
      modelPath = modelFile.absolutePath,
      backend = backend,
      maxContextWindow = maxContextWindow,
    )
    if (activeModelKey == nextKey && activeEngineHandle != null) {
      return checkNotNull(activeEngineHandle)
    }
    releaseActiveModel()
    return engineFactory.create(
      modelFile = modelFile,
      backend = backend,
      maxContextWindow = maxContextWindow,
    ).also { created ->
      activeModelKey = nextKey
      activeEngineHandle = created
    }
  }

  private fun prepareExecution(
    request: LiteRtOnDeviceRuntimeRequest,
  ): LiteRtOnDevicePreparedExecution {
    val entry = OnDeviceLlmCatalog.entry(request.modelId)
      ?: return LiteRtOnDevicePreparedExecution.Failure(
        failure(
          request = request,
          errorCode = LiteRtOnDeviceFailureCodes.MODEL_NOT_INSTALLED,
          errorMessage = "Unsupported on-device model '${request.modelId}'.",
          installed = false,
          sha256Verified = false,
        ),
      )
    val installRecord = installStore.load(entry.id)
      ?: return LiteRtOnDevicePreparedExecution.Failure(
        failure(
          request = request.copy(modelId = entry.id),
          errorCode = LiteRtOnDeviceFailureCodes.MODEL_NOT_INSTALLED,
          errorMessage = "${entry.title} is not downloaded yet.",
          installed = false,
          sha256Verified = false,
        ),
      )
    val localFile = installRecord.localFilePath
      .trim()
      .takeIf(String::isNotBlank)
      ?.let(::File)
    if (installRecord.installState != OnDeviceLlmDownloadStates.READY) {
      return LiteRtOnDevicePreparedExecution.Failure(
        failure(
          request = request.copy(modelId = entry.id),
          errorCode = LiteRtOnDeviceFailureCodes.MODEL_NOT_INSTALLED,
          errorMessage = installRecord.lastError ?: "${entry.title} is not ready yet.",
          installed = false,
          sha256Verified = installRecord.sha256Verified,
        ),
      )
    }
    if (localFile == null || !localFile.isFile) {
      return LiteRtOnDevicePreparedExecution.Failure(
        failure(
          request = request.copy(modelId = entry.id),
          errorCode = LiteRtOnDeviceFailureCodes.MODEL_FILE_MISSING,
          errorMessage = "${entry.title} is marked ready but the local model file is missing.",
          installed = false,
          sha256Verified = installRecord.sha256Verified,
        ),
      )
    }
    if (!installRecord.sha256Verified) {
      return LiteRtOnDevicePreparedExecution.Failure(
        failure(
          request = request.copy(modelId = entry.id),
          errorCode = LiteRtOnDeviceFailureCodes.MODEL_HASH_MISMATCH,
          errorMessage = "${entry.title} failed integrity verification.",
          installed = true,
          sha256Verified = false,
        ),
      )
    }
    val normalizedContextWindow = LlmSettingsState.normalizedOnDeviceMaxContextWindow(
      request.maxContextWindow,
    )
    val normalizedRequest = request.copy(
      modelId = entry.id,
      backend = OnDeviceLlmAccelerators.normalize(request.backend),
      maxContextWindow = normalizedContextWindow,
      maxTokens = LlmSettingsState.normalizedOnDeviceMaxTokens(
        rawValue = request.maxTokens,
        contextWindow = normalizedContextWindow,
      ),
      topK = LlmSettingsState.normalizedOnDeviceTopK(request.topK),
      topP = LlmSettingsState.normalizedOnDeviceTopP(request.topP),
      temperature = LlmSettingsState.normalizedOnDeviceTemperature(request.temperature),
    )
    unsupportedRequestFailure(normalizedRequest)?.let { failure ->
      return LiteRtOnDevicePreparedExecution.Failure(failure)
    }
    val engineHandle = try {
      activeEngineHandleFor(
        modelFile = localFile,
        backend = normalizedRequest.backend,
        maxContextWindow = normalizedRequest.maxContextWindow,
      )
    } catch (throwable: Throwable) {
      return LiteRtOnDevicePreparedExecution.Failure(
        failure(
          request = normalizedRequest,
          errorCode = runtimeFailureCodeFor(
            backend = normalizedRequest.backend,
            throwable = throwable,
          ),
          errorMessage = runtimeFailureMessageFor(
            backend = normalizedRequest.backend,
            throwable = throwable,
          ),
          installed = true,
          sha256Verified = true,
        ),
      )
    }
    return LiteRtOnDevicePreparedExecution.Ready(
      request = normalizedRequest,
      engineHandle = engineHandle,
    )
  }

  private fun executeWithTimeout(
    engineHandle: LiteRtOnDeviceEngineHandle,
    request: LiteRtOnDeviceRuntimeRequest,
  ): LiteRtOnDeviceRuntimeResult {
    val future: Future<LiteRtOnDeviceRuntimeResult> =
      inferenceExecutor.submit<LiteRtOnDeviceRuntimeResult> {
        engineHandle.generate(request)
      }
    return try {
      future.get(request.timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      future.cancel(true)
      engineHandle.cancelActiveGeneration()
      LiteRtOnDeviceRuntimeResult.Timeout(
        errorMessage = "LiteRT-LM inference timed out after ${request.timeoutMs} ms.",
        metadata = runtimeMetadata(
          request = request,
          installed = true,
          sha256Verified = true,
        ),
      )
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      future.cancel(true)
      engineHandle.cancelActiveGeneration()
      failure(
        request = request,
        errorCode = LiteRtOnDeviceFailureCodes.LOCAL_INFERENCE_CANCELLED,
        errorMessage = "LiteRT-LM inference was interrupted locally.",
        installed = true,
        sha256Verified = true,
      )
    } catch (error: ExecutionException) {
      failure(
        request = request,
        errorCode = runtimeFailureCodeFor(
          backend = request.backend,
          throwable = error.cause ?: error,
        ),
        errorMessage = runtimeFailureMessageFor(
          backend = request.backend,
          throwable = error.cause ?: error,
        ),
        installed = true,
        sha256Verified = true,
      )
    }
  }

  private fun prewarmWithTimeout(
    engineHandle: LiteRtOnDeviceEngineHandle,
    request: LiteRtOnDeviceRuntimeRequest,
  ): LiteRtOnDevicePrewarmResult {
    val future: Future<Unit> =
      inferenceExecutor.submit<Unit> {
        engineHandle.prewarm(request)
      }
    return try {
      future.get(request.timeoutMs, TimeUnit.MILLISECONDS)
      LiteRtOnDevicePrewarmResult.Success(
        metadata = runtimeMetadata(
          request = request,
          installed = true,
          sha256Verified = true,
        ),
      )
    } catch (_: TimeoutException) {
      future.cancel(true)
      engineHandle.cancelActiveGeneration()
      LiteRtOnDevicePrewarmResult.Failure(
        errorCode = LiteRtOnDeviceFailureCodes.LOCAL_INFERENCE_TIMEOUT,
        errorMessage = "LiteRT-LM warmup timed out after ${request.timeoutMs} ms.",
        metadata = runtimeMetadata(
          request = request,
          installed = true,
          sha256Verified = true,
        ),
      )
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      future.cancel(true)
      engineHandle.cancelActiveGeneration()
      LiteRtOnDevicePrewarmResult.Failure(
        errorCode = LiteRtOnDeviceFailureCodes.LOCAL_INFERENCE_CANCELLED,
        errorMessage = "LiteRT-LM warmup was interrupted locally.",
        metadata = runtimeMetadata(
          request = request,
          installed = true,
          sha256Verified = true,
        ),
      )
    } catch (error: ExecutionException) {
      LiteRtOnDevicePrewarmResult.Failure(
        errorCode = runtimeFailureCodeFor(
          backend = request.backend,
          throwable = error.cause ?: error,
        ),
        errorMessage = runtimeFailureMessageFor(
          backend = request.backend,
          throwable = error.cause ?: error,
        ),
        metadata = runtimeMetadata(
          request = request,
          installed = true,
          sha256Verified = true,
        ),
      )
    }
  }

  private fun runtimeFailureCodeFor(
    backend: String,
    throwable: Throwable,
  ): String {
    val message = throwable.message.orEmpty().lowercase()
    return if (backend == OnDeviceLlmAccelerators.GPU &&
      (
        "opencl" in message ||
          "gpu" in message ||
          "delegate" in message ||
          "backend" in message
        )
    ) {
      LiteRtOnDeviceFailureCodes.BACKEND_UNAVAILABLE
    } else {
      LiteRtOnDeviceFailureCodes.MODEL_LOAD_FAILED
    }
  }

  private fun runtimeFailureMessageFor(
    backend: String,
    throwable: Throwable,
  ): String {
    val detail = throwable.message?.trim()?.takeIf { it.isNotBlank() }
    if (backend == OnDeviceLlmAccelerators.GPU &&
      runtimeFailureCodeFor(
        backend = backend,
        throwable = throwable,
      ) == LiteRtOnDeviceFailureCodes.BACKEND_UNAVAILABLE
    ) {
      return detail ?: "GPU backend is unavailable for the selected LiteRT-LM model."
    }
    return detail ?: "LiteRT-LM failed to load or execute the selected local model."
  }

  companion object {
    @Volatile
    private var instance: LiteRtOnDeviceRuntime? = null

    fun fromContext(
      context: Context,
      installStore: LiteRtOnDeviceModelInstallStore =
        LiteRtOnDeviceModelInstallStore.fromContext(context.applicationContext),
    ): LiteRtOnDeviceRuntime =
      instance ?: synchronized(this) {
        instance ?: LiteRtOnDeviceRuntime(
          installStore = installStore,
        ).also { created ->
          instance = created
        }
      }

    fun clearForTest() {
      synchronized(this) {
        instance?.releaseActiveModel()
        instance = null
      }
    }
  }
}

private class LiteRtOnDeviceThreadFactory : ThreadFactory {
  override fun newThread(runnable: Runnable): Thread = Thread(
    runnable,
    "opencray-litertlm-runtime",
  ).apply {
    isDaemon = true
  }
}

private class LiteRtLmBridgeEngineHandle(
  private val delegate: LiteRtLmBridge.EngineHandle,
) : LiteRtOnDeviceEngineHandle {
  override fun generate(request: LiteRtOnDeviceRuntimeRequest): LiteRtOnDeviceRuntimeResult {
    val response = delegate.generate(request.toBridgeRequest())
    return response.toRuntimeSuccess(request)
  }

  override fun prewarm(request: LiteRtOnDeviceRuntimeRequest) {
    delegate.prewarm(request.toBridgeRequest())
  }

  override fun cancelActiveGeneration() {
    delegate.cancelActiveGeneration()
  }

  override fun close() {
    delegate.close()
  }
}

private sealed interface LiteRtOnDevicePreparedExecution {
  data class Ready(
    val request: LiteRtOnDeviceRuntimeRequest,
    val engineHandle: LiteRtOnDeviceEngineHandle,
  ) : LiteRtOnDevicePreparedExecution

  data class Failure(
    val result: LiteRtOnDeviceRuntimeResult.Failure,
  ) : LiteRtOnDevicePreparedExecution
}

private fun LiteRtOnDeviceRuntimeRequest.functionTools(): List<LiteLlmToolDefinition> =
  (tools + builtinTools.mapNotNull(LiteLlmBuiltinToolDefinition::toFunctionToolDefinition))
    .distinctBy { definition -> definition.name.lowercase() }

private fun LiteRtOnDeviceRuntimeRequest.availableTools(): List<LiteLlmToolDefinition> = when (
  toolChoice?.mode
) {
  LiteLlmToolChoiceMode.NONE -> emptyList()
  LiteLlmToolChoiceMode.TOOL -> {
    val requestedToolName = toolChoice.toolName?.trim().orEmpty()
    functionTools().filter { definition ->
      definition.name.equals(requestedToolName, ignoreCase = true)
    }
  }
  else -> functionTools()
}

private fun LiteRtOnDeviceRuntimeRequest.toBridgeRequest(): LiteRtLmBridge.Request =
  LiteRtLmBridge.Request(
    if (messages.isEmpty()) prompt else "",
    systemPrompt?.trim()?.takeIf(String::isNotBlank),
    messages.map(LiteLlmGatewayMessage::toBridgePayload),
    availableTools().map(LiteLlmToolDefinition::toBridgeDefinition),
    topK,
    topP,
    temperature,
    thinkingEnabled,
    automaticToolExecutionContext != null,
    automaticToolExecutionContext?.let(::LiteRtAutomaticToolExecutor),
  )

private fun LiteLlmToolDefinition.toBridgeDefinition(): LiteRtLmBridge.ToolDefinition =
  LiteRtLmBridge.ToolDefinition(
    name,
    description,
    inputSchema.toString(),
  )

private fun LiteLlmGatewayMessage.toBridgePayload(): LiteRtLmBridge.MessagePayload =
  LiteRtLmBridge.MessagePayload(
    when (role) {
      LiteLlmGatewayMessageRole.SYSTEM -> "system"
      LiteLlmGatewayMessageRole.USER -> "user"
      LiteLlmGatewayMessageRole.ASSISTANT -> "model"
      LiteLlmGatewayMessageRole.TOOL -> "tool"
    },
    mergedTextContent(),
    toolCalls.map(LiteLlmStructuredToolCall::toBridgePayload),
    toolResult?.toBridgePayload(),
  )

private fun LiteLlmGatewayMessage.mergedTextContent(): String? = buildList {
  content?.trim()?.takeIf(String::isNotBlank)?.let(::add)
  attachments.map(LiteLlmGatewayAttachment::toLiteRtTextBlock)
    .takeIf { attachmentBlocks -> attachmentBlocks.isNotEmpty() }
    ?.joinToString(separator = "\n\n")
    ?.let(::add)
}.joinToString(separator = "\n\n").trim().takeIf(String::isNotBlank)

private fun LiteLlmStructuredToolCall.toBridgePayload(): LiteRtLmBridge.ToolCallPayload =
  LiteRtLmBridge.ToolCallPayload(
    toolName,
    arguments.toLiteRtMap(),
  )

private fun LiteLlmGatewayToolResult.toBridgePayload(): LiteRtLmBridge.ToolResultPayload =
  LiteRtLmBridge.ToolResultPayload(
    toolName?.trim().orEmpty(),
    toLiteRtToolResponsePayload(),
  )

private fun LiteLlmGatewayToolResult.toLiteRtToolResponsePayload(): Any? {
  structuredContent?.let { structured -> structured.toLiteRtValue() }?.let { return it }
  return content.parseLooseJsonValueOrNull() ?: content
}

private fun String.parseLooseJsonValueOrNull(): Any? {
  val trimmed = trim()
  if (trimmed.isEmpty()) {
    return null
  }
  val looksLikeJson = trimmed.startsWith("{") ||
    trimmed.startsWith("[") ||
    trimmed == "true" ||
    trimmed == "false" ||
    trimmed == "null" ||
    trimmed.startsWith("\"") ||
    trimmed.matches(Regex("-?\\d+(\\.\\d+)?"))
  if (!looksLikeJson) {
    return null
  }
  return runCatching {
    JSONTokener(trimmed).nextValue().toLiteRtValue()
  }.getOrNull()
}

private fun Any?.toLiteRtValue(): Any? = when (this) {
  null,
  JSONObject.NULL -> null
  is JSONObject -> keys().asSequence().associateWith { key ->
    opt(key).toLiteRtValue()
  }
  is JSONArray -> (0 until length()).map { index ->
    opt(index).toLiteRtValue()
  }
  else -> this
}

private fun JsonObject.toLiteRtMap(): Map<String, Any?> = entries.associate { (key, value) ->
  key to value.toLiteRtValue()
}

private fun JsonElement.toLiteRtValue(): Any? = when (this) {
  JsonNull -> null
  is JsonObject -> toLiteRtMap()
  is JsonArray -> map(JsonElement::toLiteRtValue)
  is JsonPrimitive -> when {
    isString -> content
    booleanOrNull != null -> booleanOrNull
    longOrNull != null -> longOrNull
    doubleOrNull != null -> doubleOrNull
    else -> content
  }
}

internal fun LiteRtLmBridge.Response.toRuntimeSuccess(
  request: LiteRtOnDeviceRuntimeRequest,
): LiteRtOnDeviceRuntimeResult.Success {
  val structuredToolCalls = toolCalls.mapIndexed { index, toolCall ->
    LiteLlmStructuredToolCall(
      id = "${request.requestId}-tool-${index + 1}",
      toolName = toolCall.name,
      arguments = toolCall.arguments.toJsonObject(),
    )
  }
  val visibleText = text.trim()
  val reasoningText = channels.entries.firstOrNull { (name, _) ->
    name.equals("thinking", ignoreCase = true)
  }?.value?.trim()?.takeIf(String::isNotBlank)
  val finishReason = if (structuredToolCalls.isNotEmpty()) {
    "tool_calls"
  } else {
    "stop"
  }
  val completion = when {
    structuredToolCalls.isNotEmpty() -> LiteLlmStructuredCompletion(
      toolCalls = structuredToolCalls,
      commentaryText = visibleText.takeIf(String::isNotBlank),
      reasoningText = reasoningText,
      rawText = visibleText.takeIf(String::isNotBlank),
    )

    visibleText.isBlank() -> LiteLlmStructuredCompletion(
      reasoningText = reasoningText,
    )

    else -> visibleText.toLegacyActionBatchOrNull()
      ?.toStructuredCompletionOrNull(request.requestId)
      ?.copy(reasoningText = reasoningText)
      ?: LiteLlmStructuredCompletion(
        finalText = visibleText,
        reasoningText = reasoningText,
        rawText = visibleText,
      )
  }
  return LiteRtOnDeviceRuntimeResult.Success(
    outputText = visibleText,
    completion = completion.takeIf { it.hasVisibleContent },
    finishReason = finishReason,
    metadata = mapOf(
      LiteRtOnDeviceMetadataKeys.PROVIDER_MODE to LlmProviderModes.ON_DEVICE_MODEL,
      LiteRtOnDeviceMetadataKeys.RUNTIME to OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM,
      LiteRtOnDeviceMetadataKeys.MODEL_ID to request.modelId,
      LiteRtOnDeviceMetadataKeys.BACKEND to request.backend,
      LiteRtOnDeviceMetadataKeys.MAX_CONTEXT_WINDOW to request.maxContextWindow.toString(),
      LiteRtOnDeviceMetadataKeys.THINKING_ENABLED to request.thinkingEnabled.toString(),
      LiteRtOnDeviceMetadataKeys.INSTALLED to true.toString(),
      LiteRtOnDeviceMetadataKeys.SHA256_VERIFIED to true.toString(),
      LiteRtOnDeviceMetadataKeys.CONTEXT_WINDOW_TOKENS to request.maxContextWindow.toString(),
    ),
  )
}

private sealed interface LiteRtLegacyAction {
  data class Commentary(
    val text: String,
  ) : LiteRtLegacyAction

  data class Final(
    val answer: String,
    val hasAttachments: Boolean,
  ) : LiteRtLegacyAction

  data class ToolCall(
    val id: String? = null,
    val toolName: String,
    val arguments: JsonObject,
    val reason: String? = null,
  ) : LiteRtLegacyAction
}

private fun String.toLegacyActionBatchOrNull(): List<LiteRtLegacyAction>? {
  val trimmed = trim()
  if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
    return null
  }
  val parsed = runCatching {
    Json.parseToJsonElement(trimmed) as? JsonObject
  }.getOrNull() ?: return null
  return runCatching {
    parsed.toLegacyActionBatch()
  }.getOrNull()
}

private fun JsonObject.toLegacyActionBatch(): List<LiteRtLegacyAction> {
  val nestedActions = (this["actions"] as? JsonArray)
    ?.map { element ->
      (element as? JsonObject ?: error("Each action inside 'actions' must be a JSON object."))
        .toLegacyActionBatch()
    }
    .orEmpty()
  if (nestedActions.isNotEmpty()) {
    return nestedActions.flatten()
  }

  val type = primitiveContent("type")?.trim()?.lowercase()
    ?: primitiveContent("decision")?.trim()?.lowercase()
  val hasToolCallShape = primitiveContent("tool_name")?.isNotBlank() == true
  val hasFinalAnswerShape = primitiveContent("answer")?.isNotBlank() == true
  val toolCalls = (this["tool_calls"] as? JsonArray)
    ?.map { element ->
      val toolCallObject = element as? JsonObject
        ?: error("Each entry inside 'tool_calls' must be a JSON object.")
      toolCallObject.toLegacyToolCall()
    }
    .orEmpty()
  if (toolCalls.isNotEmpty()) {
    return toolCalls
  }

  return when {
    type in setOf("tool_call", "tool") || hasToolCallShape -> listOf(toLegacyToolCall())

    type in setOf("progress", "commentary", "status") -> listOf(
      LiteRtLegacyAction.Commentary(
        text = primitiveContent("text")
          ?.trim()
          .orEmpty()
          .ifBlank {
            primitiveContent("summary")
              ?.trim()
              .orEmpty()
              .ifBlank {
                primitiveContent("message")
                  ?.trim()
                  .orEmpty()
                  .ifBlank {
                    error("commentary action must contain a non-blank 'text'.")
                  }
              }
          },
      ),
    )

    type in setOf("final", "answer") || (type == null && hasFinalAnswerShape) -> listOf(
      LiteRtLegacyAction.Final(
        answer = primitiveContent("answer")?.trim().orEmpty(),
        hasAttachments = (this["attachments"] as? JsonArray)?.isNotEmpty() == true,
      ),
    )

    else -> error("Not a legacy action payload.")
  }
}

private fun JsonObject.toLegacyToolCall(): LiteRtLegacyAction.ToolCall = LiteRtLegacyAction.ToolCall(
  id = primitiveContent("id")?.trim()?.takeIf(String::isNotBlank)
    ?: primitiveContent("tool_call_id")?.trim()?.takeIf(String::isNotBlank),
  toolName = primitiveContent("tool_name")?.trim().orEmpty().ifBlank {
    error("tool_call action must contain a non-blank 'tool_name'.")
  },
  arguments = this["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
  reason = primitiveContent("reason")?.trim()?.takeIf(String::isNotBlank)
    ?: primitiveContent("justification")?.trim()?.takeIf(String::isNotBlank),
)

private fun List<LiteRtLegacyAction>.toStructuredCompletionOrNull(
  requestId: String,
): LiteLlmStructuredCompletion? {
  if (isEmpty()) {
    return null
  }
  val commentaryActions = filterIsInstance<LiteRtLegacyAction.Commentary>()
  val finalActions = filterIsInstance<LiteRtLegacyAction.Final>()
  val toolCallActions = filterIsInstance<LiteRtLegacyAction.ToolCall>()
  if (commentaryActions.size > 1) {
    return null
  }
  if (finalActions.size > 1) {
    return null
  }
  val finalAction = finalActions.singleOrNull()
  if (finalAction != null && (toolCallActions.isNotEmpty() || commentaryActions.isNotEmpty())) {
    return null
  }
  if (finalAction != null && (finalAction.hasAttachments || finalAction.answer.isBlank())) {
    return null
  }
  return LiteLlmStructuredCompletion(
    toolCalls = toolCallActions.mapIndexed { index, toolCall ->
      LiteLlmStructuredToolCall(
        id = toolCall.id ?: "${requestId}-legacy-tool-${index + 1}",
        toolName = toolCall.toolName,
        arguments = toolCall.arguments,
        reason = toolCall.reason,
      )
    },
    finalText = finalAction?.answer?.takeIf {
      toolCallActions.isEmpty() && commentaryActions.isEmpty() && it.isNotBlank()
    },
    commentaryText = commentaryActions.singleOrNull()?.text?.trim()?.takeIf(String::isNotBlank),
  ).takeIf { completion -> completion.hasStructuredActions }
}

private fun JsonObject.primitiveContent(key: String): String? =
  (this[key] as? JsonPrimitive)?.content

private fun LiteLlmBuiltinToolDefinition.toFunctionToolDefinition(): LiteLlmToolDefinition? = when (
  type
) {
  LiteLlmBuiltinToolType.WEB_SEARCH -> LiteLlmToolDefinition(
    name = "WebSearch",
    description = "Search the web through the configured search provider and return result titles, URLs, and snippets.",
    inputSchema = buildJsonObject {
      put("type", "object")
      put(
        "properties",
        buildJsonObject {
          put(
            "query",
            buildJsonObject {
              put("type", "string")
              put("description", "Search query to send to the web search provider.")
            },
          )
          put(
            "max_results",
            buildJsonObject {
              put("type", "number")
              put("description", "Maximum number of search results to return.")
            },
          )
          put(
            "domains",
            buildJsonObject {
              put("type", "array")
              put(
                "items",
                buildJsonObject {
                  put("type", "string")
                },
              )
              put(
                "description",
                "Optional domain filter. Only return results from these domains or their subdomains.",
              )
            },
          )
        },
      )
      put(
        "required",
        buildJsonArray {
          add(JsonPrimitive("query"))
        },
      )
    },
  )
}

private fun LiteLlmGatewayAttachment.toLiteRtTextBlock(): String = buildString {
  append("Attachment")
  displayName?.trim()?.takeIf(String::isNotBlank)?.let { resolvedDisplayName ->
    append(": ")
    append(resolvedDisplayName)
  }
  append('\n')
  append("kind=")
  append(kind.name.lowercase())
  filePath
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let(::File)
    ?.name
    ?.takeIf(String::isNotBlank)
    ?.let { fileName ->
      append('\n')
      append("file=")
      append(fileName)
    }
  mimeType?.trim()?.takeIf(String::isNotBlank)?.let { resolvedMimeType ->
    append('\n')
    append("mime=")
    append(resolvedMimeType)
  }
  transcriptText?.trim()?.takeIf(String::isNotBlank)?.let { transcript ->
    append('\n')
    append("transcript:\n")
    append(transcript)
  }
}

private fun Map<*, *>.toJsonObject(): JsonObject = JsonObject(
  entries.associate { (key, value) -> key.toString() to value.toJsonElement() },
)

private fun Any?.toJsonElement(): JsonElement = when (this) {
  null -> JsonNull
  is JsonElement -> this
  is Boolean -> JsonPrimitive(this)
  is Number -> JsonPrimitive(this)
  is String -> JsonPrimitive(this)
  is Map<*, *> -> JsonObject(
    entries.associate { (key, value) ->
      key.toString() to value.toJsonElement()
    },
  )
  is Iterable<*> -> JsonArray(map(Any?::toJsonElement))
  is Array<*> -> JsonArray(map(Any?::toJsonElement))
  else -> JsonPrimitive(toString())
}
