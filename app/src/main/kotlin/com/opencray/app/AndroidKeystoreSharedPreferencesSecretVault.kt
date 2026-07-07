package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.opencray.persistence.security.AndroidKeystoreSecretVault
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.security.SecretValue
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val DEFAULT_SECRET_VAULT_PREFERENCES = "opencray.secret-vault"

internal interface SecretVaultKeyValueStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun remove(key: String): Boolean
}

internal class SharedPreferencesSecretVaultKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : SecretVaultKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun remove(key: String): Boolean {
    val existed = sharedPreferences.contains(key)
    sharedPreferences.edit().remove(key).apply()
    return existed
  }
}

internal class FileBackedSecretVaultKeyValueStore(
  private val storage: DurableTextStorage,
) : SecretVaultKeyValueStore {
  override fun getString(key: String): String? =
    storage.readText(fileNameForKey(key))
      ?.takeIf(String::isNotBlank)

  override fun putString(key: String, value: String) {
    storage.writeText(fileNameForKey(key), value)
  }

  override fun remove(key: String): Boolean =
    storage.delete(fileNameForKey(key))

  private fun fileNameForKey(key: String): String =
    "secret-${sha256Hex(key)}.txt"
}

internal class MigratingSecretVaultKeyValueStore(
  private val primary: SecretVaultKeyValueStore,
  private val legacy: SecretVaultKeyValueStore?,
) : SecretVaultKeyValueStore {
  override fun getString(key: String): String? {
    val primaryValue = primary.getString(key)
    if (primaryValue != null) {
      return primaryValue
    }
    val legacyValue = legacy?.getString(key) ?: return null
    primary.putString(key, legacyValue)
    legacy.remove(key)
    return legacyValue
  }

  override fun putString(key: String, value: String) {
    primary.putString(key, value)
    legacy?.remove(key)
  }

  override fun remove(key: String): Boolean {
    val removedPrimary = primary.remove(key)
    val removedLegacy = legacy?.remove(key) ?: false
    return removedPrimary || removedLegacy
  }
}

internal class AndroidKeystoreSharedPreferencesSecretVault private constructor(
  private val keyValueStore: SecretVaultKeyValueStore,
  private val keyAliasPrefix: String = DEFAULT_KEY_ALIAS_PREFIX,
) : AndroidKeystoreSecretVault {
  override fun put(
    ref: CredentialRef,
    secret: SecretValue,
  ) {
    val alias = aliasFor(ref)
    val secretKey = getOrCreateKey(alias)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val payload = buildString {
      append(PAYLOAD_VERSION)
      append(':')
      append(base64Encode(cipher.iv))
      append(':')
      append(base64Encode(cipher.doFinal(secret.bytesCopy())))
    }
    keyValueStore.putString(storageKeyFor(ref), payload)
  }

  override fun get(ref: CredentialRef): SecretValue? {
    val payload = keyValueStore.getString(storageKeyFor(ref)) ?: return null
    val parsed = parsePayload(payload) ?: return null
    val secretKey = loadKey(aliasFor(ref)) ?: return null
    return runCatching {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        secretKey,
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, parsed.iv),
      )
      SecretValue.fromBytes(cipher.doFinal(parsed.cipherText))
    }.getOrNull()
  }

  override fun delete(ref: CredentialRef): Boolean {
    val alias = aliasFor(ref)
    val removedValue = keyValueStore.remove(storageKeyFor(ref))
    val removedKey = runCatching {
      val keyStore = keyStore()
      if (keyStore.containsAlias(alias)) {
        keyStore.deleteEntry(alias)
        true
      } else {
        false
      }
    }.getOrDefault(false)
    return removedValue || removedKey
  }

  private fun storageKeyFor(ref: CredentialRef): String = ref.uri

  private fun aliasFor(ref: CredentialRef): String = buildString {
    append(keyAliasPrefix)
    append('-')
    append(sha256Hex(ref.uri))
  }

  private fun getOrCreateKey(alias: String): SecretKey =
    loadKey(alias) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
      .apply {
        init(
          KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)
            .build(),
        )
      }.generateKey()

  private fun loadKey(alias: String): SecretKey? = runCatching {
    keyStore().getKey(alias, null) as? SecretKey
  }.getOrNull()

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
    load(null)
  }

  private fun parsePayload(payload: String): CipherPayload? {
    val parts = payload.split(':', limit = 3)
    if (parts.size != 3 || parts[0] != PAYLOAD_VERSION) {
      return null
    }
    return runCatching {
      CipherPayload(
        iv = base64Decode(parts[1]),
        cipherText = base64Decode(parts[2]),
      )
    }.getOrNull()
  }

  private fun base64Encode(bytes: ByteArray): String =
    Base64.getEncoder().withoutPadding().encodeToString(bytes)

  private fun base64Decode(value: String): ByteArray =
    Base64.getDecoder().decode(value)

  private data class CipherPayload(
    val iv: ByteArray,
    val cipherText: ByteArray,
  )

  companion object {
    private const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
    private const val DEFAULT_KEY_ALIAS_PREFIX: String = "opencray-secret"
    private const val TRANSFORMATION: String = "AES/GCM/NoPadding"
    private const val PAYLOAD_VERSION: String = "v1"
    private const val AES_KEY_SIZE_BITS: Int = 256
    private const val GCM_TAG_LENGTH_BITS: Int = 128

    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_SECRET_VAULT_PREFERENCES,
    ): AndroidKeystoreSharedPreferencesSecretVault {
      val appContext = context.applicationContext
      return AndroidKeystoreSharedPreferencesSecretVault(
        keyValueStore = MigratingSecretVaultKeyValueStore(
          primary = FileBackedSecretVaultKeyValueStore(
            DirectoryDurableTextStorage(File(appContext.filesDir, preferencesName)),
          ),
          legacy = SharedPreferencesSecretVaultKeyValueStore(
            appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
          ),
        ),
      )
    }
  }
}

private fun sha256Hex(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
