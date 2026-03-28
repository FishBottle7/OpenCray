package com.opencray.app

import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.security.CredentialStoragePolicy
import com.opencray.persistence.security.SecretVault
import com.opencray.persistence.security.SecretValue

internal class AppSecretManager(
  private val vault: SecretVault,
  private val policy: CredentialStoragePolicy = CredentialStoragePolicy(),
) {
  fun storeUtf8(
    ref: CredentialRef,
    value: String,
  ): CredentialRef {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "Secret value must not be blank." }
    val decision = policy.evaluateStoreAttempt(vault = vault, ref = ref)
    require(decision.outcome == PolicyDecisionOutcome.ALLOW) {
      decision.detail ?: decision.reasonCode
    }
    vault.put(ref, SecretValue.fromUtf8(normalized))
    return ref
  }

  fun storeOrDelete(
    ref: CredentialRef,
    value: String?,
  ): CredentialRef? {
    val normalized = value?.trim().orEmpty()
    return if (normalized.isBlank()) {
      delete(ref)
      null
    } else {
      storeUtf8(ref, normalized)
    }
  }

  fun loadUtf8(ref: CredentialRef?): String? = ref?.let { credentialRef ->
    vault.get(credentialRef)?.revealUtf8()
  }

  fun delete(ref: CredentialRef?): Boolean = ref?.let(vault::delete) ?: false
}
