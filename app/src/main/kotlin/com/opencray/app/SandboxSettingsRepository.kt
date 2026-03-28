package com.opencray.app

import android.content.Context
import com.opencray.persistence.security.CredentialRef

internal data class ResolvedSandboxSettings(
  val state: SandboxSettingsState = SandboxSettingsState(),
  val e2bApiKey: String? = null,
) {
  fun canUseSandbox(): Boolean =
    state.enabled &&
      SandboxProviderId.fromWireValue(state.providerId) == SandboxProviderId.E2B &&
      !e2bApiKey.isNullOrBlank()

  fun hasResolvedE2bApiKey(): Boolean = !e2bApiKey.isNullOrBlank()
}

internal class SandboxSettingsRepository(
  private val store: SandboxSettingsStore,
  private val secretManager: AppSecretManager,
) {
  fun load(): ResolvedSandboxSettings {
    val state = store.load()
    return ResolvedSandboxSettings(
      state = state,
      e2bApiKey = secretManager.loadUtf8(state.e2bApiKeyCredentialRefOrNull()),
    )
  }

  fun save(
    state: SandboxSettingsState,
    e2bApiKey: String? = null,
  ): ResolvedSandboxSettings {
    val sanitized = state.sanitized()
    val existing = store.load()
    val persisted = when (SandboxProviderId.fromWireValue(sanitized.providerId)) {
      SandboxProviderId.E2B -> {
        val credentialRef = if (e2bApiKey != null) {
          secretManager.storeOrDelete(E2B_API_KEY_REF, e2bApiKey)?.uri.orEmpty()
        } else {
          sanitized.e2bApiKeyCredentialRef.ifBlank { existing.e2bApiKeyCredentialRef }
        }
        sanitized.copy(e2bApiKeyCredentialRef = credentialRef)
      }

      null -> sanitized
    }
    store.save(persisted)
    return load()
  }

  fun clearE2bApiKey(): ResolvedSandboxSettings {
    val current = store.load()
    secretManager.delete(current.e2bApiKeyCredentialRefOrNull())
    store.save(current.copy(e2bApiKeyCredentialRef = ""))
    return load()
  }

  companion object {
    val E2B_API_KEY_REF: CredentialRef = CredentialRef("secret://sandbox/e2b/api-key")

    fun fromContext(context: Context): SandboxSettingsRepository = SandboxSettingsRepository(
      store = SandboxSettingsStore.fromContext(context.applicationContext),
      secretManager = AppSecretManager(
        vault = AndroidKeystoreSharedPreferencesSecretVault.fromContext(context.applicationContext),
      ),
    )
  }
}
