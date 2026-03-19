package com.opencray.runtime.skills

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteSkillPackageSupportTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun skillsApiRemoteSearchBuildsInstallRefsFromSourceAndSkillName() {
    val transport = RecordingSkillPackageHttpTransport(
      responsesByUrl = mapOf(
        "https://skills.sh/api/search?q=find+skills&limit=5" to SkillPackageHttpResponse(
          statusCode = 200,
          finalUrl = "https://skills.sh/api/search?q=find+skills&limit=5",
          body = """
            {
              "skills": [
                {
                  "id": "roin-orca/skills/find-skills",
                  "name": "find-skills",
                  "source": "roin-orca/skills",
                  "installs": 42
                },
                {
                  "id": "acme/research/research",
                  "name": "research",
                  "source": "acme/research",
                  "installs": 7
                }
              ]
            }
          """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        ),
      ),
    )
    val client = SkillsApiRemoteSkillSearchClient(transport = transport)

    val response = client.search(
      RemoteSkillSearchRequest(
        query = "find skills",
        limit = 5,
      ),
    )

    assertEquals("skills.sh", response.providerName)
    assertEquals(2, response.hits.size)
    assertEquals("roin-orca/skills@find-skills", response.hits[0].installRef)
    assertEquals("acme/research", response.hits[1].installRef)
    assertEquals("https://skills.sh/roin-orca/skills", response.hits[0].detailUrl)
  }

  @Test
  fun skillSourceResolverParsesGithubShortformAndTreeUrl() {
    val resolver = SkillSourceResolver()

    val shortform = resolver.resolve("roin-orca/skills@find-skills")
    val treeUrl = resolver.resolve("https://github.com/roin-orca/skills/tree/main/skills/find-skills")
    val gitRemote = resolver.resolve("git@github.com:roin-orca/skills.git")

    assertNotNull(shortform)
    assertEquals("roin-orca/skills", shortform?.repositoryPath)
    assertEquals("skills", shortform?.repo)
    assertEquals("find-skills", shortform?.selectedSkillName)

    assertNotNull(treeUrl)
    assertEquals("roin-orca/skills", treeUrl?.repositoryPath)
    assertEquals("skills", treeUrl?.repo)
    assertEquals("main", treeUrl?.ref)
    assertEquals("skills/find-skills", treeUrl?.subpath)

    assertNotNull(gitRemote)
    assertEquals(SkillInstallSourceType.REMOTE_GITHUB, gitRemote?.sourceType)
    assertEquals("roin-orca/skills", gitRemote?.repositoryPath)
  }

  @Test
  fun skillSourceResolverParsesGitlabUrlsAndGitRemoteForms() {
    val resolver = SkillSourceResolver()

    val gitlabShorthand = resolver.resolve("gitlab:acme/platform/skills@find-skills")
    val gitlabTreeUrl = resolver.resolve("https://gitlab.com/acme/platform/skills/-/tree/main/skills/find-skills")
    val gitlabRemote = resolver.resolve("git@gitlab.com:acme/platform/skills.git")

    assertNotNull(gitlabShorthand)
    assertEquals(SkillInstallSourceType.REMOTE_GITLAB, gitlabShorthand?.sourceType)
    assertEquals("acme/platform/skills", gitlabShorthand?.repositoryPath)
    assertEquals("find-skills", gitlabShorthand?.selectedSkillName)

    assertNotNull(gitlabTreeUrl)
    assertEquals(SkillInstallSourceType.REMOTE_GITLAB, gitlabTreeUrl?.sourceType)
    assertEquals("acme/platform/skills", gitlabTreeUrl?.repositoryPath)
    assertEquals("main", gitlabTreeUrl?.ref)
    assertEquals("skills/find-skills", gitlabTreeUrl?.subpath)

    assertNotNull(gitlabRemote)
    assertEquals(SkillInstallSourceType.REMOTE_GITLAB, gitlabRemote?.sourceType)
    assertEquals("acme/platform/skills", gitlabRemote?.repositoryPath)
  }

  @Test
  fun githubArchiveFetcherExtractsRepositoryAndResolvesSubpath() {
    val archiveBytes = zipBytes(
      mapOf(
        "roin-orca-skills-123456/skills/find-skills/SKILL.md" to """
          ---
          name: find-skills
          description: Discover skills from the archive.
          ---
          Use the archive.
        """.trimIndent(),
      ),
    )
    val transport = RecordingSkillPackageHttpTransport(
      responsesByUrl = mapOf(
        "https://api.github.com/repos/roin-orca/skills" to jsonResponse(
          """
          {
            "default_branch": "main",
            "html_url": "https://github.com/roin-orca/skills"
          }
          """.trimIndent(),
        ),
        "https://api.github.com/repos/roin-orca/skills/commits/main" to jsonResponse(
          """
          {
            "sha": "1234567890abcdef"
          }
          """.trimIndent(),
        ),
        "https://api.github.com/repos/roin-orca/skills/zipball/main" to SkillPackageHttpResponse(
          statusCode = 200,
          finalUrl = "https://api.github.com/repos/roin-orca/skills/zipball/main",
          body = archiveBytes,
          contentType = "application/zip",
        ),
      ),
    )
    val fetcher = HostedGitArchiveRemoteSkillSourceFetcher(transport = transport)
    val stagingRoot = temporaryFolder.newFolder("remote-fetch")

    val fetched = fetcher.fetch(
      source = ResolvedRemoteSkillSource(
        sourceType = SkillInstallSourceType.REMOTE_GITHUB,
        requestedSourceRef = "https://github.com/roin-orca/skills/tree/main/skills/find-skills",
        webBaseUrl = "https://github.com",
        apiBaseUrl = "https://api.github.com",
        repositoryPath = "roin-orca/skills",
        ref = "main",
        subpath = "skills/find-skills",
      ),
      stagingRoot = stagingRoot,
    )

    assertTrue(fetched.repositoryRoot.isDirectory)
    assertTrue(fetched.searchRoot.isDirectory)
    assertTrue(File(fetched.searchRoot, "SKILL.md").isFile)
    assertEquals("https://github.com/roin-orca/skills", fetched.repositoryUrl)
    assertEquals("main", fetched.resolvedRevision)
    assertEquals("1234567890abcdef", fetched.resolvedCommitSha)
  }

  @Test
  fun hostedGitRemoteInspectorResolvesLatestGithubRevisionAndCommit() {
    val transport = RecordingSkillPackageHttpTransport(
      responsesByUrl = mapOf(
        "https://api.github.com/repos/roin-orca/skills" to jsonResponse(
          """
          {
            "default_branch": "main",
            "html_url": "https://github.com/roin-orca/skills"
          }
          """.trimIndent(),
        ),
        "https://api.github.com/repos/roin-orca/skills/commits/main" to jsonResponse(
          """
          {
            "sha": "feedface1234"
          }
          """.trimIndent(),
          finalUrl = "https://api.github.com/repos/roin-orca/skills/commits/main",
        ),
      ),
    )
    val inspector = HostedGitRemoteSkillSourceInspector(transport = transport)

    val attempt = inspector.inspect(
      ResolvedRemoteSkillSource(
        sourceType = SkillInstallSourceType.REMOTE_GITHUB,
        requestedSourceRef = "roin-orca/skills@find-skills",
        webBaseUrl = "https://github.com",
        apiBaseUrl = "https://api.github.com",
        repositoryPath = "roin-orca/skills",
        selectedSkillName = "find-skills",
      ),
    )

    assertTrue(attempt.succeeded)
    val version = requireNotNull(attempt.version)
    assertEquals("https://github.com/roin-orca/skills", version.repositoryUrl)
    assertEquals("main", version.resolvedRevision)
    assertEquals("feedface1234", version.resolvedCommitSha)
  }

  @Test
  fun gitlabArchiveFetcherExtractsRepositoryAndResolvesSubpath() {
    val archiveBytes = zipBytes(
      mapOf(
        "acme-platform-skills-main/skills/find-skills/SKILL.md" to """
          ---
          name: find-skills
          description: Discover skills from GitLab.
          ---
          Use the archive.
        """.trimIndent(),
      ),
    )
    val transport = RecordingSkillPackageHttpTransport(
      responsesByUrl = mapOf(
        "https://gitlab.com/api/v4/projects/acme%2Fplatform%2Fskills" to jsonResponse(
          """
          {
            "default_branch": "main",
            "web_url": "https://gitlab.com/acme/platform/skills"
          }
          """.trimIndent(),
          finalUrl = "https://gitlab.com/api/v4/projects/acme%2Fplatform%2Fskills",
        ),
        "https://gitlab.com/api/v4/projects/acme%2Fplatform%2Fskills/repository/commits/main" to jsonResponse(
          """
          {
            "id": "abcdef1234567890"
          }
          """.trimIndent(),
          finalUrl = "https://gitlab.com/api/v4/projects/acme%2Fplatform%2Fskills/repository/commits/main",
        ),
        "https://gitlab.com/api/v4/projects/acme%2Fplatform%2Fskills/repository/archive.zip?sha=main" to SkillPackageHttpResponse(
          statusCode = 200,
          finalUrl = "https://gitlab.com/api/v4/projects/acme%2Fplatform%2Fskills/repository/archive.zip?sha=main",
          body = archiveBytes,
          contentType = "application/zip",
        ),
      ),
    )
    val fetcher = HostedGitArchiveRemoteSkillSourceFetcher(transport = transport)
    val stagingRoot = temporaryFolder.newFolder("remote-fetch-gitlab")

    val fetched = fetcher.fetch(
      source = ResolvedRemoteSkillSource(
        sourceType = SkillInstallSourceType.REMOTE_GITLAB,
        requestedSourceRef = "https://gitlab.com/acme/platform/skills/-/tree/main/skills/find-skills",
        webBaseUrl = "https://gitlab.com",
        apiBaseUrl = "https://gitlab.com/api/v4",
        repositoryPath = "acme/platform/skills",
        ref = "main",
        subpath = "skills/find-skills",
      ),
      stagingRoot = stagingRoot,
    )

    assertTrue(fetched.repositoryRoot.isDirectory)
    assertTrue(fetched.searchRoot.isDirectory)
    assertTrue(File(fetched.searchRoot, "SKILL.md").isFile)
    assertEquals("https://gitlab.com/acme/platform/skills", fetched.repositoryUrl)
    assertEquals("main", fetched.resolvedRevision)
    assertEquals("abcdef1234567890", fetched.resolvedCommitSha)
  }

  private fun jsonResponse(
    body: String,
    finalUrl: String = "https://api.github.com",
  ): SkillPackageHttpResponse = SkillPackageHttpResponse(
    statusCode = 200,
    finalUrl = finalUrl,
    body = body.toByteArray(StandardCharsets.UTF_8),
    contentType = "application/json",
  )

  private fun zipBytes(entries: Map<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      entries.forEach { (path, content) ->
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }

  private class RecordingSkillPackageHttpTransport(
    private val responsesByUrl: Map<String, SkillPackageHttpResponse>,
  ) : SkillPackageHttpTransport {
    override fun execute(request: SkillPackageHttpRequest): SkillPackageHttpResponse =
      responsesByUrl[request.url]
        ?: error("No test response configured for ${request.url}")
  }
}
