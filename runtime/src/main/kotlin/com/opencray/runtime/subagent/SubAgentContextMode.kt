package com.opencray.runtime.subagent

import java.util.Locale

enum class SubAgentContextMode(
  val wireValue: String,
) {
  MINIMAL("minimal"),
  DELEGATED("delegated"),
  MIRRORED("mirrored");

  companion object {
    fun fromWireValue(value: String?): SubAgentContextMode? {
      val normalized = value
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank)
        ?: return null
      return values().firstOrNull { mode -> mode.wireValue == normalized }
    }
  }
}
