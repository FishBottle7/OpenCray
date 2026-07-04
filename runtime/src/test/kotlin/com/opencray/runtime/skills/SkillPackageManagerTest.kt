package com.opencray.runtime.skills

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

  @Test
  fun installFromRemoteSourceWritesRemoteProvenanceToManifest() {
    val managedRoot = temporaryFolder.newFolder("managed-remote")
    val catalogRoot = temporaryFolder.newFolder("catalog-remote")
    val manifestFile = File(temporaryFolder.root, "skills-manifest-remote.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Find skills from a remote source.",
      ),
      clock = { 50_000L },
    )

    val attempt = packageManager.installFromRemoteSource("roin-orca/skills@find-skills")

    assertTrue(attempt.succeeded)
    assertNull(attempt.errorCode)
    val result = requireNotNull(attempt.result)
    assertEquals("find-skills", result.skillId)
    val entry = manifestStore.load().installations.single()
    assertEquals(SkillInstallSourceType.REMOTE_GITHUB.wireValue, entry.sourceType)
    assertEquals("roin-orca/skills@find-skills", entry.sourceRef)
    assertEquals("https://github.com/roin-orca/skills", entry.sourcePath)
    assertEquals("find-skills/SKILL.md", entry.sourceRelativePath)
    assertEquals("main", entry.resolvedRevision)
    assertEquals("deadbeef", entry.resolvedCommitSha)
    assertTrue(File(managedRoot, "find-skills").resolve("SKILL.md").isFile)
  }

  @Test
  fun installFromRemoteSourceBatchInstallsRequestedSkillsWithSingleFetch() {
    val managedRoot = temporaryFolder.newFolder("managed-remote-batch")
    val catalogRoot = temporaryFolder.newFolder("catalog-remote-batch")
    val manifestFile = File(temporaryFolder.root, "skills-manifest-remote-batch.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val fetcher = FakeRemoteSkillSourceFetcher(
      skillDirectoryName = "skills/find-skills",
      skillName = "find-skills",
      description = "Find skills from a remote source.",
      extraSkills = listOf(
        FakeRemoteSkill(
          relativeDirectory = "skills/review-skills",
          skillName = "review-skills",
          description = "Review changes from a remote source.",
        ),
      ),
    )
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      remoteSourceFetcher = fetcher,
      clock = { 55_000L },
    )

    val attempt = packageManager.installFromRemoteSourceBatch(
      sourceRef = "roin-orca/skills",
      selectedSkillNames = listOf("find-skills", "review-skills"),
    )

    assertTrue(attempt.succeeded)
    val result = requireNotNull(attempt.result)
    assertEquals(2, result.installedCount)
    assertEquals(0, result.failedCount)
    assertEquals(1, fetcher.fetchCount)
    assertTrue(File(managedRoot, "find-skills").resolve("SKILL.md").isFile)
    assertTrue(File(managedRoot, "review-skills").resolve("SKILL.md").isFile)
    assertEquals(
      listOf("find-skills", "review-skills"),
      manifestStore.load().installations.map { entry -> entry.skillId },
    )
  }

  @Test
  fun concurrentRemoteInstallsPreserveManifestEntries() {
    val managedRoot = temporaryFolder.newFolder("managed-remote-concurrent")
    val catalogRoot = temporaryFolder.newFolder("catalog-remote-concurrent")
    val manifestFile = File(temporaryFolder.root, "skills-manifest-remote-concurrent.json")
    val firstManifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val secondManifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val fetchReady = CountDownLatch(2)
    val continueFetch = CountDownLatch(1)
    val beforeFetchWrite = {
      fetchReady.countDown()
      if (!continueFetch.await(5, TimeUnit.SECONDS)) {
        throw IllegalStateException("Timed out waiting for concurrent fetch release.")
      }
    }
    val firstManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = firstManifestStore,
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "skills/find-skills",
        skillName = "find-skills",
        beforeWrite = beforeFetchWrite,
      ),
      clock = { 56_000L },
    )
    val secondManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = secondManifestStore,
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "skills/review-skills",
        skillName = "review-skills",
        beforeWrite = beforeFetchWrite,
      ),
      clock = { 57_000L },
    )
    val firstResult = AtomicReference<SkillPackageInstallAttempt>()
    val secondResult = AtomicReference<SkillPackageInstallAttempt>()
    val failure = AtomicReference<Throwable>()
    val firstThread = Thread {
      runCatching {
        firstResult.set(firstManager.installFromRemoteSource("roin-orca/skills@find-skills"))
      }.onFailure { error -> failure.compareAndSet(null, error) }
    }
    val secondThread = Thread {
      runCatching {
        secondResult.set(secondManager.installFromRemoteSource("roin-orca/skills@review-skills"))
      }.onFailure { error -> failure.compareAndSet(null, error) }
    }

    firstThread.start()
    secondThread.start()
    assertTrue(fetchReady.await(5, TimeUnit.SECONDS))
    continueFetch.countDown()
    firstThread.join(5_000)
    secondThread.join(5_000)

    assertFalse(firstThread.isAlive)
    assertFalse(secondThread.isAlive)
    failure.get()?.let { throw AssertionError("Concurrent install failed.", it) }
    assertTrue(firstResult.get().succeeded)
    assertTrue(secondResult.get().succeeded)
    assertEquals(
      listOf("find-skills", "review-skills"),
      SkillInstallManifestStore.fromFile(manifestFile).load().installations.map { entry -> entry.skillId },
    )
  }

  @Test
  fun installFromLocalSourceCopiesSkillAndWritesLocalPathProvenance() {
    val managedRoot = temporaryFolder.newFolder("managed-local-source")
    val catalogRoot = temporaryFolder.newFolder("catalog-local-source")
    val localSourceRoot = temporaryFolder.newFolder("local-source")
    writeSkill(
      root = localSourceRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from a local source path.",
      body = "Use the local source path.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-local-source.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { 60_000L },
    )

    val attempt = packageManager.installFromLocalSource(
      sourcePath = localSourceRoot,
      sourceRef = "./local-source",
    )

    assertTrue(attempt.succeeded)
    val result = requireNotNull(attempt.result)
    assertEquals("find-skills", result.skillId)
    val entry = manifestStore.load().installations.single()
    assertEquals(SkillInstallSourceType.LOCAL_PATH.wireValue, entry.sourceType)
    assertEquals("./local-source", entry.sourceRef)
    assertEquals(canonicalInvariantPath(localSourceRoot), entry.sourcePath)
    assertEquals("find-skills/SKILL.md", entry.sourceRelativePath)
    assertTrue(File(managedRoot, "find-skills").resolve("SKILL.md").isFile)
  }

  @Test
  fun inspectLocalSourceListsAllContainedSkills() {
    val managedRoot = temporaryFolder.newFolder("managed-inspect-local")
    val catalogRoot = temporaryFolder.newFolder("catalog-inspect-local")
    val localSourceRoot = temporaryFolder.newFolder("local-source-inspect")
    writeSkill(
      root = localSourceRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from a local source path.",
      body = "Use the local source path.",
    )
    writeSkill(
      root = localSourceRoot,
      directoryName = "review-skills",
      skillName = "review-skills",
      description = "Review changes from a local source path.",
      body = "Review the workspace.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-inspect-local.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
    )

    val attempt = packageManager.inspectLocalSource(
      sourcePath = localSourceRoot,
      sourceRef = "./local-source-inspect",
    )

    assertTrue(attempt.succeeded)
    val result = requireNotNull(attempt.result)
    assertEquals(SkillInstallSourceType.LOCAL_PATH.wireValue, result.sourceType)
    assertEquals("./local-source-inspect", result.sourceRef)
    assertEquals(2, result.candidates.size)
    assertEquals(listOf("find-skills", "review-skills"), result.candidates.map { it.name })
  }

  @Test
  fun inspectRemoteSourceListsAllContainedSkills() {
    val managedRoot = temporaryFolder.newFolder("managed-inspect-remote")
    val catalogRoot = temporaryFolder.newFolder("catalog-inspect-remote")
    val manifestFile = File(temporaryFolder.root, "skills-manifest-inspect-remote.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "skills/find-skills",
        skillName = "find-skills",
        description = "Find skills from a remote source.",
        extraSkills = listOf(
          FakeRemoteSkill(
            relativeDirectory = "skills/review-skills",
            skillName = "review-skills",
            description = "Review changes from a remote source.",
          ),
        ),
      ),
    )

    val attempt = packageManager.inspectRemoteSource("roin-orca/skills")

    assertTrue(attempt.succeeded)
    val result = requireNotNull(attempt.result)
    assertEquals(SkillInstallSourceType.REMOTE_GITHUB.wireValue, result.sourceType)
    assertEquals("roin-orca/skills", result.sourceRef)
    assertEquals(2, result.candidates.size)
    assertEquals(listOf("find-skills", "review-skills"), result.candidates.map { it.name })
    assertEquals("main", result.resolvedRevision)
    assertEquals("deadbeef", result.resolvedCommitSha)
  }

  @Test
  fun checkInstalledSkillsDetectsCatalogUpdatesAndPersistsLastCheckedTime() {
    val managedRoot = temporaryFolder.newFolder("managed-check-catalog")
    val catalogRoot = temporaryFolder.newFolder("catalog-check-catalog")
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the local catalog.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-check-catalog.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    var now = 70_000L
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { now },
    )
    checkNotNull(packageManager.installFromCatalog("find-skills"))
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the updated catalog.",
    )
    now = 71_000L

    val report = packageManager.checkInstalledSkills("find-skills")

    assertEquals(1, report.results.size)
    val result = report.results.single()
    assertEquals(SkillPackageCheckStatus.UPDATE_AVAILABLE, result.status)
    assertTrue(result.latestContentHash?.isNotBlank() == true)
    assertEquals(71_000L, manifestStore.load().installations.single().lastCheckedAtEpochMs)
  }

  @Test
  fun checkInstalledSkillsDetectsRemoteCommitUpdates() {
    val managedRoot = temporaryFolder.newFolder("managed-check-remote")
    val catalogRoot = temporaryFolder.newFolder("catalog-check-remote")
    val manifestFile = File(temporaryFolder.root, "skills-manifest-check-remote.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      remoteSourceInspector = FakeRemoteSkillSourceInspector(
        resolvedRevision = "main",
        resolvedCommitSha = "feedface",
      ),
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        description = "Find skills from a remote source.",
      ),
      clock = { 80_000L },
    )
    check(packageManager.installFromRemoteSource("roin-orca/skills@find-skills").succeeded)

    val report = packageManager.checkInstalledSkills("find-skills")

    assertEquals(1, report.results.size)
    val result = report.results.single()
    assertEquals(SkillPackageCheckStatus.UPDATE_AVAILABLE, result.status)
    assertEquals("deadbeef", result.installedCommitSha)
    assertEquals("feedface", result.latestCommitSha)
  }

  @Test
  fun updateInstalledSkillsRefreshesCatalogInstallInPlace() {
    val managedRoot = temporaryFolder.newFolder("managed-update-catalog")
    val catalogRoot = temporaryFolder.newFolder("catalog-update-catalog")
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the local catalog.",
    )
    val manifestFile = File(temporaryFolder.root, "skills-manifest-update-catalog.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    var now = 90_000L
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      clock = { now },
    )
    checkNotNull(packageManager.installFromCatalog("find-skills"))
    writeSkill(
      root = catalogRoot,
      directoryName = "find-skills",
      skillName = "find-skills",
      description = "Find skills from the local catalog.",
      body = "Use the refreshed catalog.",
    )
    now = 91_000L

    val report = packageManager.checkInstalledSkills("find-skills")
    val update = packageManager.updateInstalledSkills(report)

    assertEquals(1, update.updatedCount)
    assertEquals("Use the refreshed catalog.", File(managedRoot, "find-skills").resolve("SKILL.md").readLines().last())
    assertEquals(91_000L, manifestStore.load().installations.single().updatedAtEpochMs)
  }

  @Test
  fun updateInstalledSkillsRefreshesRemoteManifestCommit() {
    val managedRoot = temporaryFolder.newFolder("managed-update-remote")
    val catalogRoot = temporaryFolder.newFolder("catalog-update-remote")
    val manifestFile = File(temporaryFolder.root, "skills-manifest-update-remote.json")
    val manifestStore = SkillInstallManifestStore.fromFile(manifestFile)
    var nextCommitSha = "deadbeef"
    val packageManager = SkillPackageManager(
      managedRoot = managedRoot,
      catalogRoot = catalogRoot,
      manifestStore = manifestStore,
      remoteSourceInspector = FakeRemoteSkillSourceInspector(
        resolvedRevision = "main",
        resolvedCommitShaProvider = { nextCommitSha },
      ),
      remoteSourceFetcher = FakeRemoteSkillSourceFetcher(
        skillDirectoryName = "find-skills",
        skillName = "find-skills",
        descriptionProvider = { "Remote commit $nextCommitSha" },
        resolvedCommitShaProvider = { nextCommitSha },
      ),
      clock = { 100_000L },
    )
    check(packageManager.installFromRemoteSource("roin-orca/skills@find-skills").succeeded)
    nextCommitSha = "feedface"

    val update = packageManager.updateInstalledSkills("find-skills")

    assertEquals(1, update.updatedCount)
    val entry = manifestStore.load().installations.single()
    assertEquals("feedface", entry.resolvedCommitSha)
    assertTrue(File(managedRoot, "find-skills").resolve("SKILL.md").readText().contains("Remote commit feedface"))
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

  private class FakeRemoteSkillSourceFetcher(
    private val skillDirectoryName: String,
    private val skillName: String,
    private val description: String = "Use the remote source.",
    private val descriptionProvider: () -> String = { description },
    private val resolvedCommitShaProvider: () -> String = { "deadbeef" },
    private val extraSkills: List<FakeRemoteSkill> = emptyList(),
    private val beforeWrite: () -> Unit = {},
  ) : RemoteSkillSourceFetcher {
    var fetchCount: Int = 0

    override fun fetch(
      source: ResolvedRemoteSkillSource,
      stagingRoot: File,
    ): FetchedRemoteSkillSource {
      fetchCount += 1
      val repositoryRoot = File(stagingRoot, "repo")
      val skillDirectory = File(repositoryRoot, skillDirectoryName)
      beforeWrite()
      if (!skillDirectory.exists()) {
        skillDirectory.mkdirs()
      }
      File(skillDirectory, "SKILL.md").writeText(
        """
        ---
        name: $skillName
        description: ${descriptionProvider()}
        ---
        Use the remote source.
        """.trimIndent(),
        Charsets.UTF_8,
      )
      extraSkills.forEach { extraSkill ->
        val extraSkillDirectory = File(repositoryRoot, extraSkill.relativeDirectory)
        if (!extraSkillDirectory.exists()) {
          extraSkillDirectory.mkdirs()
        }
        File(extraSkillDirectory, "SKILL.md").writeText(
          """
          ---
          name: ${extraSkill.skillName}
          description: ${extraSkill.description}
          ---
          ${extraSkill.body}
          """.trimIndent(),
          Charsets.UTF_8,
        )
      }
      return FetchedRemoteSkillSource(
        repositoryRoot = repositoryRoot,
        searchRoot = repositoryRoot,
        repositoryUrl = source.repositoryUrl,
        resolvedRevision = source.ref ?: "main",
        resolvedCommitSha = resolvedCommitShaProvider(),
      )
    }
  }

  private data class FakeRemoteSkill(
    val relativeDirectory: String,
    val skillName: String,
    val description: String,
    val body: String = "Use the remote source.",
  )

  private class FakeRemoteSkillSourceInspector(
    private val resolvedRevision: String,
    private val resolvedCommitSha: String? = null,
    private val resolvedCommitShaProvider: () -> String? = { resolvedCommitSha },
  ) : RemoteSkillSourceInspector {
    override fun inspect(source: ResolvedRemoteSkillSource): RemoteSkillSourceVersionAttempt =
      RemoteSkillSourceVersionAttempt(
        version = RemoteSkillSourceVersion(
          repositoryUrl = source.repositoryUrl,
          resolvedRevision = resolvedRevision,
          resolvedCommitSha = resolvedCommitShaProvider(),
        ),
      )
  }
}
