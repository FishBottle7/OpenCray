package com.opencray.runtime.memory

import java.util.Locale

class MemoryCandidateExtractor(
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val userIntentInterpreter: UserMemoryIntentInterpreter = NoOpUserMemoryIntentInterpreter,
  private val soulIntentInterpreter: SoulMemoryIntentInterpreter = NoOpSoulMemoryIntentInterpreter,
) {
  fun extract(evidence: MemoryTurnEvidence): List<MemoryCandidate> {
    val candidates = linkedMapOf<String, MemoryCandidate>()
    val userIntentOutcome = extractUserIntentCandidates(evidence)

    userIntentOutcome.candidates.forEach { candidate ->
      candidates.putIfAbsent(candidate.identityKey(), candidate)
    }

    if (userIntentOutcome.shouldAttemptSoulInterpreterFallback) {
      extractSoulPreferenceCandidates(evidence).forEach { candidate ->
        candidates.putIfAbsent(candidate.identityKey(), candidate)
      }
    }

    evidence.toolObservations.forEach { observation ->
      splitStatements(observation).forEach toolObservationStatement@{ statement ->
        val candidate = extractProjectFact(
          statement = statement,
          source = MemoryEvidenceSource.TOOL_OBSERVATION,
          evidence = evidence,
        ) ?: return@toolObservationStatement
        candidates.putIfAbsent(candidate.identityKey(), candidate)
      }
    }

    splitStatements(evidence.assistantOutput.orEmpty()).forEach { statement ->
      val candidate = extractTaskCommitment(statement = statement, evidence = evidence) ?: return@forEach
      candidates.putIfAbsent(candidate.identityKey(), candidate)
    }

    return candidates.values.toList()
  }

  private fun extractUserIntentCandidates(
    evidence: MemoryTurnEvidence,
  ): UserIntentCandidateExtraction {
    val request = UserMemoryIntentRequest(
      sessionId = evidence.sessionId,
      workspaceId = evidence.workspaceId,
      userInput = evidence.userInput,
    )
    return when (val interpretation = userIntentInterpreter.interpret(request)) {
      is UserMemoryIntentInterpretation.Success -> UserIntentCandidateExtraction(
        candidates = interpretation.intents.mapNotNull { intent ->
          candidateFromUserIntent(
            intent = intent,
            evidence = evidence,
          )
        },
        shouldAttemptSoulInterpreterFallback = false,
      )

      is UserMemoryIntentInterpretation.Unavailable -> UserIntentCandidateExtraction(
        shouldAttemptSoulInterpreterFallback = true,
      )
    }
  }

  private fun extractProjectFact(
    statement: String,
    source: MemoryEvidenceSource,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = policy.normalizeCandidateContent(statement) ?: return null
    val lowered = normalized.lowercase(Locale.US)
    val matchesEnglish = PROJECT_FACT_MARKERS.any { marker -> lowered.contains(marker) }
    val matchesChinese = PROJECT_FACT_CHINESE_MARKERS.any { marker -> normalized.contains(marker) }
    if (!matchesEnglish && !matchesChinese) {
      return null
    }
    return createCandidate(
      kind = MemoryKind.PROJECT_FACT,
      content = normalized,
      source = source,
      evidence = evidence,
    )
  }

  private fun extractTaskCommitment(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = policy.normalizeCandidateContent(statement) ?: return null
    val content = canonicalizeCommitment(normalized) ?: return null
    return createCandidate(
      kind = MemoryKind.TASK_COMMITMENT,
      content = content,
      source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
      evidence = evidence,
    )
  }

  private fun canonicalizeCommitment(normalized: String): String? {
    val lowered = normalized.lowercase(Locale.US)
    val englishPrefix = TASK_COMMITMENT_PREFIXES.firstOrNull { prefix -> lowered.startsWith(prefix) }
    if (englishPrefix != null) {
      return normalized.substring(englishPrefix.length).trim().ifBlank { null }
    }
    val chinesePrefix = TASK_COMMITMENT_CHINESE_PREFIXES.firstOrNull { prefix -> normalized.startsWith(prefix) }
    if (chinesePrefix != null) {
      return normalized.removePrefix(chinesePrefix).trim().ifBlank { null }
    }
    return null
  }

  private fun createCandidate(
    kind: MemoryKind,
    content: String,
    scope: MemoryScope = policy.resolveScope(kind = kind, content = content),
    status: MemoryStatus = policy.defaultStatusFor(kind),
    source: MemoryEvidenceSource,
    evidence: MemoryTurnEvidence,
    ttlMs: Long? = policy.ttlMsFor(kind),
    extensions: Map<String, String> = emptyMap(),
  ): MemoryCandidate = MemoryCandidate(
    kind = kind,
    scope = scope,
    status = status,
    content = content,
    source = source,
    sourceSessionId = evidence.sessionId,
    sourceTaskId = evidence.taskId,
    workspaceId = evidence.workspaceId,
    ttlMs = ttlMs,
    extensions = extensions,
  )

  private fun MemoryCandidate.identityKey(): String =
    listOf(
      kind.name,
      scope.name,
      content.lowercase(Locale.US),
    ).joinToString(separator = "|")

  private fun splitStatements(text: String): List<String> = text
    .split(Regex("[\\r\\n]+|(?<=[.!?;。！？；])"))
    .mapNotNull(policy::normalizeCandidateContent)

  private fun extractSoulPreferenceCandidates(
    evidence: MemoryTurnEvidence,
  ): List<MemoryCandidate> {
    val request = SoulMemoryIntentRequest(
      sessionId = evidence.sessionId,
      workspaceId = evidence.workspaceId,
      userInput = evidence.userInput,
    )
    return when (val interpretation = soulIntentInterpreter.interpret(request)) {
      is SoulMemoryIntentInterpretation.Success -> interpretation.intents.mapNotNull { intent ->
          candidateFromSoulIntent(
            intent = intent,
            evidence = evidence,
          )
        }

      is SoulMemoryIntentInterpretation.Unavailable -> emptyList()
    }
  }

  private fun candidateFromSoulIntent(
    intent: SoulMemoryIntent,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val requestedPreferenceKey = normalizeMemoryPreferenceKeyOrNull(intent.preferenceKey)
      ?.takeIf { key -> key in supportedSoulPreferenceKeys() }
      ?: return null
    val preferenceValue = normalizeMemoryPreferenceValueOrNull(intent.preferenceValue) ?: return null
    val preferenceKey = canonicalSoulPreferenceKey(
      preferenceKey = requestedPreferenceKey,
      requestedScope = intent.scope,
    )
    val scope = effectiveSoulPreferenceScope(
      preferenceKey = preferenceKey,
      requestedScope = intent.scope,
    )
    val preferenceExtensions = canonicalSoulPreferenceExtensions(
      requestedPreferenceKey = requestedPreferenceKey,
      preferenceValue = preferenceValue,
      requestedScope = intent.scope,
      rawPreferenceExtensions = intent.preferenceExtensions,
    )
    val extensions = buildSoulPreferenceExtensions(
      preferenceKey = preferenceKey,
      preferenceValue = preferenceValue,
      scope = scope,
      soulExtensions = intent.soulExtensions,
      preferenceExtensions = preferenceExtensions,
    )
    if (extensions.isEmpty()) {
      return null
    }
    val content = canonicalSoulPreferenceContent(
      preferenceKey = preferenceKey,
      preferenceValue = preferenceValue,
      extensions = extensions,
    ) ?: return null
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = scope,
      content = content,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
      extensions = extensions,
    )
  }

  private fun candidateFromUserIntent(
    intent: UserMemoryIntent,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val requestedPreferenceKey = normalizeMemoryPreferenceKeyOrNull(intent.preferenceKey)
    val preferenceValue = normalizeMemoryPreferenceValueOrNull(intent.preferenceValue)
    if (requestedPreferenceKey != null && preferenceValue != null) {
      val preferenceKey = canonicalSoulPreferenceKey(
        preferenceKey = requestedPreferenceKey,
        requestedScope = intent.scope,
      )
      val scope = effectiveSoulPreferenceScope(
        preferenceKey = preferenceKey,
        requestedScope = intent.scope,
      )
      val preferenceExtensions = canonicalSoulPreferenceExtensions(
        requestedPreferenceKey = requestedPreferenceKey,
        preferenceValue = preferenceValue,
        requestedScope = intent.scope,
        rawPreferenceExtensions = intent.preferenceExtensions,
      )
      val extensions = buildSoulPreferenceExtensions(
        preferenceKey = preferenceKey,
        preferenceValue = preferenceValue,
        scope = scope,
        soulExtensions = intent.soulExtensions,
        preferenceExtensions = preferenceExtensions,
      )
      val content = canonicalSoulPreferenceContent(
        preferenceKey = preferenceKey,
        preferenceValue = preferenceValue,
        extensions = extensions,
      )
      if (intent.kind == MemoryKind.USER_PREFERENCE && extensions.isNotEmpty() && content != null) {
        return createCandidate(
          kind = MemoryKind.USER_PREFERENCE,
          scope = scope,
          content = content,
          source = MemoryEvidenceSource.USER_INPUT,
          evidence = evidence,
          extensions = extensions,
        )
      }
    }

    if (intent.kind == MemoryKind.PROJECT_FACT && intent.scope == MemoryScope.SESSION) {
      return null
    }
    val content = policy.normalizeCandidateContent(intent.content) ?: return null
    return createCandidate(
      kind = intent.kind,
      scope = intent.scope,
      content = content,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
    )
  }

  private fun canonicalSoulPreferenceContent(
    preferenceKey: String,
    preferenceValue: String,
    extensions: Map<String, String> = emptyMap(),
  ): String? = when (preferenceKey) {
    MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> "Agent display name is $preferenceValue"
    MemoryPreferenceKeys.USER_PREFERRED_NAME -> "Preferred user naming is $preferenceValue"
    MemoryPreferenceKeys.USER_ADDRESS_STYLE -> "Address the user in a $preferenceValue style"
    MemoryPreferenceKeys.AGENT_STYLE_PROFILE -> "Agent style profile should be $preferenceValue"
    MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL ->
      interactionPreferenceSignalContentOrNull(extensions)
    MemoryPreferenceKeys.AGENT_VERBOSITY -> "Agent verbosity should be $preferenceValue"
    else -> null
  }

  private fun canonicalSoulPreferenceKey(
    preferenceKey: String,
    requestedScope: MemoryScope,
  ): String {
    val normalizedKey = normalizeMemoryPreferenceKeyOrNull(preferenceKey) ?: return preferenceKey
    return when {
      normalizedKey == MemoryPreferenceKeys.AGENT_STYLE_PROFILE &&
        requestedScope != MemoryScope.SESSION -> MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL

      else -> normalizedKey
    }
  }

  private fun effectiveSoulPreferenceScope(
    preferenceKey: String,
    requestedScope: MemoryScope,
  ): MemoryScope = directChatSoulPreferenceScope(
    preferenceKey = preferenceKey,
    requestedScope = requestedScope,
  )

  private fun canonicalSoulPreferenceExtensions(
    requestedPreferenceKey: String,
    preferenceValue: String,
    requestedScope: MemoryScope,
    rawPreferenceExtensions: Map<String, String>,
  ): Map<String, String> {
    if (rawPreferenceExtensions.isNotEmpty()) {
      return rawPreferenceExtensions
    }
    val canonicalKey = canonicalSoulPreferenceKey(
      preferenceKey = requestedPreferenceKey,
      requestedScope = requestedScope,
    )
    return when {
      canonicalKey == MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL &&
        normalizeMemoryPreferenceKeyOrNull(requestedPreferenceKey) == MemoryPreferenceKeys.AGENT_STYLE_PROFILE ->
        styleProfileAdaptiveSignalExtensions(preferenceValue)

      else -> emptyMap()
    }
  }

  private companion object {
    val PROJECT_FACT_MARKERS: List<String> = listOf(
      "repo uses ",
      "repository uses ",
      "project uses ",
      "workspace uses ",
      "runs on port ",
      "lives in ",
      "stored in ",
      "located at ",
      "workspace root is ",
    )

    val PROJECT_FACT_CHINESE_MARKERS: List<String> = listOf(
      "项目使用",
      "仓库使用",
      "工作区使用",
      "运行在",
      "端口",
      "位于",
      "路径",
      "目录",
    )

    val TASK_COMMITMENT_PREFIXES: List<String> = listOf(
      "next i will ",
      "i will ",
      "i'll ",
    )

    val TASK_COMMITMENT_CHINESE_PREFIXES: List<String> = listOf(
      "接下来我会",
      "下一步我会",
      "我会",
    )
  }

  private data class UserIntentCandidateExtraction(
    val candidates: List<MemoryCandidate> = emptyList(),
    val shouldAttemptSoulInterpreterFallback: Boolean,
  )

}
