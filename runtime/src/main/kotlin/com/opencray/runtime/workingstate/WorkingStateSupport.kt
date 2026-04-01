package com.opencray.runtime.workingstate

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus

data class WorkingStateSupportConfig(
  val maxFindings: Int = 6,
  val maxRecentActions: Int = 8,
  val maxDecisions: Int = 4,
  val maxBlockers: Int = 3,
  val maxNextActions: Int = 4,
  val maxPrimaryGoalChars: Int = 280,
  val maxSubgoalChars: Int = 180,
  val maxStatusChars: Int = 48,
  val maxIdentityChars: Int = 96,
  val maxEntryChars: Int = 180,
  val maxDerivedRecentActions: Int = 4,
) {
  init {
    require(maxFindings >= 1) { "WorkingStateSupportConfig maxFindings must be >= 1." }
    require(maxRecentActions >= 1) { "WorkingStateSupportConfig maxRecentActions must be >= 1." }
    require(maxDecisions >= 1) { "WorkingStateSupportConfig maxDecisions must be >= 1." }
    require(maxBlockers >= 1) { "WorkingStateSupportConfig maxBlockers must be >= 1." }
    require(maxNextActions >= 1) { "WorkingStateSupportConfig maxNextActions must be >= 1." }
    require(maxPrimaryGoalChars >= 48) { "WorkingStateSupportConfig maxPrimaryGoalChars must be >= 48." }
    require(maxSubgoalChars >= 32) { "WorkingStateSupportConfig maxSubgoalChars must be >= 32." }
    require(maxStatusChars >= 8) { "WorkingStateSupportConfig maxStatusChars must be >= 8." }
    require(maxIdentityChars >= 16) { "WorkingStateSupportConfig maxIdentityChars must be >= 16." }
    require(maxEntryChars >= 24) { "WorkingStateSupportConfig maxEntryChars must be >= 24." }
    require(maxDerivedRecentActions >= 1) {
      "WorkingStateSupportConfig maxDerivedRecentActions must be >= 1."
    }
  }
}

data class WorkingStateResolution(
  val state: WorkingState = WorkingState(),
  val trace: WorkingStateTrace = WorkingStateTrace(),
)

class WorkingStateSupport(
  private val config: WorkingStateSupportConfig = WorkingStateSupportConfig(),
) {
  fun resolve(
    task: AgentTask,
    runId: String? = null,
    seededState: WorkingState = WorkingState(),
    resumeContext: WorkingStateResumeContext? = null,
    recentActionEntries: List<WorkingStateEntry> = emptyList(),
    decisionEntries: List<WorkingStateEntry> = emptyList(),
    blockerEntries: List<WorkingStateEntry> = emptyList(),
    recentObservationLines: List<String> = emptyList(),
    todoSnapshot: List<AgentTodoEntry> = emptyList(),
  ): WorkingStateResolution {
    val normalizedSeeded = normalize(seededState)
    val todoProjection = deriveTodoProjection(todoSnapshot)
    val objectiveResolution = resolveObjective(
      task = task,
      runId = runId,
      seeded = normalizedSeeded.objective,
      todoProjection = todoProjection,
    )
    val resumeProjection = deriveResumeProjection(resumeContext)
    val derivedRecentActions = if (normalizedSeeded.recentActions.isEmpty()) {
      recentObservationLines
        .map(String::trim)
        .filter(String::isNotBlank)
        .take(config.maxDerivedRecentActions)
        .map { summary ->
          WorkingStateEntry(
            text = truncate(summary, config.maxEntryChars),
            sourceType = "recent_observation",
          )
        }
    } else {
      emptyList()
    }
    val normalizedDerivedRecentActions = normalizeEntries(recentActionEntries, config.maxRecentActions)
    val normalizedDerivedDecisions = normalizeEntries(decisionEntries, config.maxDecisions)
    val normalizedDerivedBlockers = normalizeEntries(blockerEntries, config.maxBlockers)
    val combinedDerivedRecentActions = normalizeEntries(
      normalizedDerivedRecentActions + resumeProjection.recentActions,
      config.maxRecentActions,
    )
    val combinedDerivedDecisions = normalizeEntries(
      normalizedDerivedDecisions + resumeProjection.decisions,
      config.maxDecisions,
    )
    val effectiveRecentActions = when {
      normalizedSeeded.recentActions.isNotEmpty() -> normalizedSeeded.recentActions
      combinedDerivedRecentActions.isNotEmpty() -> combinedDerivedRecentActions
      else -> derivedRecentActions
    }
    val effectiveDecisions = when {
      normalizedSeeded.decisions.isNotEmpty() -> normalizedSeeded.decisions
      combinedDerivedDecisions.isNotEmpty() -> combinedDerivedDecisions
      else -> emptyList()
    }
    val effectiveBlockers = when {
      normalizedSeeded.blockers.isNotEmpty() -> normalizedSeeded.blockers
      normalizedDerivedBlockers.isNotEmpty() -> normalizedDerivedBlockers
      else -> emptyList()
    }
    val effectiveNextActions = normalizedSeeded.nextActions.ifEmpty { todoProjection.nextActions }
    val resumeContextUsed = effectiveRecentActions.any { entry -> entry.sourceType == "resume_checkpoint" } ||
      effectiveDecisions.any { entry -> entry.sourceType == "resume_checkpoint" }
    val resolvedState = normalizedSeeded.copy(
      objective = objectiveResolution.objective,
      recentActions = effectiveRecentActions,
      decisions = effectiveDecisions,
      blockers = effectiveBlockers,
      nextActions = effectiveNextActions,
    )
    if (resolvedState.isEmpty) {
      return WorkingStateResolution()
    }
    return WorkingStateResolution(
      state = resolvedState,
      trace = WorkingStateTrace(
        included = true,
        objectivePresent = resolvedState.objective?.isEmpty == false,
        findingCount = resolvedState.findings.size,
        recentActionCount = resolvedState.recentActions.size,
        decisionCount = resolvedState.decisions.size,
        blockerCount = resolvedState.blockers.size,
        nextActionCount = resolvedState.nextActions.size,
        synthesizedFromTaskInput = objectiveResolution.synthesizedFromTaskInput,
        synthesizedFromRecentObservations = normalizedDerivedRecentActions.isNotEmpty() ||
          normalizedDerivedDecisions.isNotEmpty() ||
          normalizedDerivedBlockers.isNotEmpty() ||
          derivedRecentActions.isNotEmpty(),
        synthesizedFromResumeContext = resumeContextUsed,
        synthesizedFromTodoSnapshot = objectiveResolution.synthesizedFromTodoSnapshot ||
          (normalizedSeeded.nextActions.isEmpty() && todoProjection.nextActions.isNotEmpty()),
      ),
    )
  }

  private fun normalize(state: WorkingState): WorkingState = WorkingState(
    objective = state.objective?.let(::normalizeObjective)?.takeIf { objective -> !objective.isEmpty },
    findings = normalizeEntries(state.findings, config.maxFindings),
    recentActions = normalizeEntries(state.recentActions, config.maxRecentActions),
    decisions = normalizeEntries(state.decisions, config.maxDecisions),
    blockers = normalizeEntries(state.blockers, config.maxBlockers),
    nextActions = normalizeEntries(state.nextActions, config.maxNextActions),
    updatedAtEpochMs = state.updatedAtEpochMs,
  )

  private fun normalizeObjective(objective: WorkingStateObjective): WorkingStateObjective = WorkingStateObjective(
    taskId = objective.taskId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { taskId -> truncate(taskId, config.maxIdentityChars) },
    runId = objective.runId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { runId -> truncate(runId, config.maxIdentityChars) },
    primaryGoal = objective.primaryGoal
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { goal -> truncate(goal, config.maxPrimaryGoalChars) },
    currentSubgoal = objective.currentSubgoal
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { subgoal -> truncate(subgoal, config.maxSubgoalChars) },
    status = objective.status
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { status -> truncate(status, config.maxStatusChars) },
  )

  private fun resolveObjective(
    task: AgentTask,
    runId: String?,
    seeded: WorkingStateObjective?,
    todoProjection: TodoProjection,
  ): ObjectiveResolution {
    val normalizedTaskInput = task.input.trim().takeIf(String::isNotBlank)
    val normalizedTaskId = task.id.trim().takeIf(String::isNotBlank)
    val normalizedRunId = runId?.trim()?.takeIf(String::isNotBlank)
    val synthesizedFromTaskInput = seeded?.primaryGoal.isNullOrBlank() && !normalizedTaskInput.isNullOrBlank()
    val taskId = seeded?.taskId ?: normalizedTaskId?.let { value ->
      truncate(value, config.maxIdentityChars)
    }
    val resolvedRunId = seeded?.runId ?: normalizedRunId?.let { value ->
      truncate(value, config.maxIdentityChars)
    }
    val primaryGoal = seeded?.primaryGoal ?: normalizedTaskInput?.let { input ->
      truncate(input, config.maxPrimaryGoalChars)
    }
    val currentSubgoal = seeded?.currentSubgoal ?: todoProjection.currentSubgoal
    val status = seeded?.status ?: todoProjection.status ?: primaryGoal?.let { DEFAULT_OBJECTIVE_STATUS }
    val objective = WorkingStateObjective(
      taskId = taskId,
      runId = resolvedRunId,
      primaryGoal = primaryGoal,
      currentSubgoal = currentSubgoal,
      status = status,
    ).takeIf { candidate -> !candidate.isEmpty }
    return ObjectiveResolution(
      objective = objective,
      synthesizedFromTaskInput = synthesizedFromTaskInput,
      synthesizedFromTodoSnapshot = (
        seeded?.currentSubgoal.isNullOrBlank() &&
          !todoProjection.currentSubgoal.isNullOrBlank()
        ) || (
        seeded?.status.isNullOrBlank() &&
          !todoProjection.status.isNullOrBlank()
        ),
    )
  }

  private fun deriveTodoProjection(
    todoSnapshot: List<AgentTodoEntry>,
  ): TodoProjection {
    if (todoSnapshot.isEmpty()) {
      return TodoProjection()
    }
    val normalizedTodos = todoSnapshot.mapNotNull { entry ->
      val content = entry.content.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
      AgentTodoEntry(
        content = content,
        status = entry.status,
        activeForm = entry.activeForm?.trim()?.takeIf(String::isNotBlank),
      )
    }
    if (normalizedTodos.isEmpty()) {
      return TodoProjection()
    }
    val activeTodo = normalizedTodos.firstOrNull { entry -> entry.status == AgentTodoStatus.IN_PROGRESS }
    val pendingTodos = normalizedTodos.filter { entry -> entry.status == AgentTodoStatus.PENDING }
    if (activeTodo == null && pendingTodos.isEmpty()) {
      return TodoProjection()
    }
    val derivedStatus = if (activeTodo != null) {
      "in_progress"
    } else {
      DEFAULT_OBJECTIVE_STATUS
    }
    return TodoProjection(
      currentSubgoal = activeTodo
        ?.activeForm
        ?.let { activeForm -> truncate(activeForm, config.maxSubgoalChars) }
        ?: activeTodo?.content?.let { content -> truncate(content, config.maxSubgoalChars) },
      status = derivedStatus,
      nextActions = pendingTodos
        .take(config.maxNextActions)
        .map { entry ->
          WorkingStateEntry(
            text = truncate(entry.content, config.maxEntryChars),
            sourceType = "todo_snapshot",
          )
        },
    )
  }

  private fun deriveResumeProjection(
    resumeContext: WorkingStateResumeContext?,
  ): ResumeProjection {
    if (resumeContext == null) {
      return ResumeProjection()
    }
    val recentAction = WorkingStateEntry(
      text = buildString {
        append("Resume checkpoint")
        append(" turn=")
        append(resumeContext.turnIndex)
        append(" tool_calls=")
        append(resumeContext.toolCallCount)
        append(" pending_actions=")
        append(resumeContext.pendingActionCount)
        resumeContext.nextActionType?.let { nextActionType ->
          append(" next_action=")
          append(nextActionType)
        }
        resumeContext.pendingToolName?.let { pendingToolName ->
          append(" pending_tool=")
          append(pendingToolName)
        }
      },
      sourceType = "resume_checkpoint",
      rationale = resumeContext.checkpointBoundary
        ?: resumeContext.requiresSingleActionReminder
          .takeIf { it }
          ?.let { "single_action_reminder" },
    )
    val decision = WorkingStateEntry(
      text = "Continue from the saved checkpoint state instead of restarting from the original task input.",
      sourceType = "resume_checkpoint",
      rationale = buildList {
        if (resumeContext.pendingActionCount > 0) {
          add("pending_actions")
        }
        if (resumeContext.requiresSingleActionReminder) {
          add("single_action_reminder")
        }
        resumeContext.checkpointBoundary?.let(::add)
      }.joinToString(separator = "+").takeIf(String::isNotBlank),
    )
    return ResumeProjection(
      recentActions = listOf(recentAction),
      decisions = listOf(decision),
    )
  }

  private fun normalizeEntries(
    entries: List<WorkingStateEntry>,
    maxEntries: Int,
  ): List<WorkingStateEntry> = entries.mapNotNull { entry ->
    val text = entry.text.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
    WorkingStateEntry(
      text = truncate(text, config.maxEntryChars),
      sourceType = entry.sourceType
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value -> truncate(value, config.maxStatusChars) },
      rationale = entry.rationale
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value -> truncate(value, config.maxEntryChars) },
    )
  }.take(maxEntries)

  private fun truncate(
    value: String,
    maxChars: Int,
  ): String {
    val normalized = value.trim()
    if (normalized.length <= maxChars) {
      return normalized
    }
    return normalized.take(maxChars - ELLIPSIS.length).trimEnd() + ELLIPSIS
  }

  private companion object {
    const val DEFAULT_OBJECTIVE_STATUS: String = "active"
    const val ELLIPSIS: String = "..."
  }

  private data class ObjectiveResolution(
    val objective: WorkingStateObjective?,
    val synthesizedFromTaskInput: Boolean,
    val synthesizedFromTodoSnapshot: Boolean,
  )

  private data class ResumeProjection(
    val recentActions: List<WorkingStateEntry> = emptyList(),
    val decisions: List<WorkingStateEntry> = emptyList(),
  ) {
    val isNotEmpty: Boolean
      get() = recentActions.isNotEmpty() || decisions.isNotEmpty()
  }

  private data class TodoProjection(
    val currentSubgoal: String? = null,
    val status: String? = null,
    val nextActions: List<WorkingStateEntry> = emptyList(),
  )
}
