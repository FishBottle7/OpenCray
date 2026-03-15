package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.MemoryTurnEvidence
import com.opencray.runtime.memory.MemoryWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryBackedSoulProfileResolverTest {
  private val resolver = MemoryBackedSoulProfileResolver()

  @Test
  fun overlayUsesDurableDisplayNameAndSessionStylePreferenceWithoutMutatingBaseSoul() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        customGuidance = "Stay direct.",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
        ),
      ),
      records = listOf(
        preferenceRecord(
          id = "durable-name",
          sessionId = "session-source",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          preferenceValue = "Xiao Bai",
          updatedAtEpochMs = 1_000L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
          ),
        ),
        preferenceRecord(
          id = "durable-style",
          sessionId = "session-source",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          preferenceValue = "warm",
          updatedAtEpochMs = 1_100L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "warm",
            MemorySoulExtensionKeys.VOICE to "warm and gentle",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
          ),
        ),
        preferenceRecord(
          id = "session-style",
          sessionId = "session-main",
          scope = MemoryScope.SESSION,
          preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 1_200L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          ),
        ),
        preferenceRecord(
          id = "durable-verbosity",
          sessionId = "session-source",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_VERBOSITY,
          preferenceValue = "expansive",
          updatedAtEpochMs = 1_150L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.VERBOSITY to "expansive",
          ),
        ),
      ),
      sessionId = "session-main",
    )

    assertEquals("Xiao Bai", profile?.displayName)
    assertEquals("BUILDER", profile?.presetName)
    assertEquals("serious and formal", profile?.voice)
    assertEquals("steady", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("expansive", profile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertEquals("direct", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
    assertEquals("Stay direct.", profile?.customGuidance)
  }

  @Test
  fun overlayKeepsDurableIdentityAndVerbosityAcrossSessionsWhileSessionStyleStaysLocal() {
    val store = InMemoryMemoryStore()
    val extractor = MemoryCandidateExtractor()
    val writer = MemoryWriter(
      store = store,
      clock = IncrementingClock(start = 5_000L)::next,
    )
    writer.write(
      extractor.extract(
        MemoryTurnEvidence(
          sessionId = "session-1",
          taskId = "task-1",
          workspaceId = "workspace-main",
          userInput = """
            以后叫你小白。
            以后回答详细一点。
          """.trimIndent(),
        ),
      ),
    )
    writer.write(
      extractor.extract(
        MemoryTurnEvidence(
          sessionId = "session-2",
          taskId = "task-2",
          workspaceId = "workspace-main",
          userInput = "这次说话严肃一点。",
        ),
      ),
    )

    val baseProfile = RuntimeSoulProfile(
      presetName = "BUILDER",
      voice = "decisive and direct",
      extensions = mapOf(
        SoulProfileExtensionKeys.TONE to "builder",
      ),
    )
    val sessionTwoProfile = resolver.overlay(
      baseProfile = baseProfile,
      records = store.list(),
      sessionId = "session-2",
      workspaceId = "workspace-main",
    )
    val sessionThreeProfile = resolver.overlay(
      baseProfile = baseProfile,
      records = store.list(),
      sessionId = "session-3",
      workspaceId = "workspace-main",
    )

    assertEquals("小白", sessionTwoProfile?.displayName)
    assertEquals("serious and formal", sessionTwoProfile?.voice)
    assertEquals("steady", sessionTwoProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("expansive", sessionTwoProfile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertEquals("小白", sessionThreeProfile?.displayName)
    assertEquals("decisive and direct", sessionThreeProfile?.voice)
    assertEquals("builder", sessionThreeProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("expansive", sessionThreeProfile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertNull(sessionThreeProfile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  private fun preferenceRecord(
    id: String,
    sessionId: String,
    scope: MemoryScope,
    preferenceKey: String,
    preferenceValue: String,
    updatedAtEpochMs: Long,
    extraExtensions: Map<String, String> = emptyMap(),
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "$preferenceKey=$preferenceValue",
    tags = listOf(
      "kind:user_preference",
      "scope:${scope.name.lowercase()}",
      "status:${MemoryStatus.ACTIVE.name.lowercase()}",
    ),
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to MemoryKind.USER_PREFERENCE.name.lowercase(),
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
      MemoryRecordExtensionKeys.PREFERENCE_KEY to preferenceKey,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferenceValue,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + extraExtensions,
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var value = start

    fun next(): Long = value++
  }
}
