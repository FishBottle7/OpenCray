package com.opencray.app

internal enum class PersonalizationPreset {
  STEADY,
  ;

  companion object {
    fun fromRaw(rawValue: String?): PersonalizationPreset = entries.firstOrNull { preset ->
      preset.name.equals(rawValue?.trim(), ignoreCase = true)
    } ?: STEADY
  }
}

internal enum class PersonalizationResetPreview {
  NONE,
  ;

  companion object {
    fun fromRaw(rawValue: String?): PersonalizationResetPreview = entries.firstOrNull { preview ->
      preview.name.equals(rawValue?.trim(), ignoreCase = true)
    } ?: NONE
  }
}
