package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.llm.LiteLlmVisibleTextObserver
import com.opencray.llm.ProviderRoute
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencray.litertlmbridge.LiteRtLmBridge

class LiteRtOnDeviceProviderClientTest {
  @Test
  fun onDeviceProviderClientReportsModelNotInstalled() {
    val client = LiteRtOnDeviceLlmProviderClient(
      runtime = LiteRtOnDeviceRuntime(
        installStore = InMemoryLiteRtOnDeviceModelInstallStore(),
      ),
    )

    val result = client.execute(onDeviceProviderRequest())

    val failure = result as LiteLlmProviderResult.Failure
    assertEquals(LiteRtOnDeviceFailureCodes.MODEL_NOT_INSTALLED, failure.errorCode)
    assertEquals("Gemma 4 E2B is not downloaded yet.", failure.errorMessage)
    assertEquals("false", failure.metadata[LiteRtOnDeviceMetadataKeys.INSTALLED])
    assertEquals(
      LlmProviderModes.ON_DEVICE_MODEL,
      failure.metadata[LiteRtOnDeviceMetadataKeys.PROVIDER_MODE],
    )
  }

  @Test
  fun appConfiguredProviderClientRoutesOnDeviceRequestsToOnDeviceDelegate() {
    val cloudClient = RecordingProviderClient(
      LiteLlmProviderResult.Failure(
        errorCode = "CLOUD",
        errorMessage = "cloud",
      ),
    )
    val onDeviceClient = RecordingProviderClient(
      LiteLlmProviderResult.Failure(
        errorCode = "ON_DEVICE",
        errorMessage = "on-device",
      ),
    )
    val client = AppConfiguredLiteLlmProviderClient(
      cloudProviderClient = cloudClient,
      onDeviceProviderClient = onDeviceClient,
    )

    val result = client.execute(onDeviceProviderRequest())

    val failure = result as LiteLlmProviderResult.Failure
    assertEquals("ON_DEVICE", failure.errorCode)
    assertEquals(0, cloudClient.requestCount)
    assertEquals(1, onDeviceClient.requestCount)
  }

  @Test
  fun appConfiguredProviderClientLeavesCloudRequestsOnCloudDelegate() {
    val cloudClient = RecordingProviderClient(
      LiteLlmProviderResult.Failure(
        errorCode = "CLOUD",
        errorMessage = "cloud",
      ),
    )
    val onDeviceClient = RecordingProviderClient(
      LiteLlmProviderResult.Failure(
        errorCode = "ON_DEVICE",
        errorMessage = "on-device",
      ),
    )
    val client = AppConfiguredLiteLlmProviderClient(
      cloudProviderClient = cloudClient,
      onDeviceProviderClient = onDeviceClient,
    )

    val result = client.execute(
      LiteLlmProviderRequest(
        route = ProviderRoute(
          id = "route-cloud",
          providerId = "openai",
          baseUrl = "https://api.example.com/v1",
          model = "gpt-4o-mini",
        ),
        request = LiteLlmGatewayRequest(prompt = "hello"),
        selection = LiteLlmRouteSelectionMetadata(
          profileId = "profile-default",
          routeId = "route-cloud",
          providerId = "openai",
          model = "gpt-4o-mini",
          attemptIndex = 0,
        ),
      ),
    )

    val failure = result as LiteLlmProviderResult.Failure
    assertEquals("CLOUD", failure.errorCode)
    assertEquals(1, cloudClient.requestCount)
    assertEquals(0, onDeviceClient.requestCount)
  }

  @Test
  fun onDeviceProviderUsesMessagesAsAuthoritativeTransport() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-message-transport-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "ok"),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = RecordingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)

    client.execute(
      onDeviceProviderRequest(
        gatewayRequest = LiteLlmGatewayRequest(
          systemPrompt = "System instruction",
          messages = listOf(
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.USER,
              content = "Hello from messages",
            ),
          ),
        ),
      ),
    )

    val runtimeRequest = handle.requests.single()
    assertEquals("", runtimeRequest.prompt)
    assertEquals("System instruction", runtimeRequest.systemPrompt)
    assertEquals(1, runtimeRequest.messages.size)
    assertEquals("Hello from messages", runtimeRequest.messages.single().content)
  }

  @Test
  fun runtimeUsesEngineFactoryAfterReadyFilePassesChecks() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(
        outputText = "hello from local",
        finishReason = "stop",
      ),
    )
    val factory = RecordingEngineFactory(handle)
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = factory,
    )

    val result = runtime.execute(readyRuntimeRequest())

    val success = result as LiteRtOnDeviceRuntimeResult.Success
    assertEquals("hello from local", success.outputText)
    assertEquals(1, factory.createCount)
    assertEquals(modelFile.absolutePath, factory.lastModelFile?.absolutePath)
    assertEquals(OnDeviceLlmAccelerators.GPU, factory.lastBackend)
    assertEquals(32_768, factory.lastMaxContextWindow)
    assertEquals(1, handle.requests.size)
    assertEquals("Hello", handle.requests.single().prompt)
  }

  @Test
  fun bridgeResponseParsesLegacyJsonFinalIntoStructuredCompletion() {
    val response = LiteRtLmBridge.Response(
      """{"type":"final","answer":"你好"}""",
      emptyMap(),
      emptyList(),
    )

    val success = response.toRuntimeSuccess(readyRuntimeRequest())

    assertEquals("""{"type":"final","answer":"你好"}""", success.outputText)
    assertEquals("你好", success.completion?.finalText)
    assertNull(success.completion?.commentaryText)
    assertNull(success.completion?.rawText)
    assertEquals("stop", success.finishReason)
  }

  @Test
  fun bridgeResponseParsesLegacyJsonToolCallIntoStructuredCompletion() {
    val response = LiteRtLmBridge.Response(
      """{"type":"tool_call","tool_name":"Read","arguments":{"path":"README.md"},"reason":"inspect file"}""",
      emptyMap(),
      emptyList(),
    )

    val success = response.toRuntimeSuccess(readyRuntimeRequest())

    val toolCall = success.completion?.toolCalls?.single()
    assertEquals("Read", toolCall?.toolName)
    assertEquals("README.md", toolCall?.arguments?.get("path")?.toString()?.trim('"'))
    assertEquals("inspect file", toolCall?.reason)
    assertNull(success.completion?.finalText)
    assertNull(success.completion?.rawText)
    assertEquals("stop", success.finishReason)
  }

  @Test
  fun bridgeResponseKeepsArbitraryJsonAsPlainFinalText() {
    val response = LiteRtLmBridge.Response(
      """{"message":"plain json payload"}""",
      emptyMap(),
      emptyList(),
    )

    val success = response.toRuntimeSuccess(readyRuntimeRequest())

    assertEquals("""{"message":"plain json payload"}""", success.completion?.finalText)
    assertEquals("""{"message":"plain json payload"}""", success.completion?.rawText)
    assertTrue(success.completion?.toolCalls?.isEmpty() == true)
  }

  @Test
  fun runtimeReusesActiveEngineWhileModelKeyStaysTheSame() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-reuse-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "ok"),
    )
    val factory = RecordingEngineFactory(handle)
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = factory,
    )

    runtime.execute(readyRuntimeRequest())
    runtime.execute(readyRuntimeRequest())

    assertEquals(1, factory.createCount)
    assertEquals(2, handle.requests.size)
  }

  @Test
  fun runtimePrewarmReusesActiveEngineWithoutGeneratingOutput() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-prewarm-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "ok"),
    )
    val factory = RecordingEngineFactory(handle)
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = factory,
    )

    val warmup = runtime.prewarm(readyRuntimeRequest())
    runtime.execute(readyRuntimeRequest())

    assertTrue(warmup is LiteRtOnDevicePrewarmResult.Success)
    assertEquals(1, factory.createCount)
    assertEquals(1, handle.prewarmRequests.size)
    assertEquals("Hello", handle.prewarmRequests.single().prompt)
    assertEquals(1, handle.requests.size)
  }

  @Test
  fun runtimeAcceptsBuiltinWebSearchAndTouchesEngineFactory() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-builtin-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "unused"),
    )
    val factory = RecordingEngineFactory(handle)
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = factory,
    )

    val result = runtime.execute(
      readyRuntimeRequest().copy(
        builtinTools = listOf(
          LiteLlmBuiltinToolDefinition(type = LiteLlmBuiltinToolType.WEB_SEARCH),
        ),
      ),
    )

    val success = result as LiteRtOnDeviceRuntimeResult.Success
    assertEquals("unused", success.outputText)
    assertEquals(1, factory.createCount)
    assertEquals(1, handle.requests.size)
  }

  @Test
  fun runtimeAcceptsAttachmentMessagesByUsingTextFallback() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-attachments-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "unused"),
    )
    val factory = RecordingEngineFactory(handle)
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = factory,
    )

    val result = runtime.execute(
      readyRuntimeRequest().copy(
        messages = listOf(
          LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.USER,
            attachments = listOf(
              LiteLlmGatewayAttachment(
                kind = LiteLlmGatewayAttachmentKind.AUDIO,
                displayName = "meeting.m4a",
                transcriptText = "action items go here",
              ),
            ),
          ),
        ),
      ),
    )

    val success = result as LiteRtOnDeviceRuntimeResult.Success
    assertEquals("unused", success.outputText)
    assertEquals(1, factory.createCount)
    assertEquals(1, handle.requests.size)
  }

  @Test
  fun runtimeAcceptsNamedToolChoiceAndParallelToolCalls() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-tool-choice-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "unused"),
    )
    val factory = RecordingEngineFactory(handle)
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = factory,
    )

    val result = runtime.execute(
      readyRuntimeRequest().copy(
        tools = listOf(
          LiteLlmToolDefinition(
            name = "Echo",
            description = "Return the provided value.",
          ),
        ),
        toolChoice = LiteLlmToolChoice(
          mode = LiteLlmToolChoiceMode.TOOL,
          toolName = "Echo",
        ),
        parallelToolCalls = true,
      ),
    )

    val success = result as LiteRtOnDeviceRuntimeResult.Success
    assertEquals("unused", success.outputText)
    assertEquals(1, factory.createCount)
    assertEquals(1, handle.requests.size)
  }

  @Test
  fun providerPassesAutomaticToolExecutionContextFromRegistry() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-dev-auto-tool-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(outputText = "ok"),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = RecordingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)
    val workspaceRoot = Files.createTempDirectory("litert-auto-tool-context")
    val context = LiteRtAutomaticToolExecutionContext(
      task = AgentTask(
        id = "prompt-litert-auto-tool",
        type = AgentTaskType.PROMPT,
        input = "Use a tool if needed.",
        createdAtEpochMs = 1_000L,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "TEST_ALLOW",
        ),
      ),
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> Unit },
      ),
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
    )

    LiteRtAutomaticToolExecutionRegistry.withContext(context) {
      client.execute(onDeviceProviderRequest())
    }

    assertEquals(1, handle.requests.size)
    assertTrue(handle.requests.single().automaticToolExecutionContext === context)
  }

  @Test
  fun providerCopiesStreamingMetadataIntoRuntimeRequestAndSkipsObserverWhenDisabled() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-stream-disabled-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(
        outputText = "final output",
        completion = LiteLlmStructuredCompletion(finalText = "Visible final output"),
      ),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = RecordingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)
    val observer = RecordingVisibleTextObserver()

    val result = client.execute(
      onDeviceProviderRequest(
        metadataOverrides = mapOf("stream" to "false"),
        gatewayRequest = LiteLlmGatewayRequest(
          prompt = "hello",
          streamObserver = observer,
        ),
      ),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("false", success.metadata["stream"])
    assertEquals(1, handle.requests.size)
    assertEquals(false, handle.requests.single().streamingEnabled)
    assertTrue(observer.snapshots.isEmpty())
  }

  @Test
  fun providerEmitsVisibleTextSnapshotWhenStreamingIsEnabled() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-stream-enabled-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(
        outputText = "fallback output",
        completion = LiteLlmStructuredCompletion(finalText = "Visible final output"),
      ),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = RecordingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)
    val observer = RecordingVisibleTextObserver()

    val result = client.execute(
      onDeviceProviderRequest(
        metadataOverrides = mapOf("stream" to "true"),
        gatewayRequest = LiteLlmGatewayRequest(
          prompt = "hello",
          streamObserver = observer,
        ),
      ),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata["stream"])
    assertEquals(1, handle.requests.size)
    assertEquals(true, handle.requests.single().streamingEnabled)
    assertEquals("Visible final output", observer.snapshots.last())
    assertFalse(observer.snapshots.isEmpty())
    assertEquals(0, observer.resetCount)
  }

  @Test
  fun providerEmitsVisiblePulseWhileStreamingExecutionIsBlocked() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-stream-pulse-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = BlockingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(
        outputText = "final output",
        completion = LiteLlmStructuredCompletion(finalText = "Visible final output"),
      ),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = BlockingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)
    val observer = RecordingVisibleTextObserver()
    val executor = Executors.newSingleThreadExecutor()

    try {
      val future = executor.submit<LiteLlmProviderResult> {
        client.execute(
          onDeviceProviderRequest(
            metadataOverrides = mapOf("stream" to "true"),
            gatewayRequest = LiteLlmGatewayRequest(
              prompt = "hello",
              streamObserver = observer,
            ),
          ),
        )
      }

      assertTrue(handle.started.await(2, TimeUnit.SECONDS))
      assertTrue(observer.awaitSnapshot(2, TimeUnit.SECONDS))

      handle.finish.countDown()
      val success = future.get(2, TimeUnit.SECONDS) as LiteLlmProviderResult.Success
      val settledSnapshotCount = observer.snapshots.size
      Thread.sleep(500L)

      assertEquals("Visible final output", observer.snapshots.last())
      assertTrue(
        observer.snapshots.dropLast(1).any { snapshot ->
          snapshot == "Thinking…"
        },
      )
      assertEquals(settledSnapshotCount, observer.snapshots.size)
      assertEquals("Visible final output", success.completion?.finalText)
    } finally {
      handle.finish.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun providerUsesLocalizedThinkingLabelForVisiblePulse() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-stream-pulse-localized-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = BlockingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Success(
        outputText = "final output",
        completion = LiteLlmStructuredCompletion(finalText = "Visible final output"),
      ),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = BlockingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)
    val observer = RecordingVisibleTextObserver()
    val executor = Executors.newSingleThreadExecutor()

    try {
      val future = executor.submit<LiteLlmProviderResult> {
        client.execute(
          onDeviceProviderRequest(
            metadataOverrides = mapOf(
              "stream" to "true",
              LiteRtOnDeviceMetadataKeys.THINKING_LABEL to "思考中…",
            ),
            gatewayRequest = LiteLlmGatewayRequest(
              prompt = "hello",
              streamObserver = observer,
            ),
          ),
        )
      }

      assertTrue(handle.started.await(2, TimeUnit.SECONDS))
      assertTrue(observer.awaitSnapshot(2, TimeUnit.SECONDS))

      handle.finish.countDown()
      val success = future.get(2, TimeUnit.SECONDS) as LiteLlmProviderResult.Success

      assertEquals("Visible final output", observer.snapshots.last())
      assertTrue(
        observer.snapshots.dropLast(1).all { snapshot ->
          snapshot == "思考中…"
        },
      )
      assertEquals("Visible final output", success.completion?.finalText)
    } finally {
      handle.finish.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun providerClearsDraftPlaceholderWhenStreamingEnabledRequestFails() {
    val modelFile = Files.createTempFile("gemma-e2b-provider-stream-failure-test", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val handle = RecordingEngineHandle(
      result = LiteRtOnDeviceRuntimeResult.Failure(
        errorCode = LiteRtOnDeviceFailureCodes.MODEL_LOAD_FAILED,
        errorMessage = "inference failed",
      ),
    )
    val runtime = LiteRtOnDeviceRuntime(
      installStore = readyInstallStore(modelFile),
      engineFactory = RecordingEngineFactory(handle),
    )
    val client = LiteRtOnDeviceLlmProviderClient(runtime = runtime)
    val observer = RecordingVisibleTextObserver()

    val result = client.execute(
      onDeviceProviderRequest(
        metadataOverrides = mapOf("stream" to "true"),
        gatewayRequest = LiteLlmGatewayRequest(
          prompt = "hello",
          streamObserver = observer,
        ),
      ),
    )

    val failure = result as LiteLlmProviderResult.Failure
    assertEquals(LiteRtOnDeviceFailureCodes.MODEL_LOAD_FAILED, failure.errorCode)
    assertTrue(observer.snapshots.isEmpty())
    assertEquals(1, observer.resetCount)
  }

  private fun onDeviceProviderRequest(
    metadataOverrides: Map<String, String> = emptyMap(),
    gatewayRequest: LiteLlmGatewayRequest = LiteLlmGatewayRequest(prompt = "hello"),
  ): LiteLlmProviderRequest = LiteLlmProviderRequest(
    route = ProviderRoute(
      id = "route-on-device",
      providerId = "openai",
      model = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
      metadata = mapOf(
        LiteRtOnDeviceMetadataKeys.PROVIDER_MODE to LlmProviderModes.ON_DEVICE_MODEL,
        LiteRtOnDeviceMetadataKeys.RUNTIME to OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM,
        LiteRtOnDeviceMetadataKeys.MODEL_ID to OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        LiteRtOnDeviceMetadataKeys.BACKEND to OnDeviceLlmAccelerators.GPU,
        LiteRtOnDeviceMetadataKeys.MAX_CONTEXT_WINDOW to "32768",
        LiteRtOnDeviceMetadataKeys.THINKING_ENABLED to "false",
        "max_tokens" to "4096",
        "top_k" to "40",
        "top_p" to "0.95",
        "temperature" to "0.7",
      ) + metadataOverrides,
    ),
    request = gatewayRequest,
    selection = LiteLlmRouteSelectionMetadata(
      profileId = "profile-default",
      routeId = "route-on-device",
      providerId = "openai",
      model = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
      attemptIndex = 0,
    ),
  )

  private fun readyRuntimeRequest(): LiteRtOnDeviceRuntimeRequest = LiteRtOnDeviceRuntimeRequest(
    requestId = "req-ready",
    modelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
    backend = OnDeviceLlmAccelerators.GPU,
    maxContextWindow = 32_768,
    maxTokens = 4_096,
    topK = 40,
    topP = 0.95,
    temperature = 0.7,
    thinkingEnabled = false,
    prompt = "Hello",
    timeoutMs = 30_000L,
  )

  private fun readyInstallStore(modelFile: File): InMemoryLiteRtOnDeviceModelInstallStore {
    val entry = checkNotNull(OnDeviceLlmCatalog.entry(OnDeviceLlmCatalog.GEMMA_4_E2B_IT))
    return InMemoryLiteRtOnDeviceModelInstallStore(
      initialRecords = listOf(
        LiteRtOnDeviceModelInstallRecord(
          modelId = entry.id,
          versionTag = entry.versionTag,
          sourceUrl = entry.sourceUrl,
          localFilePath = modelFile.absolutePath,
          fileSizeBytes = entry.fileSizeBytes,
          sha256 = entry.sha256,
          installState = OnDeviceLlmDownloadStates.READY,
          downloadedBytes = entry.fileSizeBytes,
          installedAtEpochMs = 123L,
          sha256Verified = true,
        ),
      ),
    )
  }

  private class RecordingProviderClient(
    private val result: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    var requestCount: Int = 0
      private set

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      requestCount += 1
      return result
    }
  }

  private class RecordingEngineFactory(
    private val handle: RecordingEngineHandle,
  ) : LiteRtOnDeviceEngineFactory {
    var createCount: Int = 0
      private set
    var lastModelFile: File? = null
      private set
    var lastBackend: String? = null
      private set
    var lastMaxContextWindow: Int? = null
      private set

    override fun create(
      modelFile: File,
      backend: String,
      maxContextWindow: Int,
    ): LiteRtOnDeviceEngineHandle {
      createCount += 1
      lastModelFile = modelFile
      lastBackend = backend
      lastMaxContextWindow = maxContextWindow
      return handle
    }
  }

  private class RecordingEngineHandle(
    private val result: LiteRtOnDeviceRuntimeResult,
  ) : LiteRtOnDeviceEngineHandle {
    val requests: MutableList<LiteRtOnDeviceRuntimeRequest> = mutableListOf()
    val prewarmRequests: MutableList<LiteRtOnDeviceRuntimeRequest> = mutableListOf()
    var cancelCount: Int = 0
      private set
    var closeCount: Int = 0
      private set

    override fun generate(request: LiteRtOnDeviceRuntimeRequest): LiteRtOnDeviceRuntimeResult {
      requests += request
      return result
    }

    override fun prewarm(request: LiteRtOnDeviceRuntimeRequest) {
      prewarmRequests += request
    }

    override fun cancelActiveGeneration() {
      cancelCount += 1
    }

    override fun close() {
      closeCount += 1
    }
  }

  private class BlockingEngineFactory(
    private val handle: BlockingEngineHandle,
  ) : LiteRtOnDeviceEngineFactory {
    override fun create(
      modelFile: File,
      backend: String,
      maxContextWindow: Int,
    ): LiteRtOnDeviceEngineHandle = handle
  }

  private class BlockingEngineHandle(
    private val result: LiteRtOnDeviceRuntimeResult,
  ) : LiteRtOnDeviceEngineHandle {
    val started: CountDownLatch = CountDownLatch(1)
    val finish: CountDownLatch = CountDownLatch(1)

    override fun generate(request: LiteRtOnDeviceRuntimeRequest): LiteRtOnDeviceRuntimeResult {
      started.countDown()
      finish.await(5, TimeUnit.SECONDS)
      return result
    }

    override fun prewarm(request: LiteRtOnDeviceRuntimeRequest) = Unit

    override fun cancelActiveGeneration() = Unit

    override fun close() = Unit
  }

  private class RecordingVisibleTextObserver : LiteLlmVisibleTextObserver {
    val snapshots: MutableList<String> = CopyOnWriteArrayList()
    private val firstSnapshot: CountDownLatch = CountDownLatch(1)
    var resetCount: Int = 0
      private set

    override fun onVisibleTextSnapshot(text: String) {
      snapshots += text
      firstSnapshot.countDown()
    }

    override fun onVisibleTextReset() {
      resetCount += 1
    }

    fun awaitSnapshot(
      timeout: Long,
      unit: TimeUnit,
    ): Boolean = firstSnapshot.await(timeout, unit)
  }
}
