package com.opencray.app.projection

import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.TodoWriteMetadataKeys
import kotlinx.serialization.json.JsonObject
import java.util.Locale

private const val MAX_RUNTIME_EVENT_FAILURE_CONTENT_CHARS: Int = 16_384

internal data class TodoSnapshotSummary(
  val todoCount: Int,
  val pendingCount: Int,
  val inProgressCount: Int,
  val completedCount: Int,
  val activeTodoContent: String?,
)

internal fun toolActionSummary(
  toolName: String,
  arguments: JsonObject,
  localeIsChinese: Boolean,
): String {
  val normalizedToolName = toolName.trim()
  val fallback = if (localeIsChinese) {
    "调用工具 $normalizedToolName"
  } else {
    "Call $normalizedToolName"
  }
  return when (normalizedToolName) {
    "Read",
    "workspace_read_file" -> {
      val path = arguments.replayString("file_path") ?: arguments.replayString("path")
      if (path == null) {
        fallback
      } else {
        val range = readRangeSummary(
          offset = arguments.replayInt("offset"),
          limit = arguments.replayInt("limit"),
          localeIsChinese = localeIsChinese,
        )
        if (localeIsChinese) {
          "读取 $path${if (range.isNotEmpty()) "，$range" else ""}"
        } else {
          "Read $path${if (range.isNotEmpty()) " $range" else ""}"
        }
      }
    }

    "Grep" -> {
      val pattern = arguments.replayString("pattern")
      if (pattern == null) {
        fallback
      } else {
        val path = arguments.replayString("path") ?: "."
        val glob = arguments.replayString("glob")
        if (localeIsChinese) {
          buildString {
            append("在 $path 中搜索 \"$pattern\"")
            glob?.let { append("，glob: $it") }
          }
        } else {
          buildString {
            append("Search \"$pattern\" in $path")
            glob?.let { append(" (glob: $it)") }
          }
        }
      }
    }

    "Glob" -> {
      val pattern = arguments.replayString("pattern")
      if (pattern == null) {
        fallback
      } else {
        val path = arguments.replayString("path") ?: "."
        if (localeIsChinese) {
          "在 $path 中匹配 $pattern"
        } else {
          "Match $pattern in $path"
        }
      }
    }

    "LS",
    "workspace_list_files" -> {
      val path = arguments.replayString("path")
        ?: arguments.replayString("file_path")
        ?: "."
      if (localeIsChinese) {
        "列出 $path"
      } else {
        "List $path"
      }
    }

    "Write",
    "workspace_write_file" -> {
      val path = arguments.replayString("file_path") ?: arguments.replayString("path")
      if (path == null) {
        fallback
      } else if (localeIsChinese) {
        "写入 $path"
      } else {
        "Write $path"
      }
    }

    "Edit" -> {
      val path = arguments.replayString("file_path") ?: arguments.replayString("path")
      if (path == null) {
        fallback
      } else if (localeIsChinese) {
        "编辑 $path"
      } else {
        "Edit $path"
      }
    }

    "MultiEdit" -> {
      val path = arguments.replayString("file_path") ?: arguments.replayString("path")
      if (path == null) {
        fallback
      } else {
        val editCount = arguments.replayArraySize("edits") ?: 0
        if (editCount > 0) {
          if (localeIsChinese) {
            "对 $path 应用 $editCount 处编辑"
          } else {
            "Apply $editCount edit(s) to $path"
          }
        } else if (localeIsChinese) {
          "批量编辑 $path"
        } else {
          "MultiEdit $path"
        }
      }
    }

    "TodoWrite" -> {
      if (!arguments.containsKey("todos")) {
        if (localeIsChinese) {
          "读取当前待办列表"
        } else {
          "Read current todo list"
        }
      } else {
        todoSummaryFromArguments(arguments)?.let { summary ->
          return todoWriteActionSummary(summary = summary, mutated = true, localeIsChinese = localeIsChinese)
        }
        if (localeIsChinese) {
          "更新待办列表"
        } else {
          "Update the todo list"
        }
      }
    }

    "ImportFile",
    "workspace_import_file" -> {
      val sourcePath = arguments.replayString("source_path")
      val destinationPath = arguments.replayString("destination_path")
      if (sourcePath == null || destinationPath == null) {
        fallback
      } else if (localeIsChinese) {
        "导入 $sourcePath 到 $destinationPath"
      } else {
        "Import $sourcePath to $destinationPath"
      }
    }

    "Bash",
    "command_exec" -> {
      val command = arguments.replayString("command")
      if (command == null) {
        fallback
      } else if (localeIsChinese) {
        "运行命令 $command"
      } else {
        "Run command $command"
      }
    }

    "python_exec" -> {
      val scriptPath = arguments.replayString("script_path")
      if (scriptPath == null) {
        fallback
      } else if (localeIsChinese) {
        "运行 Python 脚本 $scriptPath"
      } else {
        "Run Python script $scriptPath"
      }
    }

    "python_runtime_manifest" -> {
      if (localeIsChinese) {
        "查看 Python 运行时预装库清单"
      } else {
        "Inspect Python runtime manifest"
      }
    }

    "ProcessStart" -> {
      val scriptPath = arguments.replayString("script_path")
      val command = arguments.replayString("command")
      when {
        scriptPath != null && localeIsChinese -> "启动后台 Python 进程 $scriptPath"
        scriptPath != null -> "Start background Python process $scriptPath"
        command != null && localeIsChinese -> "启动后台进程 $command"
        command != null -> "Start background process $command"
        else -> fallback
      }
    }

    "ProcessRead" -> {
      val processId = arguments.replayString("process_id")
      if (processId == null) {
        fallback
      } else if (localeIsChinese) {
        "读取进程 $processId 的输出"
      } else {
        "Read output for process $processId"
      }
    }

    "ProcessWait" -> {
      val processId = arguments.replayString("process_id")
      if (processId == null) {
        fallback
      } else if (localeIsChinese) {
        "等待进程 $processId"
      } else {
        "Wait for process $processId"
      }
    }

    "ProcessTerminate" -> {
      val processId = arguments.replayString("process_id")
      if (processId == null) {
        fallback
      } else if (localeIsChinese) {
        "终止进程 $processId"
      } else {
        "Terminate process $processId"
      }
    }

    "WebFetch" -> {
      val url = arguments.replayString("url")
      if (url == null) {
        fallback
      } else if (localeIsChinese) {
        "抓取网页 $url"
      } else {
        "Fetch $url"
      }
    }

    "WebSearch" -> {
      val operation = arguments.replayString("operation")?.trim()?.lowercase()
      val query = arguments.replayString("query")
      val url = arguments.replayString("url")
      val text = arguments.replayString("text")
      when (operation) {
        "open_page" -> when {
          url == null -> fallback
          localeIsChinese -> "打开搜索结果页面 $url"
          else -> "Open search result page $url"
        }

        "find_in_page" -> when {
          url == null && text == null -> fallback
          localeIsChinese -> buildString {
            append("在页面内搜索")
            text?.let { append(" \"$it\"") }
            url?.let {
              append(" 于 ")
              append(it)
            }
          }
          else -> buildString {
            append("Find in page")
            text?.let {
              append(" \"")
              append(it)
              append("\"")
            }
            url?.let {
              append(" in ")
              append(it)
            }
          }
        }

        else -> when {
          query == null -> fallback
          localeIsChinese -> "搜索网络 \"$query\""
          else -> "Search the web for \"$query\""
        }
      }
    }

    else -> fallback
  }
}

internal fun toolResultActionSummary(
  toolName: String,
  event: OpenCrayToolResultEvent,
  pairedToolCall: OpenCrayToolCallEvent?,
  localeIsChinese: Boolean,
): String {
  event.call.arguments.takeIf { arguments -> arguments.isNotEmpty() }?.let { arguments ->
    return toolActionSummary(toolName = toolName, arguments = arguments, localeIsChinese = localeIsChinese)
  }
  pairedToolCall?.call?.arguments?.takeIf { arguments -> arguments.isNotEmpty() }?.let { arguments ->
    return toolActionSummary(toolName = toolName, arguments = arguments, localeIsChinese = localeIsChinese)
  }
  return toolActionSummaryFromResultMetadata(
    toolName = toolName,
    metadata = event.result.metadata,
    localeIsChinese = localeIsChinese,
  )
}

internal fun toolActionSummaryFromResultMetadata(
  toolName: String,
  metadata: Map<String, String>,
  localeIsChinese: Boolean,
): String {
  val normalizedToolName = toolName.trim()
  val fallback = if (localeIsChinese) {
    "工具 $normalizedToolName 已返回结果"
  } else {
    "$normalizedToolName returned a result"
  }
  return when (normalizedToolName) {
    "Read",
    "workspace_read_file" -> {
      val path = metadataValue(metadata, "filePath")
      if (path == null) {
        fallback
      } else {
        val range = readRangeSummary(
          offset = metadataInt(metadata, "offset"),
          limit = metadataInt(metadata, "limit"),
          localeIsChinese = localeIsChinese,
        )
        if (localeIsChinese) {
          "读取 $path${if (range.isNotEmpty()) "，$range" else ""}"
        } else {
          "Read $path${if (range.isNotEmpty()) " $range" else ""}"
        }
      }
    }

    "LS",
    "workspace_list_files" -> {
      val path = metadataValue(metadata, "path") ?: "."
      if (localeIsChinese) {
        "列出 $path"
      } else {
        "List $path"
      }
    }

    "Grep" -> {
      val pattern = metadataValue(metadata, "pattern")
      if (pattern == null) {
        fallback
      } else {
        val path = metadataValue(metadata, "path") ?: "."
        if (localeIsChinese) {
          "在 $path 中搜索 \"$pattern\""
        } else {
          "Search \"$pattern\" in $path"
        }
      }
    }

    "Glob" -> {
      val pattern = metadataValue(metadata, "pattern")
      if (pattern == null) {
        fallback
      } else {
        val path = metadataValue(metadata, "path") ?: "."
        if (localeIsChinese) {
          "在 $path 中匹配 $pattern"
        } else {
          "Match $pattern in $path"
        }
      }
    }

    "Write",
    "workspace_write_file",
    "Edit",
    "MultiEdit" -> {
      val path = metadataValue(metadata, "filePath")
      if (path == null) {
        fallback
      } else if (localeIsChinese) {
        "更新 $path"
      } else {
        "Update $path"
      }
    }

    "TodoWrite" ->
      todoSummaryFromMetadata(metadata)?.let { summary ->
        todoWriteActionSummary(
          summary = summary,
          mutated = metadataBoolean(metadata, TodoWriteMetadataKeys.MUTATED) == true,
          localeIsChinese = localeIsChinese,
        )
      } ?: fallback

    "WebSearch" -> {
      val operation = metadataValue(metadata, ProviderNativeWebSearchSupport.RESULT_METADATA_OPERATION)
        ?.trim()
        ?.lowercase()
      val query = metadataValue(metadata, "query")
      val url = metadataValue(metadata, "url")
      val text = metadataValue(metadata, "text")
      when (operation) {
        "open_page" -> when {
          url == null -> fallback
          localeIsChinese -> "打开搜索结果页面 $url"
          else -> "Open search result page $url"
        }

        "find_in_page" -> when {
          url == null && text == null -> fallback
          localeIsChinese -> buildString {
            append("在页面内搜索")
            text?.let { append(" \"$it\"") }
            url?.let {
              append(" 于 ")
              append(it)
            }
          }
          else -> buildString {
            append("Find in page")
            text?.let {
              append(" \"")
              append(it)
              append("\"")
            }
            url?.let {
              append(" in ")
              append(it)
            }
          }
        }

        else -> when {
          query == null -> fallback
          localeIsChinese -> "搜索网络 \"$query\""
          else -> "Search the web for \"$query\""
        }
      }
    }

    else -> fallback
  }
}

internal fun toolResultMetadataSummary(
  toolName: String,
  metadata: Map<String, String>,
  localeIsChinese: Boolean,
): String? {
  return when (toolName.trim()) {
    "LS",
    "workspace_list_files" -> {
      val entryCount = metadataInt(metadata, "entryCount") ?: return null
      val path = metadataValue(metadata, "path")
      val truncated = resultMetadataTruncated(metadata)
      if (localeIsChinese) {
        val base = if (path == null) {
          "列出了 $entryCount 项"
        } else {
          "在 $path 中列出了 $entryCount 项"
        }
        if (truncated) "$base，结果已按结果上限截断" else base
      } else if (path == null) {
        buildString {
          append("Listed $entryCount entr${if (entryCount == 1) "y" else "ies"}")
          if (truncated) {
            append(". Output truncated at the tool result limit.")
          }
        }
      } else {
        buildString {
          append("Listed $entryCount entr${if (entryCount == 1) "y" else "ies"} in $path")
          if (truncated) {
            append(". Output truncated at the tool result limit.")
          }
        }
      }
    }

    "Read",
    "workspace_read_file" -> {
      val returnedLineCount = metadataInt(metadata, "returnedLineCount")
      val totalLineCount = metadataInt(metadata, "totalLineCount")
      val truncated = resultMetadataTruncated(metadata)
      val filePath = metadataValue(metadata, "filePath")
      if (returnedLineCount == null && totalLineCount == null && !truncated && filePath == null) {
        null
      } else if (localeIsChinese) {
        buildList {
          filePath?.let(::add)
          returnedLineCount?.let { add("返回 $it 行") }
          totalLineCount?.let { add("文件总计 $it 行") }
          if (truncated) {
            add("结果已按读取预算截断")
          }
        }.joinToString(separator = "，").takeIf(String::isNotBlank)
      } else {
        buildList {
          returnedLineCount?.let { count ->
            add(if (count == 1) "Returned 1 line" else "Returned $count lines")
          }
          filePath?.let { path -> add("from $path") }
          totalLineCount?.let { count ->
            add(if (count == 1) "(1-line file)" else "($count-line file)")
          }
          if (truncated) {
            add("Output truncated to the read budget.")
          }
        }.joinToString(separator = " ").takeIf(String::isNotBlank)
      }
    }

    "Grep" -> {
      val matchCount = metadataInt(metadata, "matchCount") ?: return null
      val pattern = metadataValue(metadata, "pattern")
      val path = metadataValue(metadata, "path") ?: "."
      val truncated = resultMetadataTruncated(metadata)
      if (localeIsChinese) {
        val base = if (pattern == null) {
          "在 $path 中找到 $matchCount 处匹配"
        } else {
          "在 $path 中为 \"$pattern\" 找到 $matchCount 处匹配"
        }
        if (truncated) "$base，结果已按结果上限截断" else base
      } else if (pattern == null) {
        if (matchCount == 1) {
          if (truncated) {
            "Found 1 match in $path. Output truncated at the tool result limit."
          } else {
            "Found 1 match in $path"
          }
        } else {
          if (truncated) {
            "Found $matchCount matches in $path. Output truncated at the tool result limit."
          } else {
            "Found $matchCount matches in $path"
          }
        }
      } else if (matchCount == 1) {
        if (truncated) {
          "Found 1 match for \"$pattern\" in $path. Output truncated at the tool result limit."
        } else {
          "Found 1 match for \"$pattern\" in $path"
        }
      } else {
        if (truncated) {
          "Found $matchCount matches for \"$pattern\" in $path. Output truncated at the tool result limit."
        } else {
          "Found $matchCount matches for \"$pattern\" in $path"
        }
      }
    }

    "Glob" -> {
      val matchCount = metadataInt(metadata, "matchCount") ?: return null
      val pattern = metadataValue(metadata, "pattern")
      val path = metadataValue(metadata, "path") ?: "."
      val truncated = resultMetadataTruncated(metadata)
      if (localeIsChinese) {
        val base = if (pattern == null) {
          "在 $path 中匹配到 $matchCount 个路径"
        } else {
          "在 $path 中为 $pattern 匹配到 $matchCount 个路径"
        }
        if (truncated) "$base，结果已按结果上限截断" else base
      } else if (pattern == null) {
        if (truncated) {
          "Matched $matchCount path(s) in $path. Output truncated at the tool result limit."
        } else {
          "Matched $matchCount path(s) in $path"
        }
      } else {
        if (truncated) {
          "Matched $matchCount path(s) for $pattern in $path. Output truncated at the tool result limit."
        } else {
          "Matched $matchCount path(s) for $pattern in $path"
        }
      }
    }

    "Edit" -> {
      val replacementCount = metadataInt(metadata, "replacementCount") ?: return null
      val filePath = metadataValue(metadata, "filePath")
      if (localeIsChinese) {
        if (filePath == null) {
          "应用了 $replacementCount 处替换"
        } else {
          "在 $filePath 中应用了 $replacementCount 处替换"
        }
      } else if (filePath == null) {
        "Applied $replacementCount replacement(s)"
      } else {
        "Applied $replacementCount replacement(s) in $filePath"
      }
    }

    "MultiEdit" -> {
      val replacementCount = metadataInt(metadata, "replacementCount")
      val editCount = metadataInt(metadata, "editCount")
      val filePath = metadataValue(metadata, "filePath")
      if (replacementCount == null && editCount == null && filePath == null) {
        null
      } else if (localeIsChinese) {
        buildList {
          filePath?.let(::add)
          replacementCount?.let { add("$it 处替换") }
          editCount?.let { add("$it 个编辑块") }
        }.joinToString(separator = "，").takeIf(String::isNotBlank)?.let { "应用了 $it" }
      } else {
        buildList {
          replacementCount?.let { add("$it replacement(s)") }
          editCount?.let { add("across $it edit(s)") }
          filePath?.let { add("in $it") }
        }.joinToString(separator = " ").takeIf(String::isNotBlank)?.let { "Applied $it" }
      }
    }

    "TodoWrite" -> {
      todoWriteResultSummary(metadata, localeIsChinese)
    }

    "WebSearch" -> {
      val sourceCount = metadataInt(metadata, "sourceCount")
      val operation = metadataValue(metadata, ProviderNativeWebSearchSupport.RESULT_METADATA_OPERATION)
        ?.trim()
        ?.lowercase()
      val status = metadataValue(metadata, ProviderNativeWebSearchSupport.RESULT_METADATA_STATUS)
      val query = metadataValue(metadata, "query")
      val url = metadataValue(metadata, "url")
      val text = metadataValue(metadata, "text")
      val managed = metadataValue(
        metadata,
        ProviderNativeWebSearchSupport.RESULT_METADATA_PROVIDER_MANAGED,
      ) == "true"
      if (sourceCount == null && operation == null && status == null && query == null && url == null && text == null) {
        null
      } else if (localeIsChinese) {
        buildList {
          if (managed) {
            add("原生搜索")
          }
          when (operation) {
            "open_page" -> url?.let { add("打开页面 $it") }
            "find_in_page" -> {
              text?.let { add("页内搜索 \"$it\"") }
              url?.let { add(it) }
            }
            else -> query?.let { add("搜索 \"$it\"") }
          }
          sourceCount?.let { add("来源 $it 个") }
          status?.let { add("状态 $it") }
        }.joinToString(separator = "，").takeIf(String::isNotBlank)
      } else {
        buildList {
          if (managed) {
            add("Provider-managed search")
          }
          when (operation) {
            "open_page" -> url?.let { add("opened $it") }
            "find_in_page" -> {
              text?.let { add("find \"$it\"") }
              url?.let { add("in $it") }
            }
            else -> query?.let { add("search \"$it\"") }
          }
          sourceCount?.let { add(if (it == 1) "1 source" else "$it sources") }
          status?.let { add("status $it") }
        }.joinToString(separator = " ").takeIf(String::isNotBlank)
      }
    }

    else -> null
  }
}

internal fun toolResultBodyText(result: AgentToolResult, localeIsChinese: Boolean): String? {
  result.errorMessage
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { return it }
  val preview = result.content.trim()
    .takeIf(String::isNotBlank)
    ?.takeUnless { content ->
      content.equals("Tool finished.", ignoreCase = true) ||
        content.equals("Tool completed.", ignoreCase = true)
    }
  if (preview != null) {
    return preview
  }
  return when (result.status) {
    AgentToolResultStatus.DENIED -> if (localeIsChinese) {
      "工具调用被拒绝。"
    } else {
      "Tool call denied."
    }

    AgentToolResultStatus.CANCELLED -> if (localeIsChinese) {
      "工具调用已取消。"
    } else {
      "Tool call cancelled."
    }

    AgentToolResultStatus.TIMEOUT -> if (localeIsChinese) {
      "工具调用超时。"
    } else {
      "Tool call timed out."
    }

    AgentToolResultStatus.FAILED -> if (localeIsChinese) {
      "工具调用失败。"
    } else {
      "Tool call failed."
    }

    AgentToolResultStatus.SUCCESS -> null
  }
}

internal fun readRangeSummary(offset: Int?, limit: Int?, localeIsChinese: Boolean): String {
  if (offset == null && limit == null) {
    return ""
  }
  if (localeIsChinese) {
    if (offset != null && limit != null) {
      val endLine = offset + limit - 1
      return "第 $offset-$endLine 行"
    }
    if (offset != null) {
      return "从第 $offset 行开始"
    }
    return "前 $limit 行"
  }
  if (offset != null && limit != null) {
    val endLine = offset + limit - 1
    return "lines $offset-$endLine"
  }
  if (offset != null) {
    return "from line $offset"
  }
  return "first $limit lines"
}

internal fun toolReasonText(reason: String, localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "原因：$reason"
  } else {
    "Reason: $reason"
  }

internal fun todoWriteActionSummary(
  summary: TodoSnapshotSummary,
  mutated: Boolean,
  localeIsChinese: Boolean,
): String {
  if (!mutated) {
    return if (localeIsChinese) {
      "读取当前待办列表"
    } else {
      "Read current todo list"
    }
  }
  if (summary.todoCount == 0) {
    return if (localeIsChinese) {
      "清空待办列表"
    } else {
      "Clear the todo list"
    }
  }
  val breakdown = todoBreakdownSummary(summary, localeIsChinese)
  val active = summary.activeTodoContent
  return if (localeIsChinese) {
    buildString {
      append("更新 ${summary.todoCount} 条待办")
      breakdown?.let { append("（$it）") }
      active?.let { append("，当前进行中：$it") }
    }
  } else {
    buildString {
      append("Update ${summary.todoCount} todo(s)")
      breakdown?.let { append(" ($it)") }
      active?.let { append(", active: $it") }
    }
  }
}

internal fun todoWriteResultSummary(metadata: Map<String, String>, localeIsChinese: Boolean): String? {
  val summary = todoSummaryFromMetadata(metadata) ?: return null
  val mutated = metadataBoolean(metadata, TodoWriteMetadataKeys.MUTATED) == true
  val planChanged = metadataBoolean(metadata, TodoWriteMetadataKeys.PLAN_CHANGED)
  if (!mutated) {
    if (summary.todoCount == 0) {
      return if (localeIsChinese) {
        "当前待办列表为空"
      } else {
        "Current todo list is empty"
      }
    }
    val breakdown = todoBreakdownSummary(summary, localeIsChinese)
    return if (localeIsChinese) {
      buildString {
        append("当前待办列表共 ${summary.todoCount} 项")
        breakdown?.let { append("，$it") }
        summary.activeTodoContent?.let { append("，当前进行中：$it") }
      }
    } else {
      buildString {
        append("Current todo list has ${summary.todoCount} item(s)")
        breakdown?.let { append(": $it") }
        summary.activeTodoContent?.let { append(". Active: $it") }
      }
    }
  }
  if (summary.todoCount == 0) {
    return if (localeIsChinese) {
      if (planChanged == false) "待办列表未变化，当前为空" else "待办列表已清空"
    } else {
      if (planChanged == false) "Plan unchanged. Todo list is empty." else "Cleared the todo list"
    }
  }
  val details = mutableListOf<String>()
  val completedDeltaCount = metadataInt(metadata, TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT) ?: 0
  val addedTodoCount = metadataInt(metadata, TodoWriteMetadataKeys.ADDED_TODO_COUNT) ?: 0
  val removedTodoCount = metadataInt(metadata, TodoWriteMetadataKeys.REMOVED_TODO_COUNT) ?: 0
  val statusChangedTodoCount = metadataInt(metadata, TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT) ?: 0
  val extraStatusChangeCount = (statusChangedTodoCount - completedDeltaCount).coerceAtLeast(0)
  if (completedDeltaCount > 0) {
    details += if (localeIsChinese) {
      "完成 $completedDeltaCount 项"
    } else {
      "completed $completedDeltaCount"
    }
  }
  if (addedTodoCount > 0) {
    details += if (localeIsChinese) {
      "新增 $addedTodoCount 项"
    } else {
      "added $addedTodoCount"
    }
  }
  if (removedTodoCount > 0) {
    details += if (localeIsChinese) {
      "移除 $removedTodoCount 项"
    } else {
      "removed $removedTodoCount"
    }
  }
  if (extraStatusChangeCount > 0) {
    details += if (localeIsChinese) {
      "更新 $extraStatusChangeCount 项状态"
    } else {
      "updated $extraStatusChangeCount status${if (extraStatusChangeCount == 1) "" else "es"}"
    }
  }
  if (details.isEmpty()) {
    todoBreakdownSummary(summary, localeIsChinese)?.let(details::add)
  }
  return if (localeIsChinese) {
    buildString {
      append(if (planChanged == false) "待办计划未变化" else "待办计划已更新")
      if (details.isNotEmpty()) {
        append("：")
        append(details.joinToString(separator = "，"))
      }
      summary.activeTodoContent?.let { append("，当前进行中：$it") }
    }
  } else {
    buildString {
      append(if (planChanged == false) "Plan unchanged" else "Plan updated")
      if (details.isNotEmpty()) {
        append(": ")
        append(details.joinToString(separator = ", "))
      }
      summary.activeTodoContent?.let { append(". Active now: $it") }
    }
  }
}

internal fun todoBreakdownSummary(summary: TodoSnapshotSummary, localeIsChinese: Boolean): String? {
  if (summary.todoCount <= 0) {
    return null
  }
  return if (localeIsChinese) {
    "${summary.pendingCount} 待处理，${summary.inProgressCount} 进行中，${summary.completedCount} 已完成"
  } else {
    "${summary.pendingCount} pending, ${summary.inProgressCount} in progress, ${summary.completedCount} completed"
  }
}

internal fun todoSummaryFromArguments(arguments: JsonObject): TodoSnapshotSummary? {
  if (!arguments.containsKey("todos")) {
    return null
  }
  return todoSummaryFromTodoObjects(arguments.replayObjectArray("todos").orEmpty())
}

internal fun todoSummaryFromMetadata(metadata: Map<String, String>): TodoSnapshotSummary? {
  val todoCount = metadataInt(metadata, TodoWriteMetadataKeys.TODO_COUNT) ?: return null
  return TodoSnapshotSummary(
    todoCount = todoCount,
    pendingCount = metadataInt(metadata, TodoWriteMetadataKeys.PENDING_TODO_COUNT) ?: 0,
    inProgressCount = metadataInt(metadata, TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT) ?: 0,
    completedCount = metadataInt(metadata, TodoWriteMetadataKeys.COMPLETED_TODO_COUNT) ?: 0,
    activeTodoContent = metadataValue(metadata, TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT),
  )
}

internal fun todoSummaryFromTodoObjects(todos: List<JsonObject>): TodoSnapshotSummary {
  var pendingCount = 0
  var inProgressCount = 0
  var completedCount = 0
  var activeTodoContent: String? = null
  todos.forEach { todo ->
    when (AgentTodoStatus.fromLabelOrNull(todo.replayString("status"))) {
      AgentTodoStatus.PENDING -> pendingCount += 1
      AgentTodoStatus.IN_PROGRESS -> {
        inProgressCount += 1
        if (activeTodoContent == null) {
          activeTodoContent = todo.replayString("content")
        }
      }

      AgentTodoStatus.COMPLETED -> completedCount += 1
      null -> Unit
    }
  }
  return TodoSnapshotSummary(
    todoCount = todos.size,
    pendingCount = pendingCount,
    inProgressCount = inProgressCount,
    completedCount = completedCount,
    activeTodoContent = activeTodoContent,
  )
}

internal fun metadataValue(metadata: Map<String, String>, key: String): String? =
  metadata[key]
    ?.trim()
    ?.takeIf(String::isNotBlank)

internal fun metadataInt(metadata: Map<String, String>, key: String): Int? =
  metadataValue(metadata, key)?.toIntOrNull()

internal fun metadataBoolean(metadata: Map<String, String>, key: String): Boolean? =
  when (metadataValue(metadata, key)?.lowercase(Locale.US)) {
    "true" -> true
    "false" -> false
    else -> null
  }

internal fun resultMetadataTruncated(metadata: Map<String, String>): Boolean =
  metadataBoolean(metadata, "resultTruncated")
    ?: metadataBoolean(metadata, "truncated")
    ?: false

internal fun toolResultDetailedContentSnapshot(result: AgentToolResult): String? {
  val content = result.content.trim().takeIf(String::isNotBlank) ?: return null
  return if (result.status == AgentToolResultStatus.SUCCESS) {
    content
  } else {
    content.take(MAX_RUNTIME_EVENT_FAILURE_CONTENT_CHARS)
  }
}
