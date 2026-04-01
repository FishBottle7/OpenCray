package com.opencray.runtime.workingstate

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingStateSupportTest {
  private val support = WorkingStateSupport()

  @Test
  fun resolveSynthesizesObjectiveAndRecentActionsWhenSeededStateIsEmpty() {
    val resolution = support.resolve(
      task = promptTask(
        input = "Inspect the runtime checkpoint flow and confirm what still blocks detached continuation.",
      ),
      recentObservationLines = listOf(
        "Read file_path=runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt lines=1930-2060 limit=all truncated=false",
        "Task description=inspect readme subagent=researcher state=completed context=minimal",
      ),
    )

    assertTrue(resolution.trace.included)
    assertTrue(resolution.trace.objectivePresent)
    assertTrue(resolution.trace.synthesizedFromTaskInput)
    assertTrue(resolution.trace.synthesizedFromRecentObservations)
    assertFalse(resolution.trace.synthesizedFromTodoSnapshot)
    assertEquals(
      "Inspect the runtime checkpoint flow and confirm what still blocks detached continuation.",
      resolution.state.objective?.primaryGoal,
    )
    assertEquals("task-working-state", resolution.state.objective?.taskId)
    assertEquals("active", resolution.state.objective?.status)
    assertEquals(2, resolution.state.recentActions.size)
    assertEquals("recent_observation", resolution.state.recentActions.first().sourceType)
  }

  @Test
  fun resolveSynthesizesRunIdentityWhenProvided() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue the runtime rollout."),
      runId = "run-working-state-1",
    )

    assertEquals("task-working-state", resolution.state.objective?.taskId)
    assertEquals("run-working-state-1", resolution.state.objective?.runId)
  }

  @Test
  fun resolveProjectsResumeContextIntoRecentActionsAndDecisions() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue after approval resume."),
      resumeContext = WorkingStateResumeContext(
        turnIndex = 1,
        toolCallCount = 1,
        pendingActionCount = 1,
        nextActionType = "tool_call",
        pendingToolName = "Write",
        requiresSingleActionReminder = true,
        checkpointBoundary = "tool_result_committed",
      ),
    )

    assertTrue(resolution.trace.synthesizedFromTaskInput)
    assertTrue(resolution.trace.synthesizedFromResumeContext)
    assertEquals(
      listOf("Resume checkpoint turn=1 tool_calls=1 pending_actions=1 next_action=tool_call pending_tool=Write"),
      resolution.state.recentActions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("resume_checkpoint"),
      resolution.state.recentActions.map { entry -> entry.sourceType },
    )
    assertEquals(
      listOf("Continue from the saved checkpoint state instead of restarting from the original task input."),
      resolution.state.decisions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("pending_actions+single_action_reminder+tool_result_committed"),
      resolution.state.decisions.map { entry -> entry.rationale },
    )
  }

  @Test
  fun resolvePreservesSeededStructuredStateAndCapsEntries() {
    val resolution = support.resolve(
      task = promptTask(),
      seededState = WorkingState(
        objective = WorkingStateObjective(
          primaryGoal = "Keep a structured operational view.",
          currentSubgoal = "Finish the prompt-layer scaffold.",
          status = "in_progress",
        ),
        findings = List(8) { index ->
          WorkingStateEntry(text = "Finding ${index + 1}")
        },
        decisions = listOf(
          WorkingStateEntry(
            text = "Keep working state separate from durable memory.",
            rationale = "Avoid procedural long-term memory noise.",
          ),
        ),
        blockers = listOf(
          WorkingStateEntry(text = "Need host projection later."),
        ),
        nextActions = listOf(
          WorkingStateEntry(text = "Add prompt layer tests."),
          WorkingStateEntry(text = "Wire host projection later."),
        ),
      ),
      recentObservationLines = listOf(
        "This derived observation should not replace seeded state.",
      ),
    )

    assertFalse(resolution.trace.synthesizedFromTaskInput)
    assertTrue(resolution.trace.synthesizedFromRecentObservations)
    assertFalse(resolution.trace.synthesizedFromTodoSnapshot)
    assertEquals("Finish the prompt-layer scaffold.", resolution.state.objective?.currentSubgoal)
    assertEquals(6, resolution.state.findings.size)
    assertEquals(1, resolution.state.decisions.size)
    assertEquals(1, resolution.state.blockers.size)
    assertEquals(2, resolution.state.nextActions.size)
    assertEquals(1, resolution.state.recentActions.size)
    assertEquals("This derived observation should not replace seeded state.", resolution.state.recentActions.single().text)
  }

  @Test
  fun resolveDoesNotClaimResumeProjectionWhenSeededEntriesOverrideIt() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue after approval resume."),
      seededState = WorkingState(
        recentActions = listOf(
          WorkingStateEntry(
            text = "Keep the earlier concrete tool result.",
            sourceType = "workspace_mutation",
          ),
        ),
        decisions = listOf(
          WorkingStateEntry(
            text = "Keep the manual recovery plan.",
            sourceType = "seeded_decision",
          ),
        ),
      ),
      resumeContext = WorkingStateResumeContext(
        turnIndex = 1,
        toolCallCount = 1,
        pendingActionCount = 1,
        nextActionType = "tool_call",
        pendingToolName = "Write",
      ),
    )

    assertFalse(resolution.trace.synthesizedFromResumeContext)
    assertEquals(
      listOf("Keep the earlier concrete tool result."),
      resolution.state.recentActions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("Keep the manual recovery plan."),
      resolution.state.decisions.map { entry -> entry.text },
    )
  }

  @Test
  fun resolveUsesTodoSnapshotToFillMissingSubgoalAndNextActions() {
    val resolution = support.resolve(
      task = promptTask(input = "Ship the working-state runtime slice."),
      seededState = WorkingState(
        objective = WorkingStateObjective(
          primaryGoal = "Ship the working-state runtime slice.",
        ),
      ),
      todoSnapshot = listOf(
        AgentTodoEntry(
          content = "Inspect ContextManager wiring",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Inspecting ContextManager wiring",
        ),
        AgentTodoEntry(
          content = "Add focused tests",
          status = AgentTodoStatus.PENDING,
        ),
        AgentTodoEntry(
          content = "Run runtime unit tests",
          status = AgentTodoStatus.PENDING,
        ),
        AgentTodoEntry(
          content = "Outline earlier design",
          status = AgentTodoStatus.COMPLETED,
        ),
      ),
    )

    assertFalse(resolution.trace.synthesizedFromTaskInput)
    assertFalse(resolution.trace.synthesizedFromRecentObservations)
    assertTrue(resolution.trace.synthesizedFromTodoSnapshot)
    assertEquals("Ship the working-state runtime slice.", resolution.state.objective?.primaryGoal)
    assertEquals("Inspecting ContextManager wiring", resolution.state.objective?.currentSubgoal)
    assertEquals("in_progress", resolution.state.objective?.status)
    assertEquals(
      listOf("Add focused tests", "Run runtime unit tests"),
      resolution.state.nextActions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("todo_snapshot", "todo_snapshot"),
      resolution.state.nextActions.map { entry -> entry.sourceType },
    )
  }

  @Test
  fun resolvePrefersStructuredRecentActionEntriesOverRawObservationLines() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue the runtime rollout."),
      recentActionEntries = listOf(
        WorkingStateEntry(
          text = "Write file_path=README.md",
          sourceType = "workspace_mutation",
        ),
      ),
      recentObservationLines = listOf(
        "Read file_path=README.md lines=1-20 limit=all truncated=false",
      ),
    )

    assertTrue(resolution.trace.synthesizedFromTaskInput)
    assertTrue(resolution.trace.synthesizedFromRecentObservations)
    assertEquals(1, resolution.state.recentActions.size)
    assertEquals("Write file_path=README.md", resolution.state.recentActions.single().text)
    assertEquals("workspace_mutation", resolution.state.recentActions.single().sourceType)
  }

  @Test
  fun resolveUsesDerivedDecisionAndBlockerEntriesWhenSeededStateDoesNotHaveThem() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue the runtime rollout."),
      decisionEntries = listOf(
        WorkingStateEntry(
          text = "Approval granted for Write; resume from saved checkpoint.",
          sourceType = "approval_decision",
        ),
      ),
      blockerEntries = listOf(
        WorkingStateEntry(
          text = "Approval required for Write before continuing.",
          sourceType = "approval_boundary",
        ),
      ),
    )

    assertTrue(resolution.trace.synthesizedFromTaskInput)
    assertTrue(resolution.trace.synthesizedFromRecentObservations)
    assertEquals(
      listOf("Approval granted for Write; resume from saved checkpoint."),
      resolution.state.decisions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("Approval required for Write before continuing."),
      resolution.state.blockers.map { entry -> entry.text },
    )
  }

  @Test
  fun resolvePreservesSeededDecisionAndBlockerEntriesOverDerivedSignals() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue the runtime rollout."),
      seededState = WorkingState(
        decisions = listOf(
          WorkingStateEntry(
            text = "Keep the earlier manual checkpoint plan.",
            sourceType = "seeded_decision",
          ),
        ),
        blockers = listOf(
          WorkingStateEntry(
            text = "Waiting on the host projection slice.",
            sourceType = "seeded_blocker",
          ),
        ),
      ),
      decisionEntries = listOf(
        WorkingStateEntry(
          text = "Approval granted for Write; resume from saved checkpoint.",
          sourceType = "approval_decision",
        ),
      ),
      blockerEntries = listOf(
        WorkingStateEntry(
          text = "Approval required for Write before continuing.",
          sourceType = "approval_boundary",
        ),
      ),
    )

    assertTrue(resolution.trace.synthesizedFromRecentObservations)
    assertEquals(
      listOf("Keep the earlier manual checkpoint plan."),
      resolution.state.decisions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("Waiting on the host projection slice."),
      resolution.state.blockers.map { entry -> entry.text },
    )
  }

  @Test
  fun resolveIgnoresCompletedOnlyTodoSnapshotForLiveTodoSynthesis() {
    val resolution = support.resolve(
      task = promptTask(input = "Continue the runtime rollout."),
      todoSnapshot = listOf(
        AgentTodoEntry(
          content = "Earlier rollout slice",
          status = AgentTodoStatus.COMPLETED,
        ),
      ),
    )

    assertTrue(resolution.trace.included)
    assertTrue(resolution.trace.synthesizedFromTaskInput)
    assertFalse(resolution.trace.synthesizedFromRecentObservations)
    assertFalse(resolution.trace.synthesizedFromTodoSnapshot)
    assertEquals("Continue the runtime rollout.", resolution.state.objective?.primaryGoal)
    assertEquals(null, resolution.state.objective?.currentSubgoal)
    assertEquals("active", resolution.state.objective?.status)
    assertTrue(resolution.state.nextActions.isEmpty())
  }

  private fun promptTask(
    input: String = "Summarize the repo changes.",
  ): AgentTask = AgentTask(
    id = "task-working-state",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 100L,
  )
}
