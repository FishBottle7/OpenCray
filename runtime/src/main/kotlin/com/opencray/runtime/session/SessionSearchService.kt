package com.opencray.runtime.session

import com.opencray.runtime.memory.extractMemoryQueryTerms
import com.opencray.runtime.memory.normalizeMemoryQueryText
import java.util.Locale

data class SessionSearchMatch(
  val sessionId: String,
  val title: String?,
  val path: String,
  val startLine: Int,
  val endLine: Int,
  val score: Int,
  val matchedTerms: List<String>,
  val snippet: String,
)

data class SessionSearchResponse(
  val queryTerms: List<String>,
  val matches: List<SessionSearchMatch>,
  val corpusFileCount: Int,
)

data class SessionGetResponse(
  val path: String,
  val text: String,
  val startLine: Int,
  val endLine: Int,
  val totalLineCount: Int,
  val sessionIds: List<String> = emptyList(),
)

class SessionSearchService(
  private val projector: SessionSearchProjector = SessionSearchProjector(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun search(
    context: SessionSearchToolContext,
    query: String,
    maxResults: Int = DEFAULT_MAX_RESULTS,
    minScore: Int = DEFAULT_MIN_SCORE,
  ): SessionSearchResponse {
    val queryTerms = extractMemoryQueryTerms(text = query).toList()
    val normalizedQuery = normalizeMemoryQueryText(query).orEmpty()
    val projection = projector.project(context)
    if (projection.files.isEmpty()) {
      return SessionSearchResponse(
        queryTerms = queryTerms,
        matches = emptyList(),
        corpusFileCount = 0,
      )
    }

    val matches = projection.files
      .asSequence()
      .flatMap { file ->
        file.sections.asSequence().mapNotNull { section ->
          scoreMatch(
            section = section,
            normalizedQuery = normalizedQuery,
            queryTerms = queryTerms,
          )
        }
      }
      .filter { match -> match.score >= minScore }
      .groupBy(SessionSearchMatch::sessionId)
      .values
      .map { group ->
        checkNotNull(
          group.maxWithOrNull(
            compareBy<SessionSearchMatch>(SessionSearchMatch::score)
              .thenBy { match ->
                if (normalizeProjectedSessionPath(match.path) == normalizeProjectedSessionPath("SESSIONS.md")) {
                  0
                } else {
                  1
                }
              }
              .thenBy { match -> -(match.endLine - match.startLine) }
              .thenBy(SessionSearchMatch::path),
          ),
        )
      }
      .sortedWith(
        compareByDescending<SessionSearchMatch>(SessionSearchMatch::score)
          .thenBy { match ->
            normalizeProjectedSessionPath(match.path) == normalizeProjectedSessionPath("SESSIONS.md")
          }
          .thenBy(SessionSearchMatch::path)
          .thenBy(SessionSearchMatch::startLine),
      )
      .take(maxResults.coerceAtLeast(1))
      .toList()

    return SessionSearchResponse(
      queryTerms = queryTerms,
      matches = matches,
      corpusFileCount = projection.files.size,
    )
  }

  fun get(
    context: SessionSearchToolContext,
    path: String,
    from: Int? = null,
    lines: Int? = null,
  ): SessionGetResponse {
    val projection = projector.project(context)
    val file = projection.file(path)
      ?: throw IllegalArgumentException("Projected session path '$path' was not found.")
    val totalLineCount = file.lines.size
    val startLine = (from ?: 1).coerceAtLeast(1)
    val requestedLines = (lines ?: DEFAULT_GET_LINE_COUNT).coerceAtLeast(1)
    if (startLine > totalLineCount) {
      throw IllegalArgumentException(
        "Projected session path '$path' has only $totalLineCount line(s); cannot start from line $startLine.",
      )
    }
    val endLine = minOf(totalLineCount, startLine + requestedLines - 1)
    val sessionIds = file.sections
      .asSequence()
      .filter { section ->
        section.endLine >= startLine && section.startLine <= endLine
      }
      .map(ProjectedSessionSection::sessionId)
      .distinct()
      .toList()
    return SessionGetResponse(
      path = file.path,
      text = file.lines.subList(startLine - 1, endLine).joinToString(separator = "\n"),
      startLine = startLine,
      endLine = endLine,
      totalLineCount = totalLineCount,
      sessionIds = sessionIds,
    )
  }

  private fun scoreMatch(
    section: ProjectedSessionSection,
    normalizedQuery: String,
    queryTerms: List<String>,
  ): SessionSearchMatch? {
    val searchableText = section.searchableText.lowercase(Locale.US)
    val normalizedQueryLower = normalizedQuery.lowercase(Locale.US)
    val matchedTerms = queryTerms.filter { term ->
      searchableText.contains(term.lowercase(Locale.US))
    }
    val hasNormalizedQueryMatch = normalizedQueryLower.isNotBlank() && searchableText.contains(normalizedQueryLower)
    if (matchedTerms.isEmpty() && !hasNormalizedQueryMatch) {
      return null
    }
    var score = matchedTerms.size * 90
    if (hasNormalizedQueryMatch) {
      score += 65
    }
    score += when (section.kind) {
      ProjectedSessionSectionKind.SUMMARY -> 24
      ProjectedSessionSectionKind.MESSAGE -> 18
      ProjectedSessionSectionKind.INDEX -> 8
    }
    score += when (ageDays(section.updatedAtEpochMs)) {
      in 0..1 -> 18
      in 2..7 -> 10
      in 8..30 -> 4
      else -> 0
    }
    if (normalizeProjectedSessionPath(section.path) == normalizeProjectedSessionPath("SESSIONS.md")) {
      score -= 6
    }
    return SessionSearchMatch(
      sessionId = section.sessionId,
      title = section.title,
      path = section.path,
      startLine = section.startLine,
      endLine = section.endLine,
      score = score,
      matchedTerms = matchedTerms.distinct().sorted(),
      snippet = section.snippet,
    )
  }

  private fun ageDays(updatedAtEpochMs: Long): Long =
    ((clock() - updatedAtEpochMs).coerceAtLeast(0L)) / DAY_MS

  private companion object {
    const val DEFAULT_MAX_RESULTS: Int = 4
    const val DEFAULT_MIN_SCORE: Int = 1
    const val DEFAULT_GET_LINE_COUNT: Int = 12
    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
  }
}
