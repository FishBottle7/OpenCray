package com.opencray.app

private const val CREDENTIAL_MASK_PREFIX = "••••"
private const val CREDENTIAL_HINT_LENGTH = 4
private const val CREDENTIAL_HINT_MIN_KEY_LENGTH = 8

internal fun credentialHasValue(credential: String): Boolean =
  credential.trim().isNotBlank()

internal fun credentialHint(credential: String): String {
  val key = credential.trim()
  if (key.length < CREDENTIAL_HINT_MIN_KEY_LENGTH) {
    return ""
  }
  return key.takeLast(CREDENTIAL_HINT_LENGTH)
}

internal fun maskCredential(credential: String): String {
  val key = credential.trim()
  if (key.isEmpty()) {
    return ""
  }
  return CREDENTIAL_MASK_PREFIX + credentialHint(key)
}

internal fun resolvePatchedCredential(
  requestedApiKey: String?,
  persistedApiKey: String,
): String {
  if (requestedApiKey == null) {
    return persistedApiKey
  }
  val requested = requestedApiKey.trim()
  if (requested.isNotEmpty() &&
    persistedApiKey.isNotBlank() &&
    requested == maskCredential(persistedApiKey)
  ) {
    return persistedApiKey
  }
  return requested
}
