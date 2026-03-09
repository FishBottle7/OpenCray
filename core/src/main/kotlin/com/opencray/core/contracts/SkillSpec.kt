package com.opencray.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SkillValidationCode {
  @SerialName("invalid_name") INVALID_NAME,
  @SerialName("invalid_description") INVALID_DESCRIPTION,
}

class SkillMetadataValidationException(
  val code: SkillValidationCode,
  override val message: String,
) : IllegalArgumentException(message)

object SkillMetadataValidator {
  const val NAME_MIN_LENGTH: Int = 1
  const val NAME_MAX_LENGTH: Int = 64
  const val DESCRIPTION_MAX_LENGTH: Int = 1024

  private val namePattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

  fun validateName(name: String) {
    if (name.length !in NAME_MIN_LENGTH..NAME_MAX_LENGTH) {
      throw SkillMetadataValidationException(
        code = SkillValidationCode.INVALID_NAME,
        message = "Skill name length must be between 1 and 64 characters.",
      )
    }
    if (!namePattern.matches(name)) {
      throw SkillMetadataValidationException(
        code = SkillValidationCode.INVALID_NAME,
        message = "Skill name must use lowercase alphanumeric-hyphen format.",
      )
    }
  }

  fun validateDescription(description: String) {
    if (description.isBlank()) {
      throw SkillMetadataValidationException(
        code = SkillValidationCode.INVALID_DESCRIPTION,
        message = "Skill description must not be blank.",
      )
    }
    if (description.length > DESCRIPTION_MAX_LENGTH) {
      throw SkillMetadataValidationException(
        code = SkillValidationCode.INVALID_DESCRIPTION,
        message = "Skill description length must be <= 1024 characters.",
      )
    }
  }

  fun validate(name: String, description: String) {
    validateName(name)
    validateDescription(description)
  }
}

/**
 * Canonical portable fields are [name] and [description].
 * Extension hooks for vendor-specific fields are preserved in [extensions].
 */
@Serializable
data class SkillSpec(
  val name: String,
  val description: String,
  val license: String? = null,
  val compatibility: List<String> = emptyList(),
  val metadata: Map<String, String> = emptyMap(),
  val allowedTools: List<String> = emptyList(),
  val extensions: Map<String, String> = emptyMap(),
  val schemaVersion: Int = ContractSchemaVersion.CURRENT,
) {
  init {
    SkillMetadataValidator.validate(name = name, description = description)
  }
}
