package com.opencray.runtime.workingstate

data class WorkingStatePromptLayer(
  private val config: WorkingStatePromptLayerConfig = WorkingStatePromptLayerConfig(),
) {
  fun render(state: WorkingState): String {
    if (state.isEmpty) {
      return ""
    }
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
      entries = state.findings.take(config.maxFindings),
    )
    appendSection(
      lines = lines,
      title = "Recent Actions",
      entries = state.recentActions.take(config.maxRecentActions),
    )
    appendSection(
      lines = lines,
      title = "Decisions",
      entries = state.decisions.take(config.maxDecisions),
    )
    appendSection(
      lines = lines,
      title = "Blockers",
      entries = state.blockers.take(config.maxBlockers),
    )
    appendSection(
      lines = lines,
      title = "Next Actions",
      entries = state.nextActions.take(config.maxNextActions),
    )
    state.updatedAtEpochMs?.let { updatedAtEpochMs ->
      lines += ""
      lines += "updated_at_epoch_ms=$updatedAtEpochMs"
    }
    return lines.joinToString(separator = "\n").trim()
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
    require(maxFindings >= 1) { "WorkingStatePromptLayerConfig maxFindings must be >= 1." }
    require(maxRecentActions >= 1) { "WorkingStatePromptLayerConfig maxRecentActions must be >= 1." }
    require(maxDecisions >= 1) { "WorkingStatePromptLayerConfig maxDecisions must be >= 1." }
    require(maxBlockers >= 1) { "WorkingStatePromptLayerConfig maxBlockers must be >= 1." }
    require(maxNextActions >= 1) { "WorkingStatePromptLayerConfig maxNextActions must be >= 1." }
  }
}
