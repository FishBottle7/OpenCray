package com.opencray.runtime.compaction

import com.opencray.runtime.context.CompactionPolicy
import com.opencray.runtime.context.CompactionSummary
import com.opencray.runtime.context.ContextPruner
import com.opencray.runtime.context.ReplayPressureEvaluator
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
  val sourceTranscriptMessageCount: Int = 0,
  val retainedTranscriptMessageCount: Int = 0,
  val latestCompactedMessageCount: Int = 0,
  val includedSummaryCount: Int = 0,
  val omittedSummaryCount: Int = 0,
  val totalCompactedMessageCount: Int = 0,
  val latestCompactedAtEpochMs: Long? = null,
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
      latestCompactedMessageCount == 0 &&
      includedSummaryCount == 0 &&
      omittedSummaryCount == 0 &&
      totalCompactedMessageCount == 0 &&
      latestCompactedAtEpochMs == null
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
    if (includedEntries.isEmpty()) {
      return RenderedDurableCompaction(
        omittedSummaryCount = state.entries.size,
        totalCompactedMessageCount = totalCompactedMessageCount,
        latestCompactedAtEpochMs = latestCompactedAtEpochMs,
      )
    }
    return RenderedDurableCompaction(
      text = lines.joinToString(separator = "\n").trim(),
      includedSummaryCount = includedEntries.size,
      omittedSummaryCount = (state.entries.size - includedEntries.size).coerceAtLeast(0),
      totalCompactedMessageCount = totalCompactedMessageCount,
      latestCompactedAtEpochMs = latestCompactedAtEpochMs,
    )
  }
}

internal data class RenderedDurableCompaction(
  val text: String = "",
  val includedSummaryCount: Int = 0,
  val omittedSummaryCount: Int = 0,
  val totalCompactedMessageCount: Int = 0,
  val latestCompactedAtEpochMs: Long? = null,
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
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun compactIfNeeded(
    transcriptStore: SessionTranscriptStore,
    compactionStore: SessionCompactionStore,
    llmMetadata: Map<String, String> = emptyMap(),
  ): DurableCompactionContext {
    val conversation = transcriptStore.snapshot()
    val selection = transcriptWindowBuilder.buildSelection(conversation)
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
      )
    }
    val summary = compactionPolicy.summarize(selection.omittedMessages)
      ?: return toContext(
        rendered = renderer.render(currentState),
        compactedThisRun = false,
        sourceTranscriptMessageCount = conversation.size,
        retainedTranscriptMessageCount = conversation.size,
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
  ): DurableCompactionContext = DurableCompactionContext(
    text = rendered.text,
    trace = DurableCompactionTrace(
      compactedThisRun = compactedThisRun,
      sourceTranscriptMessageCount = sourceTranscriptMessageCount,
      retainedTranscriptMessageCount = retainedTranscriptMessageCount,
      latestCompactedMessageCount = latestCompactedMessageCount,
      includedSummaryCount = rendered.includedSummaryCount,
      omittedSummaryCount = rendered.omittedSummaryCount,
      totalCompactedMessageCount = rendered.totalCompactedMessageCount,
      latestCompactedAtEpochMs = latestCompactedAtEpochMs,
    ),
  )
}
