package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSoulProfileSeedFactoryTest {
  private val factory = RuntimeSoulProfileSeedFactory()

  @Test
  fun createMapsExistingRuntimeSoulProfileIntoTypedSeedInput() {
    val seed = factory.create(
      RuntimeSoulProfile(
        presetName = "BUILDER",
        displayName = "Night Shift",
        voice = "direct",
        customGuidance = "Stay terse.",
      ),
    )

    requireNotNull(seed)
    assertEquals("BUILDER", seed.presetName)
    assertEquals("Night Shift", seed.displayName)
    assertEquals("Stay terse.", seed.customGuidance)
    assertEquals("direct", seed.extensions["voice"])
  }

  @Test
  fun createReturnsNullForEmptyRuntimeProfile() {
    val seed = factory.create(RuntimeSoulProfile())

    assertEquals(null, seed)
  }

  @Test
  fun createNormalizesWhitespaceAndPresetCasing() {
    val seed = factory.create(
      RuntimeSoulProfile(
        presetName = " builder ",
        displayName = " Night   Shift ",
        voice = " calm \n direct ",
        customGuidance = " Stay   terse. ",
      ),
    )

    requireNotNull(seed)
    assertEquals("BUILDER", seed.presetName)
    assertEquals("Night Shift", seed.displayName)
    assertEquals("calm direct", seed.extensions["voice"])
    assertEquals("Stay terse.", seed.customGuidance)
  }
}
