package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionPreferenceStateProjectorTest {
  private val projector = InteractionPreferenceStateProjector(clock = { 10_000L })

  @Test
  fun projectUsesLatestSnapshotForMatchingScope() {
    val projection = projector.project(
      records = listOf(
        interactionPreferenceStateRecord(
          id = "snapshot-old",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
          ),
          updatedAtEpochMs = 1_000L,
        ),
        interactionPreferenceStateRecord(
          id = "snapshot-new",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 2, higherSupport = 4),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
          ),
          updatedAtEpochMs = 2_000L,
        ),
      ),
      scope = MemoryScope.USER,
    )

    assertEquals("snapshot-new", projection.snapshotRecordId)
    assertEquals(2, projection.state.warmth.offset)
    assertEquals(-1, projection.state.formality.offset)
  }

  @Test
  fun projectIgnoresOtherWorkspacesAndExpiredSnapshots() {
    val projection = projector.project(
      records = listOf(
        interactionPreferenceStateRecord(
          id = "workspace-other",
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-other",
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 2, higherSupport = 4),
          ),
          updatedAtEpochMs = 2_000L,
        ),
        interactionPreferenceStateRecord(
          id = "workspace-expired",
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
          ttlMs = 500L,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 2, higherSupport = 4),
          ),
          updatedAtEpochMs = 1_000L,
        ),
        interactionPreferenceStateRecord(
          id = "workspace-active",
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
          ),
          updatedAtEpochMs = 3_000L,
        ),
      ),
      scope = MemoryScope.WORKSPACE,
      workspaceId = "workspace-main",
    )

    assertEquals("workspace-active", projection.snapshotRecordId)
    assertEquals(1, projection.state.warmth.offset)
  }

  private fun interactionPreferenceStateRecord(
    id: String,
    scope: MemoryScope,
    state: InteractionPreferenceState,
    updatedAtEpochMs: Long,
    workspaceId: String? = null,
    ttlMs: Long? = null,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal interaction preference snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      "scope" to scope.name.lowercase(),
      "status" to "active",
      "last_confirmed_at_epoch_ms" to updatedAtEpochMs.toString(),
    ) + buildInteractionPreferenceStateMemoryExtensions(state) + buildMap {
      workspaceId?.let { put("workspace_id", it) }
      ttlMs?.let { put("ttl_ms", it.toString()) }
    },
  )
}
