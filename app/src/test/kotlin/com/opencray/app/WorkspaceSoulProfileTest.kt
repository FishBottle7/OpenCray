package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceSoulProfileTest {
  @Test
  fun toRuntimeSoulProfileFiltersReservedAndBlankExtensionsAfterNormalization() {
    val runtimeProfile = runtimeSoulProfileForTest(
      WorkspaceSoulProfile(
      presetName = "",
      customLabel = "",
      customGuidance = "",
      extensions = mapOf(
        " voice " to " calm but direct ",
        "toolUseBias" to " tool-forward ",
        "customGuidance" to "should-not-leak",
        "preset" to "should-not-leak",
        "blank-value" to "   ",
        "   " to "missing-key",
      ),
      ),
    )

    assertNull(runtimeProfile.presetName)
    assertNull(runtimeProfile.displayName)
    assertNull(runtimeProfile.customGuidance)
    assertEquals("calm but direct", runtimeProfile.extensions["voice"])
    assertEquals("tool-forward", runtimeProfile.extensions["toolUseBias"])
    assertFalse(runtimeProfile.extensions.containsKey("customGuidance"))
    assertFalse(runtimeProfile.extensions.containsKey("preset"))
    assertFalse(runtimeProfile.extensions.containsKey("blank-value"))
  }
}
