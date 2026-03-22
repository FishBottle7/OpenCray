package com.opencray.runtime.policy

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.AgentToolResult
import java.nio.file.Paths
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolInvocationSpec(
  val requestedToolName: String,
  val normalizedToolName: String,
  val arguments: JsonObject,
  val reason: String? = null,
  val preserveRequestedToolName: Boolean = true,
  val normalizationMetadata: Map<String, String> = emptyMap(),
) {
  init {
    require(requestedToolName.isNotBlank()) { "ToolInvocationSpec requestedToolName must not be blank." }
    require(normalizedToolName.isNotBlank()) { "ToolInvocationSpec normalizedToolName must not be blank." }
  }

  fun isAliasInvocation(): Boolean = requestedToolName != normalizedToolName && preserveRequestedToolName

  fun isToolRewrite(): Boolean = requestedToolName != normalizedToolName && !preserveRequestedToolName
}

class ToolCallNormalizer(
  private val toolAliases: Map<String, String> = DEFAULT_TOOL_ALIASES,
) {
  fun normalize(call: AgentToolCall): ToolInvocationSpec {
    val aliasedToolName = toolAliases[call.toolName] ?: call.toolName
    normalizeBashPythonScriptCall(
      requestedToolName = call.toolName,
      aliasedToolName = aliasedToolName,
      arguments = call.arguments,
      reason = call.reason,
    )?.let { return it }

    return ToolInvocationSpec(
      requestedToolName = call.toolName,
      normalizedToolName = aliasedToolName,
      arguments = call.arguments,
      reason = call.reason,
    )
  }

  fun aliasDefinitions(canonicalDefinitions: List<AgentToolDefinition>): List<AgentToolDefinition> {
    val definitionsByName = canonicalDefinitions.associateBy(AgentToolDefinition::name)
    return toolAliases.mapNotNull { (aliasName, canonicalName) ->
      val canonicalDefinition = definitionsByName[canonicalName] ?: return@mapNotNull null
      AgentToolDefinition(
        name = aliasName,
        description = "Compatibility alias for ${canonicalDefinition.name}. ${canonicalDefinition.description}",
        parameters = canonicalDefinition.parameters,
      )
    }
  }

  fun decorateResult(
    result: AgentToolResult,
    invocation: ToolInvocationSpec,
  ): AgentToolResult {
    val invocationMetadata = linkedMapOf(
      "requestedToolName" to invocation.requestedToolName,
      "normalizedToolName" to invocation.normalizedToolName,
    )
    if (invocation.isAliasInvocation()) {
      invocationMetadata["canonicalToolName"] = invocation.normalizedToolName
    }
    if (invocation.isToolRewrite()) {
      invocationMetadata["toolRewrite"] = "true"
      invocationMetadata["rewrittenFromToolName"] = invocation.requestedToolName
    }
    return result.copy(
      toolName = if (invocation.preserveRequestedToolName) invocation.requestedToolName else result.toolName,
      metadata = result.metadata + invocationMetadata + invocation.normalizationMetadata,
    )
  }

  private fun normalizeBashPythonScriptCall(
    requestedToolName: String,
    aliasedToolName: String,
    arguments: JsonObject,
    reason: String?,
  ): ToolInvocationSpec? {
    if (aliasedToolName != "Bash") {
      return null
    }
    if (arguments.optionalBoolean("background") == true) {
      return null
    }
    if (arguments.containsKey("process_timeout_ms") || arguments.containsKey("wait_timeout_ms")) {
      return null
    }
    val command = arguments.optionalString("command")?.trim()?.takeIf(String::isNotBlank) ?: return null
    val parsed = parseSimplePythonScriptCommand(command) ?: return null
    val scriptPath = resolveScriptPath(
      scriptToken = parsed.scriptToken,
      workingDirectory = arguments.optionalString("working_directory"),
    )
    return ToolInvocationSpec(
      requestedToolName = requestedToolName,
      normalizedToolName = "python_exec",
      arguments = buildJsonObject {
        put("script_path", JsonPrimitive(scriptPath))
        if (parsed.scriptArgs.isNotEmpty()) {
          put(
            "args",
            buildJsonArray {
              parsed.scriptArgs.forEach { scriptArg -> add(JsonPrimitive(scriptArg)) }
            },
          )
        }
        arguments.optionalLong("timeout_ms")?.let { timeoutMs ->
          put("timeout_ms", JsonPrimitive(timeoutMs))
        }
      },
      reason = reason,
      preserveRequestedToolName = false,
      normalizationMetadata = mapOf(
        "normalizationReason" to "bash_python_script_rewritten",
      ),
    )
  }

  private fun resolveScriptPath(
    scriptToken: String,
    workingDirectory: String?,
  ): String {
    val normalizedScriptToken = scriptToken.replace('\\', '/')
    if (normalizedScriptToken.startsWith("/") || normalizedScriptToken.matches(Regex("^[A-Za-z]:/.*$"))) {
      return normalizedScriptToken
    }
    val normalizedWorkingDirectory = workingDirectory
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.replace('\\', '/')
      ?: return normalizedScriptToken
    return Paths.get(normalizedWorkingDirectory).resolve(normalizedScriptToken).normalize().toString().replace('\\', '/')
  }

  private fun parseSimplePythonScriptCommand(command: String): ParsedPythonScriptCommand? {
    val tokens = tokenizeSimpleShellCommand(command) ?: return null
    if (tokens.isEmpty()) {
      return null
    }
    if (!isPythonExecutableToken(tokens.first())) {
      return null
    }
    var index = 1
    while (index < tokens.size) {
      val token = tokens[index]
      if (token == "-m" || token == "-c") {
        return null
      }
      if (!token.startsWith("-")) {
        break
      }
      index += 1
    }
    if (index >= tokens.size) {
      return null
    }
    val scriptToken = tokens[index]
    if (!scriptToken.lowercase().endsWith(".py")) {
      return null
    }
    return ParsedPythonScriptCommand(
      scriptToken = scriptToken,
      scriptArgs = tokens.drop(index + 1),
    )
  }

  private fun tokenizeSimpleShellCommand(command: String): List<String>? {
    if (command.isBlank()) {
      return emptyList()
    }
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inSingleQuotes = false
    var inDoubleQuotes = false
    var escaping = false

    fun flushToken() {
      if (current.isNotEmpty()) {
        tokens += current.toString()
        current.setLength(0)
      }
    }

    command.forEach { ch ->
      when {
        escaping -> {
          current.append(ch)
          escaping = false
        }

        ch == '\\' && !inSingleQuotes -> {
          escaping = true
        }

        ch == '\'' && !inDoubleQuotes -> {
          inSingleQuotes = !inSingleQuotes
        }

        ch == '"' && !inSingleQuotes -> {
          inDoubleQuotes = !inDoubleQuotes
        }

        !inSingleQuotes && !inDoubleQuotes && ch in SIMPLE_SHELL_CONTROL_CHARS -> {
          return null
        }

        !inSingleQuotes && !inDoubleQuotes && ch.isWhitespace() -> flushToken()

        else -> current.append(ch)
      }
    }

    if (escaping || inSingleQuotes || inDoubleQuotes) {
      return null
    }
    flushToken()
    return tokens
  }

  private fun isPythonExecutableToken(token: String): Boolean {
    val normalized = token
      .substringAfterLast('/')
      .substringAfterLast('\\')
      .lowercase()
      .removeSuffix(".exe")
    return normalized == "python" || normalized.startsWith("python3") || normalized == "py"
  }

  companion object {
    private val SIMPLE_SHELL_CONTROL_CHARS: Set<Char> = setOf('|', ';', '&', '>', '<', '\n', '\r')

    val DEFAULT_TOOL_ALIASES: Map<String, String> = linkedMapOf(
      "bash" to "Bash",
      "list" to "LS",
      "ls" to "LS",
      "read" to "Read",
      "write" to "Write",
      "grep" to "Grep",
      "glob" to "Glob",
      "websearch" to "WebSearch",
      "webfetch" to "WebFetch",
      "generateimage" to "GenerateImage",
      "imagegenerate" to "GenerateImage",
      "synthesizespeech" to "SynthesizeSpeech",
      "texttospeech" to "SynthesizeSpeech",
      "tts" to "SynthesizeSpeech",
      "edit" to "Edit",
      "multiedit" to "MultiEdit",
      "importfile" to "ImportFile",
      "import" to "ImportFile",
      "todowrite" to "TodoWrite",
      "task" to "Task",
      "processstart" to "ProcessStart",
      "processlist" to "ProcessList",
      "processread" to "ProcessRead",
      "processwait" to "ProcessWait",
      "processterminate" to "ProcessTerminate",
    )
  }

  private data class ParsedPythonScriptCommand(
    val scriptToken: String,
    val scriptArgs: List<String>,
  )

  private fun JsonObject.optionalString(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.content
  }

  private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return when (primitive.content.trim().lowercase()) {
      "true" -> true
      "false" -> false
      else -> null
    }
  }

  private fun JsonObject.optionalLong(name: String): Long? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.content.toLongOrNull()
  }
}
