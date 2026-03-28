package com.opencray.runtime.subagent

data class SubAgentTask(
  val description: String,
  val prompt: String,
  val subagentType: String,
  val contextMode: SubAgentContextMode,
  val parentRunId: String,
  val parentTaskId: String,
  val parentTurn: Int,
  val depth: Int = 1,
  val activeSkillName: String? = null,
  val activeSkillActivationSource: String? = null,
) {
  init {
    require(description.isNotBlank()) { "SubAgentTask description must not be blank." }
    require(prompt.isNotBlank()) { "SubAgentTask prompt must not be blank." }
    require(subagentType.isNotBlank()) { "SubAgentTask subagentType must not be blank." }
    require(parentRunId.isNotBlank()) { "SubAgentTask parentRunId must not be blank." }
    require(parentTaskId.isNotBlank()) { "SubAgentTask parentTaskId must not be blank." }
    require(parentTurn >= 0) { "SubAgentTask parentTurn must be >= 0." }
    require(depth >= 1) { "SubAgentTask depth must be >= 1." }
  }

  fun metadata(): Map<String, String> = buildMap {
    put(SubAgentMetadataKeys.SUBAGENT_TYPE, subagentType)
    put(SubAgentMetadataKeys.CONTEXT_MODE, contextMode.wireValue)
    put(SubAgentMetadataKeys.PARENT_RUN_ID, parentRunId)
    put(SubAgentMetadataKeys.PARENT_TASK_ID, parentTaskId)
    put(SubAgentMetadataKeys.PARENT_TURN, parentTurn.toString())
    put(SubAgentMetadataKeys.DEPTH, depth.toString())
    activeSkillName
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { skillName ->
        put(SubAgentMetadataKeys.ACTIVE_SKILL_NAME, skillName)
      }
    activeSkillActivationSource
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { activationSource ->
        put(SubAgentMetadataKeys.ACTIVE_SKILL_ACTIVATION_SOURCE, activationSource)
      }
  }
}

object SubAgentMetadataKeys {
  const val SUBAGENT_TYPE: String = "subagentType"
  const val CONTEXT_MODE: String = "subagentContextMode"
  const val PARENT_RUN_ID: String = "parentRunId"
  const val PARENT_TASK_ID: String = "parentTaskId"
  const val PARENT_TURN: String = "parentTurn"
  const val DEPTH: String = "subagentDepth"
  const val ACTIVE_SKILL_NAME: String = "activeSkillName"
  const val ACTIVE_SKILL_ACTIVATION_SOURCE: String = "activeSkillActivationSource"
}
