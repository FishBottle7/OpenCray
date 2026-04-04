package com.opencray.app

internal enum class LlmModelCapabilitySource {
  EXPLICIT_OVERRIDE,
  PROVIDER_DECLARED,
  STATIC_EXACT,
  STATIC_FAMILY,
  REGEX_FALLBACK,
  DEFAULT_FALLBACK,
}

internal data class LlmModelCapabilityResolution(
  val visionInputSupported: Boolean,
  val pdfInputSupported: Boolean = false,
  val source: LlmModelCapabilitySource,
  val matchedRuleId: String? = null,
)

internal data class LlmModelContextWindowResolution(
  val contextWindowTokens: Int,
  val source: LlmModelCapabilitySource,
  val matchedRuleId: String? = null,
)

private data class LlmStaticCapabilityRule(
  val id: String,
  val visionInputSupported: Boolean,
  val providerIds: Set<String> = emptySet(),
  val protocols: Set<String> = emptySet(),
  val exactModels: Set<String> = emptySet(),
  val prefixes: Set<String> = emptySet(),
  val substrings: Set<String> = emptySet(),
  val regexes: List<Regex> = emptyList(),
) {
  fun matchesExact(
    providerCandidates: Set<String>,
    protocol: String,
    candidates: Set<String>,
  ): Boolean =
    matchesRoute(providerCandidates = providerCandidates, protocol = protocol) &&
      exactModels.isNotEmpty() &&
      exactModels.any { model -> model in candidates }

  fun matchesFamily(
    providerCandidates: Set<String>,
    protocol: String,
    rawModel: String,
    candidates: Set<String>,
  ): Boolean {
    if (!matchesRoute(providerCandidates = providerCandidates, protocol = protocol)) {
      return false
    }
    if (prefixes.any { prefix -> candidates.any { candidate -> candidate.startsWith(prefix) } }) {
      return true
    }
    if (substrings.any { substring -> rawModel.contains(substring) }) {
      return true
    }
    return regexes.any { regex -> regex.containsMatchIn(rawModel) }
  }

  private fun matchesRoute(
    providerCandidates: Set<String>,
    protocol: String,
  ): Boolean {
    if (providerIds.isNotEmpty() && providerCandidates.none(providerIds::contains)) {
      return false
    }
    if (protocols.isNotEmpty() && protocol !in protocols) {
      return false
    }
    return true
  }
}

private data class LlmStaticContextWindowRule(
  val id: String,
  val contextWindowTokens: Int,
  val providerIds: Set<String> = emptySet(),
  val protocols: Set<String> = emptySet(),
  val exactModels: Set<String> = emptySet(),
  val prefixes: Set<String> = emptySet(),
  val substrings: Set<String> = emptySet(),
  val regexes: List<Regex> = emptyList(),
) {
  fun matchesExact(
    providerCandidates: Set<String>,
    protocol: String,
    candidates: Set<String>,
  ): Boolean =
    matchesRoute(providerCandidates = providerCandidates, protocol = protocol) &&
      exactModels.isNotEmpty() &&
      exactModels.any { model -> model in candidates }

  fun matchesFamily(
    providerCandidates: Set<String>,
    protocol: String,
    rawModel: String,
    candidates: Set<String>,
  ): Boolean {
    if (!matchesRoute(providerCandidates = providerCandidates, protocol = protocol)) {
      return false
    }
    if (prefixes.any { prefix -> candidates.any { candidate -> candidate.startsWith(prefix) } }) {
      return true
    }
    if (substrings.any { substring -> rawModel.contains(substring) }) {
      return true
    }
    return regexes.any { regex -> regex.containsMatchIn(rawModel) }
  }

  private fun matchesRoute(
    providerCandidates: Set<String>,
    protocol: String,
  ): Boolean {
    if (providerIds.isNotEmpty() && providerCandidates.none(providerIds::contains)) {
      return false
    }
    if (protocols.isNotEmpty() && protocol !in protocols) {
      return false
    }
    return true
  }
}

internal object LlmModelCapabilityRegistry {
  private const val DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 128_000
  private const val METADATA_PROVIDER_DECLARED_CONTEXT_WINDOW_TOKENS: String =
    "providerDeclaredContextWindowTokens"
  private const val METADATA_CONTEXT_WINDOW_TOKENS: String = "contextWindowTokens"
  private const val METADATA_CONTEXT_WINDOW_TOKENS_SNAKE: String = "context_window_tokens"
  private const val METADATA_PROVIDER_DECLARED_VISION_INPUT_SUPPORTED: String =
    "providerDeclaredVisionInputSupported"
  private const val METADATA_PROVIDER_VISION_INPUT_SUPPORTED: String =
    "providerVisionInputSupported"
  private const val METADATA_VISION_INPUT_SUPPORTED: String = "visionInputSupported"
  private const val METADATA_PROVIDER_DECLARED_PDF_INPUT_SUPPORTED: String =
    "providerDeclaredPdfInputSupported"
  private const val METADATA_PROVIDER_PDF_INPUT_SUPPORTED: String =
    "providerPdfInputSupported"
  private const val METADATA_PDF_INPUT_SUPPORTED: String = "pdfInputSupported"

  fun resolveContextWindow(
    providerId: String,
    protocol: String,
    model: String,
    metadata: Map<String, String> = emptyMap(),
  ): LlmModelContextWindowResolution {
    explicitContextWindowOverride(metadata)?.let { override ->
      return LlmModelContextWindowResolution(
        contextWindowTokens = override,
        source = LlmModelCapabilitySource.EXPLICIT_OVERRIDE,
      )
    }

    val normalizedProviderId = providerId.trim().lowercase()
    val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
    val normalizedModel = model.trim().lowercase()
    if (normalizedModel.isBlank()) {
      return LlmModelContextWindowResolution(
        contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS,
        source = LlmModelCapabilitySource.DEFAULT_FALLBACK,
      )
    }
    val modelCandidates = normalizedModelCandidates(normalizedModel)
    val providerCandidates = capabilityProviderCandidates(
      providerId = normalizedProviderId,
      modelCandidates = modelCandidates,
    )

    EXACT_CONTEXT_WINDOW_RULES.firstOrNull { rule ->
      rule.matchesExact(
        providerCandidates = providerCandidates,
        protocol = normalizedProtocol,
        candidates = modelCandidates,
      )
    }?.let { rule ->
      return LlmModelContextWindowResolution(
        contextWindowTokens = rule.contextWindowTokens,
        source = LlmModelCapabilitySource.STATIC_EXACT,
        matchedRuleId = rule.id,
      )
    }

    FAMILY_CONTEXT_WINDOW_RULES.firstOrNull { rule ->
      rule.matchesFamily(
        providerCandidates = providerCandidates,
        protocol = normalizedProtocol,
        rawModel = normalizedModel,
        candidates = modelCandidates,
      )
    }?.let { rule ->
      return LlmModelContextWindowResolution(
        contextWindowTokens = rule.contextWindowTokens,
        source = LlmModelCapabilitySource.STATIC_FAMILY,
        matchedRuleId = rule.id,
      )
    }

    return LlmModelContextWindowResolution(
      contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS,
      source = LlmModelCapabilitySource.DEFAULT_FALLBACK,
    )
  }

  fun resolveVisionInputSupport(
    providerId: String,
    protocol: String,
    model: String,
    metadata: Map<String, String> = emptyMap(),
  ): LlmModelCapabilityResolution? {
    explicitVisionOverride(metadata)?.let { override ->
      return LlmModelCapabilityResolution(
        visionInputSupported = override,
        pdfInputSupported = false,
        source = LlmModelCapabilitySource.PROVIDER_DECLARED,
      )
    }

    val normalizedProviderId = providerId.trim().lowercase()
    val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
    val normalizedModel = model.trim().lowercase()
    if (normalizedModel.isBlank()) {
      return null
    }
    val modelCandidates = normalizedModelCandidates(normalizedModel)
    val providerCandidates = capabilityProviderCandidates(
      providerId = normalizedProviderId,
      modelCandidates = modelCandidates,
    )

    EXACT_VISION_RULES.firstOrNull { rule ->
      rule.matchesExact(
        providerCandidates = providerCandidates,
        protocol = normalizedProtocol,
        candidates = modelCandidates,
      )
    }?.let { rule ->
      return LlmModelCapabilityResolution(
        visionInputSupported = rule.visionInputSupported,
        pdfInputSupported = false,
        source = LlmModelCapabilitySource.STATIC_EXACT,
        matchedRuleId = rule.id,
      )
    }

    FAMILY_VISION_RULES.firstOrNull { rule ->
      rule.matchesFamily(
        providerCandidates = providerCandidates,
        protocol = normalizedProtocol,
        rawModel = normalizedModel,
        candidates = modelCandidates,
      )
    }?.let { rule ->
      return LlmModelCapabilityResolution(
        visionInputSupported = rule.visionInputSupported,
        pdfInputSupported = false,
        source = LlmModelCapabilitySource.STATIC_FAMILY,
        matchedRuleId = rule.id,
      )
    }

    regexFallbackVisionInputSupport(
      protocol = normalizedProtocol,
      model = normalizedModel,
    )?.let { fallback ->
      return LlmModelCapabilityResolution(
        visionInputSupported = fallback,
        pdfInputSupported = false,
        source = LlmModelCapabilitySource.REGEX_FALLBACK,
      )
    }

    return null
  }

  fun resolvePdfInputSupport(
    providerId: String,
    protocol: String,
    model: String,
    metadata: Map<String, String> = emptyMap(),
  ): LlmModelCapabilityResolution? {
    explicitPdfOverride(metadata)?.let { override ->
      return LlmModelCapabilityResolution(
        visionInputSupported = false,
        pdfInputSupported = override,
        source = LlmModelCapabilitySource.PROVIDER_DECLARED,
      )
    }

    val normalizedProviderId = providerId.trim().lowercase()
    val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
    val normalizedModel = model.trim().lowercase()
    if (normalizedModel.isBlank()) {
      return null
    }
    val modelCandidates = normalizedModelCandidates(normalizedModel)
    val providerCandidates = capabilityProviderCandidates(
      providerId = normalizedProviderId,
      modelCandidates = modelCandidates,
    )

    EXACT_PDF_RULES.firstOrNull { rule ->
      rule.matchesExact(
        providerCandidates = providerCandidates,
        protocol = normalizedProtocol,
        candidates = modelCandidates,
      )
    }?.let { rule ->
      return LlmModelCapabilityResolution(
        visionInputSupported = false,
        pdfInputSupported = rule.visionInputSupported,
        source = LlmModelCapabilitySource.STATIC_EXACT,
        matchedRuleId = rule.id,
      )
    }

    FAMILY_PDF_RULES.firstOrNull { rule ->
      rule.matchesFamily(
        providerCandidates = providerCandidates,
        protocol = normalizedProtocol,
        rawModel = normalizedModel,
        candidates = modelCandidates,
      )
    }?.let { rule ->
      return LlmModelCapabilityResolution(
        visionInputSupported = false,
        pdfInputSupported = rule.visionInputSupported,
        source = LlmModelCapabilitySource.STATIC_FAMILY,
        matchedRuleId = rule.id,
      )
    }

    return null
  }

  private fun explicitVisionOverride(
    metadata: Map<String, String>,
  ): Boolean? = parseBooleanMetadata(
    metadata[METADATA_PROVIDER_DECLARED_VISION_INPUT_SUPPORTED],
  ) ?: parseBooleanMetadata(
    metadata[METADATA_PROVIDER_VISION_INPUT_SUPPORTED],
  ) ?: parseBooleanMetadata(
    metadata[METADATA_VISION_INPUT_SUPPORTED],
  )

  private fun explicitPdfOverride(
    metadata: Map<String, String>,
  ): Boolean? = parseBooleanMetadata(
    metadata[METADATA_PROVIDER_DECLARED_PDF_INPUT_SUPPORTED],
  ) ?: parseBooleanMetadata(
    metadata[METADATA_PROVIDER_PDF_INPUT_SUPPORTED],
  ) ?: parseBooleanMetadata(
    metadata[METADATA_PDF_INPUT_SUPPORTED],
  )

  private fun explicitContextWindowOverride(
    metadata: Map<String, String>,
  ): Int? = parsePositiveIntMetadata(
    metadata[METADATA_PROVIDER_DECLARED_CONTEXT_WINDOW_TOKENS],
  ) ?: parsePositiveIntMetadata(
    metadata[METADATA_CONTEXT_WINDOW_TOKENS],
  ) ?: parsePositiveIntMetadata(
    metadata[METADATA_CONTEXT_WINDOW_TOKENS_SNAKE],
  )

  private fun parseBooleanMetadata(
    rawValue: String?,
  ): Boolean? = when (rawValue?.trim()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
  }

  private fun parsePositiveIntMetadata(
    rawValue: String?,
  ): Int? = rawValue
    ?.trim()
    ?.toIntOrNull()
    ?.takeIf { value -> value > 0 }

  private fun normalizedModelCandidates(
    normalizedModel: String,
  ): Set<String> = linkedSetOf<String>().apply {
    val raw = normalizedModel.trim()
    if (raw.isEmpty()) {
      return@apply
    }
    add(raw)
    add(raw.substringBefore(':'))
    val leaf = raw.substringAfterLast('/')
    add(leaf)
    add(leaf.substringBefore(':'))
  }

  private fun capabilityProviderCandidates(
    providerId: String,
    modelCandidates: Set<String>,
  ): Set<String> = linkedSetOf<String>().apply {
    providerId.trim()
      .lowercase()
      .takeIf(String::isNotBlank)
      ?.let(::add)
    modelCandidates.forEach { candidate ->
      addAll(inferredProviderIdsFromModelCandidate(candidate))
    }
  }

  private fun inferredProviderIdsFromModelCandidate(
    candidate: String,
  ): Set<String> = buildSet {
    val normalizedCandidate = candidate.trim().lowercase()
    if (normalizedCandidate.isBlank()) {
      return@buildSet
    }
    when (normalizedCandidate.substringBefore('/')) {
      "openai" -> add("openai")
      "anthropic" -> add("anthropic")
      "deepseek" -> add("deepseek")
      "google",
      "gemini",
      -> {
        add("google")
        add("gemini")
      }
      "moonshot",
      "moonshotai",
      "kimi",
      -> {
        add("moonshot")
        add("moonshotai")
        add("kimi")
      }
    }
    when {
      normalizedCandidate.startsWith("gpt-") ||
        normalizedCandidate.startsWith("o1") ||
        normalizedCandidate.startsWith("o3") ||
        normalizedCandidate.startsWith("o4") ||
        normalizedCandidate.startsWith("text-embedding-") ||
        normalizedCandidate.startsWith("whisper-") ||
        normalizedCandidate.startsWith("tts-") ||
        normalizedCandidate.startsWith("gpt-image-") ||
        normalizedCandidate.startsWith("omni-moderation-") ||
        normalizedCandidate.startsWith("text-moderation-") ->
        add("openai")

      normalizedCandidate.startsWith("claude-") ->
        add("anthropic")

      normalizedCandidate.startsWith("gemini") -> {
        add("google")
        add("gemini")
      }

      normalizedCandidate.startsWith("deepseek") ->
        add("deepseek")

      normalizedCandidate.startsWith("kimi-") -> {
        add("moonshot")
        add("moonshotai")
        add("kimi")
      }
    }
  }

  private fun regexFallbackVisionInputSupport(
    protocol: String,
    model: String,
  ): Boolean? {
    if (model.isBlank()) {
      return null
    }
    if (VISION_EXCLUSION_PATTERNS.any { pattern -> pattern.containsMatchIn(model) }) {
      return false
    }
    if (protocol == LlmProviderProtocols.ANTHROPIC && model.startsWith("claude-2")) {
      return false
    }
    return if (VISION_FALLBACK_INCLUDE_PATTERNS.any { pattern -> pattern.containsMatchIn(model) }) {
      true
    } else {
      null
    }
  }

  private val OPENAI_PROVIDER_IDS: Set<String> = setOf("openai")
  private val ANTHROPIC_PROVIDER_IDS: Set<String> = setOf("anthropic")
  private val GEMINI_PROVIDER_IDS: Set<String> = setOf("google", "gemini")
  private val DEEPSEEK_PROVIDER_IDS: Set<String> = setOf("deepseek")
  private val KIMI_PROVIDER_IDS: Set<String> = setOf("moonshot", "moonshotai", "kimi")

  private val EXACT_VISION_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "openai_text_only",
      visionInputSupported = false,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf(
        "text-embedding-3-large",
        "text-embedding-3-small",
        "text-embedding-ada-002",
        "whisper-1",
        "tts-1",
        "tts-1-hd",
        "gpt-image-1",
        "omni-moderation-latest",
        "text-moderation-latest",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "openai_vision_exact",
      visionInputSupported = true,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
        "gpt-4-turbo",
        "gpt-4-vision-preview",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "o1",
        "o1-mini",
        "o1-preview",
        "o3",
        "o3-mini",
        "o4-mini",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "anthropic_vision_exact",
      visionInputSupported = true,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      exactModels = setOf(
        "claude-3-opus",
        "claude-3-sonnet",
        "claude-3-haiku",
        "claude-3-5-sonnet",
        "claude-3-5-haiku",
        "claude-3-7-sonnet",
        "claude-opus-4",
        "claude-sonnet-4",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "gemini_vision_exact",
      visionInputSupported = true,
      providerIds = GEMINI_PROVIDER_IDS,
      exactModels = setOf(
        "gemini-1.5-pro",
        "gemini-1.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "kimi_k2_5_vision_exact",
      visionInputSupported = true,
      providerIds = KIMI_PROVIDER_IDS,
      exactModels = setOf("kimi-k2.5"),
    ),
  )

  private val FAMILY_VISION_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "anthropic_text_only_legacy",
      visionInputSupported = false,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      prefixes = setOf("claude-2"),
    ),
    LlmStaticCapabilityRule(
      id = "openai_vision_family",
      visionInputSupported = true,
      providerIds = OPENAI_PROVIDER_IDS,
      prefixes = setOf(
        "gpt-4o",
        "gpt-4.1",
        "gpt-4-turbo",
        "gpt-4-vision",
        "gpt-5",
        "o1",
        "o3",
        "o4",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "anthropic_vision_family",
      visionInputSupported = true,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      prefixes = setOf(
        "claude-3",
        "claude-3.5",
        "claude-3.7",
        "claude-opus-4",
        "claude-sonnet-4",
      ),
      regexes = listOf(
        Regex("""\bclaude[-_ ]?(opus|sonnet|haiku)[-_ ]?4\b"""),
      ),
    ),
    LlmStaticCapabilityRule(
      id = "gemini_vision_family",
      visionInputSupported = true,
      providerIds = GEMINI_PROVIDER_IDS,
      prefixes = setOf(
        "gemini-1.5",
        "gemini-2.0",
        "gemini-2.5",
      ),
      substrings = setOf("gemini-exp"),
    ),
    LlmStaticCapabilityRule(
      id = "kimi_k2_5_vision_family",
      visionInputSupported = true,
      providerIds = KIMI_PROVIDER_IDS,
      prefixes = setOf("kimi-k2.5"),
    ),
    LlmStaticCapabilityRule(
      id = "qwen_vl_family",
      visionInputSupported = true,
      substrings = setOf(
        "qwen-vl",
        "qwen2-vl",
        "qwen2.5-vl",
        "qwen-vl-max",
        "qwen-vl-plus",
        "qvq",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "pixtral_family",
      visionInputSupported = true,
      prefixes = setOf("pixtral"),
    ),
    LlmStaticCapabilityRule(
      id = "llava_family",
      visionInputSupported = true,
      prefixes = setOf("llava"),
      substrings = setOf("llava"),
    ),
    LlmStaticCapabilityRule(
      id = "internvl_family",
      visionInputSupported = true,
      prefixes = setOf("internvl"),
      substrings = setOf("internvl"),
    ),
    LlmStaticCapabilityRule(
      id = "minicpm_vision_family",
      visionInputSupported = true,
      substrings = setOf(
        "minicpm-v",
        "minicpmo",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "glm_vision_family",
      visionInputSupported = true,
      substrings = setOf(
        "glm-4v",
        "glm-4.1v",
        "glm-4.5v",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "phi_vision_family",
      visionInputSupported = true,
      substrings = setOf(
        "phi-3-vision",
        "phi-3.5-vision",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "molmo_family",
      visionInputSupported = true,
      prefixes = setOf("molmo"),
    ),
    LlmStaticCapabilityRule(
      id = "step_vision_family",
      visionInputSupported = true,
      prefixes = setOf("step-1v"),
    ),
    LlmStaticCapabilityRule(
      id = "yi_vision_family",
      visionInputSupported = true,
      substrings = setOf(
        "yi-vl",
        "yi-vision",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "doubao_vision_family",
      visionInputSupported = true,
      substrings = setOf("doubao-vision"),
    ),
    LlmStaticCapabilityRule(
      id = "hunyuan_vision_family",
      visionInputSupported = true,
      substrings = setOf("hunyuan-vision"),
    ),
  )

  private val EXACT_CONTEXT_WINDOW_RULES: List<LlmStaticContextWindowRule> = listOf(
    LlmStaticContextWindowRule(
      id = "openai_gpt_4o_context_window_exact",
      contextWindowTokens = 128_000,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf(
        "gpt-4o",
        "gpt-4o-mini",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "openai_gpt_4_1_context_window_exact",
      contextWindowTokens = 1_047_576,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf(
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "openai_gpt_5_4_context_window_exact",
      contextWindowTokens = 1_048_576,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf(
        "gpt-5.4",
        "gpt-5.4-nano",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "openai_gpt_5_4_mini_context_window_exact",
      contextWindowTokens = 400_000,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf("gpt-5.4-mini"),
    ),
    LlmStaticContextWindowRule(
      id = "openai_reasoning_context_window_exact",
      contextWindowTokens = 200_000,
      providerIds = OPENAI_PROVIDER_IDS,
      exactModels = setOf(
        "o1",
        "o1-mini",
        "o1-preview",
        "o3",
        "o3-mini",
        "o4-mini",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "anthropic_context_window_exact",
      contextWindowTokens = 200_000,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      exactModels = setOf(
        "claude-3-opus",
        "claude-3-sonnet",
        "claude-3-haiku",
        "claude-3-5-sonnet",
        "claude-3-5-haiku",
        "claude-3-7-sonnet",
        "claude-sonnet-4",
        "claude-sonnet-4-5",
        "claude-opus-4",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "anthropic_context_window_exact_1m",
      contextWindowTokens = 1_048_576,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      exactModels = setOf(
        "claude-sonnet-4-6",
        "claude-opus-4-6",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "gemini_context_window_exact",
      contextWindowTokens = 1_048_576,
      providerIds = GEMINI_PROVIDER_IDS,
      exactModels = setOf(
        "gemini-1.5-pro",
        "gemini-1.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "deepseek_context_window_exact",
      contextWindowTokens = 128_000,
      providerIds = DEEPSEEK_PROVIDER_IDS,
      exactModels = setOf(
        "deepseek-chat",
        "deepseek-reasoner",
      ),
    ),
  )

  private val FAMILY_CONTEXT_WINDOW_RULES: List<LlmStaticContextWindowRule> = listOf(
    LlmStaticContextWindowRule(
      id = "openai_gpt_4o_context_window_family",
      contextWindowTokens = 128_000,
      providerIds = OPENAI_PROVIDER_IDS,
      prefixes = setOf("gpt-4o"),
    ),
    LlmStaticContextWindowRule(
      id = "openai_gpt_4_1_context_window_family",
      contextWindowTokens = 1_047_576,
      providerIds = OPENAI_PROVIDER_IDS,
      prefixes = setOf("gpt-4.1"),
    ),
    LlmStaticContextWindowRule(
      id = "openai_gpt_5_4_context_window_family",
      contextWindowTokens = 1_048_576,
      providerIds = OPENAI_PROVIDER_IDS,
      prefixes = setOf("gpt-5.4"),
    ),
    LlmStaticContextWindowRule(
      id = "openai_gpt_5_context_window_family",
      contextWindowTokens = 400_000,
      providerIds = OPENAI_PROVIDER_IDS,
      prefixes = setOf(
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "openai_reasoning_context_window_family",
      contextWindowTokens = 200_000,
      providerIds = OPENAI_PROVIDER_IDS,
      prefixes = setOf(
        "o1",
        "o3",
        "o4",
      ),
    ),
    LlmStaticContextWindowRule(
      id = "anthropic_context_window_family",
      contextWindowTokens = 200_000,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      prefixes = setOf(
        "claude-3",
        "claude-3.5",
        "claude-3.7",
        "claude-3-5",
        "claude-3-7",
        "claude-sonnet-4",
        "claude-opus-4",
      ),
      regexes = listOf(
        Regex("""\bclaude[-_ ]?(sonnet|opus)[-_ ]?4(?:[-_. ]?5)?\b"""),
      ),
    ),
    LlmStaticContextWindowRule(
      id = "anthropic_context_window_family_1m",
      contextWindowTokens = 1_048_576,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      regexes = listOf(
        Regex("""\bclaude[-_ ]?(sonnet|opus)[-_ ]?4(?:[-_. ]?6)\b"""),
      ),
    ),
    LlmStaticContextWindowRule(
      id = "gemini_context_window_family",
      contextWindowTokens = 1_048_576,
      providerIds = GEMINI_PROVIDER_IDS,
      prefixes = setOf(
        "gemini-1.5",
        "gemini-2.0",
        "gemini-2.5",
      ),
      substrings = setOf("gemini-exp"),
    ),
    LlmStaticContextWindowRule(
      id = "deepseek_context_window_family",
      contextWindowTokens = 128_000,
      providerIds = DEEPSEEK_PROVIDER_IDS,
      prefixes = setOf("deepseek"),
    ),
  )

  private val EXACT_PDF_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "openai_pdf_exact",
      visionInputSupported = true,
      providerIds = OPENAI_PROVIDER_IDS,
      protocols = setOf(
        LlmProviderProtocols.OPENAI,
        LlmProviderProtocols.OPENAI_RESPONSES,
      ),
      exactModels = setOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "o1",
        "o1-mini",
        "o1-preview",
        "o3",
        "o3-mini",
        "o4-mini",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "anthropic_pdf_exact",
      visionInputSupported = true,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      protocols = setOf(LlmProviderProtocols.ANTHROPIC),
      exactModels = setOf(
        "claude-3-5-sonnet",
        "claude-3-5-haiku",
        "claude-3-7-sonnet",
        "claude-opus-4",
        "claude-sonnet-4",
      ),
    ),
  )

  private val FAMILY_PDF_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "openai_pdf_family",
      visionInputSupported = true,
      providerIds = OPENAI_PROVIDER_IDS,
      protocols = setOf(
        LlmProviderProtocols.OPENAI,
        LlmProviderProtocols.OPENAI_RESPONSES,
      ),
      prefixes = setOf(
        "gpt-4o",
        "gpt-4.1",
        "gpt-5",
        "o1",
        "o3",
        "o4",
      ),
    ),
    LlmStaticCapabilityRule(
      id = "anthropic_pdf_family",
      visionInputSupported = true,
      providerIds = ANTHROPIC_PROVIDER_IDS,
      protocols = setOf(LlmProviderProtocols.ANTHROPIC),
      prefixes = setOf(
        "claude-3.5",
        "claude-3-5",
        "claude-3.7",
        "claude-3-7",
        "claude-opus-4",
        "claude-sonnet-4",
      ),
      regexes = listOf(
        Regex("""\bclaude[-_ ]?3[-_. ]?(5|7)\b"""),
        Regex("""\bclaude[-_ ]?(opus|sonnet)[-_ ]?4\b"""),
      ),
    ),
  )

  private val VISION_EXCLUSION_PATTERNS: List<Regex> = listOf(
    Regex("""(^|[-_/])(embedding|embeddings|rerank|moderation|transcription|whisper|tts|speech|audio|realtime)([-_/]|$)"""),
    Regex("""(^|[-_/])(image[-_]?generation|imagegen|dall[-_]?e)([-_/]|$)"""),
  )

  private val VISION_FALLBACK_INCLUDE_PATTERNS: List<Regex> = listOf(
    Regex("""(^|[-_/])(vision|vl|omni)([-_/]|$)"""),
    Regex("""\bgpt[-_]?4o\b"""),
    Regex("""\bgpt[-_]?4\.1\b"""),
    Regex("""\bgpt[-_]?4[-_]?turbo\b"""),
    Regex("""\bgpt[-_]?5\b"""),
    Regex("""(^|[-_/])(o1|o3|o4)([-_/]|$)"""),
    Regex("""\bclaude[-_ ]?3\b"""),
    Regex("""\bgemini\b"""),
  )
}

internal fun effectiveLlmCapabilityMetadata(
  providerId: String,
  protocol: String,
  model: String,
  agentCapability: LlmAgentCapabilitySnapshot,
  baseRouteMetadata: Map<String, String> = emptyMap(),
): Map<String, String> {
  val persistedMetadata = agentCapability.runtimeMetadataOverrides()
  val resolvedContextWindowMetadata = if (
    persistedMetadata.containsKey("contextWindowTokens") ||
    persistedMetadata.containsKey("context_window_tokens")
  ) {
    emptyMap()
  } else {
    mapOf(
      "context_window_tokens" to LlmModelCapabilityRegistry.resolveContextWindow(
        providerId = providerId,
        protocol = protocol,
        model = model,
        metadata = baseRouteMetadata,
      ).contextWindowTokens.toString(),
    )
  }
  val resolvedVisionMetadata = if (persistedMetadata.containsKey("visionInputSupported")) {
    emptyMap()
  } else {
    LlmModelCapabilityRegistry.resolveVisionInputSupport(
      providerId = providerId,
      protocol = protocol,
      model = model,
      metadata = baseRouteMetadata,
    )?.let { resolution ->
      mapOf("visionInputSupported" to resolution.visionInputSupported.toString())
    } ?: emptyMap()
  }
  val resolvedPdfMetadata = if (persistedMetadata.containsKey("pdfInputSupported")) {
    emptyMap()
  } else {
    LlmModelCapabilityRegistry.resolvePdfInputSupport(
      providerId = providerId,
      protocol = protocol,
      model = model,
      metadata = baseRouteMetadata,
    )?.let { resolution ->
      mapOf("pdfInputSupported" to resolution.pdfInputSupported.toString())
    } ?: emptyMap()
  }
  return resolvedContextWindowMetadata + resolvedVisionMetadata + resolvedPdfMetadata + persistedMetadata
}

internal fun effectiveLlmRouteMetadata(
  providerId: String,
  protocol: String,
  model: String,
  reasoningEffort: String,
  agentCapability: LlmAgentCapabilitySnapshot,
  openAiPromptCacheKeyStrategy: String = LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
  openAiPromptCacheRetention: String = LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
  anthropicPromptCachingEnabled: Boolean =
    LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
  anthropicPromptCacheTtl: String = LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
): Map<String, String> {
  val baseRouteMetadata = LlmProviderProtocols.routeMetadata(
    protocol = protocol,
    model = model,
    reasoningEffort = reasoningEffort,
    openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
    openAiPromptCacheRetention = openAiPromptCacheRetention,
    anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
    anthropicPromptCacheTtl = anthropicPromptCacheTtl,
  )
  return baseRouteMetadata + effectiveLlmCapabilityMetadata(
    providerId = providerId,
    protocol = protocol,
    model = model,
    agentCapability = agentCapability,
    baseRouteMetadata = baseRouteMetadata,
  )
}

internal fun effectiveLlmRouteMetadata(
  settings: LlmSettingsState,
  reasoningEffort: String = settings.reasoningEffort,
): Map<String, String> = effectiveLlmRouteMetadata(
  providerId = settings.providerId,
  protocol = settings.protocol,
  model = settings.model,
  reasoningEffort = reasoningEffort,
  agentCapability = settings.agentCapability,
  openAiPromptCacheKeyStrategy = settings.openAiPromptCacheKeyStrategy,
  openAiPromptCacheRetention = settings.openAiPromptCacheRetention,
  anthropicPromptCachingEnabled = settings.anthropicPromptCachingEnabled,
  anthropicPromptCacheTtl = settings.anthropicPromptCacheTtl,
)
