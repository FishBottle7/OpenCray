package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opencray.litertlmbridge.LiteRtLmBridge

internal data class LiteRtAutomaticToolExecutionContext(
  val task: AgentTask,
  val hooks: RuntimeExecutionHooks,
  val toolDispatcher: OpenCrayToolDispatcher,
)

internal object LiteRtAutomaticToolExecutionRegistry {
  private val currentContext: ThreadLocal<LiteRtAutomaticToolExecutionContext?> = ThreadLocal()

  fun current(): LiteRtAutomaticToolExecutionContext? = currentContext.get()

  fun <T> withContext(
    context: LiteRtAutomaticToolExecutionContext?,
    block: () -> T,
  ): T {
    val previous = currentContext.get()
    currentContext.set(context)
    return try {
      block()
    } finally {
      if (previous == null) {
        currentContext.remove()
      } else {
        currentContext.set(previous)
      }
    }
  }
}

internal class LiteRtAutomaticToolExecutor(
  private val context: LiteRtAutomaticToolExecutionContext,
) : LiteRtLmBridge.ToolExecutor {
  override fun execute(
    toolName: String,
    arguments: Map<String, Any?>,
  ): String {
    val toolResult = runCatching {
      context.toolDispatcher.dispatch(
        task = context.task,
        call = AgentToolCall(
          toolName = toolName,
          arguments = arguments.toJsonObject(),
        ),
        hooks = context.hooks,
      )
    }.getOrElse { error ->
      AgentToolResult(
        toolName = toolName,
        status = AgentToolResultStatus.FAILED,
        content = error.message ?: "Automatic LiteRT tool execution failed.",
        errorCode = "LITERT_AUTO_TOOL_EXECUTION_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
    return toolResult.toLiteRtToolResponseText()
  }
}

private fun AgentToolResult.toLiteRtToolResponseText(): String {
  if (status == AgentToolResultStatus.SUCCESS && content.isNotBlank()) {
    return content
  }
  return buildString {
    append("status=")
    append(status.name.lowercase())
    errorCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedErrorCode ->
        append('\n')
        append("error_code=")
        append(resolvedErrorCode)
      }
    errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedErrorMessage ->
        append('\n')
        append("error_message=")
        append(resolvedErrorMessage)
      }
    if (content.isNotBlank()) {
      append('\n')
      append(content)
    }
  }
}

private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(
  entries.associate { (key, value) -> key to value.toJsonElement() },
)

private fun Any?.toJsonElement(): JsonElement = when (this) {
  null -> JsonNull
  is JsonElement -> this
  is Boolean -> JsonPrimitive(this)
  is Number -> JsonPrimitive(this)
  is String -> JsonPrimitive(this)
  is Map<*, *> -> JsonObject(
    entries.associate { (key, value) ->
      key.toString() to value.toJsonElement()
    },
  )
  is Iterable<*> -> JsonArray(map(Any?::toJsonElement))
  is Array<*> -> JsonArray(map(Any?::toJsonElement))
  else -> JsonPrimitive(toString())
}
