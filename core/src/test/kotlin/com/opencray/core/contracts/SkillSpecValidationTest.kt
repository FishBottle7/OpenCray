package com.opencray.core.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillSpecValidationTest {
  @Test
  fun rejectsUppercaseSkillName() {
    val error = assertThrows(SkillMetadataValidationException::class.java) {
      SkillSpec(
        name = "Bad-Name",
        description = "Valid description",
      )
    }

    assertEquals(SkillValidationCode.INVALID_NAME, error.code)
    assertEquals("Skill name must use lowercase alphanumeric-hyphen format.", error.message)
  }

  @Test
  fun rejectsOverlongSkillName() {
    val tooLongName = "a".repeat(SkillMetadataValidator.NAME_MAX_LENGTH + 1)

    val error = assertThrows(SkillMetadataValidationException::class.java) {
      SkillSpec(
        name = tooLongName,
        description = "Valid description",
      )
    }

    assertEquals(SkillValidationCode.INVALID_NAME, error.code)
    assertEquals("Skill name length must be between 1 and 64 characters.", error.message)
  }

  @Test
  fun rejectsBlankDescription() {
    val error = assertThrows(SkillMetadataValidationException::class.java) {
      SkillSpec(
        name = "valid-name",
        description = "   ",
      )
    }

    assertEquals(SkillValidationCode.INVALID_DESCRIPTION, error.code)
    assertEquals("Skill description must not be blank.", error.message)
  }

  @Test
  fun rejectsOverlongDescription() {
    val tooLongDescription = "d".repeat(SkillMetadataValidator.DESCRIPTION_MAX_LENGTH + 1)

    val error = assertThrows(SkillMetadataValidationException::class.java) {
      SkillSpec(
        name = "valid-name",
        description = tooLongDescription,
      )
    }

    assertEquals(SkillValidationCode.INVALID_DESCRIPTION, error.code)
    assertEquals("Skill description length must be <= 1024 characters.", error.message)
  }
}
