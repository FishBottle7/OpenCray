package com.opencray.skills

import java.io.File

object SkillLoadReasonCode {
  const val FILE_READ_FAILED = "FILE_READ_FAILED"
  const val MISSING_FRONT_MATTER = "MISSING_FRONT_MATTER"
  const val UNTERMINATED_FRONT_MATTER = "UNTERMINATED_FRONT_MATTER"
  const val INVALID_FRONT_MATTER = "INVALID_FRONT_MATTER"
  const val DUPLICATE_SKILL_NAME = "DUPLICATE_SKILL_NAME"
}

data class SkillSource(
  val root: File,
  val skillFile: File,
  val relativePath: String,
) {
  val rootPath: String = root.invariantSeparatorsPath
  val skillFilePath: String = skillFile.invariantSeparatorsPath
  val skillDirectoryPath: String = skillFile.parentFile?.invariantSeparatorsPath ?: skillFilePath
}

data class ParsedSkillDocument(
  val rawFrontMatter: String,
  val frontMatter: Map<String, Any?>,
  val markdownBody: String,
)

data class LoadedSkill(
  val source: SkillSource,
  val document: ParsedSkillDocument,
  val metadata: NormalizedSkillMetadata,
) {
  val name: String
    get() = document.frontMatter["name"] as String
}

data class InvalidSkillDiagnostic(
  val source: SkillSource,
  val reasonCode: String,
  val detail: String,
  val field: String? = null,
  val lineNumber: Int? = null,
  val sourceCode: String? = null,
  val skillName: String? = null,
)

data class SkillLoadReport(
  val roots: List<File>,
  val discoveredFiles: List<SkillSource>,
  val loadedSkills: List<LoadedSkill>,
  val invalidSkills: List<InvalidSkillDiagnostic>,
  val registry: SkillRegistry,
)

class SkillRegistry internal constructor(
  skills: List<LoadedSkill>,
) {
  val skills: List<LoadedSkill> = skills.sortedWith(compareBy<LoadedSkill>({ it.name }, { it.source.skillFilePath }))
  private val skillsByName: Map<String, LoadedSkill> = this.skills.associateBy { it.name }

  fun allSkills(): List<LoadedSkill> = skills

  fun get(name: String): LoadedSkill? = skillsByName[name]

  fun explicitlyInvocableSkills(): List<LoadedSkill> =
    skills.filter { it.metadata.userInvocable }

  fun implicitlyEligibleSkills(): List<LoadedSkill> =
    skills.filter { it.metadata.invocationControl == SkillInvocationControl.EXPLICIT_AND_IMPLICIT }

  fun isExplicitlyInvocable(name: String): Boolean =
    get(name)?.metadata?.userInvocable == true

  fun isImplicitlyEligible(name: String): Boolean =
    get(name)?.metadata?.invocationControl == SkillInvocationControl.EXPLICIT_AND_IMPLICIT
}

object SkillLoader {
  private const val SKILL_FILE_NAME = "SKILL.md"
  private const val FRONT_MATTER_DELIMITER = "---"

  fun discover(roots: Iterable<File>): List<SkillSource> {
    val normalizedRoots = normalizeRoots(roots)
    val discoveredByPath = linkedMapOf<String, SkillSource>()

    for (root in normalizedRoots) {
      val candidates = when {
        root.isFile && root.name == SKILL_FILE_NAME -> listOf(root)
        root.isDirectory -> root.walkTopDown()
          .filter { file -> file.isFile && file.name == SKILL_FILE_NAME }
          .map(::normalizeFile)
          .toList()
          .sortedBy(::normalizePath)
        else -> emptyList()
      }

      for (skillFile in candidates) {
        val normalizedPath = normalizePath(skillFile)
        discoveredByPath.putIfAbsent(
          normalizedPath,
          SkillSource(
            root = root,
            skillFile = skillFile,
            relativePath = computeRelativePath(root = root, skillFile = skillFile),
          ),
        )
      }
    }

    return discoveredByPath.values.toList().sortedBy { it.skillFilePath }
  }

  fun load(roots: Iterable<File>): SkillLoadReport {
    val normalizedRoots = normalizeRoots(roots)
    val discoveredFiles = discover(normalizedRoots)
    val candidateSkills = mutableListOf<LoadedSkill>()
    val invalidSkills = mutableListOf<InvalidSkillDiagnostic>()

    for (source in discoveredFiles) {
      when (val outcome = loadFile(source)) {
        is SkillLoadOutcome.Valid -> candidateSkills += outcome.skill
        is SkillLoadOutcome.Invalid -> invalidSkills += outcome.diagnostic
      }
    }

    val activeSkills = linkedMapOf<String, LoadedSkill>()
    for (skill in candidateSkills.sortedWith(compareBy<LoadedSkill>({ it.name }, { it.source.skillFilePath }))) {
      val existing = activeSkills[skill.name]
      if (existing == null) {
        activeSkills[skill.name] = skill
      } else {
        invalidSkills += InvalidSkillDiagnostic(
          source = skill.source,
          reasonCode = SkillLoadReasonCode.DUPLICATE_SKILL_NAME,
          detail = "Duplicate skill name '${skill.name}' conflicts with ${existing.source.skillFilePath}.",
          field = "name",
          skillName = skill.name,
        )
      }
    }

    val registrySkills = activeSkills.values.toList()
    return SkillLoadReport(
      roots = normalizedRoots,
      discoveredFiles = discoveredFiles,
      loadedSkills = registrySkills,
      invalidSkills = invalidSkills.sortedWith(
        compareBy<InvalidSkillDiagnostic>({ it.source.skillFilePath }, { it.skillName ?: "" }, { it.reasonCode }, { it.field ?: "" }),
      ),
      registry = SkillRegistry(registrySkills),
    )
  }

  fun load(vararg roots: File): SkillLoadReport = load(roots.asList())

  private fun loadFile(source: SkillSource): SkillLoadOutcome {
    val rawContent = try {
      source.skillFile.readText(Charsets.UTF_8)
    } catch (error: Exception) {
      return SkillLoadOutcome.Invalid(
        InvalidSkillDiagnostic(
          source = source,
          reasonCode = SkillLoadReasonCode.FILE_READ_FAILED,
          detail = "Failed to read ${source.skillFilePath}: ${error.message ?: error::class.java.simpleName}.",
        ),
      )
    }

    val parsed = try {
      parseDocument(rawContent)
    } catch (error: FrontMatterParseException) {
      return SkillLoadOutcome.Invalid(
        InvalidSkillDiagnostic(
          source = source,
          reasonCode = error.reasonCode,
          detail = error.detail,
          field = error.field,
          lineNumber = error.lineNumber,
          skillName = error.skillName,
        ),
      )
    }

    return when (val validation = SkillValidator.validate(parsed.frontMatter)) {
      is SkillValidationResult.Valid -> SkillLoadOutcome.Valid(
        LoadedSkill(
          source = source,
          document = parsed,
          metadata = validation.metadata,
        ),
      )

      is SkillValidationResult.Invalid -> SkillLoadOutcome.Invalid(
        InvalidSkillDiagnostic(
          source = source,
          reasonCode = validation.error.reasonCode,
          detail = validation.error.detail,
          field = validation.error.field,
          sourceCode = validation.error.sourceCode?.toString(),
          skillName = parsed.frontMatter["name"] as? String,
        ),
      )
    }
  }

  private fun parseDocument(rawContent: String): ParsedSkillDocument {
    val normalizedContent = rawContent
      .replace("\r\n", "\n")
      .replace("\r", "\n")
      .removePrefix("\uFEFF")
    val lines = normalizedContent.split('\n')
    if (lines.isEmpty() || lines.first() != FRONT_MATTER_DELIMITER) {
      throw FrontMatterParseException(
        reasonCode = SkillLoadReasonCode.MISSING_FRONT_MATTER,
        detail = "Skill file must start with YAML front matter delimited by '---'.",
      )
    }

    val closingIndex = lines.indexOfFirstAfter(startIndex = 1) { it == FRONT_MATTER_DELIMITER }
    if (closingIndex == -1) {
      throw FrontMatterParseException(
        reasonCode = SkillLoadReasonCode.UNTERMINATED_FRONT_MATTER,
        detail = "Skill file front matter is missing a closing '---' delimiter.",
      )
    }

    val rawFrontMatter = lines.subList(1, closingIndex).joinToString(separator = "\n")
    val markdownBody = lines.subList(closingIndex + 1, lines.size)
      .joinToString(separator = "\n")
      .removePrefix("\n")

    return ParsedSkillDocument(
      rawFrontMatter = rawFrontMatter,
      frontMatter = FrontMatterParser(rawFrontMatter).parse(),
      markdownBody = markdownBody,
    )
  }

  private fun normalizeRoots(roots: Iterable<File>): List<File> =
    roots.map(::normalizeFile)
      .distinctBy(::normalizePath)
      .sortedBy(::normalizePath)

  private fun computeRelativePath(root: File, skillFile: File): String {
    val relativePath = root.toURI().relativize(skillFile.toURI()).path.trimEnd('/')
    return relativePath.ifBlank { skillFile.name }
  }

  private fun normalizeFile(file: File): File =
    runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }

  private fun normalizePath(file: File): String = file.invariantSeparatorsPath
}

private sealed interface SkillLoadOutcome {
  data class Valid(
    val skill: LoadedSkill,
  ) : SkillLoadOutcome

  data class Invalid(
    val diagnostic: InvalidSkillDiagnostic,
  ) : SkillLoadOutcome
}

private data class FrontMatterLine(
  val position: Int,
  val number: Int,
  val raw: String,
) {
  val indent: Int = raw.takeWhile { it == ' ' || it == '\t' }.length
  val hasTabIndentation: Boolean = raw.take(indent).contains('\t')
  val content: String = raw.drop(indent).trimEnd()
}

private class FrontMatterParser(
  text: String,
) {
  private val lines: List<FrontMatterLine> = text.split('\n').mapIndexed { index, line ->
    FrontMatterLine(position = index, number = index + 1, raw = line)
  }

  private var index: Int = 0

  fun parse(): Map<String, Any?> = parseTopLevelMap(expectedIndent = 0)

  private fun parseTopLevelMap(expectedIndent: Int): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()

    while (true) {
      val line = nextMeaningfulLine(index) ?: break
      if (line.indent < expectedIndent) {
        break
      }
      if (line.hasTabIndentation) {
        error(line = line, detail = "Tabs are not supported in skill front matter indentation.")
      }
      if (line.indent != expectedIndent) {
        error(line = line, detail = "Unexpected indentation in skill front matter.")
      }

      index = line.position + 1
      val entry = parseKeyValue(line)
      if (result.containsKey(entry.key)) {
        error(line = line, detail = "Duplicate front matter key '${entry.key}'.", field = entry.key, skillName = extractSkillName(result))
      }
      result[entry.key] = entry.value
    }

    return result
  }

  private fun parseKeyValue(line: FrontMatterLine): Map.Entry<String, Any?> {
    val content = meaningfulContent(line)
    if (content.startsWith("- ")) {
      error(line = line, detail = "Top-level front matter must be a key/value map.")
    }

    val separatorIndex = findTopLevelSeparator(content, ':')
      ?: error(line = line, detail = "Front matter entries must use 'key: value' syntax.")
    val key = parseMapKey(text = content.substring(0, separatorIndex), line = line)
    val remainder = content.substring(separatorIndex + 1).trim()
    val value = if (remainder.isNotEmpty()) {
      parseInlineValue(text = remainder, line = line)
    } else {
      parseNestedValue(parentIndent = line.indent, line = line)
    }
    return java.util.AbstractMap.SimpleEntry(key, value)
  }

  private fun parseNestedValue(parentIndent: Int, line: FrontMatterLine): Any {
    val nestedLine = nextMeaningfulLine(index)
    if (nestedLine == null || nestedLine.indent <= parentIndent) {
      return ""
    }
    if (nestedLine.hasTabIndentation) {
      error(line = nestedLine, detail = "Tabs are not supported in skill front matter indentation.")
    }

    return if (meaningfulContent(nestedLine).startsWith("- ")) {
      parseBlockList(expectedIndent = nestedLine.indent)
    } else {
      parseBlockMap(expectedIndent = nestedLine.indent, parentLine = line)
    }
  }

  private fun parseBlockList(expectedIndent: Int): List<Any> {
    val values = mutableListOf<Any>()

    while (true) {
      val line = nextMeaningfulLine(index) ?: break
      if (line.indent < expectedIndent) {
        break
      }
      if (line.hasTabIndentation) {
        error(line = line, detail = "Tabs are not supported in skill front matter indentation.")
      }
      if (line.indent != expectedIndent) {
        error(line = line, detail = "Nested list indentation must remain flat.")
      }

      val content = meaningfulContent(line)
      if (!content.startsWith("- ")) {
        error(line = line, detail = "Mixed list and map indentation is not supported.")
      }

      index = line.position + 1
      val itemText = content.removePrefix("- ").trim()
      if (itemText.isEmpty()) {
        error(line = line, detail = "List items must declare an inline scalar value.")
      }

      val value = parseInlineValue(text = itemText, line = line)
      if (value is List<*> || value is Map<*, *>) {
        error(line = line, detail = "Nested collections inside list items are not supported.")
      }
      values += value
    }

    return values
  }

  private fun parseBlockMap(expectedIndent: Int, parentLine: FrontMatterLine): Map<String, Any> {
    val values = linkedMapOf<String, Any>()

    while (true) {
      val line = nextMeaningfulLine(index) ?: break
      if (line.indent < expectedIndent) {
        break
      }
      if (line.hasTabIndentation) {
        error(line = line, detail = "Tabs are not supported in skill front matter indentation.")
      }
      if (line.indent != expectedIndent) {
        error(line = line, detail = "Nested maps inside map values are not supported.", field = parseParentField(parentLine))
      }

      val content = meaningfulContent(line)
      if (content.startsWith("- ")) {
        error(line = line, detail = "Map values must use 'key: value' syntax.", field = parseParentField(parentLine))
      }

      index = line.position + 1
      val separatorIndex = findTopLevelSeparator(content, ':')
        ?: error(line = line, detail = "Map values must use 'key: value' syntax.", field = parseParentField(parentLine))
      val key = parseMapKey(text = content.substring(0, separatorIndex), line = line)
      if (values.containsKey(key)) {
        error(line = line, detail = "Duplicate map key '$key'.", field = parseParentField(parentLine))
      }

      val remainder = content.substring(separatorIndex + 1).trim()
      if (remainder.isEmpty()) {
        val nestedValueLine = nextMeaningfulLine(index)
        if (nestedValueLine != null && nestedValueLine.indent > expectedIndent) {
          error(line = nestedValueLine, detail = "Nested collections inside map values are not supported.", field = parseParentField(parentLine))
        }
        values[key] = ""
      } else {
        val value = parseInlineValue(text = remainder, line = line)
        if (value is List<*> || value is Map<*, *>) {
          error(line = line, detail = "Nested collections inside map values are not supported.", field = parseParentField(parentLine))
        }
        values[key] = value
      }
    }

    return values
  }

  private fun parseInlineValue(text: String, line: FrontMatterLine): Any {
    val normalized = stripInlineComment(text).trim()
    return when {
      normalized.startsWith('[') -> parseInlineList(text = normalized, line = line)
      normalized.startsWith('{') -> parseInlineMap(text = normalized, line = line)
      else -> parseScalar(text = normalized, line = line)
    }
  }

  private fun parseInlineList(text: String, line: FrontMatterLine): List<Any> {
    if (!text.endsWith(']')) {
      error(line = line, detail = "Inline list must end with ']'.")
    }

    val inner = text.substring(1, text.length - 1).trim()
    if (inner.isEmpty()) {
      return emptyList()
    }

    return splitTopLevel(text = inner, separator = ',').map { item ->
      val value = parseInlineValue(text = item.trim(), line = line)
      if (value is List<*> || value is Map<*, *>) {
        error(line = line, detail = "Nested collections inside list items are not supported.")
      }
      value
    }
  }

  private fun parseInlineMap(text: String, line: FrontMatterLine): Map<String, Any> {
    if (!text.endsWith('}')) {
      error(line = line, detail = "Inline map must end with '}'.")
    }

    val inner = text.substring(1, text.length - 1).trim()
    if (inner.isEmpty()) {
      return emptyMap()
    }

    val values = linkedMapOf<String, Any>()
    for (entryText in splitTopLevel(text = inner, separator = ',')) {
      val separatorIndex = findTopLevelSeparator(entryText, ':')
        ?: error(line = line, detail = "Inline map entries must use 'key: value' syntax.")
      val key = parseMapKey(text = entryText.substring(0, separatorIndex), line = line)
      if (values.containsKey(key)) {
        error(line = line, detail = "Duplicate map key '$key'.")
      }

      val remainder = entryText.substring(separatorIndex + 1).trim()
      val value = parseScalar(text = stripInlineComment(remainder).trim(), line = line)
      values[key] = value
    }

    return values
  }

  private fun parseMapKey(text: String, line: FrontMatterLine): String {
    val key = parseScalar(text = text.trim(), line = line)
    if (key !is String || key.isBlank()) {
      error(line = line, detail = "Front matter keys must be non-blank strings.")
    }
    return key.trim()
  }

  private fun parseScalar(text: String, line: FrontMatterLine): Any {
    val normalized = stripInlineComment(text).trim()
    if (normalized.isEmpty()) {
      return ""
    }

    return if (isQuoted(normalized)) {
      unquote(text = normalized, line = line)
    } else {
      when (normalized.lowercase()) {
        "true" -> true
        "false" -> false
        else -> normalized
      }
    }
  }

  private fun meaningfulContent(line: FrontMatterLine): String = stripInlineComment(line.content).trimEnd()

  private fun nextMeaningfulLine(startIndex: Int): FrontMatterLine? {
    for (candidate in lines.drop(startIndex)) {
      val content = meaningfulContent(candidate)
      if (content.isNotEmpty()) {
        return candidate
      }
    }
    return null
  }

  private fun parseParentField(line: FrontMatterLine): String? {
    val content = meaningfulContent(line)
    val separatorIndex = findTopLevelSeparator(content, ':') ?: return null
    return parseScalar(text = content.substring(0, separatorIndex).trim(), line = line) as? String
  }

  private fun extractSkillName(frontMatter: Map<String, Any?>): String? = frontMatter["name"] as? String

  private fun splitTopLevel(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var singleQuoted = false
    var doubleQuoted = false
    var bracketDepth = 0
    var braceDepth = 0

    text.forEachIndexed { index, character ->
      when (character) {
        '\'' -> if (!doubleQuoted && !isEscaped(text, index)) {
          singleQuoted = !singleQuoted
          current.append(character)
        } else {
          current.append(character)
        }

        '"' -> if (!singleQuoted && !isEscaped(text, index)) {
          doubleQuoted = !doubleQuoted
          current.append(character)
        } else {
          current.append(character)
        }

        '[' -> {
          if (!singleQuoted && !doubleQuoted) bracketDepth += 1
          current.append(character)
        }

        ']' -> {
          if (!singleQuoted && !doubleQuoted) bracketDepth -= 1
          current.append(character)
        }

        '{' -> {
          if (!singleQuoted && !doubleQuoted) braceDepth += 1
          current.append(character)
        }

        '}' -> {
          if (!singleQuoted && !doubleQuoted) braceDepth -= 1
          current.append(character)
        }

        separator -> if (!singleQuoted && !doubleQuoted && bracketDepth == 0 && braceDepth == 0) {
          parts += current.toString()
          current.clear()
        } else {
          current.append(character)
        }

        else -> current.append(character)
      }
    }

    parts += current.toString()
    return parts
  }

  private fun findTopLevelSeparator(text: String, separator: Char): Int? {
    var singleQuoted = false
    var doubleQuoted = false
    var bracketDepth = 0
    var braceDepth = 0

    text.forEachIndexed { index, character ->
      when (character) {
        '\'' -> if (!doubleQuoted && !isEscaped(text, index)) {
          singleQuoted = !singleQuoted
        }

        '"' -> if (!singleQuoted && !isEscaped(text, index)) {
          doubleQuoted = !doubleQuoted
        }

        '[' -> if (!singleQuoted && !doubleQuoted) bracketDepth += 1
        ']' -> if (!singleQuoted && !doubleQuoted) bracketDepth -= 1
        '{' -> if (!singleQuoted && !doubleQuoted) braceDepth += 1
        '}' -> if (!singleQuoted && !doubleQuoted) braceDepth -= 1
        separator -> if (!singleQuoted && !doubleQuoted && bracketDepth == 0 && braceDepth == 0) {
          return index
        }
      }
    }

    return null
  }

  private fun stripInlineComment(text: String): String {
    var singleQuoted = false
    var doubleQuoted = false
    var bracketDepth = 0
    var braceDepth = 0

    text.forEachIndexed { index, character ->
      when (character) {
        '\'' -> if (!doubleQuoted && !isEscaped(text, index)) {
          singleQuoted = !singleQuoted
        }

        '"' -> if (!singleQuoted && !isEscaped(text, index)) {
          doubleQuoted = !doubleQuoted
        }

        '[' -> if (!singleQuoted && !doubleQuoted) bracketDepth += 1
        ']' -> if (!singleQuoted && !doubleQuoted) bracketDepth -= 1
        '{' -> if (!singleQuoted && !doubleQuoted) braceDepth += 1
        '}' -> if (!singleQuoted && !doubleQuoted) braceDepth -= 1
        '#' -> if (!singleQuoted && !doubleQuoted && bracketDepth == 0 && braceDepth == 0) {
          val previous = text.getOrNull(index - 1)
          if (previous == null || previous.isWhitespace()) {
            return text.substring(0, index).trimEnd()
          }
        }
      }
    }

    return text.trimEnd()
  }

  private fun isQuoted(text: String): Boolean =
    text.length >= 2 && ((text.first() == '"' && text.last() == '"') || (text.first() == '\'' && text.last() == '\''))

  private fun unquote(text: String, line: FrontMatterLine): String {
    val quote = text.first()
    if (text.last() != quote) {
      error(line = line, detail = "Quoted string is missing a matching closing quote.")
    }

    val inner = text.substring(1, text.length - 1)
    return if (quote == '\'') {
      inner
    } else {
      buildString {
        var cursor = 0
        while (cursor < inner.length) {
          val character = inner[cursor]
          if (character == '\\' && cursor + 1 < inner.length) {
            val escaped = inner[cursor + 1]
            append(
              when (escaped) {
                '\\' -> '\\'
                '"' -> '"'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> escaped
              },
            )
            cursor += 2
          } else {
            append(character)
            cursor += 1
          }
        }
      }
    }
  }

  private fun isEscaped(text: String, index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
      slashCount += 1
      cursor -= 1
    }
    return slashCount % 2 == 1
  }

  private fun error(
    line: FrontMatterLine,
    detail: String,
    field: String? = null,
    skillName: String? = null,
  ): Nothing {
    throw FrontMatterParseException(
      reasonCode = SkillLoadReasonCode.INVALID_FRONT_MATTER,
      detail = detail,
      field = field,
      lineNumber = line.number,
      skillName = skillName,
    )
  }
}

private class FrontMatterParseException(
  val reasonCode: String,
  val detail: String,
  val field: String? = null,
  val lineNumber: Int? = null,
  val skillName: String? = null,
) : IllegalArgumentException(detail)

private fun <T> List<T>.indexOfFirstAfter(startIndex: Int, predicate: (T) -> Boolean): Int {
  for (index in startIndex until size) {
    if (predicate(this[index])) {
      return index
    }
  }
  return -1
}

// Learnings: The skills module can stay dependency-light because its front matter only needs booleans, strings, flat string maps, and flat string lists.
// Issues: Duplicate skill names across configured roots are resolved deterministically by first sorted path wins, with later collisions reported as invalid.
// Issues: Inline YAML null literals are still outside this minimal parser scope because the loader currently normalizes supported values to non-null Kotlin types.
