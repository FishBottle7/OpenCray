package com.opencray.app

import com.opencray.runtime.soul.SoulProfileExtensionKeys
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

class WorkspaceSoulProfileStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val store = WorkspaceSoulProfileStore()

  @Test
  fun saveSoulProfileWritesWorkspaceSoulFileAndLoadsTypedExtensions() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-roundtrip").toPath()

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "Night Shift",
        customGuidance = "Stay direct and concrete.",
      ),
    )

    val document = store.loadSoulDocument(workspaceRoot)
    val profile = document?.profile

    assertNotNull(document)
    assertNotNull(profile)
    assertEquals("SOUL.md", document?.relativePath)
    assertTrue(document?.content?.contains("preset: BUILDER") == true)
    assertEquals("BUILDER", profile?.presetName)
    assertEquals("Night Shift", profile?.customLabel)
    assertEquals("Stay direct and concrete.", profile?.customGuidance)
    assertEquals("builder", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("terse", profile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertEquals("low", profile?.extensions?.get(SoulProfileExtensionKeys.PLASTICITY))
    assertEquals("tool_forward", profile?.extensions?.get(SoulProfileExtensionKeys.TOOL_USE_BIAS))
  }

  @Test
  fun saveSoulProfilePreservesExistingNonManagedExtensionsAcrossUiUpdates() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-preserve").toPath()

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Original",
        customGuidance = "Keep calm.",
        extensions = mapOf(
          "voice" to "calm but direct",
          "signature_style" to "clipped",
        ),
      ),
    )

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Updated",
        customGuidance = "Stay warm.",
      ),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertEquals("WARM", profile.presetName)
    assertEquals("Updated", profile.customLabel)
    assertEquals("Stay warm.", profile.customGuidance)
    assertEquals("calm but direct", profile.extensions["voice"])
    assertEquals("clipped", profile.extensions["signature_style"])
    assertEquals("warm", profile.extensions[SoulProfileExtensionKeys.TONE])
    assertEquals("medium", profile.extensions[SoulProfileExtensionKeys.PLASTICITY])
  }

  @Test
  fun loadSoulProfileParsesManualFrontmatterAndLetsExplicitFieldsOverridePresetDefaults() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-manual").toPath()
    Files.write(
      workspaceRoot.resolve("SOUL.md"),
      """
      ---
      kind: opencray_soul
      preset: WARM
      display_name: Lantern
      plasticity: high
      voice: deeply reassuring
      ---

      Move gently, but keep clear boundaries.
      """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertEquals("WARM", profile.presetName)
    assertEquals("Lantern", profile.customLabel)
    assertEquals("Move gently, but keep clear boundaries.", profile.customGuidance)
    assertEquals("high", profile.extensions[SoulProfileExtensionKeys.PLASTICITY])
    assertEquals("deeply reassuring", profile.extensions["voice"])
    assertEquals("supportive", profile.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE])
  }

  @Test
  fun clearSoulProfileDeletesWorkspaceSoulFile() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-clear").toPath()

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "",
        customGuidance = "",
      ),
    )

    assertTrue(Files.exists(workspaceRoot.resolve("SOUL.md")))
    assertTrue(store.clearSoulProfile(workspaceRoot))
    assertFalse(Files.exists(workspaceRoot.resolve("SOUL.md")))
    assertNull(store.loadSoulProfile(workspaceRoot))
  }
}
