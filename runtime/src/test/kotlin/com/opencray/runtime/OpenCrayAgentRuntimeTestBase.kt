package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json

abstract class OpenCrayAgentRuntimeTestBase {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  protected fun promptTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_TEST",
    ),
    metadata = metadata,
    createdAtEpochMs = 500L,
  )

  protected fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }

  protected fun runtimeHooks(
    onSuspend: (SuspensionRequest) -> Unit = {},
  ): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in OpenCrayAgentRuntimeTest.") },
    requestSuspend = onSuspend,
  )

  protected fun assertLocalContinuationFingerprintFallback(
    expectedLocalContinuationReason: String,
    expectedContextCacheBreakReason: String,
    mutateEnvelope: (
      OpenCraySerializableLocalContinuationEnvelope,
    ) -> OpenCraySerializableLocalContinuationEnvelope,
  ) {
    val workspaceRoot = temporaryFolder.newFolder(
      "agent-local-continuation-$expectedLocalContinuationReason",
    )
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val task = promptTask(input = "Read the README and then answer.")
    val eventSink = RecordingEventSink()
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"Read the README."}""",
      ),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 70_000L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val checkpointState = checkpointStateFromFirstToolResult(eventSink)
    val originalEnvelope = requireNotNull(checkpointState.localContinuationEnvelope)
    val mutatedState = checkpointState.copy(
      localContinuationEnvelope = mutateEnvelope(originalEnvelope),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf("""{"type":"final","answer":"Recovered after fingerprint mismatch."}"""),
    )
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = mutatedState,
      ),
      clock = IncrementingClock(start = 70_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Recovered after fingerprint mismatch.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("full_rebuild", resumedGateway.requests.single().metadata["localContinuationMode"])
    assertEquals(
      expectedLocalContinuationReason,
      resumedGateway.requests.single().metadata["localContinuationReason"],
    )
    assertEquals(
      expectedContextCacheBreakReason,
      resumedGateway.requests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("0", resumedResult.metadata["localContinuationUsedCount"])
    assertEquals("1", resumedResult.metadata["localContinuationFallbackCount"])
    assertEquals(
      expectedContextCacheBreakReason,
      resumedResult.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    val resumedCacheMetadata = resumedGateway.requests.single().metadata
    assertEquals(
      "non_responses_front_zone_v1",
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    val initialContinuationCacheMetadata = initialGateway.requests.last().metadata
    assertEquals(
      initialContinuationCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
    )
    assertEquals(
      initialContinuationCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
    )
  }

  protected fun checkpointStateFromFirstToolResult(
    eventSink: RecordingEventSink,
  ): OpenCrayPromptResumeState = requireNotNull(
    OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .first()
        .result
        .metadata,
      json = Json { ignoreUnknownKeys = true },
    ),
  )

  protected fun promptCacheShapeHash(
    value: String,
  ): String = MessageDigest.getInstance("SHA-256")
    .digest(value.trim().toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
    .take(24)

  protected class IncrementingClock(
    start: Long,
  ) {
    private var current: Long = start

    fun next(): Long = current++
  }

  protected class RecordingGateway(
    outputs: List<String>,
  ) : LiteLlmGateway {
    private val queuedOutputs = ArrayDeque(outputs)
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 2_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = queuedOutputs.removeFirstOrNull()
        ?: error("No fake LLM output left for request ${request.requestId}.")
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  protected class RecordingWorkingStateStore : WorkingStateStore {
    private val snapshots = mutableListOf<WorkingState>()

    val history: List<WorkingState>
      get() = snapshots.toList()

    override fun snapshot(): WorkingState = snapshots.lastOrNull() ?: WorkingState()

    override fun replace(state: WorkingState) {
      snapshots += state
    }
  }

  protected class DynamicGateway(
    private val outputProvider: (Int) -> String,
  ) : LiteLlmGateway {
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 8_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = outputProvider(requests.lastIndex)
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  protected class FailingGateway(
    private val status: LiteLlmGatewayStatus,
    private val errorCode: String,
    private val errorMessage: String,
  ) : LiteLlmGateway {
    private var now = 5_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = status,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = null,
        errorCode = errorCode,
        errorMessage = errorMessage,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.FAILED,
            errorCode = errorCode,
            outputChars = 0,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  protected class ScriptedGateway(
    results: List<LiteLlmGatewayResult>,
  ) : LiteLlmGateway {
    private val queuedResults = ArrayDeque(results)
    val requests = mutableListOf<LiteLlmGatewayRequest>()

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      return queuedResults.removeFirstOrNull()
        ?.copy(requestId = request.requestId)
        ?: error("No scripted LLM result left for request ${request.requestId}.")
    }
  }

  protected class RecordingEventSink : OpenCrayAgentRuntimeEventSink {
    val events = mutableListOf<OpenCrayAgentRunEvent>()
    val assistantDrafts = mutableListOf<String>()
    var assistantDraftClearCount: Int = 0

    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      events += event
    }

    override fun onAssistantDraftUpdated(
      task: AgentTask,
      text: String,
      emittedAtEpochMs: Long,
    ) {
      assistantDrafts += text
    }

    override fun onAssistantDraftCleared(
      task: AgentTask,
      emittedAtEpochMs: Long,
    ) {
      assistantDraftClearCount += 1
    }
  }

  protected fun visibleSupplementEvents(
    events: List<OpenCrayAgentRunEvent>,
  ): List<OpenCraySupplementEvent> = events
    .filterIsInstance<OpenCraySupplementEvent>()
    .filterNot(::isInternalCheckpointMarker)

  protected fun internalCheckpointMarkers(
    events: List<OpenCrayAgentRunEvent>,
  ): List<OpenCraySupplementEvent> = events
    .filterIsInstance<OpenCraySupplementEvent>()
    .filter(::isInternalCheckpointMarker)

  protected fun isInternalCheckpointMarker(
    event: OpenCrayAgentRunEvent,
  ): Boolean = event is OpenCraySupplementEvent &&
    event.text.isBlank() &&
    event.checkpoint == "internal_prompt_checkpoint"

  protected fun externalEventKinds(
    events: List<OpenCrayAgentRunEvent>,
  ): List<String> = events
    .filterNot(::isInternalCheckpointMarker)
    .map { event ->
      when (event) {
        is OpenCrayLifecycleEvent -> "lifecycle"
        is OpenCrayAssistantPhaseEvent -> "assistant"
        is OpenCraySupplementEvent -> "supplement"
        is OpenCrayApprovalEvent -> "approval"
        is OpenCraySubAgentEvent -> "subagent"
        is OpenCrayToolCallEvent -> "tool_call"
        is OpenCrayToolResultEvent -> "tool_result"
        is OpenCrayMemoryRetrievalEvent -> "memory_retrieval"
        is OpenCrayMemoryWriteEvent -> "memory_write"
        is OpenCrayCancellationEvent -> "cancelled"
      }
    }

  protected fun gatewayStructuredPayloadText(
    request: LiteLlmGatewayRequest,
  ): String = request.messages.joinToString(separator = "\n\n") { message ->
    buildString {
      message.content?.trim()?.takeIf(String::isNotBlank)?.let(::append)
      message.toolResult?.content?.trim()?.takeIf(String::isNotBlank)?.let { toolResultText ->
        if (isNotEmpty()) {
          append("\n")
        }
        append(toolResultText)
      }
    }.trim()
  }.trim()

  protected class ScriptedProcessRegistry : AgentProcessRegistry {
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()
    val waitTimeouts = mutableListOf<Long>()
    var startedProcessId: String? = null
      private set

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startedProcessId = request.processId
      return ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        metadata = request.metadata,
      ).also { snapshot ->
        snapshotsById[request.processId] = snapshot
      }
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      waitTimeouts += timeoutMs
      val existing = snapshotsById[processId] ?: return null
      return existing.copy(
        status = ManagedProcessStatus.SUCCESS,
        stdout = "server ready",
        exitCode = 0,
        updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
      ).also { snapshot ->
        snapshotsById[processId] = snapshot
      }
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]
  }

  protected fun gatewaySuccessResult(
    outputText: String,
    completion: LiteLlmStructuredCompletion? = null,
  ): LiteLlmGatewayResult {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    return LiteLlmGatewayResult(
      requestId = "scripted-success",
      status = LiteLlmGatewayStatus.SUCCESS,
      completionMode = LiteLlmCompletionMode.PRIMARY,
      outputText = outputText,
      completion = completion,
      selectedRoute = selection,
      attempts = listOf(
        LiteLlmAttemptRecord(
          route = selection,
          outcome = LiteLlmAttemptOutcome.SUCCESS,
          outputChars = outputText.length,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
      ),
      startedAtEpochMs = 1_000L,
      finishedAtEpochMs = 1_001L,
    )
  }

  protected fun gatewayFailureResult(
    errorCode: String,
    errorMessage: String,
    completion: LiteLlmStructuredCompletion? = null,
  ): LiteLlmGatewayResult {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    return LiteLlmGatewayResult(
      requestId = "scripted-failure",
      status = LiteLlmGatewayStatus.FAILED,
      completionMode = LiteLlmCompletionMode.TERMINAL,
      completion = completion,
      selectedRoute = selection,
      attempts = listOf(
        LiteLlmAttemptRecord(
          route = selection,
          outcome = LiteLlmAttemptOutcome.FAILED,
          errorCode = errorCode,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
      ),
      errorCode = errorCode,
      errorMessage = errorMessage,
      startedAtEpochMs = 1_000L,
      finishedAtEpochMs = 1_001L,
    )
  }
}
