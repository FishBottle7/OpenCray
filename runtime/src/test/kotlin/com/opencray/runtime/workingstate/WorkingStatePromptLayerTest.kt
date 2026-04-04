package com.opencray.runtime.workingstate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingStatePromptLayerTest {
  @Test
  fun renderMinimalKeepsObjectiveAndLatestOperationalEntries() {
    val layer = WorkingStatePromptLayer()
    val state = WorkingState(
      objective = WorkingStateObjective(
        taskId = "task-working-state",
        runId = "run-working-state",
        primaryGoal = "Preserve the active objective.",
        currentSubgoal = "Keep only the latest operational entries.",
        status = "in_progress",
      ),
      findings = listOf(
        WorkingStateEntry(text = "Finding 1", sourceType = "code_inspection"),
        WorkingStateEntry(text = "Finding 2", sourceType = "code_inspection"),
      ),
      recentActions = listOf(
        WorkingStateEntry(text = "Recent action 1", sourceType = "workspace_mutation"),
        WorkingStateEntry(text = "Recent action 2", sourceType = "workspace_mutation"),
      ),
      decisions = listOf(
        WorkingStateEntry(text = "Decision 1", sourceType = "branch_control"),
        WorkingStateEntry(text = "Decision 2", sourceType = "branch_control"),
      ),
      blockers = listOf(
        WorkingStateEntry(text = "Blocker 1", sourceType = "approval_boundary"),
        WorkingStateEntry(text = "Blocker 2", sourceType = "approval_boundary"),
      ),
      nextActions = listOf(
        WorkingStateEntry(text = "Next action 1", sourceType = "todo_snapshot"),
        WorkingStateEntry(text = "Next action 2", sourceType = "todo_snapshot"),
      ),
      updatedAtEpochMs = 123L,
    )

    val rendered = layer.render(
      state = state,
      detailMode = WorkingStatePromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.contains("[Objective]"))
    assertTrue(rendered.contains("primary_goal=Preserve the active objective."))
    assertFalse(rendered.contains("[Recent Findings]"))
    assertFalse(rendered.contains("Finding 1"))
    assertFalse(rendered.contains("Recent action 1"))
    assertTrue(rendered.contains("Recent action 2"))
    assertFalse(rendered.contains("Decision 1"))
    assertTrue(rendered.contains("Decision 2"))
    assertFalse(rendered.contains("Blocker 1"))
    assertTrue(rendered.contains("Blocker 2"))
    assertFalse(rendered.contains("Next action 1"))
    assertTrue(rendered.contains("Next action 2"))
    assertFalse(rendered.contains("updated_at_epoch_ms=123"))
  }
}
