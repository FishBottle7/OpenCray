package com.opencray.persistence.security

import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome

/**
 * Deny-by-default guardrail for credential storage.
 */
class CredentialStoragePolicy(
  private val allowedRefSchemes: Set<String> = setOf("secret", "keystore"),
) {
  fun evaluateStoreAttempt(
    vault: SecretVault,
    ref: CredentialRef,
  ): PolicyDecision {
    val scheme = ref.schemeOrNull()
    if (scheme == null || scheme !in allowedRefSchemes) {
      return PolicyDecision(
        outcome = PolicyDecisionOutcome.DENY,
        reasonCode = DENY_INSECURE_SECRET_STORE,
        detail = "CredentialRef scheme must be one of $allowedRefSchemes; got '${scheme ?: "<missing>"}'.",
      )
    }

    if (vault.storageClass == SecretVaultStorageClass.PLAINTEXT) {
      return PolicyDecision(
        outcome = PolicyDecisionOutcome.DENY,
        reasonCode = DENY_INSECURE_SECRET_STORE,
        detail = "Refusing to store secrets in PLAINTEXT vault implementation.",
      )
    }

    return PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = ALLOW_SECURE_SECRET_STORE,
    )
  }

  companion object {
    const val DENY_INSECURE_SECRET_STORE: String = "DENY_INSECURE_SECRET_STORE"
    const val ALLOW_SECURE_SECRET_STORE: String = "ALLOW_SECURE_SECRET_STORE"
  }
}
