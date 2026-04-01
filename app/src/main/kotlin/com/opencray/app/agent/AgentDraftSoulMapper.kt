package com.opencray.app.agent

import com.opencray.app.WorkspaceSoulProfile
import com.opencray.runtime.soul.SoulProfileExtensionKeys

internal class AgentDraftSoulMapper {
  fun toSoulProfile(request: AgentCreateRequest): WorkspaceSoulProfile = WorkspaceSoulProfile(
    presetName = request.presetName.trim(),
    customLabel = request.displayName.trim(),
    customGuidance = normalizeBody(request.baseDescription),
    extensions = buildMap {
      putIfMeaningful(SoulProfileExtensionKeys.VOICE, request.voiceSummary)
      putIfMeaningful(SoulProfileExtensionKeys.PREFERRED_NAMING, request.callsYou)
      putIfMeaningful(SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE, request.addressStyle)
      putIfMeaningful(SoulProfileExtensionKeys.VERBOSITY, request.verbosity)
      putIfMeaningful(SoulProfileExtensionKeys.PLASTICITY, request.plasticity)
      putIfMeaningful(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE, request.relationshipStyle)
      putIfMeaningful(SoulProfileExtensionKeys.RISK_TOLERANCE, request.riskTolerance)
      putIfMeaningful(SoulProfileExtensionKeys.TOOL_USE_BIAS, request.toolUseBias)
      putIfMeaningful(SoulProfileExtensionKeys.COLLABORATION_PREFERENCES, request.collaborationGuidance)
      putIfMeaningful(SoulProfileExtensionKeys.ESCALATION_RULES, request.escalationRules)
      putIfMeaningful(SoulProfileExtensionKeys.FORBIDDEN_BEHAVIORS, request.forbiddenBehaviors)
    },
  )

  private fun MutableMap<String, String>.putIfMeaningful(
    key: String,
    value: String?,
  ) {
    val normalized = normalizeScalar(value) ?: return
    put(key, normalized)
  }

  private fun normalizeScalar(value: String?): String? =
    value
      ?.replace(Regex("\\r\\n?"), "\n")
      ?.split('\n')
      ?.map { line -> line.trim() }
      ?.filter(String::isNotEmpty)
      ?.joinToString(separator = "\n")
      ?.takeIf(String::isNotEmpty)

  private fun normalizeBody(value: String): String =
    value
      .replace(Regex("\\r\\n?"), "\n")
      .lines()
      .map { line -> line.trimEnd() }
      .joinToString(separator = "\n")
      .trim()
}
