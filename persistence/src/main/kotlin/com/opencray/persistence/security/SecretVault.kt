package com.opencray.persistence.security

/**
 * Secure secret storage abstraction.
 *
 * Implementations are expected to be backed by Android Keystore (or equivalent secure enclave
 * + encrypted-at-rest store) in production builds.
 */
interface SecretVault {
  val storageClass: SecretVaultStorageClass

  fun put(ref: CredentialRef, secret: SecretValue)
  fun get(ref: CredentialRef): SecretValue?
  fun delete(ref: CredentialRef): Boolean
}

enum class SecretVaultStorageClass {
  /** Android Keystore-backed encryption/decryption contract. */
  ANDROID_KEYSTORE,

  /** Encrypted at rest (e.g., EncryptedFile / EncryptedSharedPreferences). */
  ENCRYPTED_AT_REST,

  /** JVM/in-memory, intended for tests only (not durable). */
  TEST_IN_MEMORY,

  /** Plaintext persistence (MUST be rejected by policy). */
  PLAINTEXT,
}

/**
 * Minimal contract for Android Keystore backed implementations.
 *
 * Task 4 defines interfaces only; implementation is deferred.
 */
interface AndroidKeystoreSecretVault : SecretVault {
  override val storageClass: SecretVaultStorageClass
    get() = SecretVaultStorageClass.ANDROID_KEYSTORE
}
