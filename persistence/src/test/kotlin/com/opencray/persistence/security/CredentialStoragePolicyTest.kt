package com.opencray.persistence.security

import com.opencray.core.contracts.PolicyDecisionOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialStoragePolicyTest {
  @Test
  fun deniesPlaintextVaultEvenWithSecureRefScheme() {
    val policy = CredentialStoragePolicy()
    val vault = object : SecretVault {
      override val storageClass: SecretVaultStorageClass = SecretVaultStorageClass.PLAINTEXT

      override fun put(ref: CredentialRef, secret: SecretValue) = Unit
      override fun get(ref: CredentialRef): SecretValue? = null
      override fun delete(ref: CredentialRef): Boolean = false
    }

    val decision = policy.evaluateStoreAttempt(
      vault = vault,
      ref = CredentialRef("secret://mcp-http-token"),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(CredentialStoragePolicy.DENY_INSECURE_SECRET_STORE, decision.reasonCode)
  }

  @Test
  fun deniesInsecureCredentialRefScheme() {
    val policy = CredentialStoragePolicy()
    val vault = object : SecretVault {
      override val storageClass: SecretVaultStorageClass = SecretVaultStorageClass.ANDROID_KEYSTORE

      override fun put(ref: CredentialRef, secret: SecretValue) = Unit
      override fun get(ref: CredentialRef): SecretValue? = null
      override fun delete(ref: CredentialRef): Boolean = false
    }

    val decision = policy.evaluateStoreAttempt(
      vault = vault,
      ref = CredentialRef("file://token.txt"),
    )

    assertEquals(PolicyDecisionOutcome.DENY, decision.outcome)
    assertEquals(CredentialStoragePolicy.DENY_INSECURE_SECRET_STORE, decision.reasonCode)
  }

  @Test
  fun allowsKeystoreBackedVaultWithSecureScheme() {
    val policy = CredentialStoragePolicy()
    val vault = object : SecretVault {
      override val storageClass: SecretVaultStorageClass = SecretVaultStorageClass.ANDROID_KEYSTORE

      override fun put(ref: CredentialRef, secret: SecretValue) = Unit
      override fun get(ref: CredentialRef): SecretValue? = null
      override fun delete(ref: CredentialRef): Boolean = false
    }

    val decision = policy.evaluateStoreAttempt(
      vault = vault,
      ref = CredentialRef("keystore://alias/opencray"),
    )

    assertEquals(PolicyDecisionOutcome.ALLOW, decision.outcome)
    assertEquals(CredentialStoragePolicy.ALLOW_SECURE_SECRET_STORE, decision.reasonCode)
  }
}
