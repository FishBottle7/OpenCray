package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryOperatorAction
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySearchService
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.soul.InteractionPreferenceDebugProjection
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.PreferredAddressState
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.RelationshipStateDebugProjection
import com.opencray.runtime.soul.RuntimeSoulProfileSeedFactory
import com.opencray.runtime.soul.SoulGateCheck
import com.opencray.runtime.soul.SoulProfile
import com.opencray.runtime.soul.SoulProfileResolver
import java.nio.file.Path
import java.util.Locale

internal class ProjectionOnlyChatDebugProjector(
  private val personalizationLocalStore: PersonalizationLocalStore?,
  private val workspaceRootProvider: (() -> Path?)? = null,
  private val workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  private val soulProfileResolver: SoulProfileResolver = SoulProfileResolver(),
  private val runtimeSoulProfileSeedFactory: RuntimeSoulProfileSeedFactory = RuntimeSoulProfileSeedFactory(),
  private val memoryBackedSoulProfileResolver: MemoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun loadMemoryDebugSnapshot(
    sessionId: String,
  ): Map<String, Any?> {
    val workspaceId = currentWorkspaceId()
    val observedAtEpochMs = clock()
    val records = personalizationLocalStore
      ?.listMemoryRecords()
      .orEmpty()
      .sortedWith(
        compareByDescending<MemoryRecord> { record -> record.updatedAtEpochMs }
          .thenBy { record -> record.id },
      )
    return mapOf(
      "sessionId" to sessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "records" to records.map { record ->
        memoryDebugRecordToMap(record = record, observedAtEpochMs = observedAtEpochMs)
      },
    )
  }

  fun searchMemoryDebug(
    sessionId: String,
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> {
    val workspaceId = currentWorkspaceId()
    val observedAtEpochMs = clock()
    val response = MemorySearchService().search(
      context = currentMemoryToolContext(
        sessionId = sessionId,
        workspaceId = workspaceId,
      ),
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    return mapOf(
      "sessionId" to sessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "query" to query,
      "queryTerms" to response.queryTerms,
      "corpusFileCount" to response.corpusFileCount,
      "results" to response.matches.map { match ->
        buildMap {
          put("recordId", match.recordId)
          put("path", match.path)
          put("startLine", match.startLine)
          put("endLine", match.endLine)
          put("score", match.score)
          if (match.matchedTerms.isNotEmpty()) {
            put("matchedTerms", match.matchedTerms)
          }
          put("kind", match.kind.name.lowercase(Locale.US))
          put("scope", match.scope.name.lowercase(Locale.US))
          put("status", match.status.name.lowercase(Locale.US))
          put("snippet", match.snippet)
        }
      },
    )
  }

  fun getMemoryDebugSlice(
    sessionId: String,
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> {
    val workspaceId = currentWorkspaceId()
    val observedAtEpochMs = clock()
    val response = MemorySearchService().get(
      context = currentMemoryToolContext(
        sessionId = sessionId,
        workspaceId = workspaceId,
      ),
      path = path,
      from = fromLine,
      lines = lines,
    )
    return mapOf(
      "sessionId" to sessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "path" to response.path,
      "text" to response.text,
      "startLine" to response.startLine,
      "endLine" to response.endLine,
      "totalLineCount" to response.totalLineCount,
      "recordIds" to response.recordIds,
    )
  }

  fun loadMemoryDebugLinksSnapshot(
    activeSessionId: String,
    allRuns: List<AgentRunSnapshot>,
    runtimeEventsBySession: Map<String, List<OpenCrayAgentRunEvent>>,
  ): Map<String, Any?> {
    val workspaceId = currentWorkspaceId()
    val observedAtEpochMs = clock()
    val records = personalizationLocalStore
      ?.listMemoryRecords()
      .orEmpty()
      .sortedWith(
        compareByDescending<MemoryRecord> { record -> record.updatedAtEpochMs }
          .thenBy { record -> record.id },
      )
    val runsByTaskId = allRuns.associateBy(AgentRunSnapshot::taskId)
    val runsById = allRuns.associateBy(AgentRunSnapshot::runId)
    val promptRecallsByRecordId = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()
    val toolRetrievalsByRecordId = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()
    val maintenanceByRecordId = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()

    personalizationLocalStore
      ?.listMemoryDebugActionAudits()
      .orEmpty()
      .forEach { audit ->
        rememberMemoryDebugActionAudit(
          target = maintenanceByRecordId,
          audit = audit,
        )
      }

    allRuns.forEach { run ->
      parseSelectedMemoryTrace(run.resultMetadata["contextMemorySelectedSummary"].orEmpty())
        .forEach memorySelection@{ selected ->
          val recordId = selected["id"] as? String ?: return@memorySelection
          rememberDebugLink(
            target = promptRecallsByRecordId,
            recordId = recordId,
            uniqueKey = "prompt:${run.runId}:$recordId",
            payload = buildMap {
              put("occurredAtEpochMs", run.updatedAtEpochMs)
              put("run", debugRunLinkToMap(run))
              selected["score"]?.let { score -> put("score", score) }
              val matchedTerms = selected["matchedTerms"] as? List<*>
              if (!matchedTerms.isNullOrEmpty()) {
                put("matchedTerms", matchedTerms)
              }
            },
          )
        }
      splitDebugCsv(run.resultMetadata["contextMemoryFlushWrittenRecordIds"])
        .forEach { recordId ->
          rememberDebugLink(
            target = maintenanceByRecordId,
            recordId = recordId,
            uniqueKey = "flush:${run.runId}:$recordId",
            payload = buildMap {
              put("action", "flush_written")
              put("occurredAtEpochMs", run.updatedAtEpochMs)
              put("run", debugRunLinkToMap(run))
            },
          )
        }
      (run.lastEvent as? OpenCrayMemoryWriteEvent)
        ?.let { event ->
          rememberMemoryWriteActions(
            target = maintenanceByRecordId,
            runLink = debugRunLinkToMap(run),
            event = event,
          )
        }
      (run.lastEvent as? OpenCrayMemoryRetrievalEvent)
        ?.let { event ->
          rememberMemoryRetrievalLinks(
            target = toolRetrievalsByRecordId,
            run = run,
            event = event,
          )
        }
    }

    runtimeEventsBySession.forEach { (sessionId, events) ->
      events.forEach runtimeEvent@{ event ->
        if (isDebugOnlyRuntimeEvent(event)) {
          return@runtimeEvent
        }
        when (event) {
          is OpenCrayMemoryWriteEvent -> {
            rememberMemoryWriteActions(
              target = maintenanceByRecordId,
              runLink = runsById[event.runId]
                ?.let(::debugRunLinkToMap)
                ?: debugRunLinkToMap(
                  sessionId = sessionId,
                  runId = event.runId,
                  taskId = event.taskId,
                  acceptedAtEpochMs = event.emittedAtEpochMs,
                  updatedAtEpochMs = event.emittedAtEpochMs,
                  lifecycleState = QueueTaskLifecycleState.COMPLETED.name.lowercase(Locale.US),
                  executionStatus = ExecutionStatus.SUCCESS.name.lowercase(Locale.US),
                ),
              event = event,
            )
          }

          is OpenCrayMemoryRetrievalEvent -> {
            val run = runsById[event.runId] ?: return@runtimeEvent
            rememberMemoryRetrievalLinks(
              target = toolRetrievalsByRecordId,
              run = run,
              event = event,
            )
          }

          else -> Unit
        }
      }
    }

    return mapOf(
      "sessionId" to activeSessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "records" to records.map { record ->
        val metadata = debugMemoryMetadata(record)
        mapOf(
          "recordId" to record.id,
          "sourceSessionId" to metadata?.sourceSessionId.orEmpty(),
          "sourceTaskId" to metadata?.sourceTaskId.orEmpty(),
          "sourceRun" to metadata
            ?.sourceTaskId
            ?.let(runsByTaskId::get)
            ?.let(::debugRunLinkToMap),
          "promptRecalls" to finalizeDebugLinks(promptRecallsByRecordId[record.id]),
          "toolRetrievals" to finalizeDebugLinks(toolRetrievalsByRecordId[record.id]),
          "maintenanceActions" to finalizeDebugLinks(maintenanceByRecordId[record.id]),
        )
      },
    )
  }

  fun loadSoulDebugSnapshot(
    sessionId: String,
  ): Map<String, Any?> {
    val workspaceId = currentWorkspaceId()
    val workspaceRoot = currentWorkspaceRoot()
    val observedAtEpochMs = clock()
    val storedSoulDocument = workspaceSoulProfileStore.loadSoulDocument(workspaceRoot)
    val storedSoulProfile = storedSoulDocument?.profile
    val baseRuntimeSoul = storedSoulProfile?.toRuntimeSoulProfile()
    val baseResolvedSoul = resolveSoulProfile(baseRuntimeSoul)
    val allMemoryRecords = personalizationLocalStore?.listMemoryRecords().orEmpty()
    val overlayRecords = applicableSoulOverlayRecords(
      records = allMemoryRecords,
      sessionId = sessionId,
      workspaceId = workspaceId,
    )
    val overlayDebug = memoryBackedSoulProfileResolver.inspectOverlay(
      baseProfile = baseRuntimeSoul,
      records = allMemoryRecords,
      sessionId = sessionId,
      workspaceId = workspaceId,
    )
    val effectiveRuntimeSoul = overlayDebug.effectiveProfile
    val effectiveResolvedSoul = resolveSoulProfile(effectiveRuntimeSoul)
    return mapOf(
      "sessionId" to sessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "storedSoul" to storedSoulProfile?.let { profile ->
        storedSoulProfileToMap(
          profile = profile,
          document = storedSoulDocument,
        )
      },
      "baseSoul" to soulProfileToMap(
        resolvedProfile = baseResolvedSoul,
        runtimeProfile = baseRuntimeSoul,
      ),
      "effectiveSoul" to soulProfileToMap(
        resolvedProfile = effectiveResolvedSoul,
        runtimeProfile = effectiveRuntimeSoul,
      ),
      "overlayRecords" to overlayRecords
        .sortedWith(soulOverlayDisplayComparator())
        .map { record ->
          memoryDebugRecordToMap(record = record, observedAtEpochMs = observedAtEpochMs)
        },
      "interactionPreferenceDebug" to overlayDebug.interactionPreferenceDebug
        ?.let(::interactionPreferenceDebugToMap),
      "relationshipStateDebug" to overlayDebug.relationshipStateDebug
        ?.let(::relationshipStateDebugToMap),
      "fieldSources" to soulFieldSources(
        baseRuntimeSoul = baseRuntimeSoul,
        effectiveRuntimeSoul = effectiveRuntimeSoul,
        baseResolvedSoul = baseResolvedSoul,
        effectiveResolvedSoul = effectiveResolvedSoul,
        overlayRecords = overlayRecords,
        interactionPreferenceDebug = overlayDebug.interactionPreferenceDebug,
        relationshipStateDebug = overlayDebug.relationshipStateDebug,
      ),
    )
  }

  private fun currentWorkspaceId(): String? = currentWorkspaceRoot()?.let { workspaceRoot ->
    AppWorkspaceIdentity.fromRoots(setOf(workspaceRoot))
  }

  private fun currentWorkspaceRoot(): Path? = runCatching {
    workspaceRootProvider?.invoke()
  }.getOrNull()

  private fun resolveSoulProfile(runtimeProfile: RuntimeSoulProfile?): SoulProfile? =
    soulProfileResolver.resolve(runtimeSoulProfileSeedFactory.create(runtimeProfile))

  private fun applicableSoulOverlayRecords(
    records: List<MemoryRecord>,
    sessionId: String,
    workspaceId: String?,
  ): List<MemoryRecord> = records.filter { record ->
    val metadata = debugMemoryMetadata(record) ?: return@filter false
    metadata.kind == DEBUG_MEMORY_KIND_USER_PREFERENCE &&
      metadata.status == DEBUG_MEMORY_STATUS_ACTIVE &&
      metadata.preferenceKey in SUPPORTED_SOUL_PREFERENCE_KEYS &&
      !metadata.preferenceValue.isNullOrBlank() &&
      debugSoulScopeMatches(
        scope = metadata.scope,
        sourceSessionId = metadata.sourceSessionId,
        recordWorkspaceId = metadata.workspaceId,
        requestSessionId = sessionId,
        requestWorkspaceId = workspaceId,
      )
  }

  private fun debugSoulScopeMatches(
    scope: String,
    sourceSessionId: String?,
    recordWorkspaceId: String?,
    requestSessionId: String,
    requestWorkspaceId: String?,
  ): Boolean = when (scope) {
    DEBUG_MEMORY_SCOPE_USER -> true
    DEBUG_MEMORY_SCOPE_SESSION -> sourceSessionId == requestSessionId
    DEBUG_MEMORY_SCOPE_WORKSPACE -> {
      val normalizedRecordWorkspaceId = recordWorkspaceId?.takeIf(String::isNotBlank)
      val normalizedRequestWorkspaceId = requestWorkspaceId?.takeIf(String::isNotBlank)
      when {
        normalizedRecordWorkspaceId == null && normalizedRequestWorkspaceId == null -> true
        normalizedRecordWorkspaceId != null && normalizedRequestWorkspaceId != null ->
          normalizedRecordWorkspaceId == normalizedRequestWorkspaceId

        else -> false
      }
    }

    else -> false
  }

  private fun storedSoulProfileToMap(
    profile: WorkspaceSoulProfile,
    document: WorkspaceSoulDocument?,
  ): Map<String, Any?> = buildMap {
    document?.relativePath?.let { relativePath ->
      put("relativePath", relativePath)
    }
    profile.presetName
      .takeIf(String::isNotBlank)
      ?.let { put("presetName", it) }
    profile.customLabel
      .takeIf(String::isNotBlank)
      ?.let { put("displayName", it) }
    profile.customGuidance
      .takeIf(String::isNotBlank)
      ?.let { put("customGuidance", it) }
    if (profile.extensions.isNotEmpty()) {
      put("extensions", profile.extensions.toSortedMap())
    }
  }

  private fun soulProfileToMap(
    resolvedProfile: SoulProfile?,
    runtimeProfile: RuntimeSoulProfile?,
  ): Map<String, Any?>? {
    if (resolvedProfile == null && runtimeProfile == null) {
      return null
    }
    return buildMap {
      (runtimeProfile?.presetName ?: resolvedProfile?.presetName)
        ?.takeIf(String::isNotBlank)
        ?.let { put("presetName", it) }
      (runtimeProfile?.displayName ?: resolvedProfile?.displayName)
        ?.takeIf(String::isNotBlank)
        ?.let { put("displayName", it) }
      (runtimeProfile?.voice ?: resolvedProfile?.voice)
        ?.takeIf(String::isNotBlank)
        ?.let { put("voice", it) }
      resolvedProfile?.preferredNaming
        ?.takeIf(String::isNotBlank)
        ?.let { put("preferredNaming", it) }
      resolvedProfile?.preferredAddressStyle?.name?.lowercase(Locale.US)
        ?.let { put("preferredAddressStyle", it) }
      resolvedProfile?.warmthPreferenceOffset?.let { put("warmthPreferenceOffset", it.toString()) }
      resolvedProfile?.formalityPreferenceOffset?.let { put("formalityPreferenceOffset", it.toString()) }
      resolvedProfile?.initiativePreferenceOffset?.let { put("initiativePreferenceOffset", it.toString()) }
      resolvedProfile?.playfulnessPreferenceOffset?.let { put("playfulnessPreferenceOffset", it.toString()) }
      resolvedProfile?.reassurancePreferenceOffset?.let { put("reassurancePreferenceOffset", it.toString()) }
      resolvedProfile?.intimacyPermissionBand?.name?.lowercase(Locale.US)
        ?.let { put("intimacyPermissionBand", it) }
      resolvedProfile?.playfulnessPermissionBand?.name?.lowercase(Locale.US)
        ?.let { put("playfulnessPermissionBand", it) }
      resolvedProfile?.supportiveReassuranceAllowed
        ?.let { put("supportiveReassuranceAllowed", it.toString()) }
      resolvedProfile?.proactiveRelationalCheckInAllowed
        ?.let { put("proactiveRelationalCheckInAllowed", it.toString()) }
      resolvedProfile?.lightPlayfulnessAllowed
        ?.let { put("lightPlayfulnessAllowed", it.toString()) }
      resolvedProfile?.playfulTeasingAllowed
        ?.let { put("playfulTeasingAllowed", it.toString()) }
      resolvedProfile?.highIntimacyBehaviorAllowed
        ?.let { put("highIntimacyBehaviorAllowed", it.toString()) }
      resolvedProfile?.playfulAffectionAllowed
        ?.let { put("playfulAffectionAllowed", it.toString()) }
      (runtimeProfile?.customGuidance ?: resolvedProfile?.customGuidance)
        ?.takeIf(String::isNotBlank)
        ?.let { put("customGuidance", it) }
      resolvedProfile?.tone?.name?.lowercase(Locale.US)?.let { put("tone", it) }
      resolvedProfile?.verbosity?.name?.lowercase(Locale.US)?.let { put("verbosity", it) }
      resolvedProfile?.userRelationshipStyle?.name?.lowercase(Locale.US)
        ?.let { put("userRelationshipStyle", it) }
      resolvedProfile?.riskTolerance?.name?.lowercase(Locale.US)
        ?.let { put("riskTolerance", it) }
      resolvedProfile?.toolUseBias?.name?.lowercase(Locale.US)
        ?.let { put("toolUseBias", it) }
      if (!resolvedProfile?.escalationRules.isNullOrEmpty()) {
        put("escalationRules", resolvedProfile?.escalationRules.orEmpty())
      }
      if (!resolvedProfile?.forbiddenBehaviors.isNullOrEmpty()) {
        put("forbiddenBehaviors", resolvedProfile?.forbiddenBehaviors.orEmpty())
      }
      if (!resolvedProfile?.collaborationPreferences.isNullOrEmpty()) {
        put("collaborationPreferences", resolvedProfile?.collaborationPreferences.orEmpty())
      }
      if (!runtimeProfile?.extensions.isNullOrEmpty()) {
        put("extensions", runtimeProfile?.extensions.orEmpty().toSortedMap())
      }
    }
  }

  private fun interactionPreferenceDebugToMap(
    projection: InteractionPreferenceDebugProjection,
  ): Map<String, Any?> = buildMap {
    put("scope", projection.sourceScope.name.lowercase(Locale.US))
    projection.snapshotRecordId?.takeIf(String::isNotBlank)?.let { put("snapshotRecordId", it) }
    projection.preferredNaming?.takeIf(String::isNotBlank)?.let { put("preferredNaming", it) }
    projection.preferredAddressStyle?.name?.lowercase(Locale.US)
      ?.let { put("preferredAddressStyle", it) }
    projection.derivedRelationshipStyle?.takeIf(String::isNotBlank)
      ?.let { put("derivedRelationshipStyle", it) }
    put("state", interactionPreferenceStateToMap(projection.state))
  }

  private fun interactionPreferenceStateToMap(
    state: InteractionPreferenceState,
  ): Map<String, Any?> = buildMap {
    put("warmth", preferenceAxisStateToMap(state.warmth))
    put("formality", preferenceAxisStateToMap(state.formality))
    put("initiative", preferenceAxisStateToMap(state.initiative))
    put("playfulness", preferenceAxisStateToMap(state.playfulness))
    put("reassurance", preferenceAxisStateToMap(state.reassurance))
    put("addressStyle", preferredAddressStateToMap(state.addressStyle))
    state.preferredNaming?.takeIf(String::isNotBlank)?.let { put("preferredNaming", it) }
    put("preferredNamingSupport", state.preferredNamingSupport)
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun preferenceAxisStateToMap(
    state: PreferenceAxisState,
  ): Map<String, Any?> = buildMap {
    put("offset", state.offset)
    put("higherSupport", state.higherSupport)
    put("lowerSupport", state.lowerSupport)
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun preferredAddressStateToMap(
    state: PreferredAddressState,
  ): Map<String, Any?> = buildMap {
    put("selectedStyle", state.selectedStyle.name.lowercase(Locale.US))
    put("neutralSupport", state.neutralSupport)
    put("friendlySupport", state.friendlySupport)
    put("intimateSupport", state.intimateSupport)
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun relationshipStateDebugToMap(
    projection: RelationshipStateDebugProjection,
  ): Map<String, Any?> = buildMap {
    put("scope", projection.sourceScope.name.lowercase(Locale.US))
    projection.snapshotRecordId?.takeIf(String::isNotBlank)?.let { put("snapshotRecordId", it) }
    if (projection.appliedEventRecordIds.isNotEmpty()) {
      put("appliedEventRecordIds", projection.appliedEventRecordIds)
    }
    put("state", relationshipStateToMap(projection.state))
    put("recentNegativeGuardActive", projection.recentNegativeGuardActive)
    put("supportiveStyleUnlocked", projection.supportiveStyleUnlocked)
    put("supportiveStyleChecks", projection.supportiveStyleChecks.map(::soulGateCheckToMap))
    put("warmToneUnlocked", projection.warmToneUnlocked)
    put("warmToneChecks", projection.warmToneChecks.map(::soulGateCheckToMap))
    projection.derivedAddressStyle?.name?.lowercase(Locale.US)
      ?.let { put("derivedAddressStyle", it) }
    put("friendlyAddressChecks", projection.friendlyAddressChecks.map(::soulGateCheckToMap))
    put("intimateAddressChecks", projection.intimateAddressChecks.map(::soulGateCheckToMap))
    put("intimacyPermissionBand", projection.intimacyPermissionBand.name.lowercase(Locale.US))
    put("playfulnessPermissionBand", projection.playfulnessPermissionBand.name.lowercase(Locale.US))
    put("supportiveReassuranceAllowed", projection.supportiveReassuranceAllowed)
    put("supportiveReassuranceChecks", projection.supportiveReassuranceChecks.map(::soulGateCheckToMap))
    put("proactiveRelationalCheckInAllowed", projection.proactiveRelationalCheckInAllowed)
    put(
      "proactiveRelationalCheckInChecks",
      projection.proactiveRelationalCheckInChecks.map(::soulGateCheckToMap),
    )
    put("lightPlayfulnessAllowed", projection.lightPlayfulnessAllowed)
    put("lightPlayfulnessChecks", projection.lightPlayfulnessChecks.map(::soulGateCheckToMap))
    put("playfulTeasingAllowed", projection.playfulTeasingAllowed)
    put("playfulTeasingChecks", projection.playfulTeasingChecks.map(::soulGateCheckToMap))
    put("highIntimacyBehaviorAllowed", projection.highIntimacyBehaviorAllowed)
    put("highIntimacyChecks", projection.highIntimacyChecks.map(::soulGateCheckToMap))
    put("playfulAffectionAllowed", projection.playfulAffectionAllowed)
    put("playfulAffectionChecks", projection.playfulAffectionChecks.map(::soulGateCheckToMap))
  }

  private fun relationshipStateToMap(
    state: RelationshipState,
  ): Map<String, Any?> = buildMap {
    put("familiarity", state.familiarity)
    put("trust", state.trust)
    put("safety", state.safety)
    put("intimacyPermission", state.intimacyPermission)
    put("playfulnessPermission", state.playfulnessPermission)
    put("affectionTendency", state.affectionTendency)
    put("reciprocity", state.reciprocity)
    state.lastPositiveEventAtEpochMs?.let { put("lastPositiveEventAtEpochMs", it) }
    state.lastNegativeEventAtEpochMs?.let { put("lastNegativeEventAtEpochMs", it) }
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun soulGateCheckToMap(
    check: SoulGateCheck,
  ): Map<String, Any?> = buildMap {
    put("key", check.key)
    put("passed", check.passed)
    check.currentValue?.let { put("currentValue", it) }
    check.threshold?.let { put("threshold", it) }
    check.actualBoolean?.let { put("actualBoolean", it) }
    check.expectedBoolean?.let { put("expectedBoolean", it) }
  }

  private fun soulFieldSources(
    baseRuntimeSoul: RuntimeSoulProfile?,
    effectiveRuntimeSoul: RuntimeSoulProfile?,
    baseResolvedSoul: SoulProfile?,
    effectiveResolvedSoul: SoulProfile?,
    overlayRecords: List<MemoryRecord>,
    interactionPreferenceDebug: InteractionPreferenceDebugProjection?,
    relationshipStateDebug: RelationshipStateDebugProjection?,
  ): List<Map<String, Any?>> {
    val effectiveFieldValues = soulFieldValues(
      resolvedProfile = effectiveResolvedSoul,
      runtimeProfile = effectiveRuntimeSoul,
    )
    if (effectiveFieldValues.isEmpty()) {
      return emptyList()
    }
    val baseFieldValues = soulFieldValues(
      resolvedProfile = baseResolvedSoul,
      runtimeProfile = baseRuntimeSoul,
    )
    val overlayFieldSources = linkedMapOf<String, SoulFieldContribution>()
    overlayRecords
      .mapNotNull { record ->
        val metadata = debugMemoryMetadata(record) ?: return@mapNotNull null
        record to metadata
      }
      .sortedWith(
        compareBy<Pair<MemoryRecord, DebugMemoryMetadata>>(
          { (_, metadata) -> soulScopePriority(metadata.scope) },
          { (record, metadata) -> metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs },
          { (record, _) -> record.id },
        ),
      )
      .forEach { (record, metadata) ->
        overlayFieldContributions(record, metadata).forEach { contribution ->
          overlayFieldSources[contribution.field] = contribution
        }
      }
    val directFieldSources = overlayFieldSources.mapValues { (_, contribution) ->
      ResolvedSoulFieldSource(
        field = contribution.field,
        value = contribution.value,
        sourceType = "memory_overlay",
        sourceLabel = soulOverlaySourceLabel(contribution.metadata.scope),
        recordId = contribution.record.id,
        preferenceKey = contribution.metadata.preferenceKey.orEmpty(),
        sourceScope = contribution.metadata.scope,
        sourceDetail = contribution.metadata.preferenceTemporality?.let { temporality ->
          "${temporality.replaceFirstChar { ch -> ch.uppercaseChar() }} preference"
        }.orEmpty(),
      )
    }
    val interactionFieldSources = interactionPreferenceFieldSources(interactionPreferenceDebug)
    val relationshipFieldSources = relationshipStateFieldSources(relationshipStateDebug)

    return SOUL_FIELD_ORDER.mapNotNull { field ->
      val value = effectiveFieldValues[field] ?: return@mapNotNull null
      val resolvedSource = prioritizedFieldSources(
        field = field,
        directFieldSources = directFieldSources,
        interactionFieldSources = interactionFieldSources,
        relationshipFieldSources = relationshipFieldSources,
      ).firstOrNull { source -> source.value == value }
      if (resolvedSource != null) {
        fieldSourceToMap(resolvedSource)
      } else if (baseFieldValues.containsKey(field)) {
        fieldSourceToMap(
          ResolvedSoulFieldSource(
            field = field,
            value = value,
            sourceType = "stored_soul",
            sourceLabel = if (field == SOUL_FIELD_PRESET_NAME) {
              "stored soul preset"
            } else {
              "stored soul"
            },
          ),
        )
      } else {
        null
      }
    }
  }

  private fun soulFieldValues(
    resolvedProfile: SoulProfile?,
    runtimeProfile: RuntimeSoulProfile?,
  ): Map<String, String> = linkedMapOf<String, String>().apply {
    (runtimeProfile?.presetName ?: resolvedProfile?.presetName)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_PRESET_NAME, it) }
    (runtimeProfile?.displayName ?: resolvedProfile?.displayName)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_DISPLAY_NAME, it) }
    (runtimeProfile?.voice ?: resolvedProfile?.voice)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_VOICE, it) }
    resolvedProfile?.preferredNaming
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_PREFERRED_NAMING, it) }
    resolvedProfile?.preferredAddressStyle?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_PREFERRED_ADDRESS_STYLE, it) }
    resolvedProfile?.warmthPreferenceOffset
      ?.let { put(SOUL_FIELD_WARMTH_PREFERENCE_OFFSET, it.toString()) }
    resolvedProfile?.formalityPreferenceOffset
      ?.let { put(SOUL_FIELD_FORMALITY_PREFERENCE_OFFSET, it.toString()) }
    resolvedProfile?.initiativePreferenceOffset
      ?.let { put(SOUL_FIELD_INITIATIVE_PREFERENCE_OFFSET, it.toString()) }
    resolvedProfile?.playfulnessPreferenceOffset
      ?.let { put(SOUL_FIELD_PLAYFULNESS_PREFERENCE_OFFSET, it.toString()) }
    resolvedProfile?.reassurancePreferenceOffset
      ?.let { put(SOUL_FIELD_REASSURANCE_PREFERENCE_OFFSET, it.toString()) }
    resolvedProfile?.intimacyPermissionBand?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_INTIMACY_PERMISSION_BAND, it) }
    resolvedProfile?.playfulnessPermissionBand?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND, it) }
    resolvedProfile?.supportiveReassuranceAllowed
      ?.let { put(SOUL_FIELD_SUPPORTIVE_REASSURANCE_ALLOWED, it.toString()) }
    resolvedProfile?.proactiveRelationalCheckInAllowed
      ?.let { put(SOUL_FIELD_PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED, it.toString()) }
    resolvedProfile?.lightPlayfulnessAllowed
      ?.let { put(SOUL_FIELD_LIGHT_PLAYFULNESS_ALLOWED, it.toString()) }
    resolvedProfile?.playfulTeasingAllowed
      ?.let { put(SOUL_FIELD_PLAYFUL_TEASING_ALLOWED, it.toString()) }
    resolvedProfile?.highIntimacyBehaviorAllowed
      ?.let { put(SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED, it.toString()) }
    resolvedProfile?.playfulAffectionAllowed
      ?.let { put(SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED, it.toString()) }
    (runtimeProfile?.customGuidance ?: resolvedProfile?.customGuidance)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_CUSTOM_GUIDANCE, it) }
    resolvedProfile?.tone?.name?.lowercase(Locale.US)?.let { put(SOUL_FIELD_TONE, it) }
    resolvedProfile?.verbosity?.name?.lowercase(Locale.US)?.let { put(SOUL_FIELD_VERBOSITY, it) }
    resolvedProfile?.userRelationshipStyle?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_USER_RELATIONSHIP_STYLE, it) }
    resolvedProfile?.riskTolerance?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_RISK_TOLERANCE, it) }
    resolvedProfile?.toolUseBias?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_TOOL_USE_BIAS, it) }
    resolvedProfile?.escalationRules
      ?.takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = " | ")
      ?.let { put(SOUL_FIELD_ESCALATION_RULES, it) }
    resolvedProfile?.forbiddenBehaviors
      ?.takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = " | ")
      ?.let { put(SOUL_FIELD_FORBIDDEN_BEHAVIORS, it) }
    resolvedProfile?.collaborationPreferences
      ?.takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = " | ")
      ?.let { put(SOUL_FIELD_COLLABORATION_PREFERENCES, it) }
  }

  private fun overlayFieldContributions(
    record: MemoryRecord,
    metadata: DebugMemoryMetadata,
  ): List<SoulFieldContribution> {
    val contributions = mutableListOf<SoulFieldContribution>()
    var hasTypedDisplayName = false
    var hasTypedVoice = false
    var hasTypedTone = false
    var hasTypedVerbosity = false
    var hasTypedPreferredNaming = false
    var hasTypedPreferredAddressStyle = false

    fun addScalar(field: String, raw: String?) {
      val normalized = normalizeDebugSoulScalarOrNull(raw) ?: return
      contributions += SoulFieldContribution(
        field = field,
        value = normalized,
        record = record,
        metadata = metadata,
      )
      when (field) {
        SOUL_FIELD_DISPLAY_NAME -> hasTypedDisplayName = true
        SOUL_FIELD_VOICE -> hasTypedVoice = true
        SOUL_FIELD_PREFERRED_NAMING -> hasTypedPreferredNaming = true
      }
    }

    fun addKey(field: String, raw: String?) {
      val normalized = normalizeDebugSoulKeyOrNull(raw) ?: return
      contributions += SoulFieldContribution(
        field = field,
        value = normalized,
        record = record,
        metadata = metadata,
      )
      when (field) {
        SOUL_FIELD_TONE -> hasTypedTone = true
        SOUL_FIELD_VERBOSITY -> hasTypedVerbosity = true
        SOUL_FIELD_PREFERRED_ADDRESS_STYLE -> hasTypedPreferredAddressStyle = true
      }
    }

    addScalar(SOUL_FIELD_DISPLAY_NAME, record.extensions[MemorySoulExtensionKeys.DISPLAY_NAME])
    addScalar(SOUL_FIELD_VOICE, record.extensions[MemorySoulExtensionKeys.VOICE])
    addScalar(SOUL_FIELD_PREFERRED_NAMING, record.extensions[MemorySoulExtensionKeys.PREFERRED_NAMING])
    addKey(SOUL_FIELD_TONE, record.extensions[MemorySoulExtensionKeys.TONE])
    addKey(SOUL_FIELD_VERBOSITY, record.extensions[MemorySoulExtensionKeys.VERBOSITY])
    addKey(
      SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
      record.extensions[MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE],
    )
    addKey(
      SOUL_FIELD_USER_RELATIONSHIP_STYLE,
      record.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE],
    )
    addKey(
      SOUL_FIELD_RISK_TOLERANCE,
      record.extensions[MemorySoulExtensionKeys.RISK_TOLERANCE],
    )
    addKey(SOUL_FIELD_TOOL_USE_BIAS, record.extensions[MemorySoulExtensionKeys.TOOL_USE_BIAS])

    when (metadata.preferenceKey) {
      MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> {
        if (!hasTypedDisplayName) {
          normalizeDebugSoulScalarOrNull(metadata.preferenceValue)?.let { value ->
            contributions += SoulFieldContribution(
              field = SOUL_FIELD_DISPLAY_NAME,
              value = value,
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.AGENT_STYLE_PROFILE -> {
        if (!hasTypedTone) {
          when (normalizeDebugSoulKeyOrNull(metadata.preferenceValue)) {
            "warm" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_TONE,
              value = "warm",
              record = record,
              metadata = metadata,
            )

            "serious" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_TONE,
              value = "steady",
              record = record,
              metadata = metadata,
            )
          }
        }
        if (!hasTypedVoice) {
          when (normalizeDebugSoulKeyOrNull(metadata.preferenceValue)) {
            "warm" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_VOICE,
              value = "warm and gentle",
              record = record,
              metadata = metadata,
            )

            "serious" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_VOICE,
              value = "serious and formal",
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.AGENT_VERBOSITY -> {
        if (!hasTypedVerbosity) {
          when (normalizeDebugSoulKeyOrNull(metadata.preferenceValue)) {
            "terse",
            "balanced",
            "expansive",
            -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_VERBOSITY,
              value = normalizeDebugSoulKeyOrNull(metadata.preferenceValue).orEmpty(),
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.USER_PREFERRED_NAME -> {
        if (!hasTypedPreferredNaming) {
          normalizeDebugSoulScalarOrNull(metadata.preferenceValue)?.let { value ->
            contributions += SoulFieldContribution(
              field = SOUL_FIELD_PREFERRED_NAMING,
              value = value,
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.USER_ADDRESS_STYLE -> {
        if (!hasTypedPreferredAddressStyle) {
          normalizeDebugSoulKeyOrNull(metadata.preferenceValue)?.let { normalized ->
            when (normalized) {
              "neutral",
              "friendly",
              "intimate",
              -> contributions += SoulFieldContribution(
                field = SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
                value = normalized,
                record = record,
                metadata = metadata,
              )
            }
          }
        }
      }
    }

    return contributions
  }

  private fun interactionPreferenceFieldSources(
    projection: InteractionPreferenceDebugProjection?,
  ): Map<String, ResolvedSoulFieldSource> {
    if (projection == null) {
      return emptyMap()
    }
    val sourceLabel = when (projection.sourceScope) {
      MemoryScope.WORKSPACE -> "workspace interaction preference"
      MemoryScope.SESSION -> "session interaction preference"
      MemoryScope.USER -> "user interaction preference"
    }
    val sourceScope = projection.sourceScope.name.lowercase(Locale.US)
    val recordId = projection.snapshotRecordId.orEmpty()
    val fields = linkedMapOf<String, ResolvedSoulFieldSource>()
    projection.preferredNaming
      ?.takeIf(String::isNotBlank)
      ?.let { preferredNaming ->
        fields[SOUL_FIELD_PREFERRED_NAMING] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_PREFERRED_NAMING,
          value = preferredNaming,
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference snapshot",
        )
      }
    projection.preferredAddressStyle
      ?.name
      ?.lowercase(Locale.US)
      ?.let { preferredAddressStyle ->
        fields[SOUL_FIELD_PREFERRED_ADDRESS_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
          value = preferredAddressStyle,
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference snapshot",
        )
      }
    fields[SOUL_FIELD_WARMTH_PREFERENCE_OFFSET] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_WARMTH_PREFERENCE_OFFSET,
      value = projection.state.warmth.offset.toString(),
      sourceType = "interaction_preference",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Projected interaction-preference snapshot",
    )
    fields[SOUL_FIELD_FORMALITY_PREFERENCE_OFFSET] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_FORMALITY_PREFERENCE_OFFSET,
      value = projection.state.formality.offset.toString(),
      sourceType = "interaction_preference",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Projected interaction-preference snapshot",
    )
    fields[SOUL_FIELD_INITIATIVE_PREFERENCE_OFFSET] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_INITIATIVE_PREFERENCE_OFFSET,
      value = projection.state.initiative.offset.toString(),
      sourceType = "interaction_preference",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Projected interaction-preference snapshot",
    )
    fields[SOUL_FIELD_PLAYFULNESS_PREFERENCE_OFFSET] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PLAYFULNESS_PREFERENCE_OFFSET,
      value = projection.state.playfulness.offset.toString(),
      sourceType = "interaction_preference",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Projected interaction-preference snapshot",
    )
    fields[SOUL_FIELD_REASSURANCE_PREFERENCE_OFFSET] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_REASSURANCE_PREFERENCE_OFFSET,
      value = projection.state.reassurance.offset.toString(),
      sourceType = "interaction_preference",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Projected interaction-preference snapshot",
    )
    when (projection.derivedRelationshipStyle) {
      "warm" -> {
        fields[SOUL_FIELD_TONE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_TONE,
          value = "warm",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_VOICE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_VOICE,
          value = "warm and gentle",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_USER_RELATIONSHIP_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_USER_RELATIONSHIP_STYLE,
          value = "supportive",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
      }

      "serious" -> {
        fields[SOUL_FIELD_TONE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_TONE,
          value = "steady",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_VOICE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_VOICE,
          value = "serious and formal",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_USER_RELATIONSHIP_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_USER_RELATIONSHIP_STYLE,
          value = "direct",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
      }
    }
    return fields
  }

  private fun relationshipStateFieldSources(
    projection: RelationshipStateDebugProjection?,
  ): Map<String, ResolvedSoulFieldSource> {
    if (projection == null) {
      return emptyMap()
    }
    val sourceLabel = when (projection.sourceScope) {
      MemoryScope.WORKSPACE -> "workspace relationship state"
      MemoryScope.SESSION -> "session relationship state"
      MemoryScope.USER -> "user relationship state"
    }
    val sourceScope = projection.sourceScope.name.lowercase(Locale.US)
    val recordId = projection.snapshotRecordId.orEmpty()
    val eventSuffix = if (projection.appliedEventRecordIds.isNotEmpty()) {
      " + ${projection.appliedEventRecordIds.size} event(s)"
    } else {
      ""
    }
    val fields = linkedMapOf<String, ResolvedSoulFieldSource>()
    if (projection.supportiveStyleUnlocked) {
      fields[SOUL_FIELD_USER_RELATIONSHIP_STYLE] = ResolvedSoulFieldSource(
        field = SOUL_FIELD_USER_RELATIONSHIP_STYLE,
        value = "supportive",
        sourceType = "relationship_state",
        sourceLabel = sourceLabel,
        recordId = recordId,
        sourceScope = sourceScope,
        sourceDetail = "Derived from relationship gates$eventSuffix",
      )
    }
    if (projection.warmToneUnlocked) {
      fields[SOUL_FIELD_TONE] = ResolvedSoulFieldSource(
        field = SOUL_FIELD_TONE,
        value = "warm",
        sourceType = "relationship_state",
        sourceLabel = sourceLabel,
        recordId = recordId,
        sourceScope = sourceScope,
        sourceDetail = "Derived from relationship gates$eventSuffix",
      )
      fields[SOUL_FIELD_VOICE] = ResolvedSoulFieldSource(
        field = SOUL_FIELD_VOICE,
        value = "warm and gentle",
        sourceType = "relationship_state",
        sourceLabel = sourceLabel,
        recordId = recordId,
        sourceScope = sourceScope,
        sourceDetail = "Derived from relationship gates$eventSuffix",
      )
    }
    projection.derivedAddressStyle
      ?.name
      ?.lowercase(Locale.US)
      ?.let { derivedAddressStyle ->
        fields[SOUL_FIELD_PREFERRED_ADDRESS_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
          value = derivedAddressStyle,
          sourceType = "relationship_state",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = if (projection.recentNegativeGuardActive) {
            "Derived from relationship gates with recent-negative guard"
          } else {
            "Derived from relationship gates$eventSuffix"
          },
        )
      }
    fields[SOUL_FIELD_INTIMACY_PERMISSION_BAND] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_INTIMACY_PERMISSION_BAND,
      value = projection.intimacyPermissionBand.name.lowercase(Locale.US),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Derived from relationship-state score band",
    )
    fields[SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND,
      value = projection.playfulnessPermissionBand.name.lowercase(Locale.US),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Derived from relationship-state score band",
    )
    fields[SOUL_FIELD_SUPPORTIVE_REASSURANCE_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_SUPPORTIVE_REASSURANCE_ALLOWED,
      value = projection.supportiveReassuranceAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate constrained by reassurance preference and recent-negative guard"
      } else {
        "Relationship gate derived from familiarity/trust/safety and constrained by reassurance preference"
      },
    )
    fields[SOUL_FIELD_PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED,
      value = projection.proactiveRelationalCheckInAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate constrained by initiative preference and recent-negative guard"
      } else {
        "Relationship gate derived from familiarity/trust/safety/reciprocity and constrained by initiative preference"
      },
    )
    fields[SOUL_FIELD_LIGHT_PLAYFULNESS_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_LIGHT_PLAYFULNESS_ALLOWED,
      value = projection.lightPlayfulnessAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate constrained by playfulness preference and recent-negative guard"
      } else {
        "Relationship gate derived from playfulness permission/safety and constrained by playfulness preference"
      },
    )
    fields[SOUL_FIELD_PLAYFUL_TEASING_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PLAYFUL_TEASING_ALLOWED,
      value = projection.playfulTeasingAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate constrained by playfulness preference and recent-negative guard"
      } else {
        "Relationship gate derived from playfulness/trust/safety/reciprocity and constrained by playfulness preference"
      },
    )
    fields[SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED,
      value = projection.highIntimacyBehaviorAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate blocked by recent-negative guard"
      } else {
        "Relationship gate derived from trust/safety/reciprocity/intimacy"
      },
    )
    fields[SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED,
      value = projection.playfulAffectionAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate blocked by recent-negative guard"
      } else {
        "Relationship gate derived from playfulness/safety/reciprocity"
      },
    )
    return fields
  }

  private fun prioritizedFieldSources(
    field: String,
    directFieldSources: Map<String, ResolvedSoulFieldSource>,
    interactionFieldSources: Map<String, ResolvedSoulFieldSource>,
    relationshipFieldSources: Map<String, ResolvedSoulFieldSource>,
  ): List<ResolvedSoulFieldSource> = when (field) {
    SOUL_FIELD_TONE,
    SOUL_FIELD_VOICE,
    SOUL_FIELD_USER_RELATIONSHIP_STYLE,
    -> listOfNotNull(
      directFieldSources[field],
      relationshipFieldSources[field],
      interactionFieldSources[field],
    )

    SOUL_FIELD_PREFERRED_ADDRESS_STYLE -> listOfNotNull(
      directFieldSources[field],
      interactionFieldSources[field],
      relationshipFieldSources[field],
    )

    SOUL_FIELD_PREFERRED_NAMING -> listOfNotNull(
      directFieldSources[field],
      interactionFieldSources[field],
    )

    SOUL_FIELD_WARMTH_PREFERENCE_OFFSET,
    SOUL_FIELD_FORMALITY_PREFERENCE_OFFSET,
    SOUL_FIELD_INITIATIVE_PREFERENCE_OFFSET,
    SOUL_FIELD_PLAYFULNESS_PREFERENCE_OFFSET,
    SOUL_FIELD_REASSURANCE_PREFERENCE_OFFSET,
    -> listOfNotNull(interactionFieldSources[field])

    SOUL_FIELD_INTIMACY_PERMISSION_BAND,
    SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND,
    SOUL_FIELD_SUPPORTIVE_REASSURANCE_ALLOWED,
    SOUL_FIELD_PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED,
    SOUL_FIELD_LIGHT_PLAYFULNESS_ALLOWED,
    SOUL_FIELD_PLAYFUL_TEASING_ALLOWED,
    SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED,
    SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED,
    -> listOfNotNull(relationshipFieldSources[field])

    else -> listOfNotNull(directFieldSources[field])
  }

  private fun fieldSourceToMap(
    source: ResolvedSoulFieldSource,
  ): Map<String, Any?> = buildMap {
    put("field", source.field)
    put("value", source.value)
    put("sourceType", source.sourceType)
    put("sourceLabel", source.sourceLabel)
    if (source.recordId.isNotBlank()) {
      put("recordId", source.recordId)
    }
    if (source.preferenceKey.isNotBlank()) {
      put("preferenceKey", source.preferenceKey)
    }
    if (source.sourceScope.isNotBlank()) {
      put("sourceScope", source.sourceScope)
    }
    if (source.sourceDetail.isNotBlank()) {
      put("sourceDetail", source.sourceDetail)
    }
  }

  private fun soulOverlayDisplayComparator(): Comparator<MemoryRecord> =
    compareByDescending<MemoryRecord> { record ->
      debugMemoryMetadata(record)?.let { metadata -> soulScopePriority(metadata.scope) } ?: 0
    }.thenByDescending { record ->
      debugMemoryMetadata(record)?.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs
    }.thenBy { record ->
      record.id
    }

  private fun soulOverlaySourceLabel(scope: String): String = when (scope) {
    DEBUG_MEMORY_SCOPE_SESSION -> "session memory"
    DEBUG_MEMORY_SCOPE_WORKSPACE -> "workspace memory"
    else -> "user memory"
  }

  private fun soulScopePriority(scope: String): Int = when (scope) {
    DEBUG_MEMORY_SCOPE_WORKSPACE -> 1
    DEBUG_MEMORY_SCOPE_USER -> 2
    DEBUG_MEMORY_SCOPE_SESSION -> 3
    else -> 0
  }

  private fun currentMemoryToolContext(
    sessionId: String,
    workspaceId: String?,
  ): MemoryToolContext = MemoryToolContext(
    sessionId = sessionId,
    workspaceId = workspaceId?.takeIf { it.isNotBlank() },
    records = personalizationLocalStore?.listMemoryRecords().orEmpty(),
  )

  private fun memoryDebugRecordToMap(
    record: MemoryRecord,
    observedAtEpochMs: Long,
  ): Map<String, Any?> {
    val metadata = debugMemoryMetadata(record)
    val expiryReferenceEpochMs = metadata?.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs
    val isExpired = metadata?.ttlMs?.let { ttlMs ->
      expiryReferenceEpochMs + ttlMs <= observedAtEpochMs
    } ?: false
    return buildMap {
      put("id", record.id)
      put("content", record.content)
      put("recordVersion", record.recordVersion)
      put("createdAtEpochMs", record.createdAtEpochMs)
      put("updatedAtEpochMs", record.updatedAtEpochMs)
      if (record.tags.isNotEmpty()) {
        put("tags", record.tags)
      }
      if (record.extensions.isNotEmpty()) {
        put("extensions", record.extensions.toSortedMap())
      }
      metadata?.kind?.let { put("kind", it) }
      metadata?.scope?.let { put("scope", it) }
      metadata?.status?.let { put("status", it) }
      metadata?.source?.let { put("source", it) }
      metadata?.sourceSessionId?.let { put("sourceSessionId", it) }
      metadata?.sourceTaskId?.let { put("sourceTaskId", it) }
      metadata?.workspaceId?.let { put("workspaceId", it) }
      metadata?.preferenceKey?.let { put("preferenceKey", it) }
      metadata?.preferenceValue?.let { put("preferenceValue", it) }
      metadata?.preferenceTemporality?.let { put("preferenceTemporality", it) }
      metadata?.lastConfirmedAtEpochMs?.let { put("lastConfirmedAtEpochMs", it) }
      metadata?.resolvedAtEpochMs?.let { put("resolvedAtEpochMs", it) }
      metadata?.ttlMs?.let { put("ttlMs", it) }
      metadata?.resolutionReason?.let { put("resolutionReason", it) }
      metadata?.supersededBy?.let { put("supersededBy", it) }
      put("isExpired", isExpired)
    }
  }

  private fun debugRunLinkToMap(run: AgentRunSnapshot): Map<String, Any?> = buildMap {
    put("sessionId", run.sessionId)
    put("runId", run.runId)
    put("taskId", run.taskId)
    put("acceptedAtEpochMs", run.acceptedAtEpochMs)
    put("updatedAtEpochMs", run.updatedAtEpochMs)
    run.lifecycleState?.name?.lowercase(Locale.US)?.let { lifecycleState ->
      put("lifecycleState", lifecycleState)
    }
    run.executionStatus?.name?.lowercase(Locale.US)?.let { executionStatus ->
      put("executionStatus", executionStatus)
    }
    run.errorCode?.takeIf(String::isNotBlank)?.let { errorCode ->
      put("errorCode", errorCode)
    }
  }

  private fun debugRunLinkToMap(
    sessionId: String,
    runId: String,
    taskId: String,
    acceptedAtEpochMs: Long,
    updatedAtEpochMs: Long,
    lifecycleState: String? = null,
    executionStatus: String? = null,
    errorCode: String? = null,
  ): Map<String, Any?> = buildMap {
    put("sessionId", sessionId)
    put("runId", runId)
    put("taskId", taskId)
    put("acceptedAtEpochMs", acceptedAtEpochMs)
    put("updatedAtEpochMs", updatedAtEpochMs)
    lifecycleState?.takeIf(String::isNotBlank)?.let { put("lifecycleState", it) }
    executionStatus?.takeIf(String::isNotBlank)?.let { put("executionStatus", it) }
    errorCode?.takeIf(String::isNotBlank)?.let { put("errorCode", it) }
  }

  private fun rememberMemoryDebugActionAudit(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    audit: MemoryDebugActionAuditEntry,
  ) {
    rememberDebugLink(
      target = target,
      recordId = audit.recordId,
      uniqueKey = "audit:${audit.entryId}",
      payload = mapOf(
        "action" to debugMemoryActionLabel(audit.action),
        "occurredAtEpochMs" to audit.occurredAtEpochMs,
        "run" to debugRunLinkToMap(
          sessionId = audit.sessionId,
          runId = audit.runId,
          taskId = audit.taskId,
          acceptedAtEpochMs = audit.occurredAtEpochMs,
          updatedAtEpochMs = audit.occurredAtEpochMs,
          lifecycleState = QueueTaskLifecycleState.COMPLETED.name.lowercase(Locale.US),
          executionStatus = ExecutionStatus.SUCCESS.name.lowercase(Locale.US),
        ),
      ),
    )
  }

  private fun debugMemoryActionLabel(rawAction: String): String = when (
    MemoryOperatorAction.fromWireValue(rawAction)
  ) {
    MemoryOperatorAction.SUPPRESS -> "suppressed"
    MemoryOperatorAction.REAFFIRM -> "reaffirmed"
    null -> rawAction
  }

  private fun rememberMemoryWriteActions(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    runLink: Map<String, Any?>,
    event: OpenCrayMemoryWriteEvent,
  ) {
    rememberMemoryActionIds(
      target = target,
      runLink = runLink,
      action = "written",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.writtenRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      runLink = runLink,
      action = "resolved",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.resolvedRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      runLink = runLink,
      action = "suppressed",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.suppressedRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      runLink = runLink,
      action = "reopened",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.reopenedRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      runLink = runLink,
      action = "reaffirmed",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.reaffirmedRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      runLink = runLink,
      action = "expired",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.expiredRecordIds,
    )
  }

  private fun rememberMemoryActionIds(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    runLink: Map<String, Any?>,
    action: String,
    occurredAtEpochMs: Long,
    recordIds: List<String>,
  ) {
    recordIds.forEach { recordId ->
      rememberDebugLink(
        target = target,
        recordId = recordId,
        uniqueKey = "$action:${runLink["runId"]}:$occurredAtEpochMs:$recordId",
        payload = mapOf(
          "action" to action,
          "occurredAtEpochMs" to occurredAtEpochMs,
          "run" to runLink,
        ),
      )
    }
  }

  private fun rememberMemoryRetrievalLinks(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    run: AgentRunSnapshot,
    event: OpenCrayMemoryRetrievalEvent,
  ) {
    event.recordIds.forEach { recordId ->
      rememberDebugLink(
        target = target,
        recordId = recordId,
        uniqueKey = "retrieval:${run.runId}:${event.emittedAtEpochMs}:$recordId:${event.operation}",
        payload = buildMap {
          put("occurredAtEpochMs", event.emittedAtEpochMs)
          put("run", debugRunLinkToMap(run))
          put("toolName", event.toolName)
          put("operation", event.operation)
          event.query?.let { query -> put("query", query) }
          if (event.queryTerms.isNotEmpty()) {
            put("queryTerms", event.queryTerms)
          }
          if (event.paths.isNotEmpty()) {
            put("paths", event.paths)
          }
          if (event.lineRanges.isNotEmpty()) {
            put("lineRanges", event.lineRanges)
          }
          event.path?.let { path -> put("path", path) }
          event.fromLine?.let { fromLine -> put("fromLine", fromLine) }
          event.returnedLineCount?.let { returnedLineCount ->
            put("returnedLineCount", returnedLineCount)
          }
        },
      )
    }
  }

  private fun rememberDebugLink(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    recordId: String,
    uniqueKey: String,
    payload: Map<String, Any?>,
  ) {
    if (recordId.isBlank()) {
      return
    }
    target.getOrPut(recordId) { linkedMapOf() }[uniqueKey] = payload
  }

  private fun finalizeDebugLinks(
    raw: LinkedHashMap<String, Map<String, Any?>>?,
  ): List<Map<String, Any?>> = raw
    ?.values
    .orEmpty()
    .sortedByDescending { entry ->
      entry["occurredAtEpochMs"] as? Long
        ?: (entry["occurredAtEpochMs"] as? Int)?.toLong()
        ?: 0L
    }
    .take(MAX_DEBUG_LINKS_PER_RECORD)

  private fun splitDebugCsv(raw: String?): List<String> = raw
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)

  private fun debugMemoryMetadata(record: MemoryRecord): DebugMemoryMetadata? {
    val kind = debugParseTaggedMemoryValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.KIND],
      tags = record.tags,
      tagPrefix = "kind:",
    ) ?: return null
    val scope = debugParseTaggedMemoryValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.SCOPE],
      tags = record.tags,
      tagPrefix = "scope:",
    ) ?: return null
    val status = debugParseTaggedMemoryValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.STATUS],
      tags = record.tags,
      tagPrefix = "status:",
    ) ?: return null
    return DebugMemoryMetadata(
      kind = kind,
      scope = scope,
      status = status,
      source = normalizeDebugSoulKeyOrNull(record.extensions[MemoryRecordExtensionKeys.SOURCE]),
      sourceSessionId = record.extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID]
        ?.takeIf(String::isNotBlank),
      sourceTaskId = record.extensions[MemoryRecordExtensionKeys.SOURCE_TASK_ID]
        ?.takeIf(String::isNotBlank),
      workspaceId = record.extensions[MemoryRecordExtensionKeys.WORKSPACE_ID]
        ?.takeIf(String::isNotBlank),
      ttlMs = record.extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull(),
      lastConfirmedAtEpochMs =
        record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]?.toLongOrNull(),
      resolvedAtEpochMs =
        record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS]?.toLongOrNull(),
      resolutionReason = normalizeDebugSoulKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
      ),
      supersededBy = record.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY]
        ?.takeIf(String::isNotBlank),
      preferenceKey = normalizeDebugSoulKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
      ),
      preferenceValue = normalizeDebugSoulScalarOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
      ),
      preferenceTemporality = normalizeDebugSoulKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
      ),
    )
  }

  private fun debugParseTaggedMemoryValue(
    extensionValue: String?,
    tags: List<String>,
    tagPrefix: String,
  ): String? {
    normalizeDebugSoulKeyOrNull(extensionValue)?.let { return it }
    return tags
      .firstOrNull { tag -> tag.startsWith(tagPrefix) }
      ?.substringAfter(tagPrefix)
      ?.let(::normalizeDebugSoulKeyOrNull)
  }

  private fun normalizeDebugSoulKeyOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
      ?.replace(Regex("[\\s\\-]+"), "_")
      ?.replace(Regex("_+"), "_")
      ?.trim('_')
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotEmpty)

  private fun normalizeDebugSoulScalarOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  private fun isDebugOnlyRuntimeEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCrayMemoryWriteEvent &&
      event.runId.startsWith(MEMORY_DEBUG_RUN_ID_PREFIX) &&
      event.taskId.startsWith(MEMORY_DEBUG_TASK_ID_PREFIX)

  private fun parseSelectedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = MEMORY_SELECTED_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      val matchedTerms = match.groupValues[3]
        .split('|')
        .map(String::trim)
        .filter(String::isNotBlank)
      mapOf(
        "id" to match.groupValues[1],
        "score" to match.groupValues[2].toIntOrNull(),
        "matchedTerms" to matchedTerms,
      )
    }

  private data class DebugMemoryMetadata(
    val kind: String,
    val scope: String,
    val status: String,
    val source: String?,
    val sourceSessionId: String?,
    val sourceTaskId: String?,
    val workspaceId: String?,
    val ttlMs: Long?,
    val lastConfirmedAtEpochMs: Long?,
    val resolvedAtEpochMs: Long?,
    val resolutionReason: String?,
    val supersededBy: String?,
    val preferenceKey: String?,
    val preferenceValue: String?,
    val preferenceTemporality: String?,
  )

  private data class SoulFieldContribution(
    val field: String,
    val value: String,
    val record: MemoryRecord,
    val metadata: DebugMemoryMetadata,
  )

  private data class ResolvedSoulFieldSource(
    val field: String,
    val value: String,
    val sourceType: String,
    val sourceLabel: String,
    val recordId: String = "",
    val preferenceKey: String = "",
    val sourceScope: String = "",
    val sourceDetail: String = "",
  )
}

private val SUPPORTED_SOUL_PREFERENCE_KEYS: Set<String> = setOf(
  MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
  MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
  MemoryPreferenceKeys.AGENT_VERBOSITY,
  MemoryPreferenceKeys.USER_PREFERRED_NAME,
  MemoryPreferenceKeys.USER_ADDRESS_STYLE,
)

private const val DEBUG_MEMORY_KIND_USER_PREFERENCE: String = "user_preference"
private const val DEBUG_MEMORY_STATUS_ACTIVE: String = "active"
private const val DEBUG_MEMORY_SCOPE_USER: String = "user"
private const val DEBUG_MEMORY_SCOPE_WORKSPACE: String = "workspace"
private const val DEBUG_MEMORY_SCOPE_SESSION: String = "session"
private const val MAX_DEBUG_LINKS_PER_RECORD: Int = 8
private const val MEMORY_DEBUG_RUN_ID_PREFIX: String = "memory-debug-run"
private const val MEMORY_DEBUG_TASK_ID_PREFIX: String = "memory-debug-task"
private const val SOUL_FIELD_PRESET_NAME: String = "presetName"
private const val SOUL_FIELD_DISPLAY_NAME: String = "displayName"
private const val SOUL_FIELD_VOICE: String = "voice"
private const val SOUL_FIELD_PREFERRED_NAMING: String = "preferredNaming"
private const val SOUL_FIELD_PREFERRED_ADDRESS_STYLE: String = "preferredAddressStyle"
private const val SOUL_FIELD_WARMTH_PREFERENCE_OFFSET: String = "warmthPreferenceOffset"
private const val SOUL_FIELD_FORMALITY_PREFERENCE_OFFSET: String = "formalityPreferenceOffset"
private const val SOUL_FIELD_INITIATIVE_PREFERENCE_OFFSET: String = "initiativePreferenceOffset"
private const val SOUL_FIELD_PLAYFULNESS_PREFERENCE_OFFSET: String = "playfulnessPreferenceOffset"
private const val SOUL_FIELD_REASSURANCE_PREFERENCE_OFFSET: String = "reassurancePreferenceOffset"
private const val SOUL_FIELD_INTIMACY_PERMISSION_BAND: String = "intimacyPermissionBand"
private const val SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND: String = "playfulnessPermissionBand"
private const val SOUL_FIELD_SUPPORTIVE_REASSURANCE_ALLOWED: String = "supportiveReassuranceAllowed"
private const val SOUL_FIELD_PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED: String = "proactiveRelationalCheckInAllowed"
private const val SOUL_FIELD_LIGHT_PLAYFULNESS_ALLOWED: String = "lightPlayfulnessAllowed"
private const val SOUL_FIELD_PLAYFUL_TEASING_ALLOWED: String = "playfulTeasingAllowed"
private const val SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED: String = "highIntimacyBehaviorAllowed"
private const val SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED: String = "playfulAffectionAllowed"
private const val SOUL_FIELD_CUSTOM_GUIDANCE: String = "customGuidance"
private const val SOUL_FIELD_TONE: String = "tone"
private const val SOUL_FIELD_VERBOSITY: String = "verbosity"
private const val SOUL_FIELD_USER_RELATIONSHIP_STYLE: String = "userRelationshipStyle"
private const val SOUL_FIELD_RISK_TOLERANCE: String = "riskTolerance"
private const val SOUL_FIELD_TOOL_USE_BIAS: String = "toolUseBias"
private const val SOUL_FIELD_ESCALATION_RULES: String = "escalationRules"
private const val SOUL_FIELD_FORBIDDEN_BEHAVIORS: String = "forbiddenBehaviors"
private const val SOUL_FIELD_COLLABORATION_PREFERENCES: String = "collaborationPreferences"

private val SOUL_FIELD_ORDER: List<String> = listOf(
  SOUL_FIELD_PRESET_NAME,
  SOUL_FIELD_DISPLAY_NAME,
  SOUL_FIELD_VOICE,
  SOUL_FIELD_PREFERRED_NAMING,
  SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
  SOUL_FIELD_WARMTH_PREFERENCE_OFFSET,
  SOUL_FIELD_FORMALITY_PREFERENCE_OFFSET,
  SOUL_FIELD_INITIATIVE_PREFERENCE_OFFSET,
  SOUL_FIELD_PLAYFULNESS_PREFERENCE_OFFSET,
  SOUL_FIELD_REASSURANCE_PREFERENCE_OFFSET,
  SOUL_FIELD_INTIMACY_PERMISSION_BAND,
  SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND,
  SOUL_FIELD_SUPPORTIVE_REASSURANCE_ALLOWED,
  SOUL_FIELD_PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED,
  SOUL_FIELD_LIGHT_PLAYFULNESS_ALLOWED,
  SOUL_FIELD_PLAYFUL_TEASING_ALLOWED,
  SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED,
  SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED,
  SOUL_FIELD_CUSTOM_GUIDANCE,
  SOUL_FIELD_TONE,
  SOUL_FIELD_VERBOSITY,
  SOUL_FIELD_USER_RELATIONSHIP_STYLE,
  SOUL_FIELD_RISK_TOLERANCE,
  SOUL_FIELD_TOOL_USE_BIAS,
  SOUL_FIELD_ESCALATION_RULES,
  SOUL_FIELD_FORBIDDEN_BEHAVIORS,
  SOUL_FIELD_COLLABORATION_PREFERENCES,
)

private val MEMORY_SELECTED_TRACE_REGEX: Regex = Regex("""^(.+?)@(\d+)(?:\[(.*)])?$""")
