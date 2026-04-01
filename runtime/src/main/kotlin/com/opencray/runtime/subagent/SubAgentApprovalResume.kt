package com.opencray.runtime.subagent

import com.opencray.runtime.OpenCrayPromptResumeState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SubAgentApprovalResume(
  val approvedToolName: String,
  val promptResumeState: OpenCrayPromptResumeState,
  val isHighRisk: Boolean = false,
  val agentId: String? = null,
  val childRunId: String? = null,
  val childTaskId: String? = null,
) {
  init {
    require(approvedToolName.isNotBlank()) { "SubAgentApprovalResume approvedToolName must not be blank." }
    require(agentId == null || agentId.isNotBlank()) { "SubAgentApprovalResume agentId must not be blank." }
    require(childRunId == null || childRunId.isNotBlank()) { "SubAgentApprovalResume childRunId must not be blank." }
    require(childTaskId == null || childTaskId.isNotBlank()) { "SubAgentApprovalResume childTaskId must not be blank." }
  }

  val handleId: String?
    get() = agentId
}

object SubAgentApprovalResumeMetadata {
  const val KEY_PROMPT_RESUME_JSON: String = "subAgentPromptResumeJson"
  const val KEY_APPROVED_TOOL_NAME: String = "subAgentApprovedToolName"
  const val KEY_IS_HIGH_RISK: String = "subAgentApprovalIsHighRisk"
  const val KEY_HANDLE_ID: String = "subAgentApprovalHandleId"
  const val KEY_AGENT_ID: String = "subAgentApprovalAgentId"
  const val KEY_CHILD_RUN_ID: String = "subAgentApprovalChildRunId"
  const val KEY_CHILD_TASK_ID: String = "subAgentApprovalChildTaskId"

  fun encodeToMetadata(
    resume: SubAgentApprovalResume,
    json: Json,
  ): Map<String, String> = buildMap {
    put(
      KEY_PROMPT_RESUME_JSON,
      json.encodeToString(
        serializer = OpenCrayPromptResumeState.serializer(),
        value = resume.promptResumeState,
      ),
    )
    put(KEY_APPROVED_TOOL_NAME, resume.approvedToolName)
    put(KEY_IS_HIGH_RISK, resume.isHighRisk.toString())
    resume.handleId?.let {
      put(KEY_HANDLE_ID, it)
      put(KEY_AGENT_ID, it)
    }
    resume.childRunId?.let { put(KEY_CHILD_RUN_ID, it) }
    resume.childTaskId?.let { put(KEY_CHILD_TASK_ID, it) }
  }

  fun decodeFromMetadata(
    metadata: Map<String, String>,
    json: Json,
  ): SubAgentApprovalResume? {
    val approvedToolName = metadata[KEY_APPROVED_TOOL_NAME]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val encodedResume = metadata[KEY_PROMPT_RESUME_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val promptResumeState = runCatching {
      json.decodeFromString(
        deserializer = OpenCrayPromptResumeState.serializer(),
        string = encodedResume,
      )
    }.getOrNull() ?: return null
    return SubAgentApprovalResume(
      approvedToolName = approvedToolName,
      promptResumeState = promptResumeState,
      isHighRisk = metadata[KEY_IS_HIGH_RISK]
        ?.trim()
        ?.equals("true", ignoreCase = true) == true,
      agentId = metadata[KEY_HANDLE_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: metadata[KEY_AGENT_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank),
      childRunId = metadata[KEY_CHILD_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank),
      childTaskId = metadata[KEY_CHILD_TASK_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank),
    )
  }
}
