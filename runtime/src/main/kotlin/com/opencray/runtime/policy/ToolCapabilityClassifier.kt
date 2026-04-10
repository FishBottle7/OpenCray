package com.opencray.runtime.policy

import com.opencray.policy.PolicyToolClass

internal class ToolCapabilityClassifier {
  fun classifyPolicyToolClass(toolName: String): PolicyToolClass = when (toolName) {
    "Write",
    "Edit",
    "MultiEdit",
    "ImportFile",
    "workspace_write_file",
    "workspace_import_file",
    "import_chat_attachment",
    "extract_workspace_package",
    "ScheduledTaskCreate",
    "ScheduledTaskDelete",
    "ScheduledTaskUpdate",
    "SkillsAdd",
    "SkillsAddBatch",
    "SkillsUpdate",
    -> PolicyToolClass.WRITE_FILE

    "workspace_move_file" -> PolicyToolClass.MOVE_FILE
    "workspace_delete_file",
    "SkillsRemove",
    -> PolicyToolClass.DELETE_FILE

    "Bash",
    "ProcessStart",
    "ProcessTerminate",
    "command_exec",
    "python_exec",
    -> PolicyToolClass.EXECUTE_COMMAND

    "WebSearch",
    "WebFetch",
    "GenerateImage",
    "SynthesizeSpeech",
    "sandbox_preview_open",
    "sandbox_session_close",
    -> PolicyToolClass.NETWORK_ACCESS

    "LS",
    "Read",
    "Grep",
    "Glob",
    "Task",
    "spawn_agent",
    "wait_agent",
    "send_input",
    "close_agent",
    "list_subagents",
    "workspace_list_files",
    "workspace_read_file",
    "search_workspace_document",
    "inspect_workspace_package",
    "view_workspace_document",
    "view_workspace_image",
    "view_workspace_pdf",
    "SkillsFind",
    "SkillsInspect",
    "SkillsList",
    "SkillsCheck",
    "sandbox_session_info",
    "ScheduledTaskList",
    "ScheduledTaskGet",
    -> PolicyToolClass.READ_FILE

    else -> error("No PolicyToolClass mapping is registered for tool '$toolName'.")
  }

  fun classifyCapabilityKind(toolName: String): String = when (toolName) {
    "TodoWrite" -> "todo_management"
    "ScheduledTaskCreate",
    "ScheduledTaskList",
    "ScheduledTaskGet",
    "ScheduledTaskUpdate",
    "ScheduledTaskDelete",
    -> "schedule_task"

    "Task",
    "spawn_agent",
    "wait_agent",
    "send_input",
    "close_agent",
    "list_subagents",
    -> "delegate_task"

    "GenerateImage",
    "SynthesizeSpeech",
    "sandbox_session_close",
    -> "network_access"

    "ProcessList",
    "ProcessRead",
    "ProcessWait",
    -> "read_process"

    "skills_list",
    "skill_read",
    -> "read_skill"

    "python_runtime_manifest" -> "read_python_runtime"

    "sandbox_session_info" -> "read_sandbox_session"

    "SkillsFind",
    "SkillsInspect",
    "SkillsList",
    -> "read_skill_package"

    "SkillsCheck" -> "check_skill_update"

    "SkillsAdd" -> "install_skill"

    "SkillsAddBatch" -> "install_skill"

    "SkillsUpdate" -> "update_skill"

    "SkillsRemove" -> "remove_skill"

    "mcp_list_servers" -> "read_mcp"

    "memory_search",
    "memory_get",
    -> "read_memory"

    "ProcessTerminate" -> "process_control"
    else -> when (toolName) {
      "LS",
      "Read",
      "Grep",
      "Glob",
      "workspace_list_files",
      "workspace_read_file",
      "search_workspace_document",
      "inspect_workspace_package",
      "view_workspace_document",
      "view_workspace_pdf",
      "view_workspace_image",
      -> "read_file"

      else -> when (classifyPolicyToolClass(toolName)) {
        PolicyToolClass.WRITE_FILE -> "write_file"
        PolicyToolClass.MOVE_FILE -> "move_file"
        PolicyToolClass.DELETE_FILE -> "delete_file"
        PolicyToolClass.RENAME_FILE -> "rename_file"
        PolicyToolClass.EXECUTE_COMMAND -> "execute_command"
        PolicyToolClass.NETWORK_ACCESS -> "network_access"
        PolicyToolClass.READ_FILE -> "read_file"
      }
    }
  }
}
