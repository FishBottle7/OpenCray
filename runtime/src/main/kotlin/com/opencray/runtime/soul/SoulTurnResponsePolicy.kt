package com.opencray.runtime.soul

enum class SoulTurnUserAffect {
  NEUTRAL,
  STRAINED,
  DISTRESSED,
  PLAYFUL,
  WARM,
}

data class SoulTurnSemanticSignal(
  val isTaskBearingRequest: Boolean = false,
  val userAffect: SoulTurnUserAffect = SoulTurnUserAffect.NEUTRAL,
  val userInvitesPlayfulness: Boolean = false,
  val userRequestsRelationalSupport: Boolean = false,
  val clarificationNeeded: Boolean = false,
)

enum class SoulTurnTaskPriority {
  TASK_FIRST,
  BALANCED,
  RELATIONAL_OPEN,
}

enum class SoulTurnResponseShape {
  DIRECT_REPLY,
  ANSWER_FIRST,
  SHORT_SUPPORT_THEN_ANSWER,
  SUPPORTIVE_REPLY,
  CASUAL_REPLY,
}

enum class SoulTurnClarificationMode {
  NONE,
  MINIMUM_NEEDED,
  ONE_NECESSARY,
  PROACTIVE_TASK_FOCUSED,
}

enum class SoulTurnReassuranceMode {
  NONE,
  BRIEF_GROUNDED,
  SUPPORTIVE,
  WITHHOLD_EXPLICIT,
}

enum class SoulTurnRelationalCheckInMode {
  DISALLOWED,
  SECONDARY_ONLY,
  BRIEF_IF_RELEVANT,
}

enum class SoulTurnPlayfulnessMode {
  DISALLOWED,
  WITHHOLD_BY_DEFAULT,
  LIGHT_ONLY,
  LIGHT_TEASING_ALLOWED,
}

enum class SoulTurnIntimacyMode {
  RESTRICTED,
  CONTEXTUAL_ONLY,
}

data class SoulTurnResponsePolicy(
  val taskPriority: SoulTurnTaskPriority,
  val responseShape: SoulTurnResponseShape,
  val clarificationMode: SoulTurnClarificationMode,
  val reassuranceMode: SoulTurnReassuranceMode,
  val relationalCheckInMode: SoulTurnRelationalCheckInMode,
  val playfulnessMode: SoulTurnPlayfulnessMode,
  val intimacyMode: SoulTurnIntimacyMode,
  val directives: List<String>,
)

class SoulTurnResponsePolicyBuilder {
  fun build(
    profile: SoulProfile,
    signal: SoulTurnSemanticSignal,
  ): SoulTurnResponsePolicy {
    val taskPriority = taskPriorityFor(signal)
    val reassuranceMode = reassuranceModeFor(profile, signal)
    val relationalCheckInMode = relationalCheckInModeFor(profile, signal)
    val playfulnessMode = playfulnessModeFor(profile, signal)
    val clarificationMode = clarificationModeFor(profile, signal)
    val intimacyMode = if (profile.highIntimacyBehaviorAllowed == true) {
      SoulTurnIntimacyMode.CONTEXTUAL_ONLY
    } else {
      SoulTurnIntimacyMode.RESTRICTED
    }
    val responseShape = responseShapeFor(
      profile = profile,
      signal = signal,
      reassuranceMode = reassuranceMode,
      playfulnessMode = playfulnessMode,
    )
    val directives = directivesFor(
      profile = profile,
      taskPriority = taskPriority,
      reassuranceMode = reassuranceMode,
      relationalCheckInMode = relationalCheckInMode,
      playfulnessMode = playfulnessMode,
      intimacyMode = intimacyMode,
      clarificationMode = clarificationMode,
      responseShape = responseShape,
    )
    return SoulTurnResponsePolicy(
      taskPriority = taskPriority,
      responseShape = responseShape,
      clarificationMode = clarificationMode,
      reassuranceMode = reassuranceMode,
      relationalCheckInMode = relationalCheckInMode,
      playfulnessMode = playfulnessMode,
      intimacyMode = intimacyMode,
      directives = directives,
    )
  }

  private fun taskPriorityFor(signal: SoulTurnSemanticSignal): SoulTurnTaskPriority = when {
    signal.isTaskBearingRequest -> SoulTurnTaskPriority.TASK_FIRST
    signal.userRequestsRelationalSupport ||
      signal.userAffect == SoulTurnUserAffect.DISTRESSED ||
      signal.userAffect == SoulTurnUserAffect.WARM -> SoulTurnTaskPriority.BALANCED
    signal.userAffect == SoulTurnUserAffect.PLAYFUL -> SoulTurnTaskPriority.RELATIONAL_OPEN
    else -> SoulTurnTaskPriority.BALANCED
  }

  private fun reassuranceModeFor(
    profile: SoulProfile,
    signal: SoulTurnSemanticSignal,
  ): SoulTurnReassuranceMode {
    val reassuranceAllowed = profile.supportiveReassuranceAllowed == true
    val reassurancePreferenceOffset = profile.reassurancePreferenceOffset ?: 0
    val userNeedsSupport = signal.userAffect == SoulTurnUserAffect.STRAINED ||
      signal.userAffect == SoulTurnUserAffect.DISTRESSED ||
      signal.userRequestsRelationalSupport
    if (!userNeedsSupport) {
      return SoulTurnReassuranceMode.NONE
    }
    if (!reassuranceAllowed) {
      return SoulTurnReassuranceMode.WITHHOLD_EXPLICIT
    }
    return if (signal.isTaskBearingRequest || reassurancePreferenceOffset <= 0) {
      SoulTurnReassuranceMode.BRIEF_GROUNDED
    } else {
      SoulTurnReassuranceMode.SUPPORTIVE
    }
  }

  private fun relationalCheckInModeFor(
    profile: SoulProfile,
    signal: SoulTurnSemanticSignal,
  ): SoulTurnRelationalCheckInMode {
    if (profile.proactiveRelationalCheckInAllowed != true) {
      return SoulTurnRelationalCheckInMode.DISALLOWED
    }
    return if (signal.isTaskBearingRequest) {
      SoulTurnRelationalCheckInMode.SECONDARY_ONLY
    } else {
      SoulTurnRelationalCheckInMode.BRIEF_IF_RELEVANT
    }
  }

  private fun playfulnessModeFor(
    profile: SoulProfile,
    signal: SoulTurnSemanticSignal,
  ): SoulTurnPlayfulnessMode {
    if (!signal.userInvitesPlayfulness) {
      return if (profile.lightPlayfulnessAllowed == true) {
        SoulTurnPlayfulnessMode.WITHHOLD_BY_DEFAULT
      } else {
        SoulTurnPlayfulnessMode.DISALLOWED
      }
    }
    if (profile.lightPlayfulnessAllowed != true) {
      return SoulTurnPlayfulnessMode.DISALLOWED
    }
    return if (profile.playfulTeasingAllowed == true && signal.userAffect == SoulTurnUserAffect.PLAYFUL) {
      SoulTurnPlayfulnessMode.LIGHT_TEASING_ALLOWED
    } else {
      SoulTurnPlayfulnessMode.LIGHT_ONLY
    }
  }

  private fun clarificationModeFor(
    profile: SoulProfile,
    signal: SoulTurnSemanticSignal,
  ): SoulTurnClarificationMode {
    if (!signal.clarificationNeeded) {
      return SoulTurnClarificationMode.NONE
    }
    val initiativePreferenceOffset = profile.initiativePreferenceOffset ?: 0
    return when {
      initiativePreferenceOffset >= 1 -> SoulTurnClarificationMode.PROACTIVE_TASK_FOCUSED
      initiativePreferenceOffset <= -1 -> SoulTurnClarificationMode.MINIMUM_NEEDED
      else -> SoulTurnClarificationMode.ONE_NECESSARY
    }
  }

  private fun responseShapeFor(
    profile: SoulProfile,
    signal: SoulTurnSemanticSignal,
    reassuranceMode: SoulTurnReassuranceMode,
    playfulnessMode: SoulTurnPlayfulnessMode,
  ): SoulTurnResponseShape = when {
    signal.isTaskBearingRequest && reassuranceMode == SoulTurnReassuranceMode.BRIEF_GROUNDED ->
      SoulTurnResponseShape.SHORT_SUPPORT_THEN_ANSWER
    signal.isTaskBearingRequest ->
      SoulTurnResponseShape.ANSWER_FIRST
    reassuranceMode == SoulTurnReassuranceMode.SUPPORTIVE ->
      SoulTurnResponseShape.SUPPORTIVE_REPLY
    playfulnessMode == SoulTurnPlayfulnessMode.LIGHT_ONLY ||
      playfulnessMode == SoulTurnPlayfulnessMode.LIGHT_TEASING_ALLOWED ->
      SoulTurnResponseShape.CASUAL_REPLY
    profile.supportiveReassuranceAllowed == false && signal.userRequestsRelationalSupport ->
      SoulTurnResponseShape.DIRECT_REPLY
    else -> SoulTurnResponseShape.DIRECT_REPLY
  }

  private fun directivesFor(
    profile: SoulProfile,
    taskPriority: SoulTurnTaskPriority,
    reassuranceMode: SoulTurnReassuranceMode,
    relationalCheckInMode: SoulTurnRelationalCheckInMode,
    playfulnessMode: SoulTurnPlayfulnessMode,
    intimacyMode: SoulTurnIntimacyMode,
    clarificationMode: SoulTurnClarificationMode,
    responseShape: SoulTurnResponseShape,
  ): List<String> {
    val directives = linkedSetOf<String>()
    when (taskPriority) {
      SoulTurnTaskPriority.TASK_FIRST ->
        directives += "Lead with useful task progress before optional relational add-ons."
      SoulTurnTaskPriority.BALANCED ->
        directives += "Balance relational tone with clear utility; do not let either side crowd out the other."
      SoulTurnTaskPriority.RELATIONAL_OPEN ->
        directives += "A more socially open reply is acceptable, but it should still stay coherent and bounded."
    }
    when (responseShape) {
      SoulTurnResponseShape.ANSWER_FIRST ->
        directives += "Answer or act first, then add optional tone-softening only if space remains."
      SoulTurnResponseShape.SHORT_SUPPORT_THEN_ANSWER ->
        directives += "If you acknowledge emotion, keep it to one brief grounded line before moving into the answer."
      SoulTurnResponseShape.SUPPORTIVE_REPLY ->
        directives += "A supportive reply is acceptable, but keep it concrete and tied to the user's actual situation."
      SoulTurnResponseShape.CASUAL_REPLY ->
        directives += "A casual reply is acceptable, but do not let tone replace substance."
      SoulTurnResponseShape.DIRECT_REPLY ->
        directives += "Keep the reply direct and bounded."
    }
    when (clarificationMode) {
      SoulTurnClarificationMode.PROACTIVE_TASK_FOCUSED ->
        directives += "If clarification is needed, ask task-focused questions that move the work forward."
      SoulTurnClarificationMode.MINIMUM_NEEDED ->
        directives += "If clarification is unavoidable, ask only the minimum needed question."
      SoulTurnClarificationMode.ONE_NECESSARY ->
        directives += "If clarification is needed, prefer one concise question over a long discovery thread."
      SoulTurnClarificationMode.NONE -> Unit
    }
    when (reassuranceMode) {
      SoulTurnReassuranceMode.BRIEF_GROUNDED ->
        directives += "Reassurance may be brief and grounded; pair it with concrete help."
      SoulTurnReassuranceMode.SUPPORTIVE ->
        directives += "Supportive reassurance is acceptable, but it should still stay anchored to concrete help or understanding."
      SoulTurnReassuranceMode.WITHHOLD_EXPLICIT ->
        directives += "Do not use overt soothing language in this turn; convey steadiness through calm competence instead."
      SoulTurnReassuranceMode.NONE -> Unit
    }
    when (relationalCheckInMode) {
      SoulTurnRelationalCheckInMode.SECONDARY_ONLY ->
        directives += "Do not open a separate relational check-in before addressing the task."
      SoulTurnRelationalCheckInMode.BRIEF_IF_RELEVANT ->
        directives += "A brief relational check-in is allowed only if it clearly fits the live context."
      SoulTurnRelationalCheckInMode.DISALLOWED ->
        directives += "Do not introduce relationship-oriented follow-up on your own."
    }
    when (playfulnessMode) {
      SoulTurnPlayfulnessMode.LIGHT_ONLY ->
        directives += "If you use playfulness, keep it light and non-disruptive."
      SoulTurnPlayfulnessMode.LIGHT_TEASING_ALLOWED ->
        directives += "Very mild teasing is acceptable only because the user is actively inviting playful tone."
      SoulTurnPlayfulnessMode.WITHHOLD_BY_DEFAULT ->
        directives += "Do not add playfulness unless the user's live tone clearly invites it."
      SoulTurnPlayfulnessMode.DISALLOWED ->
        directives += "Keep the tone straightforward rather than playful in this turn."
    }
    if (intimacyMode == SoulTurnIntimacyMode.RESTRICTED) {
      directives += "Do not intensify closeness framing, intimacy, or dependency-coded language in this turn."
      if ((profile.warmthPreferenceOffset ?: 0) > 0) {
        directives += "If you express warmth, do it through respectful helpfulness rather than special-relationship framing."
      }
    } else {
      directives += "Even when closeness is permitted, keep intimacy contextual, reciprocal, and bounded."
    }
    if ((profile.playfulnessPreferenceOffset ?: 0) > 0 && playfulnessMode == SoulTurnPlayfulnessMode.DISALLOWED) {
      directives += "A playfulness preference exists, but the active relationship gate keeps it closed in this turn."
    }
    if ((profile.reassurancePreferenceOffset ?: 0) > 0 && reassuranceMode == SoulTurnReassuranceMode.WITHHOLD_EXPLICIT) {
      directives += "A reassurance preference exists, but the active relationship gate keeps explicit soothing closed in this turn."
    }
    if ((profile.initiativePreferenceOffset ?: 0) > 0 &&
      relationalCheckInMode == SoulTurnRelationalCheckInMode.DISALLOWED
    ) {
      directives += "Use added initiative for task progress, not for relationship-oriented follow-up."
    }
    return directives.toList()
  }
}
