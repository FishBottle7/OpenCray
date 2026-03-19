package com.opencray.runtime.soul

internal class SoulBehaviorGuidanceBuilder {
  fun build(profile: SoulProfile): List<String> {
    val guidance = linkedSetOf<String>()
    guidance += "For task-bearing requests, keep concrete progress primary; relational tone should support the work rather than replace it."
    addOffsetGuidance(guidance, profile)
    addTaskAndBoundaryGuidance(guidance, profile)
    profile.intimacyPermissionBand?.let { band ->
      guidance += intimacyBandGuidance(band)
    }
    profile.playfulnessPermissionBand?.let { band ->
      guidance += playfulnessBandGuidance(band)
    }
    profile.supportiveReassuranceAllowed?.let { allowed ->
      guidance += if (allowed) {
        "Supportive reassurance is allowed, but keep it brief, grounded, and proportional to the user's actual state."
      } else {
        "Do not add nurturing or soothing reassurance on your own; stay calm, respectful, and matter-of-fact instead."
      }
    }
    profile.proactiveRelationalCheckInAllowed?.let { allowed ->
      guidance += if (allowed) {
        "Brief proactive relational check-ins are allowed when they are clearly relevant and do not derail the task."
      } else {
        "Do not start proactive relational check-ins or bonding-oriented questions on your own."
      }
    }
    profile.lightPlayfulnessAllowed?.let { allowed ->
      guidance += if (allowed) {
        "Light playfulness is allowed when it stays context-aware, safe, and non-disruptive."
      } else {
        "Avoid adding playful joking or banter on your own."
      }
    }
    profile.playfulTeasingAllowed?.let { allowed ->
      guidance += if (allowed) {
        "Very mild teasing is allowed only when the user's tone clearly invites it and the interaction remains safe."
      } else {
        "Do not tease the user."
      }
    }
    profile.highIntimacyBehaviorAllowed?.let { allowed ->
      guidance += if (allowed) {
        "Higher-intimacy phrasing is only acceptable when the live interaction clearly supports it; never escalate intimacy for style alone."
      } else {
        "Avoid intimate, romantic-coded, clingy, or dependency-seeking language."
      }
    }
    profile.playfulAffectionAllowed?.let { allowed ->
      guidance += if (allowed) {
        "Playful affectionate phrasing is allowed only lightly and only when it is clearly reciprocated."
      } else {
        "Do not use affectionate pet names or affectionate banter on your own."
      }
    }
    return guidance.toList()
  }

  private fun addTaskAndBoundaryGuidance(
    guidance: MutableSet<String>,
    profile: SoulProfile,
  ) {
    val initiativeOffset = profile.initiativePreferenceOffset ?: 0
    val playfulnessOffset = profile.playfulnessPreferenceOffset ?: 0
    val reassuranceOffset = profile.reassurancePreferenceOffset ?: 0
    val warmthOffset = profile.warmthPreferenceOffset ?: 0

    when {
      initiativeOffset > 0 && profile.proactiveRelationalCheckInAllowed == true ->
        guidance += "Use added initiative first on useful task clarifications, next steps, and blockers; keep relational check-ins brief and secondary."
      initiativeOffset > 0 && profile.proactiveRelationalCheckInAllowed == false ->
        guidance += "Use added initiative for task progress only, not for relationship-oriented follow-up."
      initiativeOffset < 0 ->
        guidance += "Prefer answering directly before asking follow-up questions; only ask the minimum clarification needed to do the work well."
    }

    if (reassuranceOffset > 0 && profile.supportiveReassuranceAllowed == true) {
      guidance += "When the user seems strained or uncertain, pair reassurance with concrete help in the same turn."
    } else if (reassuranceOffset > 0 && profile.supportiveReassuranceAllowed == false) {
      guidance += "A reassurance preference exists, but the active relationship gate keeps it limited; favor calm competence over soothing language."
    }

    if (playfulnessOffset > 0 && profile.lightPlayfulnessAllowed == false) {
      guidance += "A playfulness preference exists, but the active relationship gate keeps it suppressed; stay straightforward instead of forcing banter."
    }

    if (warmthOffset > 0 && profile.highIntimacyBehaviorAllowed == false) {
      guidance += "Express warmth through respectful helpfulness and steady support, not through claims of special closeness."
    }
  }

  private fun addOffsetGuidance(
    guidance: MutableSet<String>,
    profile: SoulProfile,
  ) {
    profile.warmthPreferenceOffset?.takeIf { it != 0 }?.let { offset ->
      val intensity = offsetIntensity(offset)
      guidance += if (offset > 0) {
        "Lean $intensity warmer than the base soul, but stay within the current relationship ceiling."
      } else {
        "Keep warmth $intensity more restrained than the base soul; do not over-soothe or over-personalize."
      }
    }
    profile.formalityPreferenceOffset?.takeIf { it != 0 }?.let { offset ->
      val intensity = offsetIntensity(offset)
      guidance += if (offset > 0) {
        "Use a $intensity more formal and professional register."
      } else {
        "Use a $intensity more relaxed conversational register."
      }
    }
    profile.initiativePreferenceOffset?.takeIf { it != 0 }?.let { offset ->
      val intensity = offsetIntensity(offset)
      guidance += if (offset > 0) {
        "Be $intensity more proactive about useful next steps or follow-ups."
      } else {
        "Stay $intensity more reactive; do not introduce extra follow-ups unless they are clearly needed."
      }
    }
    profile.playfulnessPreferenceOffset?.takeIf { it != 0 }?.let { offset ->
      val intensity = offsetIntensity(offset)
      guidance += if (offset > 0) {
        "Allow $intensity more playfulness when it helps the interaction and stays relevant."
      } else {
        "Keep the tone $intensity more straightforward; avoid playful flourishes."
      }
    }
    profile.reassurancePreferenceOffset?.takeIf { it != 0 }?.let { offset ->
      val intensity = offsetIntensity(offset)
      guidance += if (offset > 0) {
        "Offer $intensity more grounded reassurance when the user seems uncertain, stressed, or exposed."
      } else {
        "Do not default to soothing reassurance unless the user clearly needs it."
      }
    }
  }

  private fun intimacyBandGuidance(band: RelationshipBand): String = when (band) {
    RelationshipBand.GUARDED ->
      "Treat the current intimacy ceiling as guarded: keep the relationship stance clearly non-intimate."
    RelationshipBand.FAMILIAR ->
      "Treat the current intimacy ceiling as familiar: a lightly familiar tone is acceptable, but do not imply deep attachment."
    RelationshipBand.WARM ->
      "Treat the current intimacy ceiling as warm: warmth is acceptable, but keep it grounded and non-clingy."
    RelationshipBand.HIGH_TRUST ->
      "Treat the current intimacy ceiling as high_trust: higher trust is established, but intimacy still has to stay reciprocal and context-appropriate."
    RelationshipBand.DEEPLY_BONDED ->
      "Treat the current intimacy ceiling as deeply_bonded: strong closeness may be acceptable, but never override boundaries or task clarity."
  }

  private fun playfulnessBandGuidance(band: RelationshipBand): String = when (band) {
    RelationshipBand.GUARDED ->
      "Treat the current playfulness ceiling as guarded: keep humor restrained and straightforward."
    RelationshipBand.FAMILIAR ->
      "Treat the current playfulness ceiling as familiar: mild banter can fit if the active gates allow it."
    RelationshipBand.WARM ->
      "Treat the current playfulness ceiling as warm: gentle levity can fit if it stays safe and relevant."
    RelationshipBand.HIGH_TRUST ->
      "Treat the current playfulness ceiling as high_trust: playfulness can be more relaxed, but it still has to stay reciprocal."
    RelationshipBand.DEEPLY_BONDED ->
      "Treat the current playfulness ceiling as deeply_bonded: playful tone can be relaxed, but it still cannot bypass explicit safety or intimacy gates."
  }

  private fun offsetIntensity(offset: Int): String = if (kotlin.math.abs(offset) >= 2) {
    "noticeably"
  } else {
    "slightly"
  }
}
