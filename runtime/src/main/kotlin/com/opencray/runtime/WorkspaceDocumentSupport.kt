package com.opencray.runtime

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

enum class WorkspaceDocumentKind {
  IMAGE,
  PDF,
}

data class WorkspaceDocumentSearchRequest(
  val query: String? = null,
  val pageNumbers: List<Int> = emptyList(),
  val pageFrom: Int? = null,
  val pageTo: Int? = null,
  val maxResults: Int = 5,
)

data class WorkspaceDocumentSearchHit(
  val pageNumber: Int,
  val excerpt: String,
  val matchCount: Int = 0,
)

data class WorkspaceDocumentSearchResult(
  val documentKind: WorkspaceDocumentKind,
  val pageCount: Int,
  val query: String? = null,
  val hits: List<WorkspaceDocumentSearchHit> = emptyList(),
)

interface WorkspaceDocumentSearchProvider {
  fun search(
    path: Path,
    request: WorkspaceDocumentSearchRequest,
  ): WorkspaceDocumentSearchResult
}

object OpenCrayDocumentRuntimeEnvironment {
  @Volatile
  private var applicationContext: Context? = null

  fun initialize(context: Context) {
    val normalized = context.applicationContext ?: context
    applicationContext = normalized
    PdfBoxBootstrap.ensureInitialized(normalized)
  }

  internal fun applicationContextOrNull(): Context? = applicationContext
}

class DefaultWorkspaceDocumentSearchProvider(
  private val applicationContextProvider: () -> Context? = {
    OpenCrayDocumentRuntimeEnvironment.applicationContextOrNull()
  },
) : WorkspaceDocumentSearchProvider {
  override fun search(
    path: Path,
    request: WorkspaceDocumentSearchRequest,
  ): WorkspaceDocumentSearchResult {
    if (workspaceDocumentKindFor(path) != WorkspaceDocumentKind.PDF) {
      throw IllegalArgumentException(
        "search_workspace_document currently supports PDF files only: ${path.fileName}",
      )
    }
    applicationContextProvider()?.let(PdfBoxBootstrap::ensureInitialized)
    require(Files.exists(path) && Files.isRegularFile(path)) {
      "Workspace document must exist and be a file: $path"
    }
    require(Files.size(path) in 1..MAX_SEARCHABLE_DOCUMENT_BYTES) {
      "Workspace document must be between 1 byte and ${MAX_SEARCHABLE_DOCUMENT_BYTES / (1024 * 1024)} MB: $path"
    }

    PDDocument.load(path.toFile()).use { document ->
      val pageCount = document.numberOfPages
      val selectedPages = resolveSelectedPages(
        pageCount = pageCount,
        pageNumbers = request.pageNumbers,
        pageFrom = request.pageFrom,
        pageTo = request.pageTo,
      )
      val query = request.query?.trim()?.takeIf(String::isNotBlank)
      val hits = mutableListOf<WorkspaceDocumentSearchHit>()
      val stripper = PDFTextStripper()
      for (pageNumber in selectedPages) {
        stripper.startPage = pageNumber
        stripper.endPage = pageNumber
        val normalizedText = normalizeSearchableText(stripper.getText(document))
        if (normalizedText.isBlank()) {
          continue
        }
        if (query == null) {
          hits += WorkspaceDocumentSearchHit(
            pageNumber = pageNumber,
            excerpt = excerptForPreview(normalizedText),
          )
        } else {
          val matchCount = countMatches(normalizedText, query)
          if (matchCount > 0) {
            hits += WorkspaceDocumentSearchHit(
              pageNumber = pageNumber,
              excerpt = excerptAroundMatch(normalizedText, query),
              matchCount = matchCount,
            )
          }
        }
        if (hits.size >= request.maxResults) {
          break
        }
      }
      return WorkspaceDocumentSearchResult(
        documentKind = WorkspaceDocumentKind.PDF,
        pageCount = pageCount,
        query = query,
        hits = hits.toList(),
      )
    }
  }

  private fun resolveSelectedPages(
    pageCount: Int,
    pageNumbers: List<Int>,
    pageFrom: Int?,
    pageTo: Int?,
  ): List<Int> {
    if (pageCount <= 0) {
      return emptyList()
    }
    val explicitPages = pageNumbers
      .map { pageNumber ->
        require(pageNumber in 1..pageCount) {
          "Requested page $pageNumber is outside the document page range 1..$pageCount."
        }
        pageNumber
      }
      .distinct()
      .sorted()
    if (explicitPages.isNotEmpty()) {
      return explicitPages
    }
    if (pageFrom == null && pageTo == null) {
      return (1..pageCount).toList()
    }
    val start = (pageFrom ?: 1).coerceAtLeast(1)
    val end = (pageTo ?: pageCount).coerceAtMost(pageCount)
    require(start <= end) {
      "page_from must be less than or equal to page_to."
    }
    return (start..end).toList()
  }

  private fun normalizeSearchableText(rawText: String): String =
    rawText
      .replace(Regex("\\s+"), " ")
      .trim()

  private fun excerptForPreview(text: String): String =
    text.take(PREVIEW_EXCERPT_CHARS).let { preview ->
      if (preview.length < text.length) "$preview..." else preview
    }

  private fun excerptAroundMatch(
    text: String,
    query: String,
  ): String {
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    val matchIndex = lowerText.indexOf(lowerQuery)
    if (matchIndex < 0) {
      return excerptForPreview(text)
    }
    val start = (matchIndex - MATCH_CONTEXT_CHARS).coerceAtLeast(0)
    val end = (matchIndex + lowerQuery.length + MATCH_CONTEXT_CHARS).coerceAtMost(text.length)
    val prefix = if (start > 0) "..." else ""
    val suffix = if (end < text.length) "..." else ""
    return prefix + text.substring(start, end).trim() + suffix
  }

  private fun countMatches(
    text: String,
    query: String,
  ): Int {
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    if (lowerQuery.isBlank()) {
      return 0
    }
    var count = 0
    var startIndex = 0
    while (startIndex < lowerText.length) {
      val index = lowerText.indexOf(lowerQuery, startIndex)
      if (index < 0) {
        break
      }
      count += 1
      startIndex = index + lowerQuery.length
    }
    return count
  }

  private companion object {
    private const val MAX_SEARCHABLE_DOCUMENT_BYTES: Long = 32L * 1024L * 1024L
    private const val PREVIEW_EXCERPT_CHARS: Int = 220
    private const val MATCH_CONTEXT_CHARS: Int = 100
  }
}

internal fun workspaceDocumentKindFor(path: Path): WorkspaceDocumentKind? =
  when (path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()) {
    "png",
    "jpg",
    "jpeg",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
    -> WorkspaceDocumentKind.IMAGE

    "pdf" -> WorkspaceDocumentKind.PDF
    else -> null
  }

private object PdfBoxBootstrap {
  private val initialized = AtomicBoolean(false)

  fun ensureInitialized(context: Context) {
    if (initialized.get()) {
      return
    }
    synchronized(this) {
      if (initialized.get()) {
        return
      }
      PDFBoxResourceLoader.init(context.applicationContext ?: context)
      initialized.set(true)
    }
  }
}
