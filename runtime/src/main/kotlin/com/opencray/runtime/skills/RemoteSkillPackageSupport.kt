package com.opencray.runtime.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val DEFAULT_SKILL_PACKAGE_USER_AGENT: String = "OpenCray-SkillPackage/1.0"
private const val DEFAULT_SKILLS_API_BASE_URL: String = "https://skills.sh"
private const val DEFAULT_GITHUB_WEB_BASE_URL: String = "https://github.com"
private const val DEFAULT_GITHUB_API_BASE_URL: String = "https://api.github.com"

data class SkillPackageHttpRequest(
  val method: String = "GET",
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null,
  val connectTimeoutMs: Int = 15_000,
  val readTimeoutMs: Int = 20_000,
)

data class SkillPackageHttpResponse(
  val statusCode: Int,
  val body: ByteArray,
  val finalUrl: String,
  val contentType: String? = null,
) {
  fun bodyAsText(): String = body.toString(Charsets.UTF_8)
}

interface SkillPackageHttpTransport {
  fun execute(request: SkillPackageHttpRequest): SkillPackageHttpResponse
}

class HttpUrlSkillPackageHttpTransport(
  private val userAgent: String = DEFAULT_SKILL_PACKAGE_USER_AGENT,
) : SkillPackageHttpTransport {
  override fun execute(request: SkillPackageHttpRequest): SkillPackageHttpResponse {
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      requestMethod = request.method
      connectTimeout = request.connectTimeoutMs
      readTimeout = request.readTimeoutMs
      instanceFollowRedirects = true
      doInput = true
      doOutput = request.body != null
      setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.1")
      setRequestProperty("User-Agent", userAgent)
      request.headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      request.body?.let { body ->
        connection.outputStream.use { output -> output.write(body) }
      }
      val statusCode = connection.responseCode
      val body = readFully(
        input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
      )
      SkillPackageHttpResponse(
        statusCode = statusCode,
        body = body,
        finalUrl = connection.url.toString(),
        contentType = connection.contentType?.substringBefore(';')?.trim(),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun readFully(input: java.io.InputStream?): ByteArray {
    if (input == null) {
      return ByteArray(0)
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toByteArray()
    }
  }
}

data class RemoteSkillSearchRequest(
  val query: String,
  val limit: Int = 10,
)

data class RemoteSkillSearchHit(
  val id: String,
  val name: String,
  val source: String,
  val installs: Int,
  val installRef: String,
  val detailUrl: String,
)

data class RemoteSkillSearchResponse(
  val providerName: String,
  val hits: List<RemoteSkillSearchHit> = emptyList(),
  val errorCode: String? = null,
  val errorMessage: String? = null,
)

interface RemoteSkillSearchClient {
  fun search(request: RemoteSkillSearchRequest): RemoteSkillSearchResponse
}

class SkillsApiRemoteSkillSearchClient(
  private val transport: SkillPackageHttpTransport = HttpUrlSkillPackageHttpTransport(),
  private val baseUrl: String = DEFAULT_SKILLS_API_BASE_URL,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteSkillSearchClient {
  override fun search(request: RemoteSkillSearchRequest): RemoteSkillSearchResponse {
    val query = request.query.trim()
    if (query.isBlank()) {
      return RemoteSkillSearchResponse(providerName = "skills.sh")
    }
    val url = buildString {
      append(baseUrl.trimEnd('/'))
      append("/api/search?q=")
      append(urlEncodeQuery(query))
      append("&limit=")
      append(request.limit.coerceIn(1, 20))
    }
    return try {
      val response = transport.execute(
        SkillPackageHttpRequest(
          url = url,
          headers = mapOf("Accept" to "application/json"),
        ),
      )
      if (response.statusCode !in 200..299) {
        return RemoteSkillSearchResponse(
          providerName = "skills.sh",
          errorCode = "REMOTE_SKILL_SEARCH_FAILED",
          errorMessage = "Remote skill search failed with HTTP ${response.statusCode}.",
        )
      }
      val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
      val hits = payload.arrayValue("skills")
        ?.mapNotNull { element -> element as? JsonObject }
        ?.mapNotNull(::toRemoteHit)
        .orEmpty()
      RemoteSkillSearchResponse(
        providerName = "skills.sh",
        hits = hits,
      )
    } catch (error: Exception) {
      RemoteSkillSearchResponse(
        providerName = "skills.sh",
        errorCode = "REMOTE_SKILL_SEARCH_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  private fun toRemoteHit(payload: JsonObject): RemoteSkillSearchHit? {
    val id = payload.stringValue("id") ?: return null
    val name = payload.stringValue("name") ?: id.substringAfterLast('/')
    val source = payload.stringValue("source") ?: return null
    val installs = payload.intValue("installs") ?: 0
    return RemoteSkillSearchHit(
      id = id,
      name = name,
      source = source,
      installs = installs,
      installRef = installRef(source = source, skillName = name),
      detailUrl = "${baseUrl.trimEnd('/')}/${source.trimStart('/')}",
    )
  }

  private fun installRef(source: String, skillName: String): String {
    val repositoryName = source.substringAfterLast('/')
    return if (repositoryName.equals(skillName, ignoreCase = true)) {
      source
    } else {
      "$source@$skillName"
    }
  }
}

data class ResolvedRemoteSkillSource(
  val sourceType: SkillInstallSourceType,
  val requestedSourceRef: String,
  val webBaseUrl: String,
  val apiBaseUrl: String,
  val repositoryPath: String,
  val ref: String? = null,
  val subpath: String? = null,
  val selectedSkillName: String? = null,
) {
  val repo: String
    get() = repositoryPath.substringAfterLast('/')

  val repositoryUrl: String
    get() = "${webBaseUrl.trimEnd('/')}/$repositoryPath"

  val policyTargetUrl: String
    get() = when (sourceType) {
      SkillInstallSourceType.REMOTE_GITHUB ->
        "${apiBaseUrl.trimEnd('/')}/repos/$repositoryPath"

      SkillInstallSourceType.REMOTE_GITLAB ->
        "${apiBaseUrl.trimEnd('/')}/projects/${encodeProjectPath(repositoryPath)}"

      else -> repositoryUrl
    }
}

data class RemoteSkillSourceVersion(
  val repositoryUrl: String,
  val resolvedRevision: String,
  val resolvedCommitSha: String? = null,
)

data class RemoteSkillSourceVersionAttempt(
  val version: RemoteSkillSourceVersion? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = version != null
}

interface RemoteSkillSourceInspector {
  fun inspect(source: ResolvedRemoteSkillSource): RemoteSkillSourceVersionAttempt
}

class SkillSourceResolver {
  fun resolve(
    sourceRef: String,
    requestedSkillName: String? = null,
  ): ResolvedRemoteSkillSource? {
    val normalized = sourceRef.trim()
    if (normalized.isBlank()) {
      return null
    }
    return when {
      normalized.startsWith("github:", ignoreCase = true) ->
        parseGithubShorthand(
          raw = normalized.substringAfter(':'),
          requestedSourceRef = normalized,
          requestedSkillName = requestedSkillName,
        )

      normalized.startsWith("gitlab:", ignoreCase = true) ->
        parseGitlabShorthand(
          raw = normalized.substringAfter(':'),
          requestedSourceRef = normalized,
          requestedSkillName = requestedSkillName,
        )

      looksLikeGitRemote(normalized) ->
        parseHostedGitRemote(
          raw = normalized,
          requestedSkillName = requestedSkillName,
        )

      normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("https://", ignoreCase = true) ->
        parseHostedUrl(
          raw = normalized,
          requestedSkillName = requestedSkillName,
        )

      else -> parseGithubShorthand(
        raw = normalized,
        requestedSourceRef = normalized,
        requestedSkillName = requestedSkillName,
      )
    }
  }

  private fun parseHostedUrl(
    raw: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    return when (host) {
      "github.com",
      "www.github.com",
      -> parseGithubUrl(uri, raw, requestedSkillName)

      else -> parseGitlabUrl(uri, raw, requestedSkillName)
    }
  }

  private fun parseGithubShorthand(
    raw: String,
    requestedSourceRef: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val trimmed = raw.trim().removeSuffix("/")
    val slashIndex = trimmed.indexOf('/')
    if (slashIndex <= 0) {
      return null
    }
    val skillDelimiterIndex = trimmed.lastIndexOf('@')
    val inlineSkillName = if (skillDelimiterIndex > slashIndex) {
      trimmed.substring(skillDelimiterIndex + 1).trim().takeIf(String::isNotBlank)
    } else {
      null
    }
    val repositoryPart = if (skillDelimiterIndex > slashIndex) {
      trimmed.substring(0, skillDelimiterIndex)
    } else {
      trimmed
    }
    val segments = repositoryPart.split('/').filter(String::isNotBlank)
    if (segments.size != 2 || segments.any { segment -> !isValidHostedGitSegment(segment) }) {
      return null
    }
    return ResolvedRemoteSkillSource(
      sourceType = SkillInstallSourceType.REMOTE_GITHUB,
      requestedSourceRef = requestedSourceRef.trim(),
      webBaseUrl = DEFAULT_GITHUB_WEB_BASE_URL,
      apiBaseUrl = DEFAULT_GITHUB_API_BASE_URL,
      repositoryPath = segments.joinToString(separator = "/"),
      selectedSkillName = requestedSkillName?.trim()?.takeIf(String::isNotBlank) ?: inlineSkillName,
    )
  }

  private fun parseGitlabShorthand(
    raw: String,
    requestedSourceRef: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val trimmed = raw.trim().removeSuffix("/")
    val slashIndex = trimmed.indexOf('/')
    if (slashIndex <= 0) {
      return null
    }
    val skillDelimiterIndex = trimmed.lastIndexOf('@')
    val inlineSkillName = if (skillDelimiterIndex > slashIndex) {
      trimmed.substring(skillDelimiterIndex + 1).trim().takeIf(String::isNotBlank)
    } else {
      null
    }
    val repositoryPart = if (skillDelimiterIndex > slashIndex) {
      trimmed.substring(0, skillDelimiterIndex)
    } else {
      trimmed
    }
    val segments = repositoryPart.split('/').filter(String::isNotBlank)
    if (segments.size < 2 || segments.any { segment -> !isValidHostedGitSegment(segment) }) {
      return null
    }
    return ResolvedRemoteSkillSource(
      sourceType = SkillInstallSourceType.REMOTE_GITLAB,
      requestedSourceRef = requestedSourceRef.trim(),
      webBaseUrl = "https://gitlab.com",
      apiBaseUrl = "https://gitlab.com/api/v4",
      repositoryPath = segments.joinToString(separator = "/"),
      selectedSkillName = requestedSkillName?.trim()?.takeIf(String::isNotBlank) ?: inlineSkillName,
    )
  }

  private fun parseGithubUrl(
    uri: URI,
    requestedSourceRef: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val segments = normalizedPathSegments(uri)
    if (segments.size < 2) {
      return null
    }
    val repositorySegments = segments.take(2)
    if (repositorySegments.any { segment -> !isValidHostedGitSegment(segment) }) {
      return null
    }
    var ref: String? = null
    var subpath: String? = null
    if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) {
      ref = segments[3].takeIf(String::isNotBlank)
      subpath = segments.drop(4).joinToString(separator = "/").trim('/')
        .ifBlank { null }
      if (segments[2] == "blob") {
        subpath = normalizeBlobSubpath(subpath)
      }
    }
    return ResolvedRemoteSkillSource(
      sourceType = SkillInstallSourceType.REMOTE_GITHUB,
      requestedSourceRef = requestedSourceRef.trim(),
      webBaseUrl = DEFAULT_GITHUB_WEB_BASE_URL,
      apiBaseUrl = DEFAULT_GITHUB_API_BASE_URL,
      repositoryPath = repositorySegments.joinToString(separator = "/"),
      ref = ref,
      subpath = subpath,
      selectedSkillName = requestedSkillName?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun parseGitlabUrl(
    uri: URI,
    requestedSourceRef: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val host = uri.host?.trim().orEmpty()
    if (host.isBlank()) {
      return null
    }
    val segments = normalizedPathSegments(uri)
    if (segments.size < 2) {
      return null
    }
    val dashIndex = segments.indexOf("-")
    val repositorySegments = if (dashIndex >= 0) {
      segments.take(dashIndex)
    } else {
      segments
    }
    if (repositorySegments.size < 2 || repositorySegments.any { segment -> !isValidHostedGitSegment(segment) }) {
      return null
    }
    var ref: String? = null
    var subpath: String? = null
    if (dashIndex >= 0 && dashIndex + 2 < segments.size) {
      val action = segments[dashIndex + 1]
      if (action == "tree" || action == "blob") {
        ref = segments[dashIndex + 2].takeIf(String::isNotBlank)
        subpath = segments.drop(dashIndex + 3).joinToString(separator = "/").trim('/')
          .ifBlank { null }
        if (action == "blob") {
          subpath = normalizeBlobSubpath(subpath)
        }
      }
    }
    return ResolvedRemoteSkillSource(
      sourceType = SkillInstallSourceType.REMOTE_GITLAB,
      requestedSourceRef = requestedSourceRef.trim(),
      webBaseUrl = baseUrlFor(uri),
      apiBaseUrl = "${baseUrlFor(uri)}/api/v4",
      repositoryPath = repositorySegments.joinToString(separator = "/"),
      ref = ref,
      subpath = subpath,
      selectedSkillName = requestedSkillName?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun parseHostedGitRemote(
    raw: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    parseScpStyleGitRemote(
      raw = raw,
      requestedSkillName = requestedSkillName,
    )?.let { return it }
    return parseSshStyleGitRemote(
      raw = raw,
      requestedSkillName = requestedSkillName,
    )
  }

  private fun parseScpStyleGitRemote(
    raw: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val match = GIT_REMOTE_REGEX.matchEntire(raw.trim()) ?: return null
    val host = match.groupValues[1].trim()
    val path = match.groupValues[2].trim().removePrefix("/").removeSuffix(".git")
    return parseHostedGitRemotePath(
      host = host,
      repositoryPath = path,
      requestedSourceRef = raw,
      requestedSkillName = requestedSkillName,
    )
  }

  private fun parseSshStyleGitRemote(
    raw: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals("ssh", ignoreCase = true)) {
      return null
    }
    val host = uri.host?.trim().orEmpty()
    if (host.isBlank()) {
      return null
    }
    val path = uri.path?.trim()?.removePrefix("/")?.removeSuffix(".git").orEmpty()
    if (path.isBlank()) {
      return null
    }
    return parseHostedGitRemotePath(
      host = host,
      repositoryPath = path,
      requestedSourceRef = raw,
      requestedSkillName = requestedSkillName,
    )
  }

  private fun parseHostedGitRemotePath(
    host: String,
    repositoryPath: String,
    requestedSourceRef: String,
    requestedSkillName: String?,
  ): ResolvedRemoteSkillSource? {
    val normalizedHost = host.lowercase().trim()
    val segments = repositoryPath.split('/').filter(String::isNotBlank)
    if (segments.size < 2 || segments.any { segment -> !isValidHostedGitSegment(segment) }) {
      return null
    }
    return when {
      normalizedHost == "github.com" || normalizedHost == "www.github.com" -> {
        if (segments.size != 2) {
          return null
        }
        ResolvedRemoteSkillSource(
          sourceType = SkillInstallSourceType.REMOTE_GITHUB,
          requestedSourceRef = requestedSourceRef.trim(),
          webBaseUrl = DEFAULT_GITHUB_WEB_BASE_URL,
          apiBaseUrl = DEFAULT_GITHUB_API_BASE_URL,
          repositoryPath = segments.joinToString(separator = "/"),
          selectedSkillName = requestedSkillName?.trim()?.takeIf(String::isNotBlank),
        )
      }

      normalizedHost == "gitlab.com" || normalizedHost.contains("gitlab") -> ResolvedRemoteSkillSource(
        sourceType = SkillInstallSourceType.REMOTE_GITLAB,
        requestedSourceRef = requestedSourceRef.trim(),
        webBaseUrl = "https://$host",
        apiBaseUrl = "https://$host/api/v4",
        repositoryPath = segments.joinToString(separator = "/"),
        selectedSkillName = requestedSkillName?.trim()?.takeIf(String::isNotBlank),
      )

      else -> null
    }
  }

  private fun normalizedPathSegments(uri: URI): List<String> =
    uri.path.orEmpty()
      .split('/')
      .map(String::trim)
      .filter(String::isNotBlank)
      .map { segment -> segment.removeSuffix(".git") }

  private fun normalizeBlobSubpath(subpath: String?): String? = when {
    subpath.isNullOrBlank() -> null
    subpath == "SKILL.md" -> null
    subpath.endsWith("/SKILL.md") -> subpath.substringBeforeLast('/')
    else -> subpath
  }

  private fun baseUrlFor(uri: URI): String = buildString {
    append(uri.scheme?.ifBlank { "https" } ?: "https")
    append("://")
    append(uri.host)
    if (uri.port >= 0) {
      append(":")
      append(uri.port)
    }
  }

  private fun isValidHostedGitSegment(value: String): Boolean =
    value.isNotBlank() && value.none { it == '/' || it.isWhitespace() }

  private fun looksLikeGitRemote(value: String): Boolean =
    GIT_REMOTE_REGEX.matches(value.trim()) || value.trim().startsWith("ssh://", ignoreCase = true)

  private companion object {
    val GIT_REMOTE_REGEX = Regex("^git@([^:]+):(.+)$")
  }
}

data class FetchedRemoteSkillSource(
  val repositoryRoot: File,
  val searchRoot: File,
  val repositoryUrl: String,
  val resolvedRevision: String,
  val resolvedCommitSha: String? = null,
)

interface RemoteSkillSourceFetcher {
  fun fetch(
    source: ResolvedRemoteSkillSource,
    stagingRoot: File,
  ): FetchedRemoteSkillSource
}

class HostedGitRemoteSkillSourceInspector(
  private val transport: SkillPackageHttpTransport = HttpUrlSkillPackageHttpTransport(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteSkillSourceInspector {
  override fun inspect(source: ResolvedRemoteSkillSource): RemoteSkillSourceVersionAttempt = try {
    when (source.sourceType) {
      SkillInstallSourceType.REMOTE_GITHUB -> inspectGithub(source)
      SkillInstallSourceType.REMOTE_GITLAB -> inspectGitlab(source)
      else -> RemoteSkillSourceVersionAttempt(
        errorCode = "REMOTE_SKILL_SOURCE_UNSUPPORTED",
        errorMessage = "Remote source '${source.requestedSourceRef}' is not supported.",
      )
    }
  } catch (error: SkillPackageException) {
    RemoteSkillSourceVersionAttempt(
      errorCode = error.errorCode,
      errorMessage = error.message,
    )
  } catch (error: Exception) {
    RemoteSkillSourceVersionAttempt(
      errorCode = "REMOTE_SKILL_SOURCE_UNAVAILABLE",
      errorMessage = error.message ?: error::class.java.simpleName,
    )
  }

  private fun inspectGithub(source: ResolvedRemoteSkillSource): RemoteSkillSourceVersionAttempt {
    val repositoryMetadata = fetchRepositoryMetadata(
      source = source,
      projectUrl = "${source.apiBaseUrl.trimEnd('/')}/repos/${source.repositoryPath}",
      acceptHeader = "application/vnd.github+json",
      defaultBranchField = "default_branch",
      webUrlField = "html_url",
    )
    val resolvedRevision = source.ref ?: repositoryMetadata.defaultBranch
    val commitSha = fetchCommitSha(
      url = "${source.apiBaseUrl.trimEnd('/')}/repos/${source.repositoryPath}/commits/${encodePathSegment(resolvedRevision)}",
      acceptHeader = "application/vnd.github+json",
      fields = listOf("sha"),
    )
    return RemoteSkillSourceVersionAttempt(
      version = RemoteSkillSourceVersion(
        repositoryUrl = repositoryMetadata.webUrl,
        resolvedRevision = resolvedRevision,
        resolvedCommitSha = commitSha,
      ),
    )
  }

  private fun inspectGitlab(source: ResolvedRemoteSkillSource): RemoteSkillSourceVersionAttempt {
    val encodedProject = encodeProjectPath(source.repositoryPath)
    val repositoryMetadata = fetchRepositoryMetadata(
      source = source,
      projectUrl = "${source.apiBaseUrl.trimEnd('/')}/projects/$encodedProject",
      acceptHeader = "application/json",
      defaultBranchField = "default_branch",
      webUrlField = "web_url",
    )
    val resolvedRevision = source.ref ?: repositoryMetadata.defaultBranch
    val commitSha = fetchCommitSha(
      url = "${source.apiBaseUrl.trimEnd('/')}/projects/$encodedProject/repository/commits/${encodePathSegment(resolvedRevision)}",
      acceptHeader = "application/json",
      fields = listOf("id", "sha"),
    )
    return RemoteSkillSourceVersionAttempt(
      version = RemoteSkillSourceVersion(
        repositoryUrl = repositoryMetadata.webUrl,
        resolvedRevision = resolvedRevision,
        resolvedCommitSha = commitSha,
      ),
    )
  }

  private fun fetchRepositoryMetadata(
    source: ResolvedRemoteSkillSource,
    projectUrl: String,
    acceptHeader: String,
    defaultBranchField: String,
    webUrlField: String,
  ): RepositoryMetadata {
    val response = transport.execute(
      SkillPackageHttpRequest(
        url = projectUrl,
        headers = mapOf("Accept" to acceptHeader),
      ),
    )
    if (response.statusCode !in 200..299) {
      throw SkillPackageException(
        errorCode = "REMOTE_SKILL_SOURCE_NOT_FOUND",
        message = "Remote skill source '${source.requestedSourceRef}' was not found (HTTP ${response.statusCode}).",
      )
    }
    val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
    val defaultBranch = payload.stringValue(defaultBranchField)
      ?: throw SkillPackageException(
        errorCode = "REMOTE_SKILL_SOURCE_INVALID",
        message = "Remote skill source '${source.requestedSourceRef}' did not return a default branch.",
      )
    val webUrl = payload.stringValue(webUrlField).orEmpty().ifBlank { source.repositoryUrl }
    return RepositoryMetadata(
      defaultBranch = defaultBranch,
      webUrl = webUrl,
    )
  }

  private fun fetchCommitSha(
    url: String,
    acceptHeader: String,
    fields: List<String>,
  ): String? = runCatching {
    val response = transport.execute(
      SkillPackageHttpRequest(
        url = url,
        headers = mapOf("Accept" to acceptHeader),
      ),
    )
    if (response.statusCode !in 200..299) {
      return null
    }
    val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
    fields.firstNotNullOfOrNull(payload::stringValue)
  }.getOrNull()

  private data class RepositoryMetadata(
    val defaultBranch: String,
    val webUrl: String,
  )
}

class HostedGitArchiveRemoteSkillSourceFetcher(
  private val transport: SkillPackageHttpTransport = HttpUrlSkillPackageHttpTransport(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteSkillSourceFetcher {
  override fun fetch(
    source: ResolvedRemoteSkillSource,
    stagingRoot: File,
  ): FetchedRemoteSkillSource {
    if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
      throw IOException("Failed to create remote skill staging directory: ${stagingRoot.path}")
    }
    return when (source.sourceType) {
      SkillInstallSourceType.REMOTE_GITHUB -> fetchGithub(source, stagingRoot)
      SkillInstallSourceType.REMOTE_GITLAB -> fetchGitlab(source, stagingRoot)
      else -> throw IllegalArgumentException("Unsupported remote source type: ${source.sourceType}")
    }
  }

  private fun fetchGithub(
    source: ResolvedRemoteSkillSource,
    stagingRoot: File,
  ): FetchedRemoteSkillSource {
    val repositoryMetadata = fetchRepositoryMetadata(
      source = source,
      projectUrl = "${source.apiBaseUrl.trimEnd('/')}/repos/${source.repositoryPath}",
      acceptHeader = "application/vnd.github+json",
      defaultBranchField = "default_branch",
      webUrlField = "html_url",
    )
    val resolvedRevision = source.ref ?: repositoryMetadata.defaultBranch
    val commitSha = fetchCommitSha(
      url = "${source.apiBaseUrl.trimEnd('/')}/repos/${source.repositoryPath}/commits/${encodePathSegment(resolvedRevision)}",
      acceptHeader = "application/vnd.github+json",
      fields = listOf("sha"),
    )
    val archiveResponse = transport.execute(
      SkillPackageHttpRequest(
        url = "${source.apiBaseUrl.trimEnd('/')}/repos/${source.repositoryPath}/zipball/${encodePathSegment(resolvedRevision)}",
        headers = mapOf("Accept" to "application/vnd.github+json"),
      ),
    )
    return extractedRemoteSource(
      source = source,
      stagingRoot = stagingRoot,
      archiveResponse = archiveResponse,
      repositoryUrl = repositoryMetadata.webUrl,
      resolvedRevision = resolvedRevision,
      resolvedCommitSha = commitSha,
    )
  }

  private fun fetchGitlab(
    source: ResolvedRemoteSkillSource,
    stagingRoot: File,
  ): FetchedRemoteSkillSource {
    val encodedProject = encodeProjectPath(source.repositoryPath)
    val repositoryMetadata = fetchRepositoryMetadata(
      source = source,
      projectUrl = "${source.apiBaseUrl.trimEnd('/')}/projects/$encodedProject",
      acceptHeader = "application/json",
      defaultBranchField = "default_branch",
      webUrlField = "web_url",
    )
    val resolvedRevision = source.ref ?: repositoryMetadata.defaultBranch
    val commitSha = fetchCommitSha(
      url = "${source.apiBaseUrl.trimEnd('/')}/projects/$encodedProject/repository/commits/${encodePathSegment(resolvedRevision)}",
      acceptHeader = "application/json",
      fields = listOf("id", "sha"),
    )
    val archiveResponse = transport.execute(
      SkillPackageHttpRequest(
        url = "${source.apiBaseUrl.trimEnd('/')}/projects/$encodedProject/repository/archive.zip?sha=${urlEncodeQuery(resolvedRevision)}",
        headers = mapOf("Accept" to "application/json"),
      ),
    )
    return extractedRemoteSource(
      source = source,
      stagingRoot = stagingRoot,
      archiveResponse = archiveResponse,
      repositoryUrl = repositoryMetadata.webUrl,
      resolvedRevision = resolvedRevision,
      resolvedCommitSha = commitSha,
    )
  }

  private fun fetchRepositoryMetadata(
    source: ResolvedRemoteSkillSource,
    projectUrl: String,
    acceptHeader: String,
    defaultBranchField: String,
    webUrlField: String,
  ): RepositoryMetadata {
    val response = transport.execute(
      SkillPackageHttpRequest(
        url = projectUrl,
        headers = mapOf("Accept" to acceptHeader),
      ),
    )
    if (response.statusCode !in 200..299) {
      throw SkillPackageException(
        errorCode = "REMOTE_SKILL_SOURCE_NOT_FOUND",
        message = "Remote skill source '${source.requestedSourceRef}' was not found (HTTP ${response.statusCode}).",
      )
    }
    val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
    val defaultBranch = payload.stringValue(defaultBranchField)
      ?: throw SkillPackageException(
        errorCode = "REMOTE_SKILL_SOURCE_INVALID",
        message = "Remote skill source '${source.requestedSourceRef}' did not return a default branch.",
      )
    val webUrl = payload.stringValue(webUrlField).orEmpty().ifBlank { source.repositoryUrl }
    return RepositoryMetadata(
      defaultBranch = defaultBranch,
      webUrl = webUrl,
    )
  }

  private fun fetchCommitSha(
    url: String,
    acceptHeader: String,
    fields: List<String>,
  ): String? = runCatching {
    val response = transport.execute(
      SkillPackageHttpRequest(
        url = url,
        headers = mapOf("Accept" to acceptHeader),
      ),
    )
    if (response.statusCode !in 200..299) {
      return null
    }
    val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
    fields.firstNotNullOfOrNull(payload::stringValue)
  }.getOrNull()

  private fun extractedRemoteSource(
    source: ResolvedRemoteSkillSource,
    stagingRoot: File,
    archiveResponse: SkillPackageHttpResponse,
    repositoryUrl: String,
    resolvedRevision: String,
    resolvedCommitSha: String?,
  ): FetchedRemoteSkillSource {
    if (archiveResponse.statusCode !in 200..299) {
      throw SkillPackageException(
        errorCode = "REMOTE_SKILL_FETCH_FAILED",
        message = "Failed to download remote skill archive for ${source.repositoryPath} (HTTP ${archiveResponse.statusCode}).",
      )
    }
    val repositoryRoot = extractZipArchive(
      archiveBytes = archiveResponse.body,
      destinationRoot = File(stagingRoot, "archive"),
    )
    val searchRoot = source.subpath?.let { subpath ->
      File(repositoryRoot, subpath)
    } ?: repositoryRoot
    if (!searchRoot.exists() || !searchRoot.isDirectory) {
      throw SkillPackageException(
        errorCode = "REMOTE_SKILL_PATH_NOT_FOUND",
        message = "Remote skill source path '${source.subpath}' was not found in ${source.repositoryPath}.",
      )
    }
    return FetchedRemoteSkillSource(
      repositoryRoot = repositoryRoot,
      searchRoot = searchRoot,
      repositoryUrl = repositoryUrl,
      resolvedRevision = resolvedRevision,
      resolvedCommitSha = resolvedCommitSha,
    )
  }

  private fun extractZipArchive(
    archiveBytes: ByteArray,
    destinationRoot: File,
  ): File {
    if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
      throw IOException("Failed to create archive extraction directory: ${destinationRoot.path}")
    }
    val destinationPath = destinationRoot.canonicalFile.toPath()
    ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val entryName = entry.name.replace('\\', '/').trimStart('/')
        if (entryName.isBlank()) {
          zip.closeEntry()
          continue
        }
        val outputPath = destinationPath.resolve(entryName).normalize()
        if (!outputPath.startsWith(destinationPath)) {
          throw SkillPackageException(
            errorCode = "REMOTE_SKILL_ARCHIVE_INVALID",
            message = "Remote skill archive contained an invalid entry path.",
          )
        }
        if (entry.isDirectory) {
          Files.createDirectories(outputPath)
        } else {
          outputPath.parent?.let(Files::createDirectories)
          Files.newOutputStream(
            outputPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
          ).use { output ->
            zip.copyTo(output)
          }
        }
        zip.closeEntry()
      }
    }
    return resolveExtractedRepositoryRoot(destinationRoot)
  }

  private fun resolveExtractedRepositoryRoot(destinationRoot: File): File {
    val children = destinationRoot.listFiles().orEmpty().sortedBy { file -> file.name }
    if (children.size == 1 && children.single().isDirectory) {
      return children.single()
    }
    return destinationRoot
  }

  private data class RepositoryMetadata(
    val defaultBranch: String,
    val webUrl: String,
  )
}

typealias GithubArchiveRemoteSkillSourceFetcher = HostedGitArchiveRemoteSkillSourceFetcher

class SkillPackageException(
  val errorCode: String,
  override val message: String,
) : IOException(message)

private fun urlEncodeQuery(value: String): String =
  URLEncoder.encode(value, Charsets.UTF_8.name())

private fun encodePathSegment(value: String): String =
  URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun encodeProjectPath(value: String): String =
  URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun JsonObject.stringValue(key: String): String? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun JsonObject.intValue(key: String): Int? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.toIntOrNull()

private fun JsonObject.arrayValue(key: String): JsonArray? =
  this[key]?.jsonArray
