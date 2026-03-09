package com.opencray.persistence.security

import kotlinx.serialization.Serializable

/**
 * Reference to a secret stored in a secure vault.
 *
 * This is a reference only (never the secret value).
 */
@Serializable
@JvmInline
value class CredentialRef(
  val uri: String,
) {
  init {
    require(uri.isNotBlank()) { "CredentialRef uri must not be blank." }
  }
}

internal fun CredentialRef.schemeOrNull(): String? {
  val idx = uri.indexOf("://")
  if (idx <= 0) return null
  return uri.substring(0, idx)
}
