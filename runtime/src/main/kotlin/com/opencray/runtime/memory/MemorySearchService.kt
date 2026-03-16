package com.opencray.runtime.memory

import java.util.Locale

data class MemorySearchMatch(
  val recordId: String,
  val path: String,
  val startLine: Int,
  val endLine: Int,
  val score: Int,
  val matchedTerms: List<String>,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val status: MemoryStatus,
  val snippet: String,
)

data class MemorySearchResponse(
  val queryTerms: List<String>,
  val matches: List<MemorySearchMatch>,
  val corpusFileCount: Int,
)

data class MemoryGetResponse(
  val path: String,
  val text: String,
  val startLine: Int,
  val endLine: Int,
  val totalLineCount: Int,
)

class MemorySearchService(
  private val projector: MemoryCorpusProjector = MemoryCorpusProjector(),
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun search(
    context: MemoryToolContext,
    query: String,
    maxResults: Int = DEFAULT_MAX_RESULTS,
    minScore: Int = DEFAULT_MIN_SCORE,
  ): MemorySearchResponse {
    val queryTerms = extractMemoryQueryTerms(policy = policy, text = query).toList()
    val normalizedQuery = policy.normalizeCandidateContent(query).orEmpty()
    val projection = projector.project(context)
    if (projection.files.isEmpty()) {
      return MemorySearchResponse(
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
      .groupBy(MemorySearchMatch::recordId)
      .values
      .map { group ->
        group.maxWithOrNull(
          compareBy<MemorySearchMatch> { it.score }
            .thenBy { if (normalizeProjectedMemoryPath(it.path) == normalizeProjectedMemoryPath("MEMORY.md")) 0 else 1 }
            .thenBy { it.endLine - it.startLine }
            .thenBy { it.path },
        )!!
      }
      .sortedWith(
        compareByDescending<MemorySearchMatch> { it.score }
          .thenBy { normalizeProjectedMemoryPath(it.path) == normalizeProjectedMemoryPath("MEMORY.md") }
          .thenByDescending { it.endLine - it.startLine }
          .thenBy { it.path }
          .thenBy { it.startLine },
      )
      .take(maxResults.coerceAtLeast(1))
      .toList()

    return MemorySearchResponse(
      queryTerms = queryTerms,
      matches = matches,
      corpusFileCount = projection.files.size,
    )
  }

  fun get(
    context: MemoryToolContext,
    path: String,
    from: Int? = null,
    lines: Int? = null,
  ): MemoryGetResponse {
    val projection = projector.project(context)
    val file = projection.file(path)
      ?: throw IllegalArgumentException("Projected memory path '$path' was not found.")
    val totalLineCount = file.lines.size
    val startLine = (from ?: 1).coerceAtLeast(1)
    val requestedLines = (lines ?: DEFAULT_GET_LINE_COUNT).coerceAtLeast(1)
    if (startLine > totalLineCount) {
      throw IllegalArgumentException(
        "Projected memory path '$path' has only $totalLineCount line(s); cannot start from line $startLine.",
      )
    }
    val endLine = minOf(totalLineCount, startLine + requestedLines - 1)
    return MemoryGetResponse(
      path = file.path,
      text = file.lines.subList(startLine - 1, endLine).joinToString(separator = "\n"),
      startLine = startLine,
      endLine = endLine,
      totalLineCount = totalLineCount,
    )
  }

  private fun scoreMatch(
    section: ProjectedMemorySection,
    normalizedQuery: String,
    queryTerms: List<String>,
  ): MemorySearchMatch? {
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
    score += when (section.status) {
      MemoryStatus.ACTIVE -> 24
      MemoryStatus.OPEN -> 20
      MemoryStatus.RESOLVED -> 12
    }
    score += when (section.kind) {
      MemoryKind.DURABLE_INSTRUCTION -> 18
      MemoryKind.USER_PREFERENCE -> 16
      MemoryKind.PROJECT_FACT -> 12
      MemoryKind.TASK_COMMITMENT -> 10
    }
    score += when (ageDays(section.updatedAtEpochMs)) {
      in 0..1 -> 18
      in 2..7 -> 10
      in 8..30 -> 4
      else -> 0
    }
    if (normalizeProjectedMemoryPath(section.path) == normalizeProjectedMemoryPath("MEMORY.md")) {
      score -= 8
    }
    return MemorySearchMatch(
      recordId = section.recordId,
      path = section.path,
      startLine = section.startLine,
      endLine = section.endLine,
      score = score,
      matchedTerms = matchedTerms.distinct().sorted(),
      kind = section.kind,
      scope = section.scope,
      status = section.status,
      snippet = section.searchableText,
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
