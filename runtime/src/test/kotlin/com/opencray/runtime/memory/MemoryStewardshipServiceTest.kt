package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStewardshipServiceTest {
  @Test
  fun planKeepsCandidatesWhenInterpreterIsUnavailable() {
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Unavailable(
          reason = "offline",
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(
        workspaceProjectFactRecord(
          id = "fact-old",
          content = "Project runs on port 3000",
        ),
      ),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planCanDropConflictingCandidatesWithoutExistingRecords() {
    val candidateA = workspaceProjectFactCandidate(
      content = "Project runs on port 3000",
    )
    val candidateB = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          assertTrue(request.activeRecords.isEmpty())
          assertEquals(listOf(0, 1), request.proposedCandidates.map(StewardableMemoryCandidate::index))
          return MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.DROP_CANDIDATE,
                candidateIndex = 0,
              ),
            ),
          )
        }
      },
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidateA, candidateB),
    )

    assertEquals(listOf(candidateB), plan.acceptedCandidates)
    assertEquals(listOf(candidateA), plan.droppedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
  }

  @Test
  fun planCanReviewSingleProjectFactCandidateWhenConfigured() {
    var interpretCallCount = 0
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          interpretCallCount += 1
          assertTrue(request.activeRecords.isEmpty())
          assertEquals(listOf(0), request.proposedCandidates.map(StewardableMemoryCandidate::index))
          return MemoryStewardshipInterpretation.Success(decisions = emptyList())
        }
      },
      candidateOnlyReviewKinds = setOf(MemoryKind.PROJECT_FACT),
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(1, interpretCallCount)
    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planCanReviewSingleDurableInstructionCandidateWhenConfigured() {
    var interpretCallCount = 0
    val candidate = workspaceInstructionCandidate(
      content = "Do not use git reset --hard in this repo",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          interpretCallCount += 1
          assertTrue(request.activeRecords.isEmpty())
          assertEquals(listOf(0), request.proposedCandidates.map(StewardableMemoryCandidate::index))
          assertEquals(MemoryKind.DURABLE_INSTRUCTION, request.proposedCandidates.single().kind)
          assertEquals(MemoryEvidenceSource.USER_INPUT, request.proposedCandidates.single().source)
          assertEquals("session-1", request.proposedCandidates.single().sourceSessionId)
          return MemoryStewardshipInterpretation.Success(decisions = emptyList())
        }
      },
      candidateOnlyReviewKinds = setOf(MemoryKind.DURABLE_INSTRUCTION),
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(1, interpretCallCount)
    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planCanReviewSingleUserPreferenceCandidateWhenConfigured() {
    var interpretCallCount = 0
    val candidate = userPreferredNameCandidate(
      preferredName = "阿澄",
      scope = MemoryScope.USER,
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          interpretCallCount += 1
          assertTrue(request.activeRecords.isEmpty())
          assertEquals(listOf(0), request.proposedCandidates.map(StewardableMemoryCandidate::index))
          assertEquals(MemoryKind.USER_PREFERENCE, request.proposedCandidates.single().kind)
          assertEquals(MemoryEvidenceSource.USER_INPUT, request.proposedCandidates.single().source)
          assertEquals(MemoryPreferenceKeys.USER_PREFERRED_NAME, request.proposedCandidates.single().preferenceKey)
          assertEquals("阿澄", request.proposedCandidates.single().preferenceValue)
          return MemoryStewardshipInterpretation.Success(decisions = emptyList())
        }
      },
      candidateOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(1, interpretCallCount)
    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planSkipsCandidateOnlyReviewWhenThereIsOnlyOneCandidateAndNoRelatedRecord() {
    var interpretCallCount = 0
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          interpretCallCount += 1
          return MemoryStewardshipInterpretation.Success(decisions = emptyList())
        }
      },
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(0, interpretCallCount)
    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planCanDropCandidateAndReaffirmExistingRecord() {
    val existing = workspaceInstructionRecord(
      id = "instruction-1",
      content = "Do not use git reset --hard in this repo",
    )
    val candidate = workspaceInstructionCandidate(
      content = "Please avoid git reset --hard in this repo",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.DROP_CANDIDATE,
              candidateIndex = 0,
            ),
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.REAFFIRM_RECORD,
              recordId = existing.id,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
    assertEquals(listOf(existing.id), plan.reaffirmedRecords.map(MemoryRecord::id))
    assertEquals("5000", plan.reaffirmedRecords.single().extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS])
  }

  @Test
  fun planCanResolveSingleUserPreferenceRecordWithoutCandidateWhenConfigured() {
    val existing = userPreferenceRecord(
      id = "pref-old",
      content = "Preferred user naming is 阿澄",
      extensions = userPreferredNamePreferenceExtensionsForTest(
        preferredName = "阿澄",
        scope = MemoryScope.USER,
      ),
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          assertEquals(listOf("pref-old"), request.activeRecords.map(StewardableMemoryRecord::id))
          assertTrue(request.proposedCandidates.isEmpty())
          return MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.RESOLVE_RECORD,
                recordId = existing.id,
                resolutionReason = MemoryStewardshipResolutionReason.INVALIDATED,
              ),
            ),
          )
        }
      },
      recordOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(
        userInput = "以后不要再叫我阿澄了。",
      ),
      proposedCandidates = emptyList(),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(existing.id), plan.resolvedRecords.map(MemoryRecord::id))
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.droppedCandidates.isEmpty())
    assertEquals("invalidated", plan.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
  }

  @Test
  fun planCanResolveRecordOnlyWhileKeepingPassthroughTaskCommitmentCandidate() {
    val existing = workspaceInstructionRecord(
      id = "instruction-old",
      content = "Do not use git reset --hard in this repo",
    )
    val passthroughCandidate = MemoryCandidate(
      kind = MemoryKind.TASK_COMMITMENT,
      scope = MemoryScope.SESSION,
      status = MemoryStatus.OPEN,
      content = "run the targeted runtime tests",
      source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
      sourceSessionId = "session-1",
      sourceTaskId = "task-1",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          assertEquals(listOf("instruction-old"), request.activeRecords.map(StewardableMemoryRecord::id))
          assertTrue(request.proposedCandidates.isEmpty())
          return MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.RESOLVE_RECORD,
                recordId = existing.id,
                resolutionReason = MemoryStewardshipResolutionReason.OBSOLETE,
              ),
            ),
          )
        }
      },
      recordOnlyReviewKinds = setOf(MemoryKind.DURABLE_INSTRUCTION),
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(
        userInput = "这个仓库现在不用遵守 git reset --hard 这条规则了。",
      ),
      proposedCandidates = listOf(passthroughCandidate),
    )

    assertEquals(listOf(passthroughCandidate), plan.acceptedCandidates)
    assertEquals(listOf(existing.id), plan.resolvedRecords.map(MemoryRecord::id))
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planCanCombineCandidateDrivenAndRecordOnlyMaintenanceInSameTurn() {
    val existingPreference = userPreferenceRecord(
      id = "pref-old",
      content = "Preferred user naming is 阿澄",
      extensions = userPreferredNamePreferenceExtensionsForTest(
        preferredName = "阿澄",
        scope = MemoryScope.USER,
      ),
    )
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          assertEquals(
            setOf("pref-old"),
            request.activeRecords.map(StewardableMemoryRecord::id).toSet(),
          )
          assertEquals(listOf(0), request.proposedCandidates.map(StewardableMemoryCandidate::index))
          return MemoryStewardshipInterpretation.Success(
            decisions = listOf(
              MemoryStewardshipDecision(
                action = MemoryStewardshipAction.RESOLVE_RECORD,
                recordId = existingPreference.id,
                resolutionReason = MemoryStewardshipResolutionReason.INVALIDATED,
              ),
            ),
          )
        }
      },
      recordOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
    )

    val plan = service.plan(
      existingRecords = listOf(existingPreference),
      evidence = turnEvidence(
        userInput = "以后不要再叫我阿澄了。记住项目现在跑在 8000 端口。",
      ),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertEquals(listOf(existingPreference.id), plan.resolvedRecords.map(MemoryRecord::id))
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planCanRefreshExistingProjectFactWithCandidate() {
    val existing = workspaceProjectFactRecord(
      id = "fact-1",
      content = "Project runs on port 8000",
    )
    val candidate = workspaceProjectFactCandidate(
      content = "Current project port is 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
    assertEquals(listOf(existing.id), plan.reaffirmedRecords.map(MemoryRecord::id))
    assertTrue(plan.resolvedRecords.isEmpty())
    assertEquals("5000", plan.reaffirmedRecords.single().extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS])
  }

  @Test
  fun planRejectsRefreshWhenCandidateAddsNewProjectFactDetail() {
    val existing = workspaceProjectFactRecord(
      id = "fact-1",
      content = "Project uses Gradle",
    )
    val candidate = workspaceProjectFactCandidate(
      content = "Project uses the Gradle wrapper from the repo root",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.resolvedRecords.isEmpty())
  }

  @Test
  fun planCanSupersedeExistingProjectFactWithNewCandidate() {
    val existing = workspaceProjectFactRecord(
      id = "fact-old",
      content = "Project runs on port 3000",
    )
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 7_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertEquals(listOf(existing.id), plan.resolvedRecords.map(MemoryRecord::id))
    assertEquals("resolved", plan.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("superseded", plan.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertEquals(
      testStableMemoryRecordId(candidate),
      plan.resolvedRecords.single().extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY],
    )
  }

  @Test
  fun planRejectsUnsafeProjectFactSupersessionAcrossDifferentTopics() {
    val existing = workspaceProjectFactRecord(
      id = "fact-old",
      content = "Project uses the Gradle wrapper from the repo root",
    )
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 7_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planRejectsUnsafePreferenceSupersessionAcrossDifferentPreferenceKeys() {
    val existing = userPreferenceRecord(
      id = "pref-old",
      content = "Preferred user naming is A-Qing",
      extensions = userPreferredNamePreferenceExtensionsForTest(
        preferredName = "A-Qing",
        scope = MemoryScope.USER,
      ),
    )
    val candidate = MemoryCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = MemoryScope.USER,
      status = MemoryStatus.ACTIVE,
      content = "Address the user in a friendly style",
      source = MemoryEvidenceSource.USER_INPUT,
      sourceSessionId = "session-1",
      extensions = userAddressStylePreferenceExtensionsForTest(
        addressStyle = "friendly",
        scope = MemoryScope.USER,
      ),
    )
    val service = MemoryStewardshipService(
      clock = { 9_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
  }

  @Test
  fun planCanFailClosedWhenInterpreterIsUnavailable() {
    val stewardableCandidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val passthroughCandidate = MemoryCandidate(
      kind = MemoryKind.TASK_COMMITMENT,
      scope = MemoryScope.SESSION,
      status = MemoryStatus.OPEN,
      content = "verify the Android smoke tests",
      source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
      sourceSessionId = "session-1",
      sourceTaskId = "task-1",
      ttlMs = 14L * 24L * 60L * 60L * 1000L,
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Unavailable(
          reason = "offline",
        )
      },
      failClosedOnInterpreterUnavailable = true,
    )

    val plan = service.plan(
      existingRecords = listOf(
        workspaceProjectFactRecord(
          id = "fact-old",
          content = "Project runs on port 3000",
        ),
      ),
      evidence = turnEvidence(),
      proposedCandidates = listOf(stewardableCandidate, passthroughCandidate),
    )

    assertEquals(listOf(passthroughCandidate), plan.acceptedCandidates)
    assertEquals(listOf(stewardableCandidate), plan.droppedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
  }

  @Test
  fun planCanFailClosedForSingleConfiguredProjectFactCandidate() {
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Unavailable(
          reason = "offline",
        )
      },
      failClosedOnInterpreterUnavailable = true,
      candidateOnlyReviewKinds = setOf(MemoryKind.PROJECT_FACT),
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
  }

  @Test
  fun planCanFailClosedForSingleConfiguredDurableInstructionCandidate() {
    val candidate = workspaceInstructionCandidate(
      content = "Do not use git reset --hard in this repo",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Unavailable(
          reason = "offline",
        )
      },
      failClosedOnInterpreterUnavailable = true,
      candidateOnlyReviewKinds = setOf(MemoryKind.DURABLE_INSTRUCTION),
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
  }

  @Test
  fun planCanFailClosedForSingleConfiguredUserPreferenceCandidate() {
    val candidate = userPreferredNameCandidate(
      preferredName = "阿澄",
      scope = MemoryScope.USER,
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Unavailable(
          reason = "offline",
        )
      },
      failClosedOnInterpreterUnavailable = true,
      candidateOnlyReviewKinds = setOf(MemoryKind.USER_PREFERENCE),
    )

    val plan = service.plan(
      existingRecords = emptyList(),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertTrue(plan.acceptedCandidates.isEmpty())
    assertEquals(listOf(candidate), plan.droppedCandidates)
  }

  @Test
  fun planProvidesRecordRecencyMetadataToInterpreter() {
    val existing = workspaceProjectFactRecord(
      id = "fact-old",
      content = "Project runs on port 3000",
    )
    val candidate = workspaceProjectFactCandidate(
      content = "Project runs on port 8000",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation {
          assertEquals(1, request.activeRecords.size)
          val activeRecord = request.activeRecords.single()
          assertEquals("fact-old", activeRecord.id)
          assertEquals(MemoryEvidenceSource.USER_INPUT, activeRecord.source)
          assertEquals(2_000L, activeRecord.updatedAtEpochMs)
          assertEquals(1_000L, activeRecord.lastConfirmedAtEpochMs)
          return MemoryStewardshipInterpretation.Success(decisions = emptyList())
        }
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.resolvedRecords.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.droppedCandidates.isEmpty())
  }

  @Test
  fun planRejectsRefreshForChangedUserPreferenceValue() {
    val existing = userPreferenceRecord(
      id = "pref-old",
      content = "Preferred user naming is 阿澄",
      extensions = userPreferredNamePreferenceExtensionsForTest(
        preferredName = "阿澄",
        scope = MemoryScope.USER,
      ),
    )
    val candidate = MemoryCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = MemoryScope.USER,
      status = MemoryStatus.ACTIVE,
      content = "Preferred user naming is 小澄",
      source = MemoryEvidenceSource.USER_INPUT,
      sourceSessionId = "session-1",
      extensions = userPreferredNamePreferenceExtensionsForTest(
        preferredName = "小澄",
        scope = MemoryScope.USER,
      ),
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.resolvedRecords.isEmpty())
  }

  @Test
  fun planRejectsUnsafeDurableInstructionRefreshAcrossDifferentTopics() {
    val existing = workspaceInstructionRecord(
      id = "instruction-old",
      content = "Do not use git reset --hard in this repo",
    )
    val candidate = workspaceInstructionCandidate(
      content = "Use PowerShell commands in this repo",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.resolvedRecords.isEmpty())
  }

  @Test
  fun planRejectsRefreshWhenDurableInstructionChangesCommandValue() {
    val existing = workspaceInstructionRecord(
      id = "instruction-old",
      content = "Use PowerShell commands in this repo",
    )
    val candidate = workspaceInstructionCandidate(
      content = "Use Bash commands in this repo",
    )
    val service = MemoryStewardshipService(
      clock = { 5_000L },
      interpreter = object : MemoryStewardshipInterpreter {
        override fun interpret(
          request: MemoryStewardshipRequest,
        ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Success(
          decisions = listOf(
            MemoryStewardshipDecision(
              action = MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
              recordId = existing.id,
              candidateIndex = 0,
            ),
          ),
        )
      },
    )

    val plan = service.plan(
      existingRecords = listOf(existing),
      evidence = turnEvidence(),
      proposedCandidates = listOf(candidate),
    )

    assertEquals(listOf(candidate), plan.acceptedCandidates)
    assertTrue(plan.droppedCandidates.isEmpty())
    assertTrue(plan.reaffirmedRecords.isEmpty())
    assertTrue(plan.resolvedRecords.isEmpty())
  }

  private fun turnEvidence(
    userInput: String = "Remember the current project port and repo rules.",
    assistantOutput: String? = null,
    toolObservations: List<String> = emptyList(),
  ): MemoryTurnEvidence = MemoryTurnEvidence(
    sessionId = "session-1",
    workspaceId = "workspace-main",
    userInput = userInput,
    assistantOutput = assistantOutput,
    toolObservations = toolObservations,
  )

  private fun workspaceProjectFactCandidate(
    content: String,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.PROJECT_FACT,
    scope = MemoryScope.WORKSPACE,
    status = MemoryStatus.ACTIVE,
    content = content,
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "session-1",
    workspaceId = "workspace-main",
    ttlMs = 90L * 24L * 60L * 60L * 1000L,
  )

  private fun workspaceInstructionCandidate(
    content: String,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.DURABLE_INSTRUCTION,
    scope = MemoryScope.WORKSPACE,
    status = MemoryStatus.ACTIVE,
    content = content,
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "session-1",
    workspaceId = "workspace-main",
  )

  private fun userPreferredNameCandidate(
    preferredName: String,
    scope: MemoryScope,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.USER_PREFERENCE,
    scope = scope,
    status = MemoryStatus.ACTIVE,
    content = "Preferred user naming is $preferredName",
    source = MemoryEvidenceSource.USER_INPUT,
    sourceSessionId = "session-1",
    workspaceId = "workspace-main",
    extensions = userPreferredNamePreferenceExtensionsForTest(
      preferredName = preferredName,
      scope = scope,
    ),
  )

  private fun workspaceProjectFactRecord(
    id: String,
    content: String,
  ): MemoryRecord = baseRecord(
    id = id,
    content = content,
    tags = listOf("kind:project_fact", "scope:workspace", "status:active"),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "project_fact",
      MemoryRecordExtensionKeys.SCOPE to "workspace",
      MemoryRecordExtensionKeys.STATUS to "active",
      MemoryRecordExtensionKeys.SOURCE to "user_input",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-0",
      MemoryRecordExtensionKeys.WORKSPACE_ID to "workspace-main",
      MemoryRecordExtensionKeys.TTL_MS to (90L * 24L * 60L * 60L * 1000L).toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
    ),
  )

  private fun workspaceInstructionRecord(
    id: String,
    content: String,
  ): MemoryRecord = baseRecord(
    id = id,
    content = content,
    tags = listOf("kind:durable_instruction", "scope:workspace", "status:active"),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "durable_instruction",
      MemoryRecordExtensionKeys.SCOPE to "workspace",
      MemoryRecordExtensionKeys.STATUS to "active",
      MemoryRecordExtensionKeys.SOURCE to "user_input",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-0",
      MemoryRecordExtensionKeys.WORKSPACE_ID to "workspace-main",
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
    ),
  )

  private fun userPreferenceRecord(
    id: String,
    content: String,
    extensions: Map<String, String>,
  ): MemoryRecord = baseRecord(
      id = id,
      content = content,
      tags = listOf("kind:user_preference", "scope:user", "status:active"),
    extensions = linkedMapOf(
      MemoryRecordExtensionKeys.KIND to "user_preference",
      MemoryRecordExtensionKeys.SCOPE to "user",
      MemoryRecordExtensionKeys.STATUS to "active",
      MemoryRecordExtensionKeys.SOURCE to "user_input",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-0",
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
    ).apply {
      putAll(extensions)
    },
  )

  private fun baseRecord(
    id: String,
    content: String,
    tags: List<String>,
    extensions: Map<String, String>,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 2_000L,
    tags = tags,
    extensions = extensions,
  )

  private fun userPreferredNamePreferenceExtensionsForTest(
    preferredName: String,
    scope: MemoryScope,
  ): Map<String, String> = linkedMapOf(
    MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.USER_PREFERRED_NAME,
    MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferredName,
    MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityForTest(scope),
    MemorySoulExtensionKeys.PREFERRED_NAMING to preferredName,
  )

  private fun userAddressStylePreferenceExtensionsForTest(
    addressStyle: String,
    scope: MemoryScope,
  ): Map<String, String> = linkedMapOf(
    MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.USER_ADDRESS_STYLE,
    MemoryRecordExtensionKeys.PREFERENCE_VALUE to addressStyle,
    MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityForTest(scope),
    MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE to addressStyle,
  )

  private fun preferenceTemporalityForTest(scope: MemoryScope): String = when (scope) {
    MemoryScope.SESSION -> MemoryPreferenceKeys.TEMPORALITY_SESSION
    MemoryScope.USER,
    MemoryScope.WORKSPACE,
    -> MemoryPreferenceKeys.TEMPORALITY_DURABLE
  }

  private fun testStableMemoryRecordId(candidate: MemoryCandidate): String {
    val scopeIdentity = when (candidate.scope) {
      MemoryScope.USER -> "user"
      MemoryScope.WORKSPACE ->
        "workspace:${candidate.workspaceId?.takeIf(String::isNotBlank) ?: "default-workspace"}"
      MemoryScope.SESSION -> "session:${candidate.sourceSessionId}"
    }
    val canonical = if (
      candidate.kind == MemoryKind.USER_PREFERENCE &&
      !candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY].isNullOrBlank() &&
      !candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE].isNullOrBlank()
    ) {
      "pref|${candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY]}|" +
        candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE]!!.lowercase(java.util.Locale.US)
    } else {
      candidate.content.lowercase(java.util.Locale.US)
    }
    val digestSource = "${candidate.kind.name.lowercase(java.util.Locale.US)}|$scopeIdentity|$canonical"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(digestSource.toByteArray(Charsets.UTF_8))
    return "mem-${digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)}"
  }
}
