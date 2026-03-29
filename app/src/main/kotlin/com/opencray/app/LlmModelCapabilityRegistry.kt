package com.opencray.app

internal enum class LlmModelCapabilitySource {
  PROVIDER_DECLARED,
  STATIC_EXACT,
  STATIC_FAMILY,
  REGEX_FALLBACK,
}

internal data class LlmModelCapabilityResolution(
  val visionInputSupported: Boolean,
  val pdfInputSupported: Boolean = false,
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
    providerId: String,
    protocol: String,
    candidates: Set<String>,
  ): Boolean =
    matchesRoute(providerId = providerId, protocol = protocol) &&
      exactModels.isNotEmpty() &&
      exactModels.any { model -> model in candidates }

  fun matchesFamily(
    providerId: String,
    protocol: String,
    rawModel: String,
    candidates: Set<String>,
  ): Boolean {
    if (!matchesRoute(providerId = providerId, protocol = protocol)) {
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
    providerId: String,
    protocol: String,
  ): Boolean {
    if (providerIds.isNotEmpty() && providerId !in providerIds) {
      return false
    }
    if (protocols.isNotEmpty() && protocol !in protocols) {
      return false
    }
    return true
  }
}

internal object LlmModelCapabilityRegistry {
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

    EXACT_VISION_RULES.firstOrNull { rule ->
      rule.matchesExact(
        providerId = normalizedProviderId,
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
        providerId = normalizedProviderId,
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

    EXACT_PDF_RULES.firstOrNull { rule ->
      rule.matchesExact(
        providerId = normalizedProviderId,
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
        providerId = normalizedProviderId,
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

  private fun parseBooleanMetadata(
    rawValue: String?,
  ): Boolean? = when (rawValue?.trim()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
  }

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

  private val EXACT_VISION_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "openai_text_only",
      visionInputSupported = false,
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
      exactModels = setOf("kimi-k2.5"),
    ),
  )

  private val FAMILY_VISION_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "anthropic_text_only_legacy",
      visionInputSupported = false,
      prefixes = setOf("claude-2"),
    ),
    LlmStaticCapabilityRule(
      id = "openai_vision_family",
      visionInputSupported = true,
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

  private val EXACT_PDF_RULES: List<LlmStaticCapabilityRule> = listOf(
    LlmStaticCapabilityRule(
      id = "openai_pdf_exact",
      visionInputSupported = true,
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
  return resolvedVisionMetadata + resolvedPdfMetadata + persistedMetadata
}

internal fun effectiveLlmRouteMetadata(
  providerId: String,
  protocol: String,
  model: String,
  reasoningEffort: String,
  agentCapability: LlmAgentCapabilitySnapshot,
): Map<String, String> {
  val baseRouteMetadata = LlmProviderProtocols.routeMetadata(
    protocol = protocol,
    model = model,
    reasoningEffort = reasoningEffort,
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
)
