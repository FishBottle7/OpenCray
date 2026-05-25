package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun submitSandboxSessionInfoRefreshTask(
  sessionId: String,
  runtimeHostAccess: RuntimeSessionDirectoryAccess,
  taskSafetyMetadata: Map<String, String>,
  lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) {
  val handle = runtimeHostAccess.session(sessionId)
  val now = nowEpochMsProvider()
  val task = AgentTask(
    id = "tool-$sessionId-${UUID.randomUUID().toString().take(8)}",
    type = AgentTaskType.TOOL_CALL,
    input = buildJsonObject {
      put("type", "tool_call")
      put("tool_name", SANDBOX_SESSION_INFO_TOOL_NAME)
      put("arguments", buildJsonObject {})
    }.toString(),
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = HOST_UI_TOOL_ACTION_ALLOW_REASON_CODE,
    ),
    createdAtEpochMs = now,
    metadata = taskSafetyMetadata +
      lifecycleDescriptor.taskMetadata(
        submissionSource = RunSubmissionSources.HOST_UI_TOOL_ACTION,
      ) +
      mapOf(
        RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME to SANDBOX_SESSION_INFO_TOOL_NAME,
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to
          "run-$sessionId-${UUID.randomUUID().toString().take(8)}",
        AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
      ),
  )
  handle.submitTask(task)
  handle.ensureProcessing()
}

private const val SANDBOX_SESSION_INFO_TOOL_NAME: String = "sandbox_session_info"
private const val HOST_UI_TOOL_ACTION_ALLOW_REASON_CODE: String = "HOST_UI_TOOL_ACTION_ALLOW"
