package com.opencray.runtime.memory

import java.util.Locale

class MemoryCandidateExtractor(
  private val policy: MemoryPolicy = MemoryPolicy(),
) {
  fun extract(evidence: MemoryTurnEvidence): List<MemoryCandidate> {
    val candidates = linkedMapOf<String, MemoryCandidate>()

    splitStatements(evidence.userInput).forEach { statement ->
      val candidate = extractFromUserStatement(statement = statement, evidence = evidence) ?: return@forEach
      candidates.putIfAbsent(candidate.identityKey(), candidate)
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

  private fun extractFromUserStatement(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    extractDurableInstruction(statement = statement, evidence = evidence)?.let { return it }
    extractProjectFact(
      statement = statement,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
    )?.let { return it }
    extractUserPreference(statement = statement, evidence = evidence)?.let { return it }
    return null
  }

  private fun extractUserPreference(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = policy.normalizeCandidateContent(statement) ?: return null
    val lowered = normalized.lowercase(Locale.US)
    val matchesEnglish = USER_PREFERENCE_PREFIXES.any { prefix -> lowered.startsWith(prefix) }
    val matchesChinese = USER_PREFERENCE_CHINESE_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
    if (!matchesEnglish && !matchesChinese) {
      return null
    }
    val content = canonicalizeLeadingDirective(
      normalized = normalized,
      englishPrefixes = USER_PREFERENCE_PREFIXES,
      chinesePrefixes = USER_PREFERENCE_CHINESE_PREFIXES,
    )
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      content = content,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
    )
  }

  private fun extractDurableInstruction(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = policy.normalizeCandidateContent(statement) ?: return null
    val lowered = normalized.lowercase(Locale.US)
    val matchesEnglish = DURABLE_INSTRUCTION_MARKERS.any { marker -> lowered.contains(marker) }
    val matchesChinese = DURABLE_INSTRUCTION_CHINESE_MARKERS.any { marker -> normalized.contains(marker) }
    if (!matchesEnglish && !matchesChinese) {
      return null
    }
    return createCandidate(
      kind = MemoryKind.DURABLE_INSTRUCTION,
      content = normalized,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
    )
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

  private fun canonicalizeLeadingDirective(
    normalized: String,
    englishPrefixes: List<String>,
    chinesePrefixes: List<String>,
  ): String {
    val lowered = normalized.lowercase(Locale.US)
    val englishPrefix = englishPrefixes.firstOrNull { prefix -> lowered.startsWith(prefix) }
    if (englishPrefix != null) {
      val remainder = normalized.substring(englishPrefix.length).trim()
      return buildCanonicalSentence(englishPrefix = englishPrefix, remainder = remainder)
    }
    val chinesePrefix = chinesePrefixes.firstOrNull { prefix -> normalized.startsWith(prefix) }
    if (chinesePrefix != null) {
      return normalized.removePrefix(chinesePrefix).trim().ifBlank { normalized }
    }
    return normalized
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

  private fun buildCanonicalSentence(
    englishPrefix: String,
    remainder: String,
  ): String {
    if (remainder.isBlank()) {
      return englishPrefix.trim().replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
      }
    }
    val canonicalPrefix = when {
      englishPrefix.contains("default to") -> "Default to"
      englishPrefix.contains("prefer") -> "Prefer"
      englishPrefix.contains("always") -> "Always"
      else -> englishPrefix.trim().replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
      }
    }
    return "$canonicalPrefix $remainder"
  }

  private fun createCandidate(
    kind: MemoryKind,
    content: String,
    source: MemoryEvidenceSource,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate = MemoryCandidate(
    kind = kind,
    scope = policy.resolveScope(kind = kind, content = content),
    status = policy.defaultStatusFor(kind),
    content = content,
    source = source,
    sourceSessionId = evidence.sessionId,
    sourceTaskId = evidence.taskId,
    workspaceId = evidence.workspaceId,
    ttlMs = policy.ttlMsFor(kind),
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

  private companion object {
    val USER_PREFERENCE_PREFIXES: List<String> = listOf(
      "default to ",
      "prefer ",
      "please prefer ",
      "please default to ",
      "always use ",
      "always reply in ",
    )

    val USER_PREFERENCE_CHINESE_PREFIXES: List<String> = listOf(
      "请默认",
      "默认",
      "优先",
      "请优先",
      "请始终",
      "始终",
      "以后都",
    )

    val DURABLE_INSTRUCTION_MARKERS: List<String> = listOf(
      "do not ",
      "don't ",
      "never ",
      "must ",
      "must not ",
      "always ask before ",
    )

    val DURABLE_INSTRUCTION_CHINESE_MARKERS: List<String> = listOf(
      "不要",
      "不能",
      "禁止",
      "必须",
      "一定要",
    )

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
}
