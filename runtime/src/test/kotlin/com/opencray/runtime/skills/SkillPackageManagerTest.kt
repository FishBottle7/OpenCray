package com.opencray.runtime.skills

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillPackageManagerTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun installFromCatalogCopiesSkillAndWritesManifest() {
    val managedRoot = temporaryFolder.newFolder("managed")
    val catalogRoot = temporaryFolder.newFolder("catalog")
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the local catalog.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { 10_000L },
    )

    val result = packageManager.installFromCatalog("find-skills")

    assertNotNull(result)
    val installedDirectory = File(managedRoot, "find-skills")
    assertTrue(installedDirectory.isDirectory)
    assertTrue(File(installedDirectory, "SKILL.md").isFile)
    val manifest = manifestStore.load()
    assertEquals(1, manifest.installations.size)
    val entry = manifest.installations.single()
    assertEquals("find-skills", entry.skillId)
    assertEquals(SkillInstallSourceType.LOCAL_CATALOG.wireValue, entry.sourceType)
    assertEquals("find-skills", entry.sourceRef)
    assertEquals(canonicalInvariantPath(installedDirectory), entry.installRootPath)
    assertEquals(canonicalInvariantPath(File(catalogRoot, "find-skills")), entry.sourcePath)
    assertEquals("find-skills/SKILL.md", entry.sourceRelativePath)
    assertEquals(10_000L, entry.installedAtEpochMs)
    assertEquals(10_000L, entry.updatedAtEpochMs)
    assertTrue(entry.contentHash.isNotBlank())
  }

  @Test
  fun removeInstalledSkillDeletesManagedDirectoryAndManifestEntry() {
    val managedRoot = temporaryFolder.newFolder("managed-remove")
    val catalogRoot = temporaryFolder.newFolder("catalog-remove")
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the local catalog.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-remove.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { 20_000L },
    )
    checkNotNull(packageManager.installFromCatalog("find-skills"))

    val removed = packageManager.removeInstalledSkill("find-skills")

    assertNotNull(removed)
    assertFalse(File(managedRoot, "find-skills").exists())
    assertTrue(removed!!.manifestEntryRemoved)
    assertTrue(manifestStore.load().installations.isEmpty())
  }

  @Test
  fun refreshManifestPrunesMissingInstallations() {
    val managedRoot = temporaryFolder.newFolder("managed-refresh")
    val catalogRoot = temporaryFolder.newFolder("catalog-refresh")
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the local catalog.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-refresh.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { 30_000L },
    )
    checkNotNull(packageManager.installFromCatalog("find-skills"))
    assertTrue(File(managedRoot, "find-skills").deleteRecursively())

    val manifest = packageManager.refreshManifest()

    assertTrue(manifest.installations.isEmpty())
  }

  @Test
  fun reinstallPreservesInstalledAtAndRefreshesContentHash() {
    val managedRoot = temporaryFolder.newFolder("managed-reinstall")
    val catalogRoot = temporaryFolder.newFolder("catalog-reinstall")
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the local catalog.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-reinstall.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    var now = 40_000L
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { now },
    )

    checkNotNull(packageManager.installFromCatalog("find-skills"))
    val firstEntry = manifestStore.load().installations.single()
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the updated catalog.",
    )
    now = 45_000L

    checkNotNull(packageManager.installFromCatalog("find-skills"))

    val secondEntry = manifestStore.load().installations.single()
    assertEquals(firstEntry.installedAtEpochMs, secondEntry.installedAtEpochMs)
    assertEquals(45_000L, secondEntry.updatedAtEpochMs)
    assertTrue(firstEntry.contentHash != secondEntry.contentHash)
  }

  private fun writeSkill(
    root: File,
    directoryName: String,
    skillName: String,
    description: String,
    body: String,
  ) {
    val skillDirectory = File(root, directoryName)
    if (!skillDirectory.exists()) {
      skillDirectory.mkdirs()
    }
    File(skillDirectory, "SKILL.md").writeText(
      """
      ---
      name: $skillName
      description: $description
      ---
      $body
      """.trimIndent(),
      Charsets.UTF_8,
    )
  }

  private fun canonicalInvariantPath(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).replace('\\', '/')
}
