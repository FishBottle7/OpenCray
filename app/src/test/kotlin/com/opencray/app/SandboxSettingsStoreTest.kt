package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxSettingsStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadDefaultsToSafeLocalBackend() {
    val store = SandboxSettingsStore(InMemorySandboxSettingsKeyValueStore())

    val state = store.load()

    assertFalse(state.enabled)
    assertEquals("e2b", state.providerId)
    assertEquals("local", state.defaultBackend)
    assertEquals("ephemeral", state.sessionMode)
    assertEquals("kill", state.timeoutAction)
  }

  @Test
  fun saveAndLoadRoundTripsSanitizedState() {
    val store = SandboxSettingsStore(InMemorySandboxSettingsKeyValueStore())
    val saved = SandboxSettingsState(
      enabled = true,
      providerId = "E2B",
      defaultBackend = "AUTO",
      sessionMode = "sticky",
      autoResume = true,
      idleTimeoutMinutes = 45,
      startupTimeoutMs = 40_000L,
      requestTimeoutMs = 600_000L,
      timeoutAction = "pause",
      templateId = "  tmpl-opencray  ",
      e2bApiKeyCredentialRef = "secret://sandbox/e2b/api-key",
    )

    store.save(saved)

    assertEquals(saved.sanitized(), store.load())
  }

  @Test
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("sandbox-settings-file-backed")
    val firstStore = SandboxSettingsStore(
      FileBackedSandboxSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )
    val saved = SandboxSettingsState(
      enabled = true,
      defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      sessionMode = SandboxSessionMode.STICKY.wireValue,
      autoResume = true,
      idleTimeoutMinutes = 90,
      templateId = "  tmpl-runtime  ",
      e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
    )

    firstStore.save(saved)

    val secondStore = SandboxSettingsStore(
      FileBackedSandboxSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertEquals(saved.sanitized(), secondStore.load())

    secondStore.clear()

    assertEquals(SandboxSettingsState(), firstStore.load())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("sandbox-settings-migration")
    val legacyKeyValueStore = InMemorySandboxSettingsKeyValueStore()
    val legacyStore = SandboxSettingsStore(legacyKeyValueStore)
    val legacyState = SandboxSettingsState(
      enabled = true,
      defaultBackend = SandboxExecutionBackendPreference.AUTO.wireValue,
      sessionMode = SandboxSessionMode.STICKY.wireValue,
      templateId = "legacy-template",
    )
    legacyStore.save(legacyState)
    val fileBackedKeyValueStore = FileBackedSandboxSettingsKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = SandboxSettingsStore(fileBackedKeyValueStore)
    assertEquals(legacyState.sanitized(), fileBackedStore.load())

    val durableState = SandboxSettingsState(
      enabled = false,
      defaultBackend = SandboxExecutionBackendPreference.LOCAL.wireValue,
      templateId = "durable-template",
    )
    fileBackedStore.save(durableState)
    legacyStore.save(legacyState.copy(templateId = "newer-legacy-template"))

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertEquals(durableState.sanitized(), fileBackedStore.load())
  }

  @Test
  fun sanitizedStateClampsTimeoutsAndDropsBlankCredentialRef() {
    val sanitized = SandboxSettingsState(
      idleTimeoutMinutes = -1,
      startupTimeoutMs = 1L,
      requestTimeoutMs = Long.MAX_VALUE,
      e2bApiKeyCredentialRef = "   ",
    ).sanitized()

    assertEquals(SandboxSettingsState.MIN_IDLE_TIMEOUT_MINUTES, sanitized.idleTimeoutMinutes)
    assertEquals(SandboxSettingsState.MIN_STARTUP_TIMEOUT_MS, sanitized.startupTimeoutMs)
    assertEquals(SandboxSettingsState.MAX_REQUEST_TIMEOUT_MS, sanitized.requestTimeoutMs)
    assertTrue(sanitized.e2bApiKeyCredentialRef.isEmpty())
  }
}
