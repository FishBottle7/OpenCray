package com.opencray.app

import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.security.SecretVault
import com.opencray.persistence.security.SecretVaultStorageClass
import com.opencray.persistence.security.SecretValue

internal fun inMemorySecretManager(
  storageClass: SecretVaultStorageClass = SecretVaultStorageClass.TEST_IN_MEMORY,
): AppSecretManager = AppSecretManager(
  vault = object : SecretVault {
    private val values = linkedMapOf<CredentialRef, SecretValue>()

    override val storageClass: SecretVaultStorageClass = storageClass

    override fun put(
      ref: CredentialRef,
      secret: SecretValue,
    ) {
      values[ref] = secret
    }

    override fun get(ref: CredentialRef): SecretValue? = values[ref]

    override fun delete(ref: CredentialRef): Boolean = values.remove(ref) != null
  },
)

internal fun testSandboxSettingsRepository(
  store: SandboxSettingsStore = SandboxSettingsStore(InMemorySandboxSettingsKeyValueStore()),
): SandboxSettingsRepository = SandboxSettingsRepository(
  store = store,
  secretManager = inMemorySecretManager(),
)
