package com.opencray.skills

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillEngineTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun testSkillDiscoveryAndInvoke() {
    val skillsRoot = temporaryFolder.newFolder("skill-discovery-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "explicit-helper",
      frontMatter = """
        name: valid-explicit-skill
        description: Handles explicit invocation safely.
        metadata:
          owner: "  platform-team  "
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # Explicit Helper

        Supports explicit invocation flows only.
      """.trimIndent(),
    )

    val report = SkillLoader.load(skillsRoot)

    assertEquals(1, report.discoveredFiles.size)
    assertEquals("explicit-helper/SKILL.md", report.discoveredFiles.single().relativePath)
    assertTrue(report.invalidSkills.isEmpty())
    assertEquals(1, report.loadedSkills.size)

    val loadedSkill = report.registry.get("valid-explicit-skill")
    assertNotNull(loadedSkill)
    loadedSkill ?: return

    assertEquals("valid-explicit-skill", loadedSkill.name)
    assertEquals(SkillInvocationControl.EXPLICIT_ONLY, loadedSkill.metadata.invocationControl)
    assertEquals("platform-team", loadedSkill.metadata.skillSpec.metadata["owner"])
    assertEquals(listOf("read", "write"), loadedSkill.metadata.skillSpec.allowedTools)
    assertEquals(loadedSkill, report.registry.explicitlyInvocableSkills().single())
    assertEquals(loadedSkill, report.loadedSkills.single())
    assertTrue(report.registry.isExplicitlyInvocable("valid-explicit-skill"))
    assertFalse(report.registry.isImplicitlyEligible("valid-explicit-skill"))
    assertTrue(report.registry.implicitlyEligibleSkills().isEmpty())

    println(
      "TASK10 happy loadedSkillName=${loadedSkill.name} invocationControl=${loadedSkill.metadata.invocationControl} " +
        "explicitlyInvocable=${report.registry.isExplicitlyInvocable(loadedSkill.name)} " +
        "implicitlyEligible=${report.registry.isImplicitlyEligible(loadedSkill.name)} " +
        "allowedTools=${loadedSkill.metadata.skillSpec.allowedTools.joinToString(separator = ",")}",
    )
  }

  @Test
  fun testSkillValidationRejectsMalformed() {
    val skillsRoot = temporaryFolder.newFolder("skill-invalid-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "broken-skill",
      frontMatter = """
        name: broken-skill
        description: Rejects unreachable explicit-only skill metadata.
        invocation-control: explicit-only
        user-invocable: false
      """.trimIndent(),
      body = """
        # Broken Skill

        This skill should never enter the active registry.
      """.trimIndent(),
    )

    val report = SkillLoader.load(skillsRoot)

    assertEquals(1, report.discoveredFiles.size)
    assertTrue(report.loadedSkills.isEmpty())
    assertEquals(1, report.invalidSkills.size)

    val invalidSkill = report.invalidSkills.single()
    assertEquals(SkillValidationReasonCode.INVALID_SKILL_METADATA, invalidSkill.reasonCode)
    assertEquals("broken-skill", invalidSkill.skillName)
    assertEquals("user-invocable", invalidSkill.field)
    assertNull(report.registry.get("broken-skill"))
    assertFalse(report.registry.isExplicitlyInvocable("broken-skill"))
    assertFalse(report.registry.isImplicitlyEligible("broken-skill"))
    assertTrue(report.registry.allSkills().isEmpty())

    println(
      "TASK10 invalid skillName=${invalidSkill.skillName} reasonCode=${invalidSkill.reasonCode} " +
        "invalidField=${invalidSkill.field} registryEntryCreated=${report.registry.get("broken-skill") != null}",
    )
  }

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())

    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }

    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }
}

// Learnings: SkillLoader already exposes enough registry helpers to verify explicit and implicit invocation behavior directly.
// Issues: Discovery is file-based, so malformed skills still appear in discoveredFiles even when validation blocks registry activation.
// Learnings: Stable evidence is clearer when tests print normalized registry and validator facts after assertions pass.
// Issues: Android unit-test stdout is hidden unless module-level testLogging.showStandardStreams is enabled.
