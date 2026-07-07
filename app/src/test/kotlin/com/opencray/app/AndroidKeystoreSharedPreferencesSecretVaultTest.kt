package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidKeystoreSharedPreferencesSecretVaultTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedSecretVaultKeyValueStorePersistsUriKeysThroughSafeDurableFiles() {
    val directory = temporaryFolder.newFolder("secret-vault")
    val store = FileBackedSecretVaultKeyValueStore(
      DirectoryDurableTextStorage(directory),
    )
    val key = "secret://sandbox/e2b/api-key"
    val payload = "v1:iv:ciphertext"

    store.putString(key, payload)

    assertEquals(payload, store.getString(key))
    val payloadFiles = directory.listFiles()
      .orEmpty()
      .filter { file -> file.isFile && file.name.endsWith(".txt") }
    assertEquals(1, payloadFiles.size)
    assertTrue(payloadFiles.single().name.startsWith("secret-"))
    assertFalse(payloadFiles.single().name.contains("/"))
    assertFalse(payloadFiles.single().name.contains(":"))
    assertTrue(store.remove(key))
    assertNull(store.getString(key))
  }

  @Test
  fun migratingSecretVaultKeyValueStoreCopiesLegacyReadsAndClearsLegacyWrites() {
    val key = "secret://mcp/community-bridge-token"
    val primary = RecordingSecretVaultKeyValueStore()
    val legacy = RecordingSecretVaultKeyValueStore(mapOf(key to "legacy-payload"))
    val store = MigratingSecretVaultKeyValueStore(primary = primary, legacy = legacy)

    assertEquals("legacy-payload", store.getString(key))
    assertEquals("legacy-payload", primary.getString(key))
    assertNull(legacy.getString(key))

    store.putString(key, "new-payload")

    assertEquals("new-payload", primary.getString(key))
    assertNull(legacy.getString(key))
    assertTrue(store.remove(key))
    assertNull(primary.getString(key))
    assertNull(legacy.getString(key))
  }

  private class RecordingSecretVaultKeyValueStore(
    initialValues: Map<String, String> = emptyMap(),
  ) : SecretVaultKeyValueStore {
    private val values = initialValues.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
      values[key] = value
    }

    override fun remove(key: String): Boolean =
      values.remove(key) != null
  }
}
