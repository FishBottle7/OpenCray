package com.opencray.app

import com.opencray.runtime.soul.SoulProfileExtensionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationSoulExtensionFactoryTest {
  private val factory = PersonalizationSoulExtensionFactory()

  @Test
  fun createManagedExtensionsBuildsCoreRuntimeAxesFromBuilderPreset() {
    val extensions = factory.createManagedExtensions("builder")

    assertEquals("builder", extensions[SoulProfileExtensionKeys.TONE])
    assertEquals("terse", extensions[SoulProfileExtensionKeys.VERBOSITY])
    assertEquals("low", extensions[SoulProfileExtensionKeys.PLASTICITY])
    assertEquals("direct", extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE])
    assertEquals("balanced", extensions[SoulProfileExtensionKeys.RISK_TOLERANCE])
    assertEquals("tool_forward", extensions[SoulProfileExtensionKeys.TOOL_USE_BIAS])
  }

  @Test
  fun createManagedExtensionsReturnsEmptyForBlankPreset() {
    assertTrue(factory.createManagedExtensions("").isEmpty())
    assertTrue(factory.createManagedExtensions("   ").isEmpty())
    assertTrue(factory.createManagedExtensions(null).isEmpty())
  }

  @Test
  fun normalizeKeyNormalizesCamelCaseHyphenAndWhitespace() {
    assertEquals("tool_use_bias", PersonalizationSoulExtensionFactory.normalizeKey("toolUseBias"))
    assertEquals("tool_use_bias", PersonalizationSoulExtensionFactory.normalizeKey("tool-use-bias"))
    assertEquals("tool_use_bias", PersonalizationSoulExtensionFactory.normalizeKey(" tool use bias "))
  }
}
