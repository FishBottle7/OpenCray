package com.opencray.runtime.subagent

import java.util.Locale

enum class SubAgentContextMode(
  val wireValue: String,
  val publicControlPlaneEnabled: Boolean = true,
) {
  MINIMAL("minimal"),
  DELEGATED("delegated"),
  MIRRORED("mirrored", publicControlPlaneEnabled = false);

  companion object {
    fun fromWireValue(value: String?): SubAgentContextMode? {
      val normalized = value
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank)
        ?: return null
      return values().firstOrNull { mode -> mode.wireValue == normalized }
    }

    fun publicModes(): List<SubAgentContextMode> = values().filter { mode ->
      mode.publicControlPlaneEnabled
    }

    fun publicWireValuesDescription(): String = publicModes()
      .joinToString(", ") { mode -> mode.wireValue }
  }
}
