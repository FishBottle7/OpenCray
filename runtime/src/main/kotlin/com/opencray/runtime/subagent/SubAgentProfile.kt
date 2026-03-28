package com.opencray.runtime.subagent

import java.util.Locale

data class SubAgentProfile(
  val id: String,
  val description: String,
  val defaultContextMode: SubAgentContextMode,
  val allowedToolNames: Set<String>,
  val allowsNestedTask: Boolean = false,
  val maxTurns: Int = DEFAULT_MAX_TURNS,
) {
  init {
    require(id.isNotBlank()) { "SubAgentProfile id must not be blank." }
    require(description.isNotBlank()) { "SubAgentProfile description must not be blank." }
    require(allowedToolNames.isNotEmpty()) { "SubAgentProfile allowedToolNames must not be empty." }
    require(maxTurns >= 1) { "SubAgentProfile maxTurns must be >= 1." }
  }

  companion object {
    const val DEFAULT_MAX_TURNS: Int = 8
  }
}

object BuiltInSubAgentProfiles {
  private val readOnlyWorkspaceTools: Set<String> = linkedSetOf(
    "LS",
    "Read",
    "Grep",
    "Glob",
  )

  private val writeCapableWorkspaceTools: Set<String> = linkedSetOf(
    "LS",
    "Read",
    "Grep",
    "Glob",
    "Write",
    "Edit",
    "MultiEdit",
  )

  val researcher: SubAgentProfile = SubAgentProfile(
    id = "researcher",
    description = "Read-only workspace investigator for focused exploration tasks.",
    defaultContextMode = SubAgentContextMode.MINIMAL,
    allowedToolNames = readOnlyWorkspaceTools,
  )

  val generalPurpose: SubAgentProfile = SubAgentProfile(
    id = "general-purpose",
    description = "Read-only delegated worker for bounded analysis and follow-up.",
    defaultContextMode = SubAgentContextMode.DELEGATED,
    allowedToolNames = readOnlyWorkspaceTools,
  )

  val reviewer: SubAgentProfile = SubAgentProfile(
    id = "reviewer",
    description = "Read-only reviewer for audit, comparison, and code review tasks.",
    defaultContextMode = SubAgentContextMode.DELEGATED,
    allowedToolNames = readOnlyWorkspaceTools,
  )

  val worker: SubAgentProfile = SubAgentProfile(
    id = "worker",
    description = "Bounded delegated worker for focused workspace edits and follow-up analysis.",
    defaultContextMode = SubAgentContextMode.DELEGATED,
    allowedToolNames = writeCapableWorkspaceTools,
  )

  private val profiles: List<SubAgentProfile> = listOf(
    generalPurpose,
    researcher,
    reviewer,
    worker,
  )

  private val profilesById: Map<String, SubAgentProfile> = profiles.associateBy { profile ->
    profile.id.lowercase(Locale.US)
  }

  private val aliasesById: Map<String, String> = mapOf(
    "default" to generalPurpose.id,
    generalPurpose.id to generalPurpose.id,
    "explorer" to researcher.id,
    researcher.id to researcher.id,
    reviewer.id to reviewer.id,
    worker.id to worker.id,
  )

  fun all(): List<SubAgentProfile> = profiles

  fun normalizedRequestedId(id: String?): String? = id
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf { normalized -> aliasesById.containsKey(normalized) }

  fun resolve(id: String?): SubAgentProfile? {
    val normalized = normalizedRequestedId(id)
      ?: return null
    val canonicalId = aliasesById[normalized] ?: return null
    return profilesById[canonicalId.lowercase(Locale.US)]
  }
}
