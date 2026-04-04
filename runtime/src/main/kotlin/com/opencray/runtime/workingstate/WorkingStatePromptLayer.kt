package com.opencray.runtime.workingstate

data class WorkingStatePromptLayer(
  private val config: WorkingStatePromptLayerConfig = WorkingStatePromptLayerConfig(),
) {
  fun render(
    state: WorkingState,
    detailMode: WorkingStatePromptDetailMode = WorkingStatePromptDetailMode.FULL,
  ): String {
    if (state.isEmpty) {
      return ""
    }
    val renderConfig = renderConfig(detailMode)
    val lines = mutableListOf<String>()
    lines += "Compact operational state for the current task."
    state.objective?.takeIf { objective -> !objective.isEmpty }?.let { objective ->
      lines += ""
      lines += "[Objective]"
      objective.taskId?.let { taskId -> lines += "task_id=$taskId" }
      objective.runId?.let { runId -> lines += "run_id=$runId" }
      objective.primaryGoal?.let { primaryGoal -> lines += "primary_goal=$primaryGoal" }
      objective.currentSubgoal?.let { subgoal -> lines += "current_subgoal=$subgoal" }
      objective.status?.let { status -> lines += "status=$status" }
    }
    appendSection(
      lines = lines,
      title = "Recent Findings",
      entries = selectEntries(
        entries = state.findings,
        maxEntries = renderConfig.maxFindings,
        keepLatest = renderConfig.keepLatestEntries,
      ),
    )
    appendSection(
      lines = lines,
      title = "Recent Actions",
      entries = selectEntries(
        entries = state.recentActions,
        maxEntries = renderConfig.maxRecentActions,
        keepLatest = renderConfig.keepLatestEntries,
      ),
    )
    appendSection(
      lines = lines,
      title = "Decisions",
      entries = selectEntries(
        entries = state.decisions,
        maxEntries = renderConfig.maxDecisions,
        keepLatest = renderConfig.keepLatestEntries,
      ),
    )
    appendSection(
      lines = lines,
      title = "Blockers",
      entries = selectEntries(
        entries = state.blockers,
        maxEntries = renderConfig.maxBlockers,
        keepLatest = renderConfig.keepLatestEntries,
      ),
    )
    appendSection(
      lines = lines,
      title = "Next Actions",
      entries = selectEntries(
        entries = state.nextActions,
        maxEntries = renderConfig.maxNextActions,
        keepLatest = renderConfig.keepLatestEntries,
      ),
    )
    state.updatedAtEpochMs?.takeIf { renderConfig.includeUpdatedAt }?.let { updatedAtEpochMs ->
      lines += ""
      lines += "updated_at_epoch_ms=$updatedAtEpochMs"
    }
    return lines.joinToString(separator = "\n").trim()
  }

  private fun renderConfig(
    detailMode: WorkingStatePromptDetailMode,
  ): WorkingStatePromptRenderConfig = when (detailMode) {
    WorkingStatePromptDetailMode.FULL -> WorkingStatePromptRenderConfig(
      maxFindings = config.maxFindings,
      maxRecentActions = config.maxRecentActions,
      maxDecisions = config.maxDecisions,
      maxBlockers = config.maxBlockers,
      maxNextActions = config.maxNextActions,
      keepLatestEntries = false,
      includeUpdatedAt = true,
    )

    WorkingStatePromptDetailMode.COMPACT -> WorkingStatePromptRenderConfig(
      maxFindings = minOf(config.maxFindings, 1),
      maxRecentActions = minOf(config.maxRecentActions, 2),
      maxDecisions = minOf(config.maxDecisions, 1),
      maxBlockers = minOf(config.maxBlockers, 2),
      maxNextActions = minOf(config.maxNextActions, 2),
      keepLatestEntries = true,
      includeUpdatedAt = false,
    )

    WorkingStatePromptDetailMode.MINIMAL -> WorkingStatePromptRenderConfig(
      maxFindings = 0,
      maxRecentActions = minOf(config.maxRecentActions, 1),
      maxDecisions = minOf(config.maxDecisions, 1),
      maxBlockers = minOf(config.maxBlockers, 1),
      maxNextActions = minOf(config.maxNextActions, 1),
      keepLatestEntries = true,
      includeUpdatedAt = false,
    )
  }

  private fun selectEntries(
    entries: List<WorkingStateEntry>,
    maxEntries: Int,
    keepLatest: Boolean,
  ): List<WorkingStateEntry> {
    if (maxEntries <= 0 || entries.isEmpty()) {
      return emptyList()
    }
    return if (keepLatest) {
      entries.takeLast(maxEntries)
    } else {
      entries.take(maxEntries)
    }
  }

  private fun appendSection(
    lines: MutableList<String>,
    title: String,
    entries: List<WorkingStateEntry>,
  ) {
    if (entries.isEmpty()) {
      return
    }
    lines += ""
    lines += "[$title]"
    entries.forEach { entry ->
      val suffixes = buildList {
        entry.sourceType?.let { sourceType -> add("source=$sourceType") }
        entry.rationale?.let { rationale -> add("why=$rationale") }
      }
      val suffixText = if (suffixes.isEmpty()) {
        ""
      } else {
        " [" + suffixes.joinToString(separator = "; ") + "]"
      }
      lines += "- ${entry.text}$suffixText"
    }
  }
}

data class WorkingStatePromptLayerConfig(
  val maxFindings: Int = 6,
  val maxRecentActions: Int = 8,
  val maxDecisions: Int = 4,
  val maxBlockers: Int = 3,
  val maxNextActions: Int = 4,
) {
  init {
    require(maxFindings >= 0) { "WorkingStatePromptLayerConfig maxFindings must be >= 0." }
    require(maxRecentActions >= 0) { "WorkingStatePromptLayerConfig maxRecentActions must be >= 0." }
    require(maxDecisions >= 0) { "WorkingStatePromptLayerConfig maxDecisions must be >= 0." }
    require(maxBlockers >= 0) { "WorkingStatePromptLayerConfig maxBlockers must be >= 0." }
    require(maxNextActions >= 0) { "WorkingStatePromptLayerConfig maxNextActions must be >= 0." }
  }
}

enum class WorkingStatePromptDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

private data class WorkingStatePromptRenderConfig(
  val maxFindings: Int,
  val maxRecentActions: Int,
  val maxDecisions: Int,
  val maxBlockers: Int,
  val maxNextActions: Int,
  val keepLatestEntries: Boolean,
  val includeUpdatedAt: Boolean,
)
