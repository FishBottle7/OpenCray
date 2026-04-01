package com.opencray.app

internal interface AppLanguageSettingsGatewayAccess {
  fun setAppLanguage(languageId: String): Map<String, Any?>
}

internal class GatewayBackedAppLanguageSettingsGatewayAccess(
  private val gateway: OpenCraySettingsGateway,
) : AppLanguageSettingsGatewayAccess {
  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    gateway.setAppLanguage(languageId)
}
