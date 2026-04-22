package com.opencray.app

import com.opencray.runtime.OpenCrayAgentRunEvent

internal fun planRunRecovery(
  run: AgentRunSnapshot,
  checkpoint: PersistedPromptCheckpoint?,
  lastJournalEvent: OpenCrayAgentRunEvent?,
  planner: RunRecoveryPlanner = RunRecoveryPlanner(),
  approvalStateOverride: AgentTaskApprovalState? = null,
): RunRecoveryPlan? = planner.plan(
  RunRecoveryPlannerInput(
    run = run,
    checkpoint = checkpoint,
    lastJournalEvent = lastJournalEvent,
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
    planner = planner,
    approvalStateOverride = approvalStateOverride,
  )
}
