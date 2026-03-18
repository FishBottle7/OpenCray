package com.opencray.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.isRegularFile

internal data class WorkspaceSoulDocument(
  val file: Path,
  val relativePath: String,
  val content: String,
  val profile: WorkspaceSoulProfile?,
)

internal class WorkspaceSoulProfileStore(
  private val soulExtensionFactory: PersonalizationSoulExtensionFactory = PersonalizationSoulExtensionFactory(),
) {
  fun loadSoulDocument(workspaceRoot: Path?): WorkspaceSoulDocument? {
    val normalizedRoot = workspaceRoot?.toAbsolutePath()?.normalize() ?: return null
    val file = normalizedRoot.resolve(SOUL_FILE_NAME).normalize()
    if (!file.startsWith(normalizedRoot) || !file.isRegularFile()) {
      return null
    }
    val content = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    return WorkspaceSoulDocument(
      file = file,
      relativePath = SOUL_FILE_NAME,
      content = content,
      profile = parseSoulProfile(content),
    )
  }

  fun loadSoulProfile(workspaceRoot: Path?): WorkspaceSoulProfile? =
    loadSoulDocument(workspaceRoot)?.profile

  fun saveSoulProfile(
    workspaceRoot: Path,
    profile: WorkspaceSoulProfile,
  ) {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    val target = normalizedRoot.resolve(SOUL_FILE_NAME).normalize()
    require(target.startsWith(normalizedRoot)) {
      "SOUL.md must stay inside the workspace root."
    }
    Files.createDirectories(normalizedRoot)
    val existingProfile = loadSoulProfile(normalizedRoot)
    val updatedProfile = mergedProfile(
      existingProfile = existingProfile,
      incomingProfile = profile,
    )
    Files.write(
      target,
      renderDocument(updatedProfile).toByteArray(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
  }

  fun clearSoulProfile(workspaceRoot: Path?): Boolean {
    val normalizedRoot = workspaceRoot?.toAbsolutePath()?.normalize() ?: return false
    val target = normalizedRoot.resolve(SOUL_FILE_NAME).normalize()
    if (!target.startsWith(normalizedRoot) || !Files.exists(target)) {
      return false
    }
    return Files.deleteIfExists(target)
  }

  private fun mergedProfile(
    existingProfile: WorkspaceSoulProfile?,
    incomingProfile: WorkspaceSoulProfile,
  ): WorkspaceSoulProfile {
    val normalizedPresetName = incomingProfile.presetName.trim()
    val explicitExtensions = incomingProfile.extensions
      .mapNotNull { (rawKey, rawValue) ->
        val normalizedKey = PersonalizationSoulExtensionFactory.normalizeKey(rawKey) ?: return@mapNotNull null
        val normalizedValue = rawValue.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (normalizedKey in RESERVED_KEYS) {
          return@mapNotNull null
        }
        normalizedKey to normalizedValue
      }
      .toMap(linkedMapOf())
    val explicitNormalizedKeys = explicitExtensions.keys
    val managedExtensions = soulExtensionFactory.createManagedExtensions(normalizedPresetName)
    val managedNormalizedKeys = managedExtensions.keys
      .mapNotNull(PersonalizationSoulExtensionFactory::normalizeKey)
      .toSet()
    val preservedExtensions = existingProfile?.extensions
      .orEmpty()
      .mapNotNull { (rawKey, rawValue) ->
        val normalizedKey = PersonalizationSoulExtensionFactory.normalizeKey(rawKey) ?: return@mapNotNull null
        val normalizedValue = rawValue.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (
          normalizedKey in RESERVED_KEYS ||
          PersonalizationSoulExtensionFactory.isManagedKey(rawKey) ||
          normalizedKey in explicitNormalizedKeys ||
          normalizedKey in managedNormalizedKeys
        ) {
          return@mapNotNull null
        }
        normalizedKey to normalizedValue
      }
      .toMap(linkedMapOf())
    return WorkspaceSoulProfile(
      presetName = normalizedPresetName,
      customLabel = incomingProfile.customLabel.trim(),
      customGuidance = incomingProfile.customGuidance.trim(),
      extensions = preservedExtensions + explicitExtensions + managedExtensions,
    )
  }

  private fun parseSoulProfile(content: String): WorkspaceSoulProfile? {
    val normalizedContent = content.replace("\r\n", "\n").trim()
    if (normalizedContent.isBlank()) {
      return null
    }
    val document = parseDocument(normalizedContent)
    val presetName = document.frontmatter["preset"].orEmpty()
    val customLabel = document.frontmatter["display_name"]
      ?: document.frontmatter["displayName"]
      ?: ""
    val customGuidance = document.body.ifBlank {
      document.frontmatter["custom_guidance"]
        ?: document.frontmatter["customGuidance"]
        ?: ""
    }
    val explicitExtensions = document.frontmatter
      .mapNotNull { (rawKey, rawValue) ->
        val normalizedKey = PersonalizationSoulExtensionFactory.normalizeKey(rawKey) ?: return@mapNotNull null
        val normalizedValue = rawValue.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (normalizedKey in RESERVED_KEYS || normalizedKey in DOCUMENT_RESERVED_KEYS) {
          return@mapNotNull null
        }
        normalizedKey to normalizedValue
      }
      .toMap(linkedMapOf())
    val managedExtensions = soulExtensionFactory.createManagedExtensions(presetName)
    val mergedExtensions = managedExtensions + explicitExtensions
    val profile = WorkspaceSoulProfile(
      presetName = presetName,
      customLabel = customLabel.trim(),
      customGuidance = customGuidance.trim(),
      extensions = mergedExtensions,
    )
    return if (
      profile.presetName.isBlank() &&
      profile.customLabel.isBlank() &&
      profile.customGuidance.isBlank() &&
      profile.extensions.isEmpty()
    ) {
      null
    } else {
      profile
    }
  }

  private fun renderDocument(profile: WorkspaceSoulProfile): String = buildString {
    appendLine("---")
    appendLine("kind: opencray_soul")
    profile.presetName
      .trim()
      .takeIf(String::isNotEmpty)
      ?.let { presetName ->
        append("preset: ")
        appendLine(presetName)
      }
    profile.customLabel
      .trim()
      .takeIf(String::isNotEmpty)
      ?.let { displayName ->
        append("display_name: ")
        appendLine(displayName)
      }
    profile.extensions
      .mapNotNull { (rawKey, rawValue) ->
        val normalizedKey = PersonalizationSoulExtensionFactory.normalizeKey(rawKey) ?: return@mapNotNull null
        val normalizedValue = rawValue.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (normalizedKey in RESERVED_KEYS || normalizedKey in DOCUMENT_RESERVED_KEYS) {
          return@mapNotNull null
        }
        normalizedKey to normalizedValue
      }
      .sortedBy(Pair<String, String>::first)
      .forEach { (key, value) ->
        append(key)
        append(": ")
        appendLine(value)
      }
    appendLine("---")
    profile.customGuidance
      .trim()
      .takeIf(String::isNotEmpty)
      ?.let { customGuidance ->
        appendLine()
        appendLine(customGuidance)
      }
  }

  private fun parseDocument(content: String): ParsedSoulDocument {
    if (!content.startsWith("---\n")) {
      return ParsedSoulDocument(
        frontmatter = emptyMap(),
        body = content.trim(),
      )
    }
    val lines = content.split('\n')
    val closingOffset = lines.drop(1).indexOfFirst { line -> line == "---" }
    val closingIndex = closingOffset
      .takeIf { offset -> offset >= 0 }
      ?.plus(1)
      ?: return ParsedSoulDocument(
      frontmatter = emptyMap(),
      body = content.trim(),
    )
    val frontmatter = parseFrontmatter(lines.subList(1, closingIndex))
    val body = lines.drop(closingIndex + 1)
      .joinToString("\n")
      .trim()
    return ParsedSoulDocument(
      frontmatter = frontmatter,
      body = body,
    )
  }

  private fun parseFrontmatter(lines: List<String>): Map<String, String> {
    val values = linkedMapOf<String, String>()
    var index = 0
    while (index < lines.size) {
      val rawLine = lines[index]
      val trimmedLine = rawLine.trim()
      if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) {
        index += 1
        continue
      }
      val separatorIndex = trimmedLine.indexOf(':')
      if (separatorIndex <= 0) {
        index += 1
        continue
      }
      val key = trimmedLine.substring(0, separatorIndex).trim()
      val rawValue = trimmedLine.substring(separatorIndex + 1).trim()
      if (rawValue == "|" || rawValue == "|-" || rawValue == "|+") {
        index += 1
        val blockLines = mutableListOf<String>()
        while (index < lines.size) {
          val blockLine = lines[index]
          if (blockLine.startsWith("  ") || blockLine.isBlank()) {
            blockLines += blockLine.removePrefix("  ")
            index += 1
            continue
          }
          break
        }
        values[key] = blockLines.joinToString("\n").trimEnd()
        continue
      }
      values[key] = rawValue.unquoted()
      index += 1
    }
    return values
  }

  private fun String.unquoted(): String {
    if (length < 2) {
      return this
    }
    val first = first()
    val last = last()
    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
      return substring(1, length - 1)
    }
    return this
  }

  private data class ParsedSoulDocument(
    val frontmatter: Map<String, String>,
    val body: String,
  )

  companion object {
    internal const val SOUL_FILE_NAME: String = "SOUL.md"

    private val RESERVED_KEYS: Set<String> = setOf(
      "preset",
      "display_name",
      "custom_guidance",
    )

    private val DOCUMENT_RESERVED_KEYS: Set<String> = setOf(
      "kind",
    )
  }
}
