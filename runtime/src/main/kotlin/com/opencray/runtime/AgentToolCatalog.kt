package com.opencray.runtime

internal fun OpenCrayToolDispatcher.toolDefinitions(): List<AgentToolDefinition> {
    val pythonManifestProviderAvailable = config.pythonRuntimeManifestProvider != null
    val canonicalDefinitions = listOf(
      AgentToolDefinition(
        name = "LS",
        description = "List files and directories under the approved readable roots. Use workspace-relative paths for the main workspace, or absolute paths for approved external read-only roots listed in task metadata.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = false, description = "Workspace-relative path, or an absolute path inside an approved external read-only root. Defaults to the writable workspace root."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of entries to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Read",
        description = "Read a text file from the approved readable roots. Supports optional 1-based line offsets and limits.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("offset", "number", required = false, description = "1-based starting line number."),
          AgentToolParameter("limit", "number", required = false, description = "Maximum number of lines to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Write",
        description = "Create or overwrite a text file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("content", "string", required = true, description = "Full UTF-8 text content to write."),
        ),
      ),
      AgentToolDefinition(
        name = "Grep",
        description = "Search readable text files with a regular expression and return matching lines from the workspace or approved external read-only roots.",
        parameters = listOf(
          AgentToolParameter("pattern", "string", required = true, description = "Regular expression pattern to search for."),
          AgentToolParameter("path", "string", required = false, description = "Optional workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to relative file paths."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of matching lines to return."),
        ),
      ),
      AgentToolDefinition(
        name = "Glob",
        description = "Recursively match readable paths with a glob pattern across the workspace and approved external read-only roots.",
        parameters = listOf(
          AgentToolParameter("pattern", "string", required = true, description = "Glob pattern to match against workspace-relative paths."),
          AgentToolParameter("path", "string", required = false, description = "Optional workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of matching paths to return."),
        ),
      ),
      AgentToolDefinition(
        name = "WebSearch",
        description = "Search the web through the configured search provider and return result titles, URLs, and snippets.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = true, description = "Search query to send to the web search provider."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of search results to return."),
          AgentToolParameter("domains", "string[]", required = false, description = "Optional domain filter. Only return results from these domains or their subdomains."),
        ),
      ),
      AgentToolDefinition(
        name = "WebFetch",
        description = "Fetch one HTTP or HTTPS page and return extracted readable text content.",
        parameters = listOf(
          AgentToolParameter("url", "string", required = true, description = "Absolute http or https URL to fetch."),
          AgentToolParameter("max_chars", "number", required = false, description = "Maximum number of extracted characters to return."),
        ),
      ),
      AgentToolDefinition(
        name = "GenerateImage",
        description = "Generate one or more images through the configured media provider and save them under the workspace media store so they can be attached in the final response by artifact_id.",
        parameters = listOf(
          AgentToolParameter("prompt", "string", required = true, description = "Text prompt describing the image to generate."),
          AgentToolParameter("count", "number", required = false, description = "How many images to generate. Maximum 9."),
          AgentToolParameter("size", "string", required = false, description = "Optional provider-specific size hint such as 1024x1024."),
          AgentToolParameter("format", "string", required = false, description = "Optional output image format. Supported values: png, jpg, jpeg, webp."),
          AgentToolParameter("model", "string", required = false, description = "Optional provider model override. Defaults to the configured image model."),
          AgentToolParameter("async", "boolean", required = false, description = "Start the request as a background media job and return a job_id instead of waiting for the final image files."),
        ),
      ),
      AgentToolDefinition(
        name = "GenerateVideo",
        description = "Generate a video clip through the configured media provider. This tool defaults to a background job and returns a job_id that can be polled until the final artifact is ready.",
        parameters = listOf(
          AgentToolParameter("prompt", "string", required = true, description = "Text prompt describing the video to generate."),
          AgentToolParameter("duration_seconds", "number", required = false, description = "Optional target duration in seconds."),
          AgentToolParameter("size", "string", required = false, description = "Optional provider-specific size hint such as 1280x720."),
          AgentToolParameter("format", "string", required = false, description = "Optional output video format. Supported values: mp4, mov, webm."),
          AgentToolParameter("model", "string", required = false, description = "Optional provider model override. Defaults to the configured video model."),
          AgentToolParameter("async", "boolean", required = false, description = "Whether to run as a background job. Defaults to true for video generation."),
        ),
      ),
      AgentToolDefinition(
        name = "SynthesizeSpeech",
        description = "Convert text into a voice clip through the configured speech provider, save it under the workspace media store, and return an artifact_id that can be attached in the final response.",
        parameters = listOf(
          AgentToolParameter("text", "string", required = true, description = "Text to synthesize into spoken audio."),
          AgentToolParameter("format", "string", required = false, description = "Optional audio format. Supported values: mp3, wav, m4a."),
          AgentToolParameter("voice", "string", required = false, description = "Optional voice override. Defaults to the configured voice preset."),
          AgentToolParameter("model", "string", required = false, description = "Optional provider model override. Defaults to the configured speech model."),
          AgentToolParameter("async", "boolean", required = false, description = "Start the request as a background media job and return a job_id instead of waiting for the final audio artifact."),
        ),
      ),
      AgentToolDefinition(
        name = "PublishMediaArtifact",
        description = "Copy one generated media artifact to a stable workspace-relative path. This never moves the original artifact, and fails if the destination already exists.",
        parameters = listOf(
          AgentToolParameter("artifact_id", "string", required = true, description = "Artifact id returned by GenerateImage, GenerateVideo, SynthesizeSpeech, or another workspace artifact-producing tool."),
          AgentToolParameter("relative_path", "string", required = true, description = "Destination path inside the writable workspace root. Existing files are not overwritten."),
        ),
      ),
      AgentToolDefinition(
        name = "PollMediaJob",
        description = "Inspect one background media job by job_id and return either the current pending state or the final artifact metadata when the job completes.",
        parameters = listOf(
          AgentToolParameter("job_id", "string", required = true, description = "Background media job identifier returned by GenerateImage, GenerateVideo, or SynthesizeSpeech."),
        ),
      ),
      AgentToolDefinition(
        name = "CancelMediaJob",
        description = "Request cancellation for one background media job by job_id.",
        parameters = listOf(
          AgentToolParameter("job_id", "string", required = true, description = "Background media job identifier returned by GenerateImage, GenerateVideo, or SynthesizeSpeech."),
        ),
      ),
      AgentToolDefinition(
        name = "Edit",
        description = "Apply an exact string replacement to one existing text file. Fails if the target text is missing or ambiguous unless replace_all is true.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("old_string", "string", required = true, description = "Exact text to replace."),
          AgentToolParameter("new_string", "string", required = true, description = "Replacement text."),
          AgentToolParameter("replace_all", "boolean", required = false, description = "Replace every match instead of requiring a unique match."),
        ),
      ),
      AgentToolDefinition(
        name = "MultiEdit",
        description = "Apply multiple exact string replacements to one existing text file atomically.",
        parameters = listOf(
          AgentToolParameter("file_path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter(
            name = "edits",
            type = "object[]",
            required = true,
            description = "Array of edit objects with old_string, new_string, and optional replace_all.",
            jsonSchema = multiEditArraySchema(
              description = "Array of exact text edit objects to apply in order.",
            ),
          ),
        ),
      ),
      AgentToolDefinition(
        name = "ImportFile",
        description = "Copy a file or folder from an approved readable root into the writable workspace without mutating the source. Use this to bring photos or public files into the workspace.",
        parameters = listOf(
          AgentToolParameter("source_path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("destination_path", "string", required = true, description = "Destination path inside the writable workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "TodoWrite",
        description = "Read or replace the current chat session's in-memory todo list. Omit todos to inspect the current list; provide todos to replace it; provide an empty todos array to clear it. Keep todo contents unique, keep at most one todo in_progress, and only that active todo may set activeForm.",
        parameters = listOf(
          AgentToolParameter(
            name = "todos",
            type = "object[]",
            required = false,
            description = "Array of todo objects with unique content, status, and optional activeForm. At most one entry may use status=in_progress, and only that entry may include activeForm.",
            jsonSchema = todoEntryArraySchema(
              description = "Optional replacement todo list. Omit this field to inspect the current todos without mutating them. Send an empty array to clear the current todo list. Keep contents unique, keep at most one entry in_progress, and only that active entry may include activeForm.",
            ),
          ),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskCreate",
        description = "Create one persisted scheduled task that later enqueues a normal run on the target session queue. Use trigger.at for one absolute time, trigger.after for one relative delay, or trigger.start_at plus trigger.rrule for recurrence. If session_id is omitted, the current chat session is used.",
        parameters = listOf(
          AgentToolParameter("prompt", "string", required = true, description = "Prompt text that should be submitted when the schedule fires."),
          AgentToolParameter("title", "string", required = false, description = "Optional user-visible schedule title. Defaults to a short prompt-derived title."),
          AgentToolParameter("session_id", "string", required = false, description = "Optional existing target session id. Defaults to the current chat session."),
          AgentToolParameter(
            "trigger",
            "object",
            required = true,
            description = "Trigger object. Use exactly one form: at, after, or recurrence with start_at plus rrule.",
            jsonSchema = scheduledTaskTriggerSchema(),
          ),
          AgentToolParameter("enabled", "boolean", required = false, description = "Whether the new scheduled task is enabled immediately. Defaults to true."),
          AgentToolParameter("conflict_policy", "string", required = false, description = "Optional conflict policy. Supported values: enqueue_new_run, skip_if_session_busy, cancel_older_waiting_trigger."),
          AgentToolParameter("notify_on_queued", "boolean", required = false, description = "Whether to notify when the scheduled trigger is accepted into the queue."),
          AgentToolParameter("notify_on_approval", "boolean", required = false, description = "Whether to notify if the scheduled run later waits for approval."),
          AgentToolParameter("notify_on_completion", "boolean", required = false, description = "Whether to notify when the scheduled run completes."),
          AgentToolParameter("notify_on_interruption", "boolean", required = false, description = "Whether to notify when the scheduled run is interrupted or paused."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskList",
        description = "List persisted scheduled tasks. If session_id is omitted, the current chat session is used when available; otherwise all sessions are listed.",
        parameters = listOf(
          AgentToolParameter("session_id", "string", required = false, description = "Optional existing target session id filter."),
          AgentToolParameter("enabled", "boolean", required = false, description = "Optional enabled-state filter."),
          AgentToolParameter("limit", "number", required = false, description = "Maximum number of scheduled tasks to return. Defaults to 20."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskGet",
        description = "Inspect one persisted scheduled task in detail, including its prompt, trigger configuration, notification policy, next fire time, and a bounded slice of recent run history.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact scheduled task id."),
          AgentToolParameter("recent_run_limit", "number", required = false, description = "Maximum number of recent run records to return. Defaults to 5."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskUpdate",
        description = "Patch one persisted scheduled task. If trigger is provided, it replaces the full stored trigger definition. Use enabled to enable or disable future wakes.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact scheduled task id."),
          AgentToolParameter("title", "string", required = false, description = "Optional replacement user-visible title."),
          AgentToolParameter("prompt", "string", required = false, description = "Optional replacement prompt text that should be submitted when the schedule fires."),
          AgentToolParameter(
            "trigger",
            "object",
            required = false,
            description = "Optional full replacement trigger object. Use exactly one form: at, after, or recurrence with start_at plus rrule.",
            jsonSchema = scheduledTaskTriggerSchema(),
          ),
          AgentToolParameter("enabled", "boolean", required = false, description = "Optional enabled state. Disabled schedules keep their configuration and history but do not create future wakes."),
          AgentToolParameter("conflict_policy", "string", required = false, description = "Optional replacement conflict policy. Supported values: enqueue_new_run, skip_if_session_busy, cancel_older_waiting_trigger."),
          AgentToolParameter("notify_on_queued", "boolean", required = false, description = "Optional replacement queued notification flag."),
          AgentToolParameter("notify_on_approval", "boolean", required = false, description = "Optional replacement approval notification flag."),
          AgentToolParameter("notify_on_completion", "boolean", required = false, description = "Optional replacement completion notification flag."),
          AgentToolParameter("notify_on_interruption", "boolean", required = false, description = "Optional replacement interruption notification flag."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskRunNow",
        description = "Request one immediate run of an enabled persisted schedule through the detached runtime. This does not change the schedule's future trigger.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact enabled scheduled task id."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskSnooze",
        description = "Delay the next wake of an enabled persisted schedule without changing its trigger definition. Defaults to 15 minutes.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact enabled scheduled task id."),
          AgentToolParameter("duration_minutes", "number", required = false, description = "Delay in whole minutes from 1 through 10080. Defaults to 15."),
        ),
      ),
      AgentToolDefinition(
        name = "ScheduledTaskDelete",
        description = "Delete one persisted scheduled task, unregister its future wake, and remove its stored run history.",
        parameters = listOf(
          AgentToolParameter("schedule_id", "string", required = true, description = "Exact scheduled task id."),
        ),
      ),
      AgentToolDefinition(
        name = "Task",
        description = "Delegate one bounded subtask to a child runtime and wait for its summarized result before continuing. Prefer explorer or default for read-only work, and worker for bounded workspace edits. Legacy aliases researcher, reviewer, and general-purpose are still accepted.",
        parameters = listOf(
          AgentToolParameter("description", "string", required = true, description = "Short task label for the delegated child run."),
          AgentToolParameter("prompt", "string", required = true, description = "Exact instructions for the child run."),
          AgentToolParameter("subagent_type", "string", required = true, description = "Child profile id such as explorer, default, or worker. Legacy aliases researcher, reviewer, and general-purpose also work."),
          AgentToolParameter("context_mode", "string", required = false, description = "Optional child context override. Supported public values: minimal, delegated. mirrored is reserved for internal-only recovery/testing paths."),
        ),
      ),
      AgentToolDefinition(
        name = "spawn_agent",
        description = "Start one bounded subagent handle immediately. During prompt runs the child begins running in the background right away; use wait_agent later to inspect its latest stable state or block for completion.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "Optional explicit child handle id for the delegated child run."),
          AgentToolParameter("description", "string", required = true, description = "Short task label for the delegated child run."),
          AgentToolParameter("prompt", "string", required = true, description = "Exact instructions for the child run."),
          AgentToolParameter("subagent_type", "string", required = true, description = "Child profile id such as explorer, default, or worker. Legacy aliases researcher, reviewer, and general-purpose also work."),
          AgentToolParameter("context_mode", "string", required = false, description = "Optional child context override. Supported public values: minimal, delegated. mirrored is reserved for internal-only recovery/testing paths."),
        ),
      ),
      AgentToolDefinition(
        name = "wait_agent",
        description = "Wait for one delegated child handle to reach its latest stable state and return a summarized result. If that child is already running, wait_agent blocks until it finishes or pauses for approval. Approval-unlocked children resume through the session-owned recovery path; use wait_agent later to observe that resumed state.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "One delegated child handle id returned by spawn_agent."),
          AgentToolParameter("agent_ids", "string[]", required = false, description = "Optional batch form. The first listed id is used in this runtime."),
          AgentToolParameter("ids", "string[]", required = false, description = "Compatibility alias for agent_ids."),
        ),
      ),
      AgentToolDefinition(
        name = "send_input",
        description = "Queue one parent follow-up message in the delegated child mailbox. Use it for queued, background-running, or approval-waiting children. Idle queued children may resume immediately, while background-running children normally apply it after the current child turn reaches its next safe boundary. Set interrupt=true only when you need to redirect a currently running child immediately.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "Delegated child handle id returned by spawn_agent."),
          AgentToolParameter("id", "string", required = false, description = "Compatibility alias for agent_id."),
          AgentToolParameter("message", "string", required = false, description = "Parent follow-up message to queue in the child mailbox before the next resume boundary."),
          AgentToolParameter("input", "string", required = false, description = "Compatibility alias for message."),
          AgentToolParameter("interrupt", "boolean", required = false, description = "When true and the child is already running, interrupt that in-flight child turn, keep the not-yet-committed mailbox delivery pending, and restart the child immediately with the updated input."),
        ),
      ),
      AgentToolDefinition(
        name = "close_agent",
        description = "Close one delegated child handle. Running or paused children are cancelled and removed; completed children are simply forgotten.",
        parameters = listOf(
          AgentToolParameter("agent_id", "string", required = false, description = "Delegated child handle id returned by spawn_agent."),
          AgentToolParameter("id", "string", required = false, description = "Compatibility alias for agent_id."),
        ),
      ),
      AgentToolDefinition(
        name = "list_subagents",
        description = "List delegated child handles currently known to this runtime, including parent linkage, lifecycle state, mailbox backlog, and the latest summarized child result.",
      ),
      AgentToolDefinition(
        name = "Bash",
        description = "Run one shell command string inside the approved workspace through the host shell. Each call starts a fresh managed shell process; if it keeps running after the initial wait, continue with ProcessRead, ProcessWait, or ProcessTerminate. Do not use Bash for python/python3/py invocations or Python runtime diagnostics.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = true, description = "Shell command string to execute."),
          AgentToolParameter("working_directory", "string", required = false, description = "Workspace-relative working directory. Defaults to the workspace root."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "How long Bash should wait for completion before returning a still-running managed process."),
          AgentToolParameter("wait_timeout_ms", "number", required = false, description = "Explicit alias for timeout_ms."),
          AgentToolParameter("process_timeout_ms", "number", required = false, description = "Maximum lifetime for the managed shell process before it is terminated."),
          AgentToolParameter("background", "boolean", required = false, description = "If true, return immediately after the managed shell process starts."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessStart",
        description = "Start a managed background command inside the approved workspace and return a process id for later inspection. Use python_exec instead of ProcessStart for workspace Python scripts unless the runtime explicitly supports managed Python process launches.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = false, description = "Executable to launch. Provide exactly one of command or script_path."),
          AgentToolParameter("script_path", "string", required = false, description = "Workspace-relative Python script to launch through the managed Python runner on runtimes that support host Python processes. Prefer python_exec for workspace-local Python scripts."),
          AgentToolParameter("args", "string[]", required = false, description = "Optional command arguments."),
          AgentToolParameter("python_executable", "string", required = false, description = "Python executable used when script_path is provided. Defaults to python."),
          AgentToolParameter("working_directory", "string", required = false, description = "Workspace-relative working directory. Defaults to the workspace root."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "Maximum runtime before the managed process is terminated."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessList",
        description = "List managed background processes for the current chat session.",
      ),
      AgentToolDefinition(
        name = "ProcessRead",
        description = "Read the latest status and captured output for one managed background process.",
        parameters = listOf(
          AgentToolParameter("process_id", "string", required = true, description = "Managed process id returned by ProcessStart."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessWait",
        description = "Wait briefly for one managed background process to advance or finish, then return its latest status and output.",
        parameters = listOf(
          AgentToolParameter("process_id", "string", required = true, description = "Managed process id returned by ProcessStart."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "How long to wait before returning the current snapshot."),
        ),
      ),
      AgentToolDefinition(
        name = "ProcessTerminate",
        description = "Terminate one managed background process started in the current chat session.",
        parameters = listOf(
          AgentToolParameter("process_id", "string", required = true, description = "Managed process id returned by ProcessStart."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_list_files",
        description = "List files under the approved readable roots.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = false, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of entries to return."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_read_file",
        description = "Read a text file from the approved readable roots.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_write_file",
        description = "Create or overwrite a text file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "File path relative to the workspace root."),
          AgentToolParameter("content", "string", required = true, description = "Full UTF-8 text content to write."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_import_file",
        description = "Copy a file or folder from an approved readable root into the writable workspace.",
        parameters = listOf(
          AgentToolParameter("source_path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("destination_path", "string", required = true, description = "Destination path inside the writable workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "import_chat_attachment",
        description = "Copy one existing chat attachment from the current session into the writable workspace without exposing the chat-media storage path.",
        parameters = listOf(
          AgentToolParameter("chat_attachment_id", "string", required = true, description = "Attachment id from the current chat history."),
          AgentToolParameter("destination_path", "string", required = true, description = "Destination path inside the writable workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "search_workspace_document",
        description = "Search a readable workspace document for relevant PDF pages and text excerpts. Use this before attaching a large PDF when you need to locate the right pages or verify whether specific keywords appear.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("query", "string", required = false, description = "Optional keyword or phrase to search for inside the document. When omitted, returns page previews instead."),
          AgentToolParameter("pages", "number[]", required = false, description = "Optional explicit 1-based page numbers to inspect."),
          AgentToolParameter("page_from", "number", required = false, description = "Optional inclusive 1-based page number to start scanning from."),
          AgentToolParameter("page_to", "number", required = false, description = "Optional inclusive 1-based page number to stop scanning at."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of preview or match results to return."),
        ),
      ),
      AgentToolDefinition(
        name = "inspect_workspace_package",
        description = "Inspect one readable ZIP-based package such as zip, docx, xlsx, pptx, odt, ods, or odp. Use this to list internal entries, preview specific XML or text parts, and identify the main document parts before extracting anything.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to package entry paths."),
          AgentToolParameter("max_entries", "number", required = false, description = "Maximum number of matched entries to return."),
          AgentToolParameter("preview_entries", "string[]", required = false, description = "Optional exact package entry paths to preview when they are safe text or XML entries."),
          AgentToolParameter("preview_chars", "number", required = false, description = "Maximum characters to preview for each requested entry."),
          AgentToolParameter("include_relationship_hints", "boolean", required = false, description = "Whether to include package kind hints such as main parts and relationship parts."),
        ),
      ),
      AgentToolDefinition(
        name = "extract_workspace_package",
        description = "Extract selected entries from one readable ZIP-based package into a writable workspace directory. Requires entries or glob and never defaults to full-package extraction.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
          AgentToolParameter("destination_dir", "string", required = true, description = "Writable workspace directory where the selected entries will be extracted."),
          AgentToolParameter("entries", "string[]", required = false, description = "Optional exact package entry paths, or package subdirectories, to extract."),
          AgentToolParameter("glob", "string", required = false, description = "Optional glob filter applied to package entry paths."),
          AgentToolParameter("strip_top_level", "boolean", required = false, description = "Whether to remove one shared top-level directory segment from extracted paths when present."),
          AgentToolParameter("overwrite", "boolean", required = false, description = "Whether existing destination files may be overwritten."),
        ),
      ),
      AgentToolDefinition(
        name = "view_workspace_image",
        description = "Attach one readable workspace image into the next model turn for direct visual inspection. Use this when you need to see what an existing image actually contains instead of guessing from its path or filename.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
        ),
      ),
      AgentToolDefinition(
        name = "view_workspace_document",
        description = "Attach one readable workspace image or PDF into the next model turn for direct inspection. Use this when you need the model to inspect the existing document itself instead of guessing from the path or filename.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
        ),
      ),
      AgentToolDefinition(
        name = "view_workspace_pdf",
        description = "Attach one readable workspace PDF into the next model turn for direct inspection. Use this when you need the model to inspect the PDF contents directly instead of guessing from the path or filename.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Workspace-relative path, or an absolute path inside an approved external read-only root."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_move_file",
        description = "Move or rename a file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("source_path", "string", required = true, description = "Existing file path relative to the workspace root."),
          AgentToolParameter("destination_path", "string", required = true, description = "New file path relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "workspace_delete_file",
        description = "Delete a file inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "File path relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "command_exec",
        description = "Execute a local command inside the approved workspace.",
        parameters = listOf(
          AgentToolParameter("command", "string", required = true, description = "Executable name or command path."),
          AgentToolParameter("args", "string[]", required = false, description = "Command arguments."),
          AgentToolParameter("working_directory", "string", required = false, description = "Directory relative to the workspace root."),
        ),
      ),
      AgentToolDefinition(
        name = "python_exec",
        description = buildString {
          append("Execute one workspace-local Python script through the active Python runtime backend. ")
          append("Use this instead of Bash for workspace Python scripts and Python runtime diagnostics.")
          if (pythonManifestProviderAvailable) {
            append(" Runtime packages are preinstalled-only; call python_runtime_manifest for exact available packages when imports matter.")
          }
        },
        parameters = listOf(
          AgentToolParameter("script_path", "string", required = true, description = "Script path relative to the workspace root."),
          AgentToolParameter("args", "string[]", required = false, description = "Script arguments."),
          AgentToolParameter("timeout_ms", "number", required = false, description = "Maximum runtime before the Python execution is timed out."),
          AgentToolParameter("startup_timeout_ms", "number", required = false, description = "Optional extra startup budget before Python script timeout accounting begins."),
        ),
      ),
      config.pythonRuntimeManifestProvider?.let {
        AgentToolDefinition(
          name = "python_runtime_manifest",
          description = "Inspect the active Python runtime package policy and exact preinstalled packages. Use this before writing Python imports when package availability matters.",
        )
      },
      config.sandboxPreviewService?.let {
        AgentToolDefinition(
          name = "sandbox_preview_open",
          description = "Return a preview URL for one port exposed by the active cloud sandbox session and run a short reachability probe against it. Use this only when cloud execution is enabled and the target service is expected to be running inside the sandbox. If exactly one candidate preview port has already been discovered from sandbox output, port can be omitted.",
          parameters = listOf(
            AgentToolParameter("port", "number", required = false, description = "TCP port exposed by the sandbox service. Optional when the active sandbox session has exactly one discovered preview candidate port."),
            AgentToolParameter("path", "string", required = false, description = "Optional path suffix such as / or /health."),
          ),
        )
      },
      config.sandboxSessionControlService?.let {
        AgentToolDefinition(
          name = "sandbox_session_close",
          description = "Terminate the active reusable cloud sandbox session for the current workspace and clear its local resume snapshot. Use this when cloud work is finished or when the next cloud run should start from a fresh sandbox.",
        )
      },
      config.sandboxSessionInfoService?.let {
        AgentToolDefinition(
          name = "sandbox_session_info",
          description = "Inspect the active reusable cloud sandbox session for the current workspace, including whether it is in memory, persisted for resume, which preview candidate ports are known, and whether any sandbox requests are still running.",
        )
      },
      AgentToolDefinition(
        name = "skills_list",
        description = "List discovered skills from configured skills roots.",
      ),
      AgentToolDefinition(
        name = "skill_read",
        description = "Read one discovered skill's metadata and markdown body.",
        parameters = listOf(
          AgentToolParameter("name", "string", required = true, description = "Exact skill name."),
          AgentToolParameter("pin", "boolean", required = false, description = "When true, promote the active skill capsule into durable front context. Defaults to false."),
        ),
      ),
      AgentToolDefinition(
        name = "skill_execute",
        description = "Activate and execute a discovered skill. Inline skills activate a run-local skill capsule; fork skills delegate the skill workflow to a child runtime.",
        parameters = listOf(
          AgentToolParameter("name", "string", required = true, description = "Exact skill name."),
          AgentToolParameter("prompt", "string", required = false, description = "Task prompt for a forked skill child runtime, or optional inline activation note."),
          AgentToolParameter("pin", "boolean", required = false, description = "When true, promote the active skill capsule into durable front context. Defaults to false."),
          AgentToolParameter("context_mode", "string", required = false, description = "Optional child context mode for fork skills. Supported public values: minimal, delegated."),
          AgentToolParameter("subagent_type", "string", required = false, description = "Optional child subagent type for fork skills. Defaults to general-purpose."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsFind",
        description = "Search installable skills from the remote skills index and the host-managed local catalog.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = false, description = "Optional case-insensitive search query. Non-blank queries also search the remote skills index."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of combined results to return."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsInspect",
        description = "Inspect an explicit local path, GitHub source, or GitLab source and list the installable skills it contains before installation.",
        parameters = listOf(
          AgentToolParameter("source_ref", "string", required = true, description = "Explicit local path, owner/repo, gitlab:group/project/repo, GitHub URL, GitLab URL, or supported git remote URL."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsList",
        description = "List skills currently installed in the host-managed skills directory.",
      ),
      AgentToolDefinition(
        name = "SkillsCheck",
        description = "Check installed skills against their recorded source provenance and report whether updates are available.",
        parameters = listOf(
          AgentToolParameter("skill_id", "string", required = false, description = "Optional exact installed skill id to check. Defaults to all installed skills."),
          AgentToolParameter("all", "boolean", required = false, description = "Optional compatibility flag. When true, check all installed skills."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsAdd",
        description = "Install one skill from the host-managed local catalog, an explicit local path, or a supported remote source such as owner/repo, gitlab:group/project/repo, a GitHub URL, or a GitLab URL.",
        parameters = listOf(
          AgentToolParameter("source_ref", "string", required = true, description = "Catalog skill id, explicit local path, owner/repo, owner/repo@skill-name, gitlab:group/project/repo, GitHub URL, GitLab URL, or supported git remote URL."),
          AgentToolParameter("skill", "string", required = false, description = "Optional explicit skill name when a remote source exposes multiple skills."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsAddBatch",
        description = "Install multiple skills from one explicit local path, GitHub source, or GitLab source through the shared host-managed skills pipeline.",
        parameters = listOf(
          AgentToolParameter("source_ref", "string", required = true, description = "Explicit local path, owner/repo, gitlab:group/project/repo, GitHub URL, GitLab URL, or supported git remote URL."),
          AgentToolParameter("skills", "string[]", required = false, description = "Optional explicit skill names to install from the inspected source."),
          AgentToolParameter("install_all", "boolean", required = false, description = "When true, install every valid skill discovered in the source."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsUpdate",
        description = "Update installed skills in place using their recorded source provenance.",
        parameters = listOf(
          AgentToolParameter("skill_id", "string", required = false, description = "Optional exact installed skill id to update. Defaults to all installed skills."),
          AgentToolParameter("all", "boolean", required = false, description = "Optional compatibility flag. When true, update all installed skills."),
          AgentToolParameter("yes", "boolean", required = false, description = "Optional compatibility flag. Native updates are non-interactive, so this is accepted but ignored."),
        ),
      ),
      AgentToolDefinition(
        name = "SkillsRemove",
        description = "Remove one installed skill from the host-managed skills directory.",
        parameters = listOf(
          AgentToolParameter("skill_id", "string", required = true, description = "Exact installed skill id."),
        ),
      ),
      AgentToolDefinition(
        name = "mcp_list_servers",
        description = "Inspect currently exposed MCP servers and their trust state. This runtime does not proxy remote MCP tools yet.",
      ),
    ).filterNotNull() + memoryToolDefinitions() + sessionToolDefinitions()
    val visibleCanonicalDefinitions = canonicalDefinitions
      .filter { definition -> !isToolHiddenByConfig(definition.name) }
      .let { visibleDefinitions ->
        allowedToolNames?.let { allowed ->
          visibleDefinitions.filter { definition -> definition.name in allowed }
        } ?: visibleDefinitions
      }
    val aliasDefinitions = toolCallNormalizer.aliasDefinitions(visibleCanonicalDefinitions)
    return visibleCanonicalDefinitions + aliasDefinitions
}

internal fun OpenCrayToolDispatcher.memoryToolDefinitions(): List<AgentToolDefinition> {
    if (config.memoryToolContext == null) {
      return emptyList()
    }
    return listOf(
      AgentToolDefinition(
        name = "memory_search",
        description = "Search the projected runtime memory corpus before answering prior-work questions about decisions, preferences, dates, people, paths, or todos. Use this first, then memory_get for a narrow snippet.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = true, description = "Search query describing the prior work or memory to retrieve."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of memory matches to return."),
          AgentToolParameter("min_score", "number", required = false, description = "Minimum relevance score for returned matches."),
        ),
      ),
      AgentToolDefinition(
        name = "memory_get",
        description = "Read a narrow line range from the projected runtime memory corpus after memory_search identifies the relevant path and line range.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Projected memory path such as MEMORY.md or memory/YYYY-MM-DD.md."),
          AgentToolParameter("from", "number", required = false, description = "1-based start line to read."),
          AgentToolParameter("lines", "number", required = false, description = "Maximum number of lines to read."),
        ),
      ),
    )
}

internal fun OpenCrayToolDispatcher.sessionToolDefinitions(): List<AgentToolDefinition> {
    if (config.sessionSearchToolContext == null) {
      return emptyList()
    }
    return listOf(
      AgentToolDefinition(
        name = "session_search",
        description = "Search projected prior-session history before answering questions about what happened in an earlier chat. The current session is excluded by default. Use this first, then session_get for a narrow snippet.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = true, description = "Search query describing the prior session context to retrieve."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of prior-session matches to return."),
          AgentToolParameter("min_score", "number", required = false, description = "Minimum relevance score for returned matches."),
        ),
      ),
      AgentToolDefinition(
        name = "session_get",
        description = "Read a narrow line range from the projected prior-session history corpus after session_search identifies the relevant path and line range.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Projected session path such as SESSIONS.md or sessions/<sessionId>.md."),
          AgentToolParameter("from", "number", required = false, description = "1-based start line to read."),
          AgentToolParameter("lines", "number", required = false, description = "Maximum number of lines to read."),
        ),
      ),
      AgentToolDefinition(
        name = "past_session_search",
        description = "Search the past-session archive explicitly. Returns per-session summary hits plus key snippet references so you can drill in with past_session_get.",
        parameters = listOf(
          AgentToolParameter("query", "string", required = true, description = "Search query describing what to retrieve from other archived sessions."),
          AgentToolParameter("max_results", "number", required = false, description = "Maximum number of archived session matches to return."),
          AgentToolParameter("min_score", "number", required = false, description = "Minimum relevance score for returned matches."),
        ),
      ),
      AgentToolDefinition(
        name = "past_session_get",
        description = "Read a narrow line range from one past-session archive reference returned by past_session_search.",
        parameters = listOf(
          AgentToolParameter("path", "string", required = true, description = "Projected archive path such as SESSIONS.md or sessions/<sessionId>.md."),
          AgentToolParameter("from", "number", required = false, description = "1-based start line to read."),
          AgentToolParameter("lines", "number", required = false, description = "Maximum number of lines to read."),
        ),
      ),
    )
}
