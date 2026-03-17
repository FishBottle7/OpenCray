package com.opencray.app

import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.soul.SoulProfileResolver
import com.opencray.runtime.soul.SoulProfileSeed

internal class PersonalizationSoulExtensionFactory(
  private val resolver: SoulProfileResolver = SoulProfileResolver(),
) {
  fun createManagedExtensions(presetName: String?): Map<String, String> {
    val resolved = resolver.resolve(
      SoulProfileSeed(
        presetName = presetName?.trim()?.takeIf(String::isNotEmpty),
      ),
    ) ?: return emptyMap()

    return linkedMapOf(
      SoulProfileExtensionKeys.TONE to resolved.tone.name.lowercase(),
      SoulProfileExtensionKeys.VERBOSITY to resolved.verbosity.name.lowercase(),
      SoulProfileExtensionKeys.PLASTICITY to resolved.plasticity.name.lowercase(),
      SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to resolved.userRelationshipStyle.name.lowercase(),
      SoulProfileExtensionKeys.RISK_TOLERANCE to resolved.riskTolerance.name.lowercase(),
      SoulProfileExtensionKeys.TOOL_USE_BIAS to resolved.toolUseBias.name.lowercase(),
    )
  }

  companion object {
    val MANAGED_KEYS: Set<String> = setOf(
      SoulProfileExtensionKeys.TONE,
      SoulProfileExtensionKeys.VERBOSITY,
      SoulProfileExtensionKeys.PLASTICITY,
      SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE,
      SoulProfileExtensionKeys.RISK_TOLERANCE,
      SoulProfileExtensionKeys.TOOL_USE_BIAS,
    )

    fun normalizeKey(rawKey: String?): String? =
      rawKey
        ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        ?.replace(Regex("[\\s\\-]+"), "_")
        ?.replace(Regex("_+"), "_")
        ?.trim('_')
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)

    fun isManagedKey(rawKey: String?): Boolean = normalizeKey(rawKey) in MANAGED_KEYS
  }
}
