package com.opencray.app

import android.content.Context
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationFacade

internal interface AppLanguageSettingsGatewayAccess {
  fun setAppLanguage(languageId: String): Map<String, Any?>
}

internal class FacadeBackedAppLanguageSettingsGatewayAccess(
  private val facadeProvider: () -> PersonalizationFacade,
) : AppLanguageSettingsGatewayAccess {
  override fun setAppLanguage(languageId: String): Map<String, Any?> {
    facadeProvider().setAppLanguage(languageId)
    return facadeProvider().load().toPersonalizationGatewayMap()
  }

  companion object {
    fun fromContext(context: Context): AppLanguageSettingsGatewayAccess =
      FacadeBackedAppLanguageSettingsGatewayAccess(
        facadeProvider = { LocalPersonalizationFacade.fromContext(context.applicationContext) },
      )
  }
}

internal class GatewayBackedAppLanguageSettingsGatewayAccess(
  private val gateway: OpenCraySettingsGateway,
) : AppLanguageSettingsGatewayAccess {
  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    gateway.setAppLanguage(languageId)
}
