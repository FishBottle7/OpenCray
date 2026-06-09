package com.opencray.runtime.compaction

import com.opencray.runtime.context.CompactionPolicy
import com.opencray.runtime.context.CompactionSummary
import com.opencray.runtime.context.ContextPruner
import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.ReplayPressureEvaluator
import com.opencray.runtime.context.ReplayPressureSnapshot
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.TranscriptWindowBuilder
import com.opencray.runtime.session.SessionTranscriptStore
import kotlinx.serialization.Serializable

@Serializable
data class DurableCompactionEntry(
  val text: String,
  val compactedMessageCount: Int,
  val omittedUserMessageCount: Int = 0,
  val omittedAssistantMessageCount: Int = 0,
  val omittedToolMessageCount: Int = 0,
  val omittedSystemMessageCount: Int = 0,
  val compactedAtEpochMs: Long = 0L,
) {
  init {
    require(text.isNotBlank()) { "DurableCompactionEntry text must not be blank." }
    require(compactedMessageCount >= 1) { "DurableCompactionEntry compactedMessageCount must be >= 1." }
  }

  fun toCompactionSummary(): CompactionSummary = CompactionSummary(
    text = text,
    compactedMessageCount = compactedMessageCount,
    omittedUserMessageCount = omittedUserMessageCount,
    omittedAssistantMessageCount = omittedAssistantMessageCount,
    omittedToolMessageCount = omittedToolMessageCount,
    omittedSystemMessageCount = omittedSystemMessageCount,
  )

  companion object {
    fun from(summary: CompactionSummary, compactedAtEpochMs: Long): DurableCompactionEntry = DurableCompactionEntry(
      text = summary.text,
      compactedMessageCount = summary.compactedMessageCount,
      omittedUserMessageCount = summary.omittedUserMessageCount,
      omittedAssistantMessageCount = summary.omittedAssistantMessageCount,
      omittedToolMessageCount = summary.omittedToolMessageCount,
      omittedSystemMessageCount = summary.omittedSystemMessageCount,
      compactedAtEpochMs = compactedAtEpochMs,
    )
  }
}

@Serializable
data class DurableCompactionState(
  val entries: List<DurableCompactionEntry> = emptyList(),
)

data class DurableCompactionTrace(
  val compactedThisRun: Boolean = false,
  val triggerStage: String = "",
  val maintenanceTask: String = "",
  val executionMode: String = "",
  val contextWindowTokens: Int = 0,
  val autoCompactTokenLimit: Int = 0,
  val estimatedReplayTokens: Int = 0,
  val tokenThresholdTriggered: Boolean = false,
  val sourceTranscriptMessageCount: Int = 0,
  val retainedTranscriptMessageCount: Int = 0,
  val latestCompactedMessageCount: Int = 0,
  val includedSummaryCount: Int = 0,
  val omittedSummaryCount: Int = 0,
  val totalCompactedMessageCount: Int = 0,
  val latestCompactedAtEpochMs: Long? = null,
  val entryTraces: List<DurableCompactionEntryTrace> = emptyList(),
  val remoteCompactionMetadata: Map<String, String> = emptyMap(),
) {
  init {
    require(sourceTranscriptMessageCount >= 0) {
      "DurableCompactionTrace sourceTranscriptMessageCount must be >= 0."
    }
    require(retainedTranscriptMessageCount >= 0) {
      "DurableCompactionTrace retainedTranscriptMessageCount must be >= 0."
    }
    require(latestCompactedMessageCount >= 0) {
      "DurableCompactionTrace latestCompactedMessageCount must be >= 0."
    }
    require(includedSummaryCount >= 0) {
      "DurableCompactionTrace includedSummaryCount must be >= 0."
    }
    require(omittedSummaryCount >= 0) {
      "DurableCompactionTrace omittedSummaryCount must be >= 0."
    }
    require(totalCompactedMessageCount >= 0) {
      "DurableCompactionTrace totalCompactedMessageCount must be >= 0."
    }
  }

  val totalSummaryCount: Int
    get() = includedSummaryCount + omittedSummaryCount

  val isEmpty: Boolean
    get() = !compactedThisRun &&
      triggerStage.isBlank() &&
      maintenanceTask.isBlank() &&
      executionMode.isBlank() &&
      contextWindowTokens == 0 &&
      autoCompactTokenLimit == 0 &&
      estimatedReplayTokens == 0 &&
      !tokenThresholdTriggered &&
      latestCompactedMessageCount == 0 &&
      includedSummaryCount == 0 &&
      omittedSummaryCount == 0 &&
      totalCompactedMessageCount == 0 &&
      latestCompactedAtEpochMs == null &&
      entryTraces.isEmpty() &&
      remoteCompactionMetadata.isEmpty()
}

data class DurableCompactionEntryTrace(
  val compactedMessageCount: Int,
  val omittedUserMessageCount: Int = 0,
  val omittedAssistantMessageCount: Int = 0,
  val omittedToolMessageCount: Int = 0,
  val omittedSystemMessageCount: Int = 0,
  val compactedAtEpochMs: Long? = null,
) {
  init {
    require(compactedMessageCount >= 0) {
      "DurableCompactionEntryTrace compactedMessageCount must be >= 0."
    }
    require(omittedUserMessageCount >= 0) {
      "DurableCompactionEntryTrace omittedUserMessageCount must be >= 0."
    }
    require(omittedAssistantMessageCount >= 0) {
      "DurableCompactionEntryTrace omittedAssistantMessageCount must be >= 0."
    }
    require(omittedToolMessageCount >= 0) {
      "DurableCompactionEntryTrace omittedToolMessageCount must be >= 0."
    }
    require(omittedSystemMessageCount >= 0) {
      "DurableCompactionEntryTrace omittedSystemMessageCount must be >= 0."
    }
  }
}

data class RemoteCompactionRequest(
  val triggerStage: String,
  val conversation: List<RuntimeConversationMessage>,
  val omittedMessages: List<RuntimeConversationMessage>,
  val retainedMessages: List<RuntimeConversationMessage>,
  val llmMetadata: Map<String, String> = emptyMap(),
  val replayPressure: ReplayPressureSnapshot,
)

sealed interface RemoteCompactionResult {
  data class Success(
    val summary: CompactionSummary,
    val metadata: Map<String, String> = emptyMap(),
  ) : RemoteCompactionResult

  data class Unavailable(
    val reason: String,
    val metadata: Map<String, String> = emptyMap(),
  ) : RemoteCompactionResult {
    init {
      require(reason.isNotBlank()) { "RemoteCompactionResult.Unavailable reason must not be blank." }
    }
  }

  data class Failure(
    val errorCode: String,
    val errorMessage: String,
    val metadata: Map<String, String> = emptyMap(),
  ) : RemoteCompactionResult {
    init {
      require(errorCode.isNotBlank()) { "RemoteCompactionResult.Failure errorCode must not be blank." }
      require(errorMessage.isNotBlank()) { "RemoteCompactionResult.Failure errorMessage must not be blank." }
    }
  }
}

fun interface RemoteCompactionProvider {
  fun compact(request: RemoteCompactionRequest): RemoteCompactionResult
}

object NoOpRemoteCompactionProvider : RemoteCompactionProvider {
  override fun compact(request: RemoteCompactionRequest): RemoteCompactionResult =
    RemoteCompactionResult.Unavailable("remote_compaction_not_configured")
}

data class DurableCompactionContext(
  val text: String = "",
  val trace: DurableCompactionTrace = DurableCompactionTrace(),
) {
  val included: Boolean
    get() = text.isNotBlank()
}

data class DurableCompactionPolicy(
  val minOmittedMessages: Int = 4,
  val maxStoredEntries: Int = 6,
  val maxRenderedEntries: Int = 4,
  val maxRenderedChars: Int = 1_200,
) {
  init {
    require(minOmittedMessages >= 1) { "DurableCompactionPolicy minOmittedMessages must be >= 1." }
    require(maxStoredEntries >= 1) { "DurableCompactionPolicy maxStoredEntries must be >= 1." }
    require(maxRenderedEntries >= 1) { "DurableCompactionPolicy maxRenderedEntries must be >= 1." }
    require(maxRenderedChars >= 128) { "DurableCompactionPolicy maxRenderedChars must be >= 128." }
  }

  fun shouldCompact(
    omittedMessages: List<RuntimeConversationMessage>,
    replayPressure: com.opencray.runtime.context.ReplayPressureSnapshot,
  ): Boolean =
    omittedMessages.size >= minOmittedMessages &&
      replayPressure.tokenThresholdTriggered

  fun append(
    existing: DurableCompactionState,
    summary: CompactionSummary,
    compactedAtEpochMs: Long,
  ): DurableCompactionState = DurableCompactionState(
    entries = (existing.entries + DurableCompactionEntry.from(summary, compactedAtEpochMs))
      .takeLast(maxStoredEntries),
  )
}

class DurableCompactionRenderer(
  private val policy: DurableCompactionPolicy = DurableCompactionPolicy(),
) {
  internal fun render(state: DurableCompactionState): RenderedDurableCompaction {
    if (state.entries.isEmpty()) {
      return RenderedDurableCompaction()
    }
    val candidateEntries = state.entries.takeLast(policy.maxRenderedEntries)
    val includedEntries = mutableListOf<DurableCompactionEntry>()
    val lines = mutableListOf<String>()
    lines += "Older session history has been durably compacted into summaries."
    candidateEntries.forEach { entry ->
      val nextLines = lines + listOf(
        "[Compacted History]",
        entry.text,
      )
      if (nextLines.joinToString(separator = "\n").length > policy.maxRenderedChars) {
        return@forEach
      }
      lines += "[Compacted History]"
      lines += entry.text
      includedEntries += entry
    }
    val totalCompactedMessageCount = state.entries.sumOf(DurableCompactionEntry::compactedMessageCount)
    val latestCompactedAtEpochMs = state.entries.lastOrNull()
      ?.compactedAtEpochMs
      ?.takeIf { compactedAtEpochMs -> compactedAtEpochMs > 0L }
    val entryTraces = state.entries.map { entry -> entry.toTrace() }
    if (includedEntries.isEmpty()) {
      return RenderedDurableCompaction(
        omittedSummaryCount = state.entries.size,
        totalCompactedMessageCount = totalCompactedMessageCount,
        latestCompactedAtEpochMs = latestCompactedAtEpochMs,
        entryTraces = entryTraces,
      )
    }
    return RenderedDurableCompaction(
      text = lines.joinToString(separator = "\n").trim(),
      includedSummaryCount = includedEntries.size,
      omittedSummaryCount = (state.entries.size - includedEntries.size).coerceAtLeast(0),
      totalCompactedMessageCount = totalCompactedMessageCount,
      latestCompactedAtEpochMs = latestCompactedAtEpochMs,
      entryTraces = entryTraces,
    )
  }

  private fun DurableCompactionEntry.toTrace(): DurableCompactionEntryTrace =
    DurableCompactionEntryTrace(
      compactedMessageCount = compactedMessageCount,
      omittedUserMessageCount = omittedUserMessageCount,
      omittedAssistantMessageCount = omittedAssistantMessageCount,
      omittedToolMessageCount = omittedToolMessageCount,
      omittedSystemMessageCount = omittedSystemMessageCount,
      compactedAtEpochMs = compactedAtEpochMs.takeIf { value -> value > 0L },
    )
}

internal data class RenderedDurableCompaction(
  val text: String = "",
  val includedSummaryCount: Int = 0,
  val omittedSummaryCount: Int = 0,
  val totalCompactedMessageCount: Int = 0,
  val latestCompactedAtEpochMs: Long? = null,
  val entryTraces: List<DurableCompactionEntryTrace> = emptyList(),
)

interface SessionCompactionStore {
  fun load(): DurableCompactionState

  fun save(state: DurableCompactionState)

  fun clear()
}

class InMemorySessionCompactionStore : SessionCompactionStore {
  private val lock = Any()
  private var state: DurableCompactionState = DurableCompactionState()

  override fun load(): DurableCompactionState = synchronized(lock) { state }

  override fun save(state: DurableCompactionState) {
    synchronized(lock) {
      this.state = state
    }
  }

  override fun clear() {
    synchronized(lock) {
      state = DurableCompactionState()
    }
  }
}

class DurableCompactionCoordinator(
  private val contextPruner: ContextPruner = ContextPruner(),
  private val transcriptWindowBuilder: TranscriptWindowBuilder = TranscriptWindowBuilder(),
  private val compactionPolicy: CompactionPolicy = CompactionPolicy(),
  private val durableCompactionPolicy: DurableCompactionPolicy = DurableCompactionPolicy(),
  private val replayPressureEvaluator: ReplayPressureEvaluator = ReplayPressureEvaluator(),
  private val renderer: DurableCompactionRenderer = DurableCompactionRenderer(durableCompactionPolicy),
  private val sourceBudgetPolicy: ContextSourceBudgetPolicy? = null,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun compactIfNeeded(
    transcriptStore: SessionTranscriptStore,
    compactionStore: SessionCompactionStore,
    llmMetadata: Map<String, String> = emptyMap(),
    remoteCompactionProvider: RemoteCompactionProvider = NoOpRemoteCompactionProvider,
  ): DurableCompactionContext = compact(
    triggerStage = MEMORY_COMPACTION_TRIGGER_STAGE_PRE_COMPACTION,
    transcriptStore = transcriptStore,
    compactionStore = compactionStore,
    llmMetadata = llmMetadata,
    remoteCompactionProvider = remoteCompactionProvider,
  )

  fun compactMidTurn(
    transcriptStore: SessionTranscriptStore,
    compactionStore: SessionCompactionStore,
    llmMetadata: Map<String, String> = emptyMap(),
    remoteCompactionProvider: RemoteCompactionProvider = NoOpRemoteCompactionProvider,
  ): DurableCompactionContext = compact(
    triggerStage = MEMORY_COMPACTION_TRIGGER_STAGE_MID_TURN,
    transcriptStore = transcriptStore,
    compactionStore = compactionStore,
    llmMetadata = llmMetadata,
    remoteCompactionProvider = remoteCompactionProvider,
  )

  private fun compact(
    triggerStage: String,
    transcriptStore: SessionTranscriptStore,
    compactionStore: SessionCompactionStore,
    llmMetadata: Map<String, String> = emptyMap(),
    remoteCompactionProvider: RemoteCompactionProvider = NoOpRemoteCompactionProvider,
  ): DurableCompactionContext {
    val conversation = transcriptStore.snapshot()
    val effectiveTranscriptWindowBuilder = sourceBudgetPolicy
      ?.resolve(llmMetadata)
      ?.let { profile -> TranscriptWindowBuilder(profile.transcriptWindowConfig) }
      ?: transcriptWindowBuilder
    val selection = effectiveTranscriptWindowBuilder.buildSelection(conversation)
    val replayPressure = replayPressureEvaluator.evaluate(
      conversation = contextPruner.prune(conversation).messages,
      llmMetadata = llmMetadata,
    )
    val currentState = compactionStore.load()
    if (!durableCompactionPolicy.shouldCompact(selection.omittedMessages, replayPressure)) {
      return toContext(
        rendered = renderer.render(currentState),
        compactedThisRun = false,
        sourceTranscriptMessageCount = conversation.size,
        retainedTranscriptMessageCount = conversation.size,
        replayPressure = replayPressure,
        triggerStage = triggerStage,
      )
    }
    val remoteResult = remoteCompactionProvider.compact(
      RemoteCompactionRequest(
        triggerStage = triggerStage,
        conversation = conversation,
        omittedMessages = selection.omittedMessages,
        retainedMessages = selection.window.messages,
        llmMetadata = llmMetadata,
        replayPressure = replayPressure,
      ),
    )
    val remoteCompactionMetadata = remoteResult.metadata()
    val summary = when (remoteResult) {
      is RemoteCompactionResult.Success -> remoteResult.summary
      is RemoteCompactionResult.Unavailable,
      is RemoteCompactionResult.Failure,
      -> compactionPolicy.summarize(selection.omittedMessages)
    }
      ?: return toContext(
        rendered = renderer.render(currentState),
        compactedThisRun = false,
        sourceTranscriptMessageCount = conversation.size,
        retainedTranscriptMessageCount = conversation.size,
        replayPressure = replayPressure,
        triggerStage = triggerStage,
        remoteCompactionMetadata = remoteCompactionMetadata,
      )
    val compactedAtEpochMs = clock()
    val updatedState = durableCompactionPolicy.append(
      existing = currentState,
      summary = summary,
      compactedAtEpochMs = compactedAtEpochMs,
    )
    compactionStore.save(updatedState)
    transcriptStore.replace(selection.window.messages)
    return toContext(
      rendered = renderer.render(updatedState),
      compactedThisRun = true,
      sourceTranscriptMessageCount = conversation.size,
      retainedTranscriptMessageCount = selection.window.messages.size,
      latestCompactedMessageCount = summary.compactedMessageCount,
      latestCompactedAtEpochMs = compactedAtEpochMs,
      replayPressure = replayPressure,
      triggerStage = triggerStage,
      remoteCompactionMetadata = remoteCompactionMetadata,
    )
  }

  fun currentContext(compactionStore: SessionCompactionStore): DurableCompactionContext =
    toContext(
      rendered = renderer.render(compactionStore.load()),
      compactedThisRun = false,
      sourceTranscriptMessageCount = 0,
      retainedTranscriptMessageCount = 0,
    )

  private fun toContext(
    rendered: RenderedDurableCompaction,
    compactedThisRun: Boolean,
    sourceTranscriptMessageCount: Int,
    retainedTranscriptMessageCount: Int,
    latestCompactedMessageCount: Int = 0,
    latestCompactedAtEpochMs: Long? = rendered.latestCompactedAtEpochMs,
    replayPressure: ReplayPressureSnapshot? = null,
    triggerStage: String = MEMORY_COMPACTION_TRIGGER_STAGE_PRE_COMPACTION,
    remoteCompactionMetadata: Map<String, String> = emptyMap(),
  ): DurableCompactionContext = DurableCompactionContext(
    text = rendered.text,
    trace = DurableCompactionTrace(
      compactedThisRun = compactedThisRun,
      triggerStage = replayPressure?.let { triggerStage }.orEmpty(),
      maintenanceTask = replayPressure?.let { "durable_compaction:$triggerStage" }.orEmpty(),
      executionMode = replayPressure?.let { MEMORY_MAINTENANCE_EXECUTION_MODE_INLINE }.orEmpty(),
      contextWindowTokens = replayPressure?.contextWindowTokens ?: 0,
      autoCompactTokenLimit = replayPressure?.autoCompactTokenLimit ?: 0,
      estimatedReplayTokens = replayPressure?.estimatedReplayTokens ?: 0,
      tokenThresholdTriggered = replayPressure?.tokenThresholdTriggered ?: false,
      sourceTranscriptMessageCount = sourceTranscriptMessageCount,
      retainedTranscriptMessageCount = retainedTranscriptMessageCount,
      latestCompactedMessageCount = latestCompactedMessageCount,
      includedSummaryCount = rendered.includedSummaryCount,
      omittedSummaryCount = rendered.omittedSummaryCount,
      totalCompactedMessageCount = rendered.totalCompactedMessageCount,
      latestCompactedAtEpochMs = latestCompactedAtEpochMs,
      entryTraces = rendered.entryTraces,
      remoteCompactionMetadata = remoteCompactionMetadata,
    ),
  )
}

private fun RemoteCompactionResult.metadata(): Map<String, String> = when (this) {
  is RemoteCompactionResult.Success -> metadata
  is RemoteCompactionResult.Unavailable -> metadata
  is RemoteCompactionResult.Failure -> metadata
}

private const val MEMORY_COMPACTION_TRIGGER_STAGE_PRE_COMPACTION: String = "pre_compaction"
private const val MEMORY_COMPACTION_TRIGGER_STAGE_MID_TURN: String = "mid_turn"
private const val MEMORY_MAINTENANCE_EXECUTION_MODE_INLINE: String = "inline"
