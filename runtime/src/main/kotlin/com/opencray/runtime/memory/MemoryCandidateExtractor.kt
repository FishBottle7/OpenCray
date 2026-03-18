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

    if (userIntentOutcome.allowHeuristicUserFallback) {
      val soulIntentOutcome = extractSoulPreferenceCandidates(evidence)
      soulIntentOutcome.candidates.forEach { candidate ->
        candidates.putIfAbsent(candidate.identityKey(), candidate)
      }

      splitUserStatements(evidence.userInput).forEach { statement ->
        val candidate = extractFromUserStatement(
          statement = statement,
          evidence = evidence,
          allowHeuristicSoulFallback = soulIntentOutcome.allowHeuristicSoulFallback,
        ) ?: return@forEach
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
        allowHeuristicUserFallback = false,
      )

      is UserMemoryIntentInterpretation.Unavailable -> UserIntentCandidateExtraction(
        allowHeuristicUserFallback = interpretation.allowHeuristicFallback,
      )
    }
  }

  private fun extractFromUserStatement(
    statement: String,
    evidence: MemoryTurnEvidence,
    allowHeuristicSoulFallback: Boolean,
  ): MemoryCandidate? {
    extractDurableInstruction(statement = statement, evidence = evidence)?.let { return it }
    if (allowHeuristicSoulFallback) {
      extractAgentDisplayNamePreference(statement = statement, evidence = evidence)?.let { return it }
      extractUserPreferredNaming(statement = statement, evidence = evidence)?.let { return it }
      extractUserAddressStylePreference(statement = statement, evidence = evidence)?.let { return it }
      extractAgentStylePreference(statement = statement, evidence = evidence)?.let { return it }
      extractAgentVerbosityPreference(statement = statement, evidence = evidence)?.let { return it }
    }
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

  private fun extractAgentDisplayNamePreference(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = normalizeSoulDirectiveStatement(statement) ?: return null
    val displayName = extractDisplayName(normalized) ?: return null
    val scope = directChatSoulPreferenceScope(
      preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
      requestedScope = resolvePreferenceScope(
        normalized = normalized,
        defaultScope = MemoryScope.USER,
      ),
    )
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = scope,
      content = "Agent display name is $displayName",
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
      extensions = displayNamePreferenceExtensions(
        displayName = displayName,
        scope = scope,
      ),
    )
  }

  private fun extractAgentStylePreference(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = normalizeSoulDirectiveStatement(statement) ?: return null
    val styleProfile = resolveStyleProfile(normalized) ?: return null
    val requestedScope = resolvePreferenceScope(
      normalized = normalized,
      defaultScope = MemoryScope.SESSION,
    )
    val preferenceKey = canonicalSoulStylePreferenceKey(
      preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
      requestedScope = requestedScope,
    )
    val scope = effectiveSoulPreferenceScope(
      preferenceKey = preferenceKey,
      requestedScope = requestedScope,
    )
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = scope,
      content = canonicalSoulPreferenceContent(
        preferenceKey = preferenceKey,
        preferenceValue = styleProfile,
      ) ?: return null,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
      extensions = buildSoulPreferenceExtensions(
        preferenceKey = preferenceKey,
        preferenceValue = styleProfile,
        scope = scope,
      ),
    )
  }

  private fun extractUserPreferredNaming(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = normalizeSoulDirectiveStatement(statement) ?: return null
    val preferredName = extractPreferredNaming(normalized) ?: return null
    val scope = directChatSoulPreferenceScope(
      preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
      requestedScope = resolvePreferenceScope(
        normalized = normalized,
        defaultScope = MemoryScope.USER,
      ),
    )
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = scope,
      content = canonicalSoulPreferenceContent(
        preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
        preferenceValue = preferredName,
      ) ?: return null,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
      extensions = userPreferredNamePreferenceExtensions(
        preferredName = preferredName,
        scope = scope,
      ),
    )
  }

  private fun extractUserAddressStylePreference(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = normalizeSoulDirectiveStatement(statement) ?: return null
    val addressStyle = resolvePreferredAddressStyle(normalized) ?: return null
    val scope = directChatSoulPreferenceScope(
      preferenceKey = MemoryPreferenceKeys.USER_ADDRESS_STYLE,
      requestedScope = resolvePreferenceScope(
        normalized = normalized,
        defaultScope = MemoryScope.USER,
      ),
    )
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = scope,
      content = canonicalSoulPreferenceContent(
        preferenceKey = MemoryPreferenceKeys.USER_ADDRESS_STYLE,
        preferenceValue = addressStyle,
      ) ?: return null,
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
      extensions = userAddressStylePreferenceExtensions(
        addressStyle = addressStyle,
        scope = scope,
      ),
    )
  }

  private fun extractAgentVerbosityPreference(
    statement: String,
    evidence: MemoryTurnEvidence,
  ): MemoryCandidate? {
    val normalized = normalizeSoulDirectiveStatement(statement) ?: return null
    val verbosity = resolveVerbosityProfile(normalized) ?: return null
    val scope = directChatSoulPreferenceScope(
      preferenceKey = MemoryPreferenceKeys.AGENT_VERBOSITY,
      requestedScope = resolvePreferenceScope(
        normalized = normalized,
        defaultScope = MemoryScope.SESSION,
      ),
    )
    return createCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = scope,
      content = "Agent verbosity should be $verbosity",
      source = MemoryEvidenceSource.USER_INPUT,
      evidence = evidence,
      extensions = verbosityPreferenceExtensions(
        verbosity = verbosity,
        scope = scope,
      ),
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

  private fun splitUserStatements(text: String): List<String> = text
    .split(Regex("[\\r\\n]+|(?<=[.!?;。！？；])"))
    .mapNotNull(::normalizeSoulDirectiveStatement)

  private fun normalizeSoulDirectiveStatement(raw: String?): String? =
    raw
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.trim('"', '\'', '`')
      ?.trim('-', '*', ' ', '.', ',', ';', ':', '。', '，', '；', '：')
      ?.takeIf(String::isNotEmpty)

  private fun extractSoulPreferenceCandidates(
    evidence: MemoryTurnEvidence,
  ): SoulPreferenceCandidateExtraction {
    val request = SoulMemoryIntentRequest(
      sessionId = evidence.sessionId,
      workspaceId = evidence.workspaceId,
      userInput = evidence.userInput,
    )
    return when (val interpretation = soulIntentInterpreter.interpret(request)) {
      is SoulMemoryIntentInterpretation.Success -> SoulPreferenceCandidateExtraction(
        candidates = interpretation.intents.mapNotNull { intent ->
          candidateFromSoulIntent(
            intent = intent,
            evidence = evidence,
          )
        },
        allowHeuristicSoulFallback = false,
      )

      is SoulMemoryIntentInterpretation.Unavailable -> SoulPreferenceCandidateExtraction(
        allowHeuristicSoulFallback = interpretation.allowHeuristicFallback,
      )
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
    val preferenceKey = canonicalSoulStylePreferenceKey(
      preferenceKey = requestedPreferenceKey,
      requestedScope = intent.scope,
    )
    val scope = effectiveSoulPreferenceScope(
      preferenceKey = preferenceKey,
      requestedScope = intent.scope,
    )
    val extensions = buildSoulPreferenceExtensions(
      preferenceKey = preferenceKey,
      preferenceValue = preferenceValue,
      scope = scope,
      soulExtensions = intent.soulExtensions,
      preferenceExtensions = intent.preferenceExtensions,
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
      val preferenceKey = canonicalSoulStylePreferenceKey(
        preferenceKey = requestedPreferenceKey,
        requestedScope = intent.scope,
      )
      val scope = effectiveSoulPreferenceScope(
        preferenceKey = preferenceKey,
        requestedScope = intent.scope,
      )
      val extensions = buildSoulPreferenceExtensions(
        preferenceKey = preferenceKey,
        preferenceValue = preferenceValue,
        scope = scope,
        soulExtensions = intent.soulExtensions,
        preferenceExtensions = intent.preferenceExtensions,
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
    MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE ->
      "Relationship style should gradually move toward $preferenceValue"
    MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL ->
      interactionPreferenceSignalContentOrNull(extensions)
    MemoryPreferenceKeys.AGENT_VERBOSITY -> "Agent verbosity should be $preferenceValue"
    else -> null
  }

  private fun canonicalSoulStylePreferenceKey(
    preferenceKey: String,
    requestedScope: MemoryScope,
  ): String {
    val normalizedKey = normalizeMemoryPreferenceKeyOrNull(preferenceKey) ?: return preferenceKey
    return when {
      normalizedKey == MemoryPreferenceKeys.AGENT_STYLE_PROFILE &&
        requestedScope != MemoryScope.SESSION -> MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE

      else -> normalizedKey
    }
  }

  private fun effectiveSoulPreferenceScope(
    preferenceKey: String,
    requestedScope: MemoryScope,
  ): MemoryScope = when (normalizeMemoryPreferenceKeyOrNull(preferenceKey)) {
    MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE -> requestedScope
    else -> directChatSoulPreferenceScope(
      preferenceKey = preferenceKey,
      requestedScope = requestedScope,
    )
  }

  private companion object {
    val DISPLAY_NAME_PATTERNS: List<Regex> = listOf(
      Regex("(?i)^(?:from now on\\s*,?\\s*)?(?:please\\s+)?(?:call yourself|your name is)\\s+(.+)$"),
      Regex("^(?:以后|之后|从现在开始|这次|这一轮|暂时|先)?(?:请)?(?:你叫|你的名字是|以后叫你|之后叫你)\\s*(.+)$"),
    )

    val USER_PREFERRED_NAME_PATTERNS: List<Regex> = listOf(
      Regex("(?i)^(?:from now on\\s*,?\\s*)?(?:please\\s+)?(?:call me|address me as|refer to me as)\\s+(.+)$"),
      Regex("^(?:以后|之后|从现在开始|这次|这一轮|暂时|先)?(?:请)?(?:叫我|以后叫我|之后叫我|称呼我|以后称呼我)\\s*(.+)$"),
    )

    val LONG_TERM_SCOPE_MARKERS: List<String> = listOf(
      "from now on",
      "going forward",
      "default",
      "always",
      "以后",
      "之后",
      "默认",
      "今后",
      "以后都",
      "从现在开始",
    )

    val SESSION_SCOPE_MARKERS: List<String> = listOf(
      "for now",
      "this session",
      "this chat",
      "for this chat",
      "this time",
      "暂时",
      "先",
      "这次",
      "这一轮",
      "这回",
    )

    val WARM_STYLE_MARKERS: List<String> = listOf(
      "warm",
      "warmer",
      "gentle",
      "gentler",
      "friendly",
      "friendlier",
      "soft",
      "softer",
      "less cold",
      "not so cold",
      "温柔",
      "温暖",
      "暖一点",
      "柔和",
      "别太冷",
      "别这么冷",
      "别冷冰冰",
    )

    val SERIOUS_STYLE_MARKERS: List<String> = listOf(
      "serious",
      "formal",
      "professional",
      "严肃",
      "正式",
      "严谨",
      "专业",
    )

    val STYLE_TARGET_MARKERS: List<String> = listOf(
      "be ",
      "sound ",
      "talk ",
      "speak ",
      "reply ",
      "tone",
      "voice",
      "说话",
      "语气",
      "风格",
      "一点",
      "一些",
    )

    val ADDRESS_STYLE_TARGET_MARKERS: List<String> = listOf(
      "call me",
      "address me",
      "refer to me",
      "how you address me",
      "称呼我",
      "叫我",
      "对我的称呼",
      "称呼方式",
      "叫我的时候",
    )

    val FRIENDLY_ADDRESS_STYLE_MARKERS: List<String> = listOf(
      "friendly",
      "friendlier",
      "casual",
      "more casual",
      "like a friend",
      "亲切",
      "亲和",
      "随和",
      "自然一点",
      "像朋友一样",
      "朋友一点",
    )

    val INTIMATE_ADDRESS_STYLE_MARKERS: List<String> = listOf(
      "intimate",
      "more intimate",
      "affectionate",
      "closer",
      "更亲密",
      "亲密一点",
      "亲近一点",
      "更亲近",
      "暧昧一点",
    )

    val NEUTRAL_ADDRESS_STYLE_MARKERS: List<String> = listOf(
      "neutral",
      "more neutral",
      "normal",
      "normal way",
      "standard",
      "中性",
      "正常一点",
      "普通一点",
      "正常称呼",
      "普通称呼",
      "别太亲密",
      "别太亲昵",
    )

    val NON_NAMING_PREFERRED_NAME_MARKERS: List<String> = listOf(
      "friendly",
      "intimate",
      "neutral",
      "casual",
      "style",
      "一点",
      "一些",
      "亲切",
      "亲密",
      "亲近",
      "中性",
      "正常",
      "普通",
      "称呼方式",
      "像朋友一样",
    )

    val TERSE_VERBOSITY_MARKERS: List<String> = listOf(
      "brief",
      "briefer",
      "concise",
      "more concise",
      "shorter",
      "keep it short",
      "keep it brief",
      "terse",
      "less verbose",
      "简洁",
      "简短",
      "精炼",
      "简明",
      "短一点",
      "少说一点",
      "别太长",
      "言简意赅",
    )

    val EXPANSIVE_VERBOSITY_MARKERS: List<String> = listOf(
      "detailed",
      "more detailed",
      "detail",
      "elaborate",
      "more verbose",
      "go deeper",
      "详细",
      "更详细",
      "展开",
      "多讲一点",
      "说细一点",
      "讲细一点",
      "详细一点",
      "多一点细节",
    )

    val VERBOSITY_TARGET_MARKERS: List<String> = listOf(
      "reply",
      "respond",
      "answer",
      "explain",
      "回答",
      "回复",
      "解释",
      "说明",
      "说话",
    )

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

    const val MAX_DISPLAY_NAME_CHARS: Int = 32
    const val MAX_PREFERRED_NAME_CHARS: Int = 32
  }

  private fun extractDisplayName(normalized: String): String? {
    val match = DISPLAY_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
      pattern.matchEntire(normalized)?.groupValues?.getOrNull(1)
    } ?: return null
    return normalizeMemoryPreferenceValueOrNull(
      match.removePrefix("叫你").trim(),
    )?.takeIf { value ->
      value.length <= MAX_DISPLAY_NAME_CHARS
    }
  }

  private fun extractPreferredNaming(normalized: String): String? {
    val match = USER_PREFERRED_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
      pattern.matchEntire(normalized)?.groupValues?.getOrNull(1)
    } ?: return null
    return normalizeMemoryPreferenceValueOrNull(match)?.takeIf(::looksLikePreferredNaming)
  }

  private fun looksLikePreferredNaming(candidate: String): Boolean {
    if (candidate.length > MAX_PREFERRED_NAME_CHARS) {
      return false
    }
    if (candidate.split(Regex("\\s+")).size > 4) {
      return false
    }
    val lowered = candidate.lowercase(Locale.US)
    return NON_NAMING_PREFERRED_NAME_MARKERS.none { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || candidate.contains(marker)
    }
  }

  private fun resolveStyleProfile(normalized: String): String? {
    if (!looksLikeStylePreference(normalized)) {
      return null
    }
    val lowered = normalized.lowercase(Locale.US)
    return when {
      WARM_STYLE_MARKERS.any { marker -> lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker) } -> "warm"
      SERIOUS_STYLE_MARKERS.any { marker -> lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker) } -> "serious"
      else -> null
    }
  }

  private fun resolvePreferredAddressStyle(normalized: String): String? {
    if (!looksLikeAddressStylePreference(normalized)) {
      return null
    }
    val lowered = normalized.lowercase(Locale.US)
    return when {
      INTIMATE_ADDRESS_STYLE_MARKERS.any { marker ->
        lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
      } -> "intimate"

      FRIENDLY_ADDRESS_STYLE_MARKERS.any { marker ->
        lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
      } -> "friendly"

      NEUTRAL_ADDRESS_STYLE_MARKERS.any { marker ->
        lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
      } -> "neutral"

      else -> null
    }
  }

  private fun looksLikeAddressStylePreference(normalized: String): Boolean {
    val lowered = normalized.lowercase(Locale.US)
    val hasTargetMarker = ADDRESS_STYLE_TARGET_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    }
    if (!hasTargetMarker) {
      return false
    }
    return FRIENDLY_ADDRESS_STYLE_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    } || INTIMATE_ADDRESS_STYLE_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    } || NEUTRAL_ADDRESS_STYLE_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    }
  }

  private fun looksLikeStylePreference(normalized: String): Boolean {
    val lowered = normalized.lowercase(Locale.US)
    return STYLE_TARGET_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    }
  }

  private fun resolveVerbosityProfile(normalized: String): String? {
    if (!looksLikeVerbosityPreference(normalized)) {
      return null
    }
    val lowered = normalized.lowercase(Locale.US)
    return when {
      TERSE_VERBOSITY_MARKERS.any { marker -> lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker) } ->
        "terse"

      EXPANSIVE_VERBOSITY_MARKERS.any { marker ->
        lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
      } -> "expansive"

      else -> null
    }
  }

  private fun looksLikeVerbosityPreference(normalized: String): Boolean {
    val lowered = normalized.lowercase(Locale.US)
    val hasTargetMarker = VERBOSITY_TARGET_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    }
    if (!hasTargetMarker) {
      return false
    }
    return TERSE_VERBOSITY_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    } || EXPANSIVE_VERBOSITY_MARKERS.any { marker ->
      lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker)
    }
  }

  private fun resolvePreferenceScope(
    normalized: String,
    defaultScope: MemoryScope,
  ): MemoryScope {
    val lowered = normalized.lowercase(Locale.US)
    if (SESSION_SCOPE_MARKERS.any { marker -> lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker) }) {
      return MemoryScope.SESSION
    }
    if (LONG_TERM_SCOPE_MARKERS.any { marker -> lowered.contains(marker.lowercase(Locale.US)) || normalized.contains(marker) }) {
      return MemoryScope.USER
    }
    return defaultScope
  }

  private data class SoulPreferenceCandidateExtraction(
    val candidates: List<MemoryCandidate> = emptyList(),
    val allowHeuristicSoulFallback: Boolean,
  )

  private data class UserIntentCandidateExtraction(
    val candidates: List<MemoryCandidate> = emptyList(),
    val allowHeuristicUserFallback: Boolean,
  )

}
