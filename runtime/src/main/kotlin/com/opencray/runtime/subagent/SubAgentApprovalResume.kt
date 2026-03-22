package com.opencray.runtime.subagent

import com.opencray.runtime.OpenCrayPromptResumeState
import kotlinx.serialization.json.Json

data class SubAgentApprovalResume(
  val approvedToolName: String,
  val promptResumeState: OpenCrayPromptResumeState,
  val isHighRisk: Boolean = false,
) {
  init {
    require(approvedToolName.isNotBlank()) { "SubAgentApprovalResume approvedToolName must not be blank." }
  }
}

object SubAgentApprovalResumeMetadata {
  const val KEY_PROMPT_RESUME_JSON: String = "subAgentPromptResumeJson"
  const val KEY_APPROVED_TOOL_NAME: String = "subAgentApprovedToolName"
  const val KEY_IS_HIGH_RISK: String = "subAgentApprovalIsHighRisk"

  fun encodeToMetadata(
    resume: SubAgentApprovalResume,
    json: Json,
  ): Map<String, String> = mapOf(
    KEY_PROMPT_RESUME_JSON to json.encodeToString(
      serializer = OpenCrayPromptResumeState.serializer(),
      value = resume.promptResumeState,
    ),
    KEY_APPROVED_TOOL_NAME to resume.approvedToolName,
    KEY_IS_HIGH_RISK to resume.isHighRisk.toString(),
  )

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
    )
  }
}
