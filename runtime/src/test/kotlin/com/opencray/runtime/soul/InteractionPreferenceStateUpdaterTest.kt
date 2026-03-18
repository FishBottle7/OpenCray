package com.opencray.runtime.soul

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionPreferenceStateUpdaterTest {
  private val updater = InteractionPreferenceStateUpdater()

  @Test
  fun lowPlasticityRequiresRepeatedWarmthSignalsAndCapsDurableDrift() {
    var state = InteractionPreferenceState()

    state = updater.apply(
      state = state,
      signal = warmthSignal(
        direction = InteractionPreferenceDirection.HIGHER,
        occurredAtEpochMs = 1_000L,
      ),
      plasticity = SoulPlasticity.LOW,
    )
    state = updater.apply(
      state = state,
      signal = warmthSignal(
        direction = InteractionPreferenceDirection.HIGHER,
        occurredAtEpochMs = 2_000L,
      ),
      plasticity = SoulPlasticity.LOW,
    )

    assertEquals(0, state.warmth.offset)
    assertEquals(2, state.warmth.higherSupport)

    state = updater.apply(
      state = state,
      signal = warmthSignal(
        direction = InteractionPreferenceDirection.HIGHER,
        occurredAtEpochMs = 3_000L,
      ),
      plasticity = SoulPlasticity.LOW,
    )

    assertEquals(1, state.warmth.offset)

    repeat(3) { index ->
      state = updater.apply(
        state = state,
        signal = warmthSignal(
          direction = InteractionPreferenceDirection.HIGHER,
          occurredAtEpochMs = 4_000L + index,
        ),
        plasticity = SoulPlasticity.LOW,
      )
    }

    assertEquals(1, state.warmth.offset)
    assertEquals(6, state.warmth.higherSupport)
  }

  @Test
  fun mediumPlasticityConflictingSignalsPullPreferenceThroughNeutralBeforeSwitching() {
    var state = InteractionPreferenceState()

    repeat(2) { index ->
      state = updater.apply(
        state = state,
        signal = warmthSignal(
          direction = InteractionPreferenceDirection.HIGHER,
          occurredAtEpochMs = 1_000L + index,
        ),
        plasticity = SoulPlasticity.MEDIUM,
      )
    }
    assertEquals(1, state.warmth.offset)

    repeat(4) { index ->
      state = updater.apply(
        state = state,
        signal = warmthSignal(
          direction = InteractionPreferenceDirection.LOWER,
          occurredAtEpochMs = 2_000L + index,
        ),
        plasticity = SoulPlasticity.MEDIUM,
      )
    }

    assertEquals(-1, state.warmth.offset)
    assertEquals(2, state.warmth.higherSupport)
    assertEquals(4, state.warmth.lowerSupport)
  }

  @Test
  fun batchedSupportWeightBehavesLikeRepeatedSignals() {
    val state = updater.apply(
      state = InteractionPreferenceState(),
      signal = InteractionPreferenceSignal(
        axis = InteractionPreferenceAxis.WARMTH,
        direction = InteractionPreferenceDirection.HIGHER,
        supportWeight = 4,
        occurredAtEpochMs = 1_000L,
      ),
      plasticity = SoulPlasticity.MEDIUM,
    )

    assertEquals(2, state.warmth.offset)
    assertEquals(4, state.warmth.higherSupport)
    assertEquals(0, state.warmth.lowerSupport)
  }

  @Test
  fun addressStyleSwitchesOnlyAfterDurableLeadBecomesClear() {
    var state = InteractionPreferenceState()

    state = updater.apply(
      state = state,
      signal = InteractionPreferenceSignal(
        preferredAddressStyle = PreferredAddressStyle.FRIENDLY,
        occurredAtEpochMs = 1_000L,
      ),
      plasticity = SoulPlasticity.MEDIUM,
    )
    assertEquals(PreferredAddressStyle.NEUTRAL, state.addressStyle.selectedStyle)

    state = updater.apply(
      state = state,
      signal = InteractionPreferenceSignal(
        preferredAddressStyle = PreferredAddressStyle.FRIENDLY,
        occurredAtEpochMs = 2_000L,
      ),
      plasticity = SoulPlasticity.MEDIUM,
    )
    assertEquals(PreferredAddressStyle.FRIENDLY, state.addressStyle.selectedStyle)

    repeat(2) { index ->
      state = updater.apply(
        state = state,
        signal = InteractionPreferenceSignal(
          preferredAddressStyle = PreferredAddressStyle.INTIMATE,
          occurredAtEpochMs = 3_000L + index,
        ),
        plasticity = SoulPlasticity.MEDIUM,
      )
    }
    assertEquals(PreferredAddressStyle.FRIENDLY, state.addressStyle.selectedStyle)

    state = updater.apply(
      state = state,
      signal = InteractionPreferenceSignal(
        preferredAddressStyle = PreferredAddressStyle.INTIMATE,
        occurredAtEpochMs = 5_000L,
      ),
      plasticity = SoulPlasticity.MEDIUM,
    )

    assertEquals(PreferredAddressStyle.INTIMATE, state.addressStyle.selectedStyle)
  }

  @Test
  fun preferredNamingAppliesImmediatelyAndAccumulatesSupport() {
    var state = InteractionPreferenceState()

    state = updater.apply(
      state = state,
      signal = InteractionPreferenceSignal(
        preferredNaming = "  小雨  ",
        occurredAtEpochMs = 1_000L,
      ),
      plasticity = SoulPlasticity.LOW,
    )
    state = updater.apply(
      state = state,
      signal = InteractionPreferenceSignal(
        preferredNaming = "小雨",
        occurredAtEpochMs = 2_000L,
      ),
      plasticity = SoulPlasticity.LOW,
    )

    assertEquals("小雨", state.preferredNaming)
    assertEquals(2, state.preferredNamingSupport)
  }

  private fun warmthSignal(
    direction: InteractionPreferenceDirection,
    occurredAtEpochMs: Long,
  ): InteractionPreferenceSignal = InteractionPreferenceSignal(
    axis = InteractionPreferenceAxis.WARMTH,
    direction = direction,
    occurredAtEpochMs = occurredAtEpochMs,
  )
}
