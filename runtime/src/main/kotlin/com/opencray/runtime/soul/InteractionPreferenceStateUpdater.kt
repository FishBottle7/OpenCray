package com.opencray.runtime.soul

class InteractionPreferenceStateUpdater {
  fun apply(
    state: InteractionPreferenceState,
    signal: InteractionPreferenceSignal,
    plasticity: SoulPlasticity,
  ): InteractionPreferenceState {
    var updatedState = state
    repeat(signal.supportWeight) {
      updatedState = applySingle(
        state = updatedState,
        signal = signal.copy(supportWeight = 1),
        plasticity = plasticity,
      )
    }
    return updatedState
  }

  private fun applySingle(
    state: InteractionPreferenceState,
    signal: InteractionPreferenceSignal,
    plasticity: SoulPlasticity,
  ): InteractionPreferenceState {
    val policy = plasticity.preferencePolicy()
    var updatedState = state

    if (signal.axis != null && signal.direction != null) {
      val updatedAxisState = updateAxis(
        state = updatedState.axisStateFor(signal.axis),
        direction = signal.direction,
        supportWeight = signal.supportWeight,
        occurredAtEpochMs = signal.occurredAtEpochMs,
        policy = policy,
      )
      updatedState = updatedState.withAxisState(signal.axis, updatedAxisState)
    }
    if (signal.preferredAddressStyle != null) {
      updatedState = updatedState.copy(
        addressStyle = updateAddressStyle(
          state = updatedState.addressStyle,
          style = signal.preferredAddressStyle,
          supportWeight = signal.supportWeight,
          occurredAtEpochMs = signal.occurredAtEpochMs,
          policy = policy,
        ),
      )
    }
    normalizePreferredNamingOrNull(signal.preferredNaming)?.let { naming ->
      updatedState = updatedState.copy(
        preferredNaming = naming,
        preferredNamingSupport = when {
          updatedState.preferredNaming == naming -> {
            (updatedState.preferredNamingSupport + signal.supportWeight)
              .coerceAtMost(PreferredAddressState.MAX_SUPPORT)
          }

          else -> signal.supportWeight.coerceAtMost(PreferredAddressState.MAX_SUPPORT)
        },
      )
    }
    return updatedState.copy(
      lastUpdatedAtEpochMs = maxOfTimestamp(updatedState.lastUpdatedAtEpochMs, signal.occurredAtEpochMs),
    )
  }

  private fun updateAxis(
    state: PreferenceAxisState,
    direction: InteractionPreferenceDirection,
    supportWeight: Int,
    occurredAtEpochMs: Long,
    policy: InteractionPreferenceUpdatePolicy,
  ): PreferenceAxisState {
    val higherSupport = when (direction) {
      InteractionPreferenceDirection.HIGHER ->
        (state.higherSupport + supportWeight).coerceAtMost(PreferenceAxisState.MAX_SUPPORT)

      InteractionPreferenceDirection.LOWER -> state.higherSupport
    }
    val lowerSupport = when (direction) {
      InteractionPreferenceDirection.HIGHER -> state.lowerSupport
      InteractionPreferenceDirection.LOWER ->
        (state.lowerSupport + supportWeight).coerceAtMost(PreferenceAxisState.MAX_SUPPORT)
    }
    val lead = higherSupport - lowerSupport
    val targetOffset = when {
      lead >= policy.durableSupportThreshold * 2 && policy.maxOffset >= 2 -> 2
      lead >= policy.durableSupportThreshold -> 1
      lead <= -(policy.durableSupportThreshold * 2) && policy.maxOffset >= 2 -> -2
      lead <= -policy.durableSupportThreshold -> -1
      else -> 0
    }.coerceIn(-policy.maxOffset, policy.maxOffset)

    return state.copy(
      offset = moveToward(current = state.offset, target = targetOffset),
      higherSupport = higherSupport,
      lowerSupport = lowerSupport,
      lastUpdatedAtEpochMs = maxOfTimestamp(state.lastUpdatedAtEpochMs, occurredAtEpochMs),
    )
  }

  private fun updateAddressStyle(
    state: PreferredAddressState,
    style: PreferredAddressStyle,
    supportWeight: Int,
    occurredAtEpochMs: Long,
    policy: InteractionPreferenceUpdatePolicy,
  ): PreferredAddressState {
    val updatedState = when (style) {
      PreferredAddressStyle.NEUTRAL -> state.copy(
        neutralSupport = (state.neutralSupport + supportWeight).coerceAtMost(PreferredAddressState.MAX_SUPPORT),
        lastUpdatedAtEpochMs = maxOfTimestamp(state.lastUpdatedAtEpochMs, occurredAtEpochMs),
      )

      PreferredAddressStyle.FRIENDLY -> state.copy(
        friendlySupport = (state.friendlySupport + supportWeight).coerceAtMost(PreferredAddressState.MAX_SUPPORT),
        lastUpdatedAtEpochMs = maxOfTimestamp(state.lastUpdatedAtEpochMs, occurredAtEpochMs),
      )

      PreferredAddressStyle.INTIMATE -> state.copy(
        intimateSupport = (state.intimateSupport + supportWeight).coerceAtMost(PreferredAddressState.MAX_SUPPORT),
        lastUpdatedAtEpochMs = maxOfTimestamp(state.lastUpdatedAtEpochMs, occurredAtEpochMs),
      )
    }
    val rankedStyles = PreferredAddressStyle.values().toList().sortedWith(
      compareByDescending<PreferredAddressStyle> { preferredStyle ->
        updatedState.supportFor(preferredStyle)
      }.thenByDescending { preferredStyle ->
        if (preferredStyle == updatedState.selectedStyle) 1 else 0
      },
    )
    val winner = rankedStyles.first()
    val winnerSupport = updatedState.supportFor(winner)
    val runnerUpSupport = rankedStyles.getOrNull(1)?.let { preferredStyle ->
      updatedState.supportFor(preferredStyle)
    } ?: 0
    val selectedStyle = when {
      winner == updatedState.selectedStyle -> updatedState.selectedStyle
      winner == PreferredAddressStyle.NEUTRAL && winnerSupport == 0 -> updatedState.selectedStyle
      winnerSupport >= policy.durableSupportThreshold && winnerSupport > runnerUpSupport -> winner
      else -> updatedState.selectedStyle
    }

    return updatedState.copy(selectedStyle = selectedStyle)
  }

  private fun InteractionPreferenceState.axisStateFor(axis: InteractionPreferenceAxis): PreferenceAxisState = when (axis) {
    InteractionPreferenceAxis.WARMTH -> warmth
    InteractionPreferenceAxis.FORMALITY -> formality
    InteractionPreferenceAxis.INITIATIVE -> initiative
  }

  private fun InteractionPreferenceState.withAxisState(
    axis: InteractionPreferenceAxis,
    state: PreferenceAxisState,
  ): InteractionPreferenceState = when (axis) {
    InteractionPreferenceAxis.WARMTH -> copy(warmth = state)
    InteractionPreferenceAxis.FORMALITY -> copy(formality = state)
    InteractionPreferenceAxis.INITIATIVE -> copy(initiative = state)
  }

  private fun PreferredAddressState.supportFor(style: PreferredAddressStyle): Int = when (style) {
    PreferredAddressStyle.NEUTRAL -> neutralSupport
    PreferredAddressStyle.FRIENDLY -> friendlySupport
    PreferredAddressStyle.INTIMATE -> intimateSupport
  }

  private fun normalizePreferredNamingOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  private fun moveToward(current: Int, target: Int): Int = when {
    current < target -> current + 1
    current > target -> current - 1
    else -> current
  }

  private fun maxOfTimestamp(current: Long?, candidate: Long): Long = maxOf(current ?: candidate, candidate)

  private data class InteractionPreferenceUpdatePolicy(
    val durableSupportThreshold: Int,
    val maxOffset: Int,
  )

  private fun SoulPlasticity.preferencePolicy(): InteractionPreferenceUpdatePolicy = when (this) {
    SoulPlasticity.LOW -> InteractionPreferenceUpdatePolicy(
      durableSupportThreshold = 3,
      maxOffset = 1,
    )

    SoulPlasticity.MEDIUM -> InteractionPreferenceUpdatePolicy(
      durableSupportThreshold = 2,
      maxOffset = 2,
    )

    SoulPlasticity.HIGH -> InteractionPreferenceUpdatePolicy(
      durableSupportThreshold = 1,
      maxOffset = 2,
    )
  }
}
