package com.opencray.app

import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import com.opencray.runtime.OpenCraySoulVisualIdentity
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
  fun saveSoulProfileFileSupportsPrivateSoulPathsOutsideWorkspaceRoot() {
    val privateSoulFile = temporaryFolder.root.toPath()
      .resolve("agents")
      .resolve("agent-test")
      .resolve("private")
      .resolve("SOUL.md")

    store.saveSoulProfileFile(
      soulFile = privateSoulFile,
      profile = WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Private Soul",
        customGuidance = "Stay internal.",
      ),
    )

    val document = requireNotNull(
      store.loadSoulDocumentFile(
        soulFile = privateSoulFile,
        relativePath = "private/SOUL.md",
      ),
    )

    assertEquals("private/SOUL.md", document.relativePath)
    assertEquals("Private Soul", document.profile?.customLabel)
    assertTrue(Files.exists(privateSoulFile))
  }

  @Test
  fun saveSoulProfileRoundTripsVisualIdentity() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-visual-identity").toPath()
    val visualIdentity = OpenCraySoulVisualIdentity(
      portraitSummary = "Short dark hair, practical coat, calm expression.",
      primaryPortrait = OpenCrayImageReference(
        refId = "portrait-1",
        role = OpenCrayImageReferenceRole.PORTRAIT,
        storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
        relativePath = "soul-assets/portrait/portrait-1.png",
        mimeType = "image/png",
        sha256 = "a".repeat(64),
        widthPx = 1024,
        heightPx = 1024,
        caption = "Primary portrait",
        summary = "Front-facing portrait with calm expression.",
        createdAtEpochMs = 33L,
      ),
    )

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Lantern",
        customGuidance = "Stay warm.",
        visualIdentity = visualIdentity,
      ),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertNotNull(profile.visualIdentity)
    assertEquals(
      "Short dark hair, practical coat, calm expression.",
      profile.visualIdentity?.portraitSummary,
    )
    assertEquals("portrait-1", profile.visualIdentity?.primaryPortrait?.refId)
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
  fun saveSoulProfileLetsExplicitManagedExtensionsOverridePresetDefaults() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-managed-override").toPath()

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Lantern",
        customGuidance = "Stay warm.",
        extensions = mapOf(
          SoulProfileExtensionKeys.PLASTICITY to "high",
          SoulProfileExtensionKeys.VERBOSITY to "detailed",
          SoulProfileExtensionKeys.RISK_TOLERANCE to "assertive",
        ),
      ),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertEquals("high", profile.extensions[SoulProfileExtensionKeys.PLASTICITY])
    assertEquals("detailed", profile.extensions[SoulProfileExtensionKeys.VERBOSITY])
    assertEquals("assertive", profile.extensions[SoulProfileExtensionKeys.RISK_TOLERANCE])
  }

  @Test
  fun saveSoulProfilePreservesExistingVisualIdentityAcrossTextOnlyUpdates() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-preserve-visual").toPath()
    val visualIdentity = OpenCraySoulVisualIdentity(
      portraitSummary = "Measured expression and dark coat.",
      primaryPortrait = OpenCrayImageReference(
        refId = "portrait-existing",
        role = OpenCrayImageReferenceRole.PORTRAIT,
        storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
        relativePath = "soul-assets/portrait/existing.png",
        mimeType = "image/png",
        sha256 = "b".repeat(64),
        widthPx = 800,
        heightPx = 800,
        caption = "Existing portrait",
        summary = "Existing portrait summary.",
        createdAtEpochMs = 44L,
      ),
    )

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Original",
        customGuidance = "Keep calm.",
        visualIdentity = visualIdentity,
      ),
    )

    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "Updated",
        customGuidance = "Stay direct.",
      ),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertEquals("BUILDER", profile.presetName)
    assertEquals("Updated", profile.customLabel)
    assertEquals("Measured expression and dark coat.", profile.visualIdentity?.portraitSummary)
    assertEquals("portrait-existing", profile.visualIdentity?.primaryPortrait?.refId)
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

  @Test
  fun saveSoulVisualIdentityUpdatesOnlyVisualIdentityFields() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-save-visual-only").toPath()
    store.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Lantern",
        customGuidance = "Stay warm.",
        extensions = mapOf("voice" to "soft"),
      ),
    )

    store.saveSoulVisualIdentity(
      workspaceRoot = workspaceRoot,
      visualIdentity = OpenCraySoulVisualIdentity(
        portraitSummary = "Short hair and a practical coat.",
      ),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertEquals("WARM", profile.presetName)
    assertEquals("Lantern", profile.customLabel)
    assertEquals("Stay warm.", profile.customGuidance)
    assertEquals("soft", profile.extensions["voice"])
    assertEquals("Short hair and a practical coat.", profile.visualIdentity?.portraitSummary)
  }

  @Test
  fun saveSoulVisualIdentityCreatesReadableSoulProfileEvenWithoutTextFields() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-visual-only-profile").toPath()

    store.saveSoulVisualIdentity(
      workspaceRoot = workspaceRoot,
      visualIdentity = OpenCraySoulVisualIdentity(
        portraitSummary = "Dark hair and a precise expression.",
      ),
    )

    val profile = requireNotNull(store.loadSoulProfile(workspaceRoot))

    assertEquals("", profile.presetName)
    assertEquals("", profile.customLabel)
    assertEquals("", profile.customGuidance)
    assertEquals("Dark hair and a precise expression.", profile.visualIdentity?.portraitSummary)
  }
}
