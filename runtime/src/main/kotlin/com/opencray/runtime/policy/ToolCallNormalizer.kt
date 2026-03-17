package com.opencray.runtime.policy

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.AgentToolResult
import kotlinx.serialization.json.JsonObject

data class ToolInvocationSpec(
  val requestedToolName: String,
  val normalizedToolName: String,
  val arguments: JsonObject,
  val reason: String? = null,
) {
  init {
    require(requestedToolName.isNotBlank()) { "ToolInvocationSpec requestedToolName must not be blank." }
    require(normalizedToolName.isNotBlank()) { "ToolInvocationSpec normalizedToolName must not be blank." }
  }

  fun isAliasInvocation(): Boolean = requestedToolName != normalizedToolName
}

class ToolCallNormalizer(
  private val toolAliases: Map<String, String> = DEFAULT_TOOL_ALIASES,
) {
  fun normalize(call: AgentToolCall): ToolInvocationSpec = ToolInvocationSpec(
    requestedToolName = call.toolName,
    normalizedToolName = toolAliases[call.toolName] ?: call.toolName,
    arguments = call.arguments,
    reason = call.reason,
  )

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
    return result.copy(
      toolName = invocation.requestedToolName,
      metadata = result.metadata + invocationMetadata,
    )
  }

  companion object {
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
      "edit" to "Edit",
      "multiedit" to "MultiEdit",
      "importfile" to "ImportFile",
      "import" to "ImportFile",
      "todowrite" to "TodoWrite",
      "processstart" to "ProcessStart",
      "processlist" to "ProcessList",
      "processread" to "ProcessRead",
      "processwait" to "ProcessWait",
      "processterminate" to "ProcessTerminate",
    )
  }
}
