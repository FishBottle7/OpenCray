package com.opencray.app

import com.opencray.runtime.bootstrap.BootstrapMode

enum class LiveContextMode(
  val wireValue: String,
) {
  FULL("full"),
  LIGHTWEIGHT("lightweight"),
  NONE("none"),
  NO_SOUL("no_soul"),
  NO_MEMORY_OR_SOUL("no_memory_or_soul"),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): LiveContextMode =
      entries.firstOrNull { mode -> mode.wireValue == rawValue?.trim() } ?: FULL
  }
}

internal data class LiveContextPolicy(
  val bootstrapMode: BootstrapMode,
  val soulEnabled: Boolean,
  val memoryRecallEnabled: Boolean,
)

internal fun LiveContextMode.toPolicy(): LiveContextPolicy = when (this) {
  LiveContextMode.FULL -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.FULL,
    soulEnabled = true,
    memoryRecallEnabled = true,
  )

  LiveContextMode.LIGHTWEIGHT -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.LIGHTWEIGHT,
    soulEnabled = true,
    memoryRecallEnabled = true,
  )

  LiveContextMode.NONE -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.NONE,
    soulEnabled = true,
    memoryRecallEnabled = true,
  )

  LiveContextMode.NO_SOUL -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.LIGHTWEIGHT,
    soulEnabled = false,
    memoryRecallEnabled = true,
  )

  LiveContextMode.NO_MEMORY_OR_SOUL -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.LIGHTWEIGHT,
    soulEnabled = false,
    memoryRecallEnabled = false,
  )
}
