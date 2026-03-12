package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

private const val DEFAULT_LOCALE_SETTINGS_PREFERENCES = "opencray.locale-settings"

internal enum class AppLanguage(
  val tag: String,
) {
  ENGLISH("en"),
  SIMPLIFIED_CHINESE("zh-CN"),
  ;

  companion object {
    val default: AppLanguage = ENGLISH

    fun fromRaw(rawValue: String?): AppLanguage = entries.firstOrNull { language ->
      language.tag.equals(rawValue?.trim(), ignoreCase = true) ||
        language.name.equals(rawValue?.trim(), ignoreCase = true)
    } ?: default
  }
}

private object LocaleSettingsStoreKeys {
  const val APP_LANGUAGE = "app_language"
}

internal class LocaleSettingsStore(
  private val preferences: SharedPreferences,
) {
  fun loadLanguage(): AppLanguage = AppLanguage.fromRaw(
    preferences.getString(LocaleSettingsStoreKeys.APP_LANGUAGE, AppLanguage.default.tag),
  )

  fun saveLanguage(language: AppLanguage) {
    preferences.edit().putString(LocaleSettingsStoreKeys.APP_LANGUAGE, language.tag).apply()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_LOCALE_SETTINGS_PREFERENCES,
    ): LocaleSettingsStore = LocaleSettingsStore(
      preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
    )
  }
}

internal object OpenCrayLocaleManager {
  fun wrap(base: Context): Context {
    val language = LocaleSettingsStore.fromContext(base).loadLanguage()
    val locale = when (language) {
      AppLanguage.ENGLISH -> Locale.ENGLISH
      AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    }
    Locale.setDefault(locale)

    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return base.createConfigurationContext(configuration)
  }
}
