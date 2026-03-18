package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryCandidate
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.normalizePreferredAddressStyleValueOrNull
import com.opencray.runtime.memory.parseMemoryMetadata

data class InteractionPreferenceMemoryWritePlan(
  val stateSnapshotCandidates: List<MemoryCandidate> = emptyList(),
) {
  val candidates: List<MemoryCandidate>
    get() = stateSnapshotCandidates
}

class InteractionPreferenceMemoryWritePlanner(
  private val projector: InteractionPreferenceStateProjector = InteractionPreferenceStateProjector(),
  private val updater: InteractionPreferenceStateUpdater = InteractionPreferenceStateUpdater(),
  private val candidateFactory: SoulMemoryCandidateFactory = SoulMemoryCandidateFactory(),
) {
  fun plan(
    existingRecords: List<MemoryRecord>,
    sourceRecords: List<MemoryRecord>,
    plasticity: SoulPlasticity,
    sourceSessionId: String,
    workspaceId: String? = null,
    sourceTaskId: String? = null,
  ): InteractionPreferenceMemoryWritePlan {
    if (sourceRecords.isEmpty()) {
      return InteractionPreferenceMemoryWritePlan()
    }

    val groupedSignals = sourceRecords
      .mapNotNull { record ->
        val metadata = record.parseMemoryMetadata() ?: return@mapNotNull null
        if (metadata.status != MemoryStatus.ACTIVE) {
          return@mapNotNull null
        }
        val scope = resolveMemoryScope(metadata.scope, workspaceId) ?: return@mapNotNull null
        val signals = signalsFor(record = record, metadata = metadata)
        if (signals.isEmpty()) {
          null
        } else {
          scope to signals
        }
      }
      .groupBy(
        keySelector = { (scope, _) -> scope },
        valueTransform = { (_, signals) -> signals },
      )

    if (groupedSignals.isEmpty()) {
      return InteractionPreferenceMemoryWritePlan()
    }

    val snapshotCandidates = mutableListOf<MemoryCandidate>()
    groupedSignals.forEach { (scope, scopedSignalLists) ->
      var projectedState = projector.project(
        records = existingRecords,
        scope = scope,
        sessionId = sourceSessionId,
        workspaceId = workspaceId,
      ).state
      scopedSignalLists
        .flatten()
        .sortedWith(
          compareBy<InteractionPreferenceSignal> { signal -> signal.occurredAtEpochMs }
            .thenBy { signal -> signal.axis?.name.orEmpty() }
            .thenBy { signal -> signal.preferredAddressStyle?.name.orEmpty() }
            .thenBy { signal -> signal.preferredNaming.orEmpty() },
        )
        .forEach { signal ->
          projectedState = updater.apply(
            state = projectedState,
            signal = signal,
            plasticity = plasticity,
          )
        }
      snapshotCandidates += candidateFactory.interactionPreferenceStateCandidate(
        state = projectedState,
        scope = scope,
        sourceSessionId = sourceSessionId,
        workspaceId = workspaceId.takeIf { scope == MemoryScope.WORKSPACE },
        sourceTaskId = sourceTaskId,
      )
    }

    return InteractionPreferenceMemoryWritePlan(
      stateSnapshotCandidates = snapshotCandidates,
    )
  }

  private fun resolveMemoryScope(
    recordScope: MemoryScope,
    workspaceId: String?,
  ): MemoryScope? = when (recordScope) {
    MemoryScope.USER -> MemoryScope.USER
    MemoryScope.WORKSPACE -> {
      if (workspaceId.isNullOrBlank()) {
        null
      } else {
        MemoryScope.WORKSPACE
      }
    }

    MemoryScope.SESSION -> null
  }

  private fun signalsFor(
    record: MemoryRecord,
    metadata: com.opencray.runtime.memory.ParsedMemoryMetadata,
  ): List<InteractionPreferenceSignal> = when (metadata.preferenceKey) {
    MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE -> relationshipStyleSignals(
      preferenceValue = metadata.preferenceValue,
      occurredAtEpochMs = metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs,
      supportWeight = record.recordVersion.coerceAtLeast(1L).coerceAtMost(PreferenceAxisState.MAX_SUPPORT.toLong()).toInt(),
    )

    MemoryPreferenceKeys.USER_PREFERRED_NAME -> preferredNamingSignals(
      preferenceValue = metadata.preferenceValue,
      occurredAtEpochMs = metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs,
      supportWeight = record.recordVersion.coerceAtLeast(1L).coerceAtMost(PreferredAddressState.MAX_SUPPORT.toLong()).toInt(),
    )

    MemoryPreferenceKeys.USER_ADDRESS_STYLE -> preferredAddressStyleSignals(
      preferenceValue = metadata.preferenceValue,
      occurredAtEpochMs = metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs,
      supportWeight = record.recordVersion.coerceAtLeast(1L).coerceAtMost(PreferredAddressState.MAX_SUPPORT.toLong()).toInt(),
    )

    else -> emptyList()
  }

  private fun relationshipStyleSignals(
    preferenceValue: String?,
    occurredAtEpochMs: Long,
    supportWeight: Int,
  ): List<InteractionPreferenceSignal> = when (preferenceValue?.lowercase()) {
    "warm" -> listOf(
      InteractionPreferenceSignal(
        axis = InteractionPreferenceAxis.WARMTH,
        direction = InteractionPreferenceDirection.HIGHER,
        supportWeight = supportWeight,
        occurredAtEpochMs = occurredAtEpochMs,
      ),
      InteractionPreferenceSignal(
        axis = InteractionPreferenceAxis.FORMALITY,
        direction = InteractionPreferenceDirection.LOWER,
        supportWeight = supportWeight,
        occurredAtEpochMs = occurredAtEpochMs,
      ),
    )

    "serious" -> listOf(
      InteractionPreferenceSignal(
        axis = InteractionPreferenceAxis.WARMTH,
        direction = InteractionPreferenceDirection.LOWER,
        supportWeight = supportWeight,
        occurredAtEpochMs = occurredAtEpochMs,
      ),
      InteractionPreferenceSignal(
        axis = InteractionPreferenceAxis.FORMALITY,
        direction = InteractionPreferenceDirection.HIGHER,
        supportWeight = supportWeight,
        occurredAtEpochMs = occurredAtEpochMs,
      ),
    )

    else -> emptyList()
  }

  private fun preferredNamingSignals(
    preferenceValue: String?,
    occurredAtEpochMs: Long,
    supportWeight: Int,
  ): List<InteractionPreferenceSignal> {
    val normalized = preferenceValue?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
    return listOf(
      InteractionPreferenceSignal(
        preferredNaming = normalized,
        supportWeight = supportWeight,
        occurredAtEpochMs = occurredAtEpochMs,
      ),
    )
  }

  private fun preferredAddressStyleSignals(
    preferenceValue: String?,
    occurredAtEpochMs: Long,
    supportWeight: Int,
  ): List<InteractionPreferenceSignal> {
    val normalized = normalizePreferredAddressStyleValueOrNull(preferenceValue) ?: return emptyList()
    return listOf(
      InteractionPreferenceSignal(
        preferredAddressStyle = PreferredAddressStyle.valueOf(normalized.uppercase()),
        supportWeight = supportWeight,
        occurredAtEpochMs = occurredAtEpochMs,
      ),
    )
  }
}
