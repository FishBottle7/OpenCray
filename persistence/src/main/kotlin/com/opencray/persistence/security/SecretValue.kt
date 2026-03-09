package com.opencray.persistence.security

/**
 * In-memory secret container.
 *
 * NOTE: This wrapper intentionally redacts its toString() to reduce accidental log leakage.
 */
class SecretValue private constructor(
  private val bytes: ByteArray,
) {
  fun bytesCopy(): ByteArray = bytes.copyOf()

  fun revealUtf8(): String = bytes.toString(Charsets.UTF_8)

  override fun toString(): String = "SecretValue(<redacted>)"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SecretValue) return false
    return bytes.contentEquals(other.bytes)
  }

  override fun hashCode(): Int = bytes.contentHashCode()

  companion object {
    fun fromUtf8(value: String): SecretValue {
      require(value.isNotEmpty()) { "SecretValue must not be empty." }
      return SecretValue(value.toByteArray(Charsets.UTF_8))
    }

    fun fromBytes(bytes: ByteArray): SecretValue {
      require(bytes.isNotEmpty()) { "SecretValue must not be empty." }
      return SecretValue(bytes.copyOf())
    }
  }
}
