package com.opencray.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

internal data class TwinImportSourceProbeSnapshot(
  val filePath: String,
  val fileName: String,
  val fileExtension: String,
  val sourceMode: String?,
  val formatKey: String,
  val formatLabel: String,
  val confidence: String,
  val usesExistingImporter: Boolean,
  val needsManualSelection: Boolean,
  val notes: List<String>,
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "filePath" to filePath,
    "fileName" to fileName,
    "fileExtension" to fileExtension,
    "sourceMode" to sourceMode,
    "formatKey" to formatKey,
    "formatLabel" to formatLabel,
    "confidence" to confidence,
    "usesExistingImporter" to usesExistingImporter,
    "needsManualSelection" to needsManualSelection,
    "notes" to notes,
  )
}

internal object TwinImportSourceProbe {
  private const val SOURCE_MODE_CHAT_HISTORY = "chat_history"
  private const val SOURCE_MODE_FICTION_WORK = "fiction_work"
  private const val JSONL_PROBE_LIMIT = 256

  fun inspect(rawPath: String): TwinImportSourceProbeSnapshot {
    val normalizedPath = rawPath.trim()
    if (normalizedPath.isBlank()) {
      return unsupported(
        path = null,
        fileName = "",
        fileExtension = "",
        formatKey = "missing_path",
        formatLabel = "No file selected",
        note = "No file path was provided for import source probing.",
      )
    }
    return inspect(Path.of(normalizedPath))
  }

  fun inspect(path: Path): TwinImportSourceProbeSnapshot {
    val fileName = path.fileName?.toString().orEmpty().ifBlank { path.toString() }
    val extension = fileName.substringAfterLast('.', "").trim().lowercase(Locale.US)
    if (!Files.exists(path)) {
      return unsupported(
        path = path,
        fileName = fileName,
        fileExtension = extension,
        formatKey = "missing_file",
        formatLabel = "Missing file",
        note = "The selected import file does not exist on disk.",
      )
    }
    if (Files.isDirectory(path)) {
      return unsupported(
        path = path,
        fileName = fileName,
        fileExtension = extension,
        formatKey = "directory",
        formatLabel = "Directory",
        note = "Import probing expects a file, not a directory.",
      )
    }
    return when (extension) {
      "json" -> inspectJson(path = path, fileName = fileName, fileExtension = extension)
      "jsonl" -> inspectJsonl(path = path, fileName = fileName, fileExtension = extension)
      else -> unsupported(
        path = path,
        fileName = fileName,
        fileExtension = extension,
        formatKey = "unsupported_extension",
        formatLabel = "Unsupported file type",
        note = "Only JSON and JSONL corpus files can be classified automatically right now.",
      )
    }
  }

  private fun inspectJson(
    path: Path,
    fileName: String,
    fileExtension: String,
  ): TwinImportSourceProbeSnapshot {
    val payload = runCatching {
      val rawJson = Files.readAllBytes(path).toString(StandardCharsets.UTF_8)
      JSONTokener(rawJson).nextValue()
    }.getOrNull() as? JSONObject ?: return unsupported(
      path = path,
      fileName = fileName,
      fileExtension = fileExtension,
      formatKey = "invalid_json",
      formatLabel = "Invalid JSON",
      note = "The selected JSON file could not be parsed into an object payload.",
    )

    return when {
      isChatLabJson(payload) -> detected(
        path = path,
        fileName = fileName,
        fileExtension = fileExtension,
        sourceMode = SOURCE_MODE_CHAT_HISTORY,
        formatKey = "chatlab_json",
        formatLabel = "ChatLab JSON",
        confidence = "high",
        note = "Detected ChatLab JSON using the meta/members/messages container shape.",
      )
      isNormalizedChatHistory(payload) -> detected(
        path = path,
        fileName = fileName,
        fileExtension = fileExtension,
        sourceMode = SOURCE_MODE_CHAT_HISTORY,
        formatKey = "normalized_chat_history",
        formatLabel = "Normalized chat history JSON",
        confidence = "high",
        note = "Detected the normalized chat corpus schema with participants and turns.",
      )
      isNormalizedFictionWork(payload) -> detected(
        path = path,
        fileName = fileName,
        fileExtension = fileExtension,
        sourceMode = SOURCE_MODE_FICTION_WORK,
        formatKey = "normalized_fiction_work",
        formatLabel = "Normalized fiction work JSON",
        confidence = "high",
        note = "Detected the normalized fiction-work schema with characters and scenes.",
      )
      else -> unsupported(
        path = path,
        fileName = fileName,
        fileExtension = fileExtension,
        formatKey = "unknown_json",
        formatLabel = "Unknown JSON corpus",
        note = "The JSON file did not match ChatLab or the normalized OpenCray import schemas.",
      )
    }
  }

  private fun inspectJsonl(
    path: Path,
    fileName: String,
    fileExtension: String,
  ): TwinImportSourceProbeSnapshot {
    var sawHeader = false
    var sawMessage = false
    var sawMember = false
    var parsedAnyRecord = false
    val invalidRecord = runCatching {
      Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
        lines.take(JSONL_PROBE_LIMIT).forEach { rawLine ->
          val line = rawLine.removePrefix("\uFEFF").trim()
          if (line.isEmpty() || line.startsWith("#")) {
            return@forEach
          }
          val payload = runCatching { JSONTokener(line).nextValue() }.getOrNull() as? JSONObject
            ?: throw IllegalArgumentException("JSONL record is not a JSON object.")
          parsedAnyRecord = true
          when (payload.optString("_type").trim().lowercase(Locale.US)) {
            "header" -> if (payload.optJSONObject("meta") != null) {
              sawHeader = true
            }
            "member" -> if (payload.optString("platformId").isNotBlank()) {
              sawMember = true
            }
            "message" -> if (payload.optString("sender").isNotBlank() || payload.has("content")) {
              sawMessage = true
            }
          }
        }
      }
      false
    }.getOrElse { true }

    if (invalidRecord) {
      return unsupported(
        path = path,
        fileName = fileName,
        fileExtension = fileExtension,
        formatKey = "invalid_jsonl",
        formatLabel = "Invalid JSONL",
        note = "The JSONL file could not be parsed into ChatLab-style object records.",
      )
    }
    if (parsedAnyRecord && sawHeader && sawMessage) {
      return detected(
        path = path,
        fileName = fileName,
        fileExtension = fileExtension,
        sourceMode = SOURCE_MODE_CHAT_HISTORY,
        formatKey = "chatlab_jsonl",
        formatLabel = "ChatLab JSONL",
        confidence = if (sawMember) "high" else "medium",
        note = if (sawMember) {
          "Detected ChatLab JSONL using header/member/message records."
        } else {
          "Detected ChatLab-like JSONL using header and message records."
        },
      )
    }
    return unsupported(
      path = path,
      fileName = fileName,
      fileExtension = fileExtension,
      formatKey = "unknown_jsonl",
      formatLabel = "Unknown JSONL corpus",
      note = "The JSONL file did not match the ChatLab header/member/message pattern.",
    )
  }

  private fun isChatLabJson(payload: JSONObject): Boolean =
    payload.optJSONObject("meta") != null && payload.optJSONArray("messages") != null && (
      payload.optJSONArray("members") != null || payload.optJSONObject("chatlab") != null
    )

  private fun isNormalizedChatHistory(payload: JSONObject): Boolean =
    payload.optJSONArray("participants") != null && payload.optJSONArray("turns") != null

  private fun isNormalizedFictionWork(payload: JSONObject): Boolean =
    payload.optJSONArray("scenes") != null && (
      payload.optJSONArray("characters") != null ||
        payload.has("work_id") ||
        payload.has("workId")
    )

  private fun detected(
    path: Path,
    fileName: String,
    fileExtension: String,
    sourceMode: String,
    formatKey: String,
    formatLabel: String,
    confidence: String,
    note: String,
  ): TwinImportSourceProbeSnapshot = TwinImportSourceProbeSnapshot(
    filePath = path.toString(),
    fileName = fileName,
    fileExtension = fileExtension,
    sourceMode = sourceMode,
    formatKey = formatKey,
    formatLabel = formatLabel,
    confidence = confidence,
    usesExistingImporter = true,
    needsManualSelection = false,
    notes = listOf(note),
  )

  private fun unsupported(
    path: Path?,
    fileName: String,
    fileExtension: String,
    formatKey: String,
    formatLabel: String,
    note: String,
  ): TwinImportSourceProbeSnapshot = TwinImportSourceProbeSnapshot(
    filePath = path?.toString().orEmpty(),
    fileName = fileName,
    fileExtension = fileExtension,
    sourceMode = null,
    formatKey = formatKey,
    formatLabel = formatLabel,
    confidence = "low",
    usesExistingImporter = false,
    needsManualSelection = true,
    notes = listOf(note),
  )
}
