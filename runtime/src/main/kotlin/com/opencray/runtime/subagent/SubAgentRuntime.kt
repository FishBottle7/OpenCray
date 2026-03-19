package com.opencray.runtime.subagent

import com.opencray.core.contracts.AgentTask
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.AgentToolResult

data class SubAgentExecutionRequest(
  val parentTask: AgentTask,
  val childTask: SubAgentTask,
  val profile: SubAgentProfile,
)

data class SubAgentExecutionResult(
  val childRunId: String,
  val toolResult: AgentToolResult,
  val childTurnCount: Int = 0,
  val childToolCallCount: Int = 0,
)

fun interface SubAgentExecutor {
  fun execute(
    request: SubAgentExecutionRequest,
    hooks: RuntimeExecutionHooks,
  ): SubAgentExecutionResult
}
