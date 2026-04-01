package com.opencray.app

import com.opencray.runtime.OpenCraySoulVisualIdentity
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.soul.SoulVisualIdentitySupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

  @Test
  fun toRuntimeSoulProfileEncodesVisualIdentityIntoExtensions() {
    val runtimeProfile = runtimeSoulProfileForTest(
      WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Lantern",
        customGuidance = "",
        visualIdentity = OpenCraySoulVisualIdentity(
          portraitSummary = "Calm expression and practical coat.",
        ),
      ),
    )

    val visualIdentity = SoulVisualIdentitySupport.decodeFromExtensions(runtimeProfile.extensions)

    assertNotNull(visualIdentity)
    assertEquals(
      "Calm expression and practical coat.",
      visualIdentity?.portraitSummary,
    )
    assertFalse(runtimeProfile.extensions[SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON].isNullOrBlank())
  }
}
