package com.opencray.app.facade.skills

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillEnablementStateStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsSkillEnablementAcrossReload() {
    val runtimeRoot = temporaryFolder.newFolder("skill-enablement")
    val store = FileBackedSkillEnablementStateStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 1_000L },
    )

    store.setEnabled("voice-notes", enabled = false)

    val restored = FileBackedSkillEnablementStateStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 2_000L },
    )
    assertFalse(restored.isEnabled("voice-notes"))
    assertTrue(restored.isEnabled("missing-skill"))
  }

  @Test
  fun removeRestoresDefaultEnabledState() {
    val runtimeRoot = temporaryFolder.newFolder("skill-enablement-remove")
    val store = FileBackedSkillEnablementStateStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 1_000L },
    )

    store.setEnabled("voice-notes", enabled = false)
    store.remove("voice-notes")

    assertTrue(store.isEnabled("voice-notes"))
  }

  @Test
  fun migratesLegacySkillEnablementWhenDurableRecordIsEmpty() {
    val runtimeRoot = temporaryFolder.newFolder("skill-enablement-migration")
    val store = FileBackedSkillEnablementStateStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 1_000L },
    )

    store.migrateFromLegacyIfEmpty(
      MapSkillEnablementStateStore(mapOf("voice-notes" to false)),
    )

    assertFalse(store.isEnabled("voice-notes"))
  }

  @Test
  fun migratesLegacySkillEnablementWhenDurableRecordIsMalformed() {
    val runtimeRoot = temporaryFolder.newFolder("skill-enablement-malformed-migration")
    val storage = DirectoryDurableTextStorage(runtimeRoot)
    storage.writeText("skill-enablement.json", "{not-json")
    val store = FileBackedSkillEnablementStateStore(
      storage = storage,
      clock = { 1_000L },
    )

    store.migrateFromLegacyIfEmpty(
      MapSkillEnablementStateStore(mapOf("voice-notes" to false)),
    )

    assertFalse(store.isEnabled("voice-notes"))
  }

  @Test
  fun migrationDoesNotOverwriteExistingDurableRecord() {
    val runtimeRoot = temporaryFolder.newFolder("skill-enablement-existing")
    val store = FileBackedSkillEnablementStateStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 1_000L },
    )
    store.setEnabled("voice-notes", enabled = true)

    store.migrateFromLegacyIfEmpty(
      MapSkillEnablementStateStore(mapOf("voice-notes" to false)),
    )

    assertTrue(store.isEnabled("voice-notes"))
  }

  private class MapSkillEnablementStateStore(
    initialValues: Map<String, Boolean>,
  ) : SkillEnablementStateStore {
    private val values = linkedMapOf<String, Boolean>().apply {
      putAll(initialValues)
    }

    override fun isEnabled(skillId: String): Boolean =
      values[skillId.trim()] ?: true

    override fun setEnabled(skillId: String, enabled: Boolean) {
      values[skillId.trim()] = enabled
    }

    override fun remove(skillId: String) {
      values.remove(skillId.trim())
    }

    override fun explicitEnablement(): Map<String, Boolean> = values.toMap()
  }
}
