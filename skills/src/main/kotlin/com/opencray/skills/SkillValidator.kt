package com.opencray.skills

import com.opencray.core.contracts.SkillMetadataValidationException
import com.opencray.core.contracts.SkillMetadataValidator
import com.opencray.core.contracts.SkillSpec
import com.opencray.core.contracts.SkillValidationCode

object SkillValidationReasonCode {
  const val INVALID_SKILL_METADATA = "INVALID_SKILL_METADATA"
}

enum class SkillInvocationControl {
  EXPLICIT_ONLY,
  EXPLICIT_AND_IMPLICIT,
}

enum class SkillExecutionContext {
  INLINE,
  FORK,
}

enum class SkillPermissionDecision {
  ALLOW,
  ASK,
  DENY,
}

data class SkillPermissionRule(
  val pattern: String,
  val decision: SkillPermissionDecision,
)

data class NormalizedSkillMetadata(
  val skillSpec: SkillSpec,
  val invocationControl: SkillInvocationControl,
  val userInvocable: Boolean,
  val executionContext: SkillExecutionContext,
  val subagent: String? = null,
  val toolPermissions: List<SkillPermissionRule> = emptyList(),
  val subagentPermissions: List<SkillPermissionRule> = emptyList(),
)

data class InvalidSkillMetadata(
  val reasonCode: String = SkillValidationReasonCode.INVALID_SKILL_METADATA,
  val field: String,
  val detail: String,
  val sourceCode: SkillValidationCode? = null,
)

class InvalidSkillMetadataException(
  val error: InvalidSkillMetadata,
  cause: Throwable? = null,
) : IllegalArgumentException(error.detail, cause) {
  val reasonCode: String = error.reasonCode
}

sealed interface SkillValidationResult {
  data class Valid(
    val metadata: NormalizedSkillMetadata,
  ) : SkillValidationResult

  data class Invalid(
    val error: InvalidSkillMetadata,
  ) : SkillValidationResult
}

object SkillValidator {
  private const val FIELD_NAME = "name"
  private const val FIELD_DESCRIPTION = "description"
  private const val FIELD_LICENSE = "license"
  private const val FIELD_COMPATIBILITY = "compatibility"
  private const val FIELD_METADATA = "metadata"
  private const val FIELD_INVOCATION_CONTROL = "invocation-control"
  private const val FIELD_DISABLE_MODEL_INVOCATION = "disable-model-invocation"
  private const val FIELD_USER_INVOCABLE = "user-invocable"
  private const val FIELD_ALLOWED_TOOLS = "allowed-tools"
  private const val FIELD_CONTEXT = "context"
  private const val FIELD_AGENT = "agent"
  private const val FIELD_TOOL_PERMISSIONS = "tool-permissions"
  private const val FIELD_SUBAGENT_PERMISSIONS = "subagent-permissions"

  private val invocationControlValues: Map<String, SkillInvocationControl> = mapOf(
    "explicit-only" to SkillInvocationControl.EXPLICIT_ONLY,
    "explicit-and-implicit" to SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
  )

  private val executionContextValues: Map<String, SkillExecutionContext> = mapOf(
    "inline" to SkillExecutionContext.INLINE,
    "fork" to SkillExecutionContext.FORK,
  )

  private val permissionDecisionValues: Map<String, SkillPermissionDecision> = mapOf(
    "allow" to SkillPermissionDecision.ALLOW,
    "ask" to SkillPermissionDecision.ASK,
    "deny" to SkillPermissionDecision.DENY,
  )

  fun validate(frontMatter: Map<String, Any?>): SkillValidationResult =
    try {
      SkillValidationResult.Valid(requireValid(frontMatter))
    } catch (error: InvalidSkillMetadataException) {
      SkillValidationResult.Invalid(error.error)
    }

  fun requireValid(frontMatter: Map<String, Any?>): NormalizedSkillMetadata {
    val name = requiredString(frontMatter, FIELD_NAME)
    val description = requiredString(frontMatter, FIELD_DESCRIPTION)
    validateBaseFields(name = name, description = description)

    val license = optionalString(frontMatter, FIELD_LICENSE)
    val compatibility = optionalCompatibility(frontMatter)
    val metadata = optionalStringMap(frontMatter, FIELD_METADATA)
    val invocationControl = resolveInvocationControl(frontMatter)
    val userInvocable = optionalBoolean(frontMatter, FIELD_USER_INVOCABLE) ?: true
    val allowedTools = optionalAllowedTools(frontMatter)
    val executionContext = resolveExecutionContext(frontMatter)
    val subagent = optionalString(frontMatter, FIELD_AGENT)
    val explicitToolPermissions = optionalPermissionRules(frontMatter, FIELD_TOOL_PERMISSIONS)
    val toolPermissions = explicitToolPermissions.ifEmpty {
      allowedTools.map { SkillPermissionRule(pattern = it, decision = SkillPermissionDecision.ALLOW) }
    }
    val subagentPermissions = optionalPermissionRules(frontMatter, FIELD_SUBAGENT_PERMISSIONS)

    if (!userInvocable && invocationControl == SkillInvocationControl.EXPLICIT_ONLY) {
      invalid(
        field = FIELD_USER_INVOCABLE,
        detail = "user-invocable=false would make an explicit-only skill unreachable.",
      )
    }

    if (subagent != null && executionContext != SkillExecutionContext.FORK) {
      invalid(
        field = FIELD_AGENT,
        detail = "agent requires context to be set to 'fork'.",
      )
    }

    if (subagentPermissions.isNotEmpty() && executionContext != SkillExecutionContext.FORK) {
      invalid(
        field = FIELD_SUBAGENT_PERMISSIONS,
        detail = "subagent-permissions require context to be set to 'fork'.",
      )
    }

    val skillSpec = buildSkillSpec(
      name = name,
      description = description,
      license = license,
      compatibility = compatibility,
      metadata = metadata,
      allowedTools = allowedTools,
      invocationControl = invocationControl,
      userInvocable = userInvocable,
      executionContext = executionContext,
      subagent = subagent,
      toolPermissions = toolPermissions,
      subagentPermissions = subagentPermissions,
    )

    return NormalizedSkillMetadata(
      skillSpec = skillSpec,
      invocationControl = invocationControl,
      userInvocable = userInvocable,
      executionContext = executionContext,
      subagent = subagent,
      toolPermissions = toolPermissions,
      subagentPermissions = subagentPermissions,
    )
  }

  private fun buildSkillSpec(
    name: String,
    description: String,
    license: String?,
    compatibility: List<String>,
    metadata: Map<String, String>,
    allowedTools: List<String>,
    invocationControl: SkillInvocationControl,
    userInvocable: Boolean,
    executionContext: SkillExecutionContext,
    subagent: String?,
    toolPermissions: List<SkillPermissionRule>,
    subagentPermissions: List<SkillPermissionRule>,
  ): SkillSpec {
    val extensions = buildMap<String, String> {
      put(FIELD_INVOCATION_CONTROL, invocationControl.serializedValue())
      put(FIELD_USER_INVOCABLE, userInvocable.toString())
      put(FIELD_CONTEXT, executionContext.serializedValue())
      subagent?.let { put(FIELD_AGENT, it) }
      if (toolPermissions.isNotEmpty()) {
        put(FIELD_TOOL_PERMISSIONS, serializePermissionRules(toolPermissions))
      }
      if (subagentPermissions.isNotEmpty()) {
        put(FIELD_SUBAGENT_PERMISSIONS, serializePermissionRules(subagentPermissions))
      }
    }

    return try {
      SkillSpec(
        name = name,
        description = description,
        license = license,
        compatibility = compatibility,
        metadata = metadata,
        allowedTools = allowedTools,
        extensions = extensions,
      )
    } catch (error: SkillMetadataValidationException) {
      val field = when (error.code) {
        SkillValidationCode.INVALID_NAME -> FIELD_NAME
        SkillValidationCode.INVALID_DESCRIPTION -> FIELD_DESCRIPTION
      }
      invalid(
        field = field,
        detail = error.message,
        sourceCode = error.code,
        cause = error,
      )
    }
  }

  private fun validateBaseFields(name: String, description: String) {
    try {
      SkillMetadataValidator.validate(name = name, description = description)
    } catch (error: SkillMetadataValidationException) {
      val field = when (error.code) {
        SkillValidationCode.INVALID_NAME -> FIELD_NAME
        SkillValidationCode.INVALID_DESCRIPTION -> FIELD_DESCRIPTION
      }
      invalid(
        field = field,
        detail = error.message,
        sourceCode = error.code,
        cause = error,
      )
    }
  }

  private fun resolveInvocationControl(frontMatter: Map<String, Any?>): SkillInvocationControl {
    val explicitValue = frontMatter[FIELD_INVOCATION_CONTROL]?.let { rawValue ->
      if (rawValue !is String) {
        invalid(
          field = FIELD_INVOCATION_CONTROL,
          detail = "invocation-control must be a string.",
        )
      }
      invocationControlValues[rawValue.trim().lowercase()] ?: invalid(
        field = FIELD_INVOCATION_CONTROL,
        detail = "invocation-control must be one of explicit-only or explicit-and-implicit.",
      )
    }

    val disableModelInvocation = optionalBoolean(frontMatter, FIELD_DISABLE_MODEL_INVOCATION)
    val derivedValue = disableModelInvocation?.let { disabled ->
      if (disabled) SkillInvocationControl.EXPLICIT_ONLY else SkillInvocationControl.EXPLICIT_AND_IMPLICIT
    }

    if (explicitValue != null && derivedValue != null && explicitValue != derivedValue) {
      invalid(
        field = FIELD_INVOCATION_CONTROL,
        detail = "invocation-control conflicts with disable-model-invocation.",
      )
    }

    return explicitValue ?: derivedValue ?: SkillInvocationControl.EXPLICIT_AND_IMPLICIT
  }

  private fun resolveExecutionContext(frontMatter: Map<String, Any?>): SkillExecutionContext {
    val rawValue = frontMatter[FIELD_CONTEXT] ?: return SkillExecutionContext.INLINE
    if (rawValue !is String) {
      invalid(
        field = FIELD_CONTEXT,
        detail = "context must be a string.",
      )
    }
    return executionContextValues[rawValue.trim().lowercase()] ?: invalid(
      field = FIELD_CONTEXT,
      detail = "context must be one of inline or fork.",
    )
  }

  private fun requiredString(frontMatter: Map<String, Any?>, field: String): String {
    if (!frontMatter.containsKey(field)) {
      invalid(
        field = field,
        detail = "$field is required.",
      )
    }

    val rawValue = frontMatter[field]
    if (rawValue !is String) {
      invalid(
        field = field,
        detail = "$field must be a string.",
      )
    }
    return rawValue
  }

  private fun optionalString(frontMatter: Map<String, Any?>, field: String): String? {
    if (!frontMatter.containsKey(field)) {
      return null
    }

    val rawValue = frontMatter[field]
    if (rawValue !is String) {
      invalid(
        field = field,
        detail = "$field must be a string.",
      )
    }

    val normalized = rawValue.trim()
    if (normalized.isEmpty()) {
      invalid(
        field = field,
        detail = "$field must not be blank.",
      )
    }
    return normalized
  }

  private fun optionalBoolean(frontMatter: Map<String, Any?>, field: String): Boolean? {
    if (!frontMatter.containsKey(field)) {
      return null
    }

    val rawValue = frontMatter[field]
    if (rawValue !is Boolean) {
      invalid(
        field = field,
        detail = "$field must be a boolean.",
      )
    }
    return rawValue
  }

  private fun optionalCompatibility(frontMatter: Map<String, Any?>): List<String> {
    if (!frontMatter.containsKey(FIELD_COMPATIBILITY)) {
      return emptyList()
    }

    val rawValue = frontMatter[FIELD_COMPATIBILITY]
    return when (rawValue) {
      is String -> listOf(normalizeNonBlankValue(FIELD_COMPATIBILITY, rawValue))
      is List<*> -> rawValue.mapIndexed { index, item ->
        if (item !is String) {
          invalid(
            field = FIELD_COMPATIBILITY,
            detail = "compatibility[$index] must be a string.",
          )
        }
        normalizeNonBlankValue(FIELD_COMPATIBILITY, item)
      }
      else -> invalid(
        field = FIELD_COMPATIBILITY,
        detail = "compatibility must be a string or list of strings.",
      )
    }
  }

  private fun optionalStringMap(frontMatter: Map<String, Any?>, field: String): Map<String, String> {
    if (!frontMatter.containsKey(field)) {
      return emptyMap()
    }

    val rawValue = frontMatter[field]
    if (rawValue !is Map<*, *>) {
      invalid(
        field = field,
        detail = "$field must be a string-to-string map.",
      )
    }

    return buildMap {
      for ((rawKey, rawEntryValue) in rawValue) {
        if (rawKey !is String) {
          invalid(
            field = field,
            detail = "$field keys must be strings.",
          )
        }
        if (rawEntryValue !is String) {
          invalid(
            field = field,
            detail = "$field values must be strings.",
          )
        }
        val normalizedKey = normalizeNonBlankValue(field, rawKey)
        put(normalizedKey, rawEntryValue.trim())
      }
    }
  }

  private fun optionalAllowedTools(frontMatter: Map<String, Any?>): List<String> {
    if (!frontMatter.containsKey(FIELD_ALLOWED_TOOLS)) {
      return emptyList()
    }

    val rawValue = frontMatter[FIELD_ALLOWED_TOOLS]
    return when (rawValue) {
      is String -> rawValue.split(',').mapIndexed { index, item ->
        normalizeNonBlankValue("$FIELD_ALLOWED_TOOLS[$index]", item)
      }
      is List<*> -> rawValue.mapIndexed { index, item ->
        if (item !is String) {
          invalid(
            field = FIELD_ALLOWED_TOOLS,
            detail = "allowed-tools[$index] must be a string.",
          )
        }
        normalizeNonBlankValue("$FIELD_ALLOWED_TOOLS[$index]", item)
      }
      else -> invalid(
        field = FIELD_ALLOWED_TOOLS,
        detail = "allowed-tools must be a comma-separated string or list of strings.",
      )
    }
  }

  private fun optionalPermissionRules(
    frontMatter: Map<String, Any?>,
    field: String,
  ): List<SkillPermissionRule> {
    if (!frontMatter.containsKey(field)) {
      return emptyList()
    }

    val rawValue = frontMatter[field]
    if (rawValue !is Map<*, *>) {
      invalid(
        field = field,
        detail = "$field must be a map of pattern to allow|ask|deny.",
      )
    }

    return buildList {
      for ((rawPattern, rawDecision) in rawValue) {
        if (rawPattern !is String) {
          invalid(
            field = field,
            detail = "$field keys must be strings.",
          )
        }
        if (rawDecision !is String) {
          invalid(
            field = field,
            detail = "$field values must be strings.",
          )
        }
        val normalizedPattern = normalizeNonBlankValue(field, rawPattern)
        val normalizedDecision = permissionDecisionValues[rawDecision.trim().lowercase()] ?: invalid(
          field = field,
          detail = "$field values must be allow, ask, or deny.",
        )
        add(
          SkillPermissionRule(
            pattern = normalizedPattern,
            decision = normalizedDecision,
          ),
        )
      }
    }
  }

  private fun normalizeNonBlankValue(field: String, rawValue: String): String {
    val normalized = rawValue.trim()
    if (normalized.isEmpty()) {
      invalid(
        field = field,
        detail = "$field must not be blank.",
      )
    }
    return normalized
  }

  private fun serializePermissionRules(rules: List<SkillPermissionRule>): String =
    rules.joinToString(separator = ",") { rule ->
      "${rule.pattern}:${rule.decision.name.lowercase()}"
    }

  private fun SkillInvocationControl.serializedValue(): String = when (this) {
    SkillInvocationControl.EXPLICIT_ONLY -> "explicit-only"
    SkillInvocationControl.EXPLICIT_AND_IMPLICIT -> "explicit-and-implicit"
  }

  private fun SkillExecutionContext.serializedValue(): String = when (this) {
    SkillExecutionContext.INLINE -> "inline"
    SkillExecutionContext.FORK -> "fork"
  }

  private fun invalid(
    field: String,
    detail: String,
    sourceCode: SkillValidationCode? = null,
    cause: Throwable? = null,
  ): Nothing {
    throw InvalidSkillMetadataException(
      error = InvalidSkillMetadata(
        field = field,
        detail = detail,
        sourceCode = sourceCode,
      ),
      cause = cause,
    )
  }
}
