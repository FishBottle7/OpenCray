package com.opencray.runtime.context

internal class ReplayPressureTranscriptMaintenanceSelector(
  private val transcriptWindowBuilder: TranscriptWindowBuilder = TranscriptWindowBuilder(),
  private val contextPruner: ContextPruner = ContextPruner(),
  private val replayPressureEvaluator: ReplayPressureEvaluator = ReplayPressureEvaluator(),
  private val minRetainedMessages: Int = DEFAULT_MIN_RETAINED_MESSAGES,
) {
  init {
    require(minRetainedMessages >= 1) {
      "ReplayPressureTranscriptMaintenanceSelector minRetainedMessages must be >= 1."
    }
  }

  fun select(
    conversation: List<RuntimeConversationMessage>,
    llmMetadata: Map<String, String> = emptyMap(),
  ): TranscriptWindowSelection {
    val baseSelection = transcriptWindowBuilder.buildSelection(conversation)
    if (baseSelection.normalizedMessages.size <= 1 || baseSelection.window.messages.size <= 1) {
      return baseSelection
    }
    val basePressure = replayPressureEvaluator.evaluate(
      conversation = contextPruner.prune(baseSelection.window.messages).messages,
      llmMetadata = llmMetadata,
    )
    if (!basePressure.tokenThresholdTriggered) {
      return baseSelection
    }

    val maxCandidateRetainedMessages = minOf(
      transcriptWindowBuilder.config.maxMessages - 1,
      baseSelection.window.messages.size - 1,
    )
    val effectiveMinRetainedMessages = maxOf(
      1,
      minOf(
        minRetainedMessages,
        baseSelection.normalizedMessages.size - 1,
      ),
    )
    if (maxCandidateRetainedMessages < effectiveMinRetainedMessages) {
      return baseSelection
    }

    var fallbackSelection: TranscriptWindowSelection? = baseSelection
      .takeIf { selection -> selection.omittedMessages.isNotEmpty() }
    for (
      retainedCount in maxCandidateRetainedMessages downTo effectiveMinRetainedMessages
    ) {
      val candidateSelection = transcriptWindowBuilder.buildSelection(
        messages = baseSelection.normalizedMessages,
        windowConfig = transcriptWindowBuilder.config.copy(maxMessages = retainedCount),
      )
      if (candidateSelection.omittedMessages.isEmpty()) {
        continue
      }
      fallbackSelection = candidateSelection
      val candidatePressure = replayPressureEvaluator.evaluate(
        conversation = contextPruner.prune(candidateSelection.window.messages).messages,
        llmMetadata = llmMetadata,
      )
      if (!candidatePressure.tokenThresholdTriggered) {
        return candidateSelection
      }
    }
    return fallbackSelection ?: baseSelection
  }

  companion object {
    const val DEFAULT_MIN_RETAINED_MESSAGES: Int = 4
  }
}
