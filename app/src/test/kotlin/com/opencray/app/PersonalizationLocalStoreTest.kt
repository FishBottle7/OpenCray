package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SoulRecord
import com.opencray.persistence.store.file.JsonFileSoulStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalizationLocalStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadSoulProfileReturnsNonReservedExtensions() {
    val directory = temporaryFolder.newFolder("personalization-load")
    JsonFileSoulStore(directory).save(
      SoulRecord(
        agentId = "agent-1",
        displayName = "Night Shift",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        extensions = mapOf(
          "preset" to "BUILDER",
          "custom_guidance" to "Stay direct.",
          "voice" to "calm but direct",
          "toolUseBias" to "tool-forward",
        ),
      ),
    )

    val profile = PersonalizationLocalStore(directory).loadSoulProfile()

    requireNotNull(profile)
    assertEquals("BUILDER", profile.presetName)
    assertEquals("Night Shift", profile.customLabel)
    assertEquals("Stay direct.", profile.customGuidance)
    assertEquals("calm but direct", profile.extensions["voice"])
    assertEquals("tool-forward", profile.extensions["toolUseBias"])
    assertFalse(profile.extensions.containsKey("preset"))
    assertFalse(profile.extensions.containsKey("custom_guidance"))
  }

  @Test
  fun saveSoulProfilePreservesExistingNonReservedExtensionsForUiOnlyUpdates() {
    val directory = temporaryFolder.newFolder("personalization-save-preserve")
    val soulStore = JsonFileSoulStore(directory)
    soulStore.save(
      SoulRecord(
        agentId = "agent-1",
        displayName = "Original",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        extensions = mapOf(
          "preset" to "STEADY",
          "custom_guidance" to "Old guidance",
          "voice" to "calm",
          "toolUseBias" to "verify-first",
        ),
      ),
    )

    PersonalizationLocalStore(directory, nowEpochMs = { 2_000L }).saveSoulProfile(
      PersonalizationLocalStore.SoulProfile(
        presetName = "WARM",
        customLabel = "Night Shift",
        customGuidance = "Stay calm.",
      ),
    )

    val updatedRecord = requireNotNull(soulStore.load())
    assertEquals("Night Shift", updatedRecord.displayName)
    assertEquals("WARM", updatedRecord.extensions["preset"])
    assertEquals("Stay calm.", updatedRecord.extensions["custom_guidance"])
    assertEquals("calm", updatedRecord.extensions["voice"])
    assertEquals("warm", updatedRecord.extensions["tone"])
    assertEquals("balanced", updatedRecord.extensions["verbosity"])
    assertEquals("medium", updatedRecord.extensions["plasticity"])
    assertEquals("supportive", updatedRecord.extensions["user_relationship_style"])
    assertEquals("conservative", updatedRecord.extensions["risk_tolerance"])
    assertEquals("verify_first", updatedRecord.extensions["tool_use_bias"])
    assertNull(updatedRecord.extensions["toolUseBias"])
  }

  @Test
  fun saveSoulProfileAllowsExplicitNonReservedExtensionsToOverrideExistingValues() {
    val directory = temporaryFolder.newFolder("personalization-save-override")
    val soulStore = JsonFileSoulStore(directory)
    soulStore.save(
      SoulRecord(
        agentId = "agent-1",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        extensions = mapOf(
          "voice" to "calm",
          "toolUseBias" to "verify-first",
        ),
      ),
    )

    PersonalizationLocalStore(directory, nowEpochMs = { 2_000L }).saveSoulProfile(
      PersonalizationLocalStore.SoulProfile(
        presetName = "",
        customLabel = "",
        customGuidance = "",
        extensions = mapOf(
          "voice" to "direct",
          "toolUseBias" to "tool-forward",
          "preset" to "ignored",
        ),
      ),
    )

    val updatedRecord = requireNotNull(soulStore.load())
    assertEquals("direct", updatedRecord.extensions["voice"])
    assertEquals("tool-forward", updatedRecord.extensions["toolUseBias"])
    assertFalse(updatedRecord.extensions.containsKey("preset"))
  }

  @Test
  fun saveSoulProfileClearsStaleManagedExtensionsWhenPresetGenerationIsAbsent() {
    val directory = temporaryFolder.newFolder("personalization-save-clear-stale-managed")
    val soulStore = JsonFileSoulStore(directory)
    soulStore.save(
      SoulRecord(
        agentId = "agent-1",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        extensions = mapOf(
          "tone" to "builder",
          "verbosity" to "terse",
          "voice" to "direct",
        ),
      ),
    )

    PersonalizationLocalStore(directory, nowEpochMs = { 2_000L }).saveSoulProfile(
      PersonalizationLocalStore.SoulProfile(
        presetName = "",
        customLabel = "",
        customGuidance = "",
        extensions = mapOf(
          "voice" to "calm",
        ),
      ),
    )

    val updatedRecord = requireNotNull(soulStore.load())
    assertEquals("calm", updatedRecord.extensions["voice"])
    assertNull(updatedRecord.extensions["tone"])
    assertNull(updatedRecord.extensions["verbosity"])
    assertNull(updatedRecord.extensions["plasticity"])
  }

  @Test
  fun memoryHelpersRoundTripStructuredRecords() {
    val directory = temporaryFolder.newFolder("personalization-memory-roundtrip")
    val store = PersonalizationLocalStore(directory)
    store.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-1",
        content = "Default to concise Chinese replies.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          "kind" to "user_preference",
          "scope" to "user",
          "status" to "active",
          "source_session_id" to "session-1",
        ),
      ),
    )

    val records = store.listMemoryRecords()

    assertEquals(listOf("memory-1"), records.map { record -> record.id })
    assertEquals("Default to concise Chinese replies.", records.single().content)
  }
}
