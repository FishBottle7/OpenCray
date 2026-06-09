package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.runtime.OpenCrayAgentRunEvent

internal fun planRunRecovery(
  run: AgentRunSnapshot,
  checkpoint: PersistedPromptCheckpoint?,
  lastJournalEvent: OpenCrayAgentRunEvent?,
  durableResult: ExecutionResult? = null,
  planner: RunRecoveryPlanner = RunRecoveryPlanner(),
  approvalStateOverride: AgentTaskApprovalState? = null,
): RunRecoveryPlan? = planner.plan(
  RunRecoveryPlannerInput(
    run = run,
    checkpoint = checkpoint,
    lastJournalEvent = lastJournalEvent,
    durableResult = durableResult,
    approvalState = approvalStateOverride ?: checkpointApprovalDecisionState(checkpoint),
  ),
)

internal fun loadStoredRunRecoveryPlan(
  run: AgentRunSnapshot,
  checkpointStore: PromptCheckpointStore,
  journalStore: RunEventJournalStore,
  planner: RunRecoveryPlanner = RunRecoveryPlanner(),
  approvalStateOverride: AgentTaskApprovalState? = null,
): RunRecoveryPlan? {
  val checkpoint = checkpointStore.get(run.taskId)
  val lastJournalEvent = run.lastEvent ?: journalStore
    .listForRun(run.runId)
    .latestRuntimeEventOrNull()
  return planRunRecovery(
    run = run,
    checkpoint = checkpoint,
    lastJournalEvent = lastJournalEvent,
    durableResult = null,
    planner = planner,
    approvalStateOverride = approvalStateOverride,
  )
}
