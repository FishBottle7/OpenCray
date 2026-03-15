package com.opencray.runtime.memory

import java.util.Locale

enum class MemoryKind {
  USER_PREFERENCE,
  PROJECT_FACT,
  DURABLE_INSTRUCTION,
  TASK_COMMITMENT,
}

enum class MemoryScope {
  USER,
  WORKSPACE,
  SESSION,
}

enum class MemoryStatus {
  ACTIVE,
  OPEN,
  RESOLVED,
}

enum class MemoryEvidenceSource {
  USER_INPUT,
  ASSISTANT_OUTPUT,
  TOOL_OBSERVATION,
}

data class MemoryRecallBudget(
  val maxRecords: Int = 6,
  val maxChars: Int = 900,
  val maxRecordsPerKind: Int = 2,
)

data class MemoryCandidate(
  val kind: MemoryKind,
  val scope: MemoryScope,
  val status: MemoryStatus,
  val content: String,
  val source: MemoryEvidenceSource,
  val sourceSessionId: String,
  val sourceTaskId: String? = null,
  val workspaceId: String? = null,
  val ttlMs: Long? = null,
  val extensions: Map<String, String> = emptyMap(),
) {
  init {
    require(content.isNotBlank()) { "MemoryCandidate content must not be blank." }
    require(sourceSessionId.isNotBlank()) { "MemoryCandidate sourceSessionId must not be blank." }
  }
}

data class MemoryTurnEvidence(
  val sessionId: String,
  val taskId: String? = null,
  val workspaceId: String? = null,
  val userInput: String,
  val assistantOutput: String? = null,
  val toolObservations: List<String> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "MemoryTurnEvidence sessionId must not be blank." }
    require(userInput.isNotBlank()) { "MemoryTurnEvidence userInput must not be blank." }
  }
}

data class MemoryKindPolicy(
  val kind: MemoryKind,
  val defaultStatus: MemoryStatus,
  val ttlMs: Long? = null,
)

class MemoryPolicy(
  val recallBudget: MemoryRecallBudget = MemoryRecallBudget(),
  val maxCandidateContentChars: Int = DEFAULT_MAX_CANDIDATE_CONTENT_CHARS,
  private val kindPolicies: Map<MemoryKind, MemoryKindPolicy> = defaultKindPolicies(),
) {
  fun policyFor(kind: MemoryKind): MemoryKindPolicy =
    kindPolicies[kind] ?: error("Missing MemoryKindPolicy for $kind")

  fun defaultStatusFor(kind: MemoryKind): MemoryStatus = policyFor(kind).defaultStatus

  fun ttlMsFor(kind: MemoryKind): Long? = policyFor(kind).ttlMs

  fun resolveScope(
    kind: MemoryKind,
    content: String,
  ): MemoryScope = when (kind) {
    MemoryKind.USER_PREFERENCE ->
      if (containsWorkspaceSignal(content)) MemoryScope.WORKSPACE else MemoryScope.USER

    MemoryKind.PROJECT_FACT -> MemoryScope.WORKSPACE
    MemoryKind.DURABLE_INSTRUCTION ->
      if (containsWorkspaceSignal(content)) MemoryScope.WORKSPACE else MemoryScope.USER

    MemoryKind.TASK_COMMITMENT -> MemoryScope.SESSION
  }

  fun normalizeCandidateContent(raw: String?): String? {
    val normalized = raw
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.trim('"', '\'', '`')
      ?.trim('-', '*', ' ', '.', ',', ';', ':', '。', '，', '；', '：')
      ?.takeIf(String::isNotEmpty)
      ?: return null
    if (normalized.length < MIN_CANDIDATE_CONTENT_CHARS || normalized.length > maxCandidateContentChars) {
      return null
    }
    if (normalized.contains("```")) {
      return null
    }
    val punctuationHeavy = normalized.count { character ->
      character == '{' || character == '}' || character == '[' || character == ']'
    }
    if (punctuationHeavy >= 4) {
      return null
    }
    return normalized
  }

  fun containsWorkspaceSignal(content: String): Boolean {
    val lowered = content.lowercase(Locale.US)
    return WORKSPACE_SCOPE_SIGNALS.any { signal ->
      lowered.contains(signal)
    } || CHINESE_WORKSPACE_SCOPE_SIGNALS.any { signal ->
      content.contains(signal)
    }
  }

  companion object {
    const val DEFAULT_MAX_CANDIDATE_CONTENT_CHARS: Int = 180
    private const val MIN_CANDIDATE_CONTENT_CHARS: Int = 8
    private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

    val WORKSPACE_SCOPE_SIGNALS: List<String> = listOf(
      "repo",
      "repository",
      "project",
      "workspace",
      "codebase",
      "this app",
      "this module",
    )

    val CHINESE_WORKSPACE_SCOPE_SIGNALS: List<String> = listOf(
      "仓库",
      "项目",
      "工作区",
      "代码库",
      "模块",
    )

    fun defaultKindPolicies(): Map<MemoryKind, MemoryKindPolicy> = linkedMapOf(
      MemoryKind.USER_PREFERENCE to MemoryKindPolicy(
        kind = MemoryKind.USER_PREFERENCE,
        defaultStatus = MemoryStatus.ACTIVE,
        ttlMs = null,
      ),
      MemoryKind.PROJECT_FACT to MemoryKindPolicy(
        kind = MemoryKind.PROJECT_FACT,
        defaultStatus = MemoryStatus.ACTIVE,
        ttlMs = 90L * DAY_MS,
      ),
      MemoryKind.DURABLE_INSTRUCTION to MemoryKindPolicy(
        kind = MemoryKind.DURABLE_INSTRUCTION,
        defaultStatus = MemoryStatus.ACTIVE,
        ttlMs = null,
      ),
      MemoryKind.TASK_COMMITMENT to MemoryKindPolicy(
        kind = MemoryKind.TASK_COMMITMENT,
        defaultStatus = MemoryStatus.OPEN,
        ttlMs = 14L * DAY_MS,
      ),
    )
  }
}

object MemoryRecordExtensionKeys {
  const val KIND: String = "kind"
  const val SCOPE: String = "scope"
  const val STATUS: String = "status"
  const val SOURCE: String = "source"
  const val SOURCE_SESSION_ID: String = "source_session_id"
  const val SOURCE_TASK_ID: String = "source_task_id"
  const val WORKSPACE_ID: String = "workspace_id"
  const val TTL_MS: String = "ttl_ms"
  const val FIRST_CONFIRMED_AT_EPOCH_MS: String = "first_confirmed_at_epoch_ms"
  const val LAST_CONFIRMED_AT_EPOCH_MS: String = "last_confirmed_at_epoch_ms"
  const val RESOLVED_AT_EPOCH_MS: String = "resolved_at_epoch_ms"
  const val RESOLUTION_REASON: String = "resolution_reason"
  const val SUPERSEDED_BY: String = "superseded_by"
  const val PREFERENCE_KEY: String = "preference_key"
  const val PREFERENCE_VALUE: String = "preference_value"
  const val PREFERENCE_TEMPORALITY: String = "preference_temporality"
}
