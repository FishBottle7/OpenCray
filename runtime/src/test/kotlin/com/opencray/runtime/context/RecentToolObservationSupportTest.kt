package com.opencray.runtime.context

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.ScheduledTaskToolMetadataKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentToolObservationSupportTest {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  @Test
  fun buildLayerSkipsReplayOwnedControlPlaneObservations() {
    val support = RecentToolObservationSupport(
      config = RecentToolObservationConfig(
        maxEntries = 2,
        maxReadChars = 512,
        maxReadLines = 24,
        maxListChars = 512,
        maxListLines = 16,
      ),
    )

    val layer = support.buildLayer(
      listOf(
        toolResultMessage(
          toolName = "ScheduledTaskList",
          content = "Listed 1 scheduled task(s).",
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.RETURNED_COUNT to "1",
            ScheduledTaskToolMetadataKeys.TOTAL_COUNT to "2",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsFind",
          content = "ui-ux-pro-max\tremote\tinstall_ref=ui-ux-pro-max\tsource=skills.sh",
          metadata = mapOf(
            "query" to "ui",
            "providerName" to "skills.sh",
            "remoteResultCount" to "1",
            "localResultCount" to "0",
            "resultCount" to "1",
          ),
        ),
        toolResultMessage(
          toolName = "Task",
          content = "Subagent completed: README says hello.",
          metadata = mapOf(
            "delegationDescription" to "inspect readme",
            "delegationSubagentType" to "researcher",
            "delegationContextMode" to "minimal",
            "childExecutionState" to "completed",
            "childTurnCount" to "1",
            "childToolCallCount" to "1",
            "childSummaryHeadline" to "README says hello.",
          ),
        ),
      ),
    )

    assertNull(layer)
  }

  @Test
  fun buildLayerCompactSkipsReplayOwnedControlPlaneObservations() {
    val support = RecentToolObservationSupport(
      config = RecentToolObservationConfig(
        maxEntries = 3,
        maxCompactEntries = 2,
        maxReadChars = 512,
        maxReadLines = 24,
        maxListChars = 512,
        maxListLines = 16,
        maxCompactBodyChars = 128,
        maxCompactBodyLines = 6,
      ),
    )

    val layer = support.buildLayer(
      listOf(
        toolResultMessage(
          toolName = "ScheduledTaskList",
          content = "Listed 1 scheduled task(s).",
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.RETURNED_COUNT to "1",
            ScheduledTaskToolMetadataKeys.TOTAL_COUNT to "2",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsFind",
          content = "ui-ux-pro-max\tremote\tinstall_ref=ui-ux-pro-max\tsource=skills.sh",
          metadata = mapOf(
            "query" to "ui",
            "providerName" to "skills.sh",
            "remoteResultCount" to "1",
            "localResultCount" to "0",
            "resultCount" to "1",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsInspect",
          content =
            "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2\n" +
              "candidate\tui-ux-pro-max\tdescription=UI helpers\trelative_path=skills/ui-ux-pro-max\n" +
              "candidate\thumanizer\tdescription=Writing polish\trelative_path=skills/humanizer",
          metadata = mapOf(
            "sourceRef" to "github:opencray/skills",
            "sourceType" to "remote_github",
            "candidateCount" to "2",
          ),
        ),
      ),
    )

    assertNull(layer)
  }

  @Test
  fun buildLayerMinimalSkipsReplayOwnedControlPlaneObservations() {
    val support = RecentToolObservationSupport(
      config = RecentToolObservationConfig(
        maxEntries = 3,
        maxMinimalEntries = 1,
        maxReadChars = 512,
        maxReadLines = 24,
        maxListChars = 512,
        maxListLines = 16,
      ),
    )

    val layer = support.buildLayer(
      messages = listOf(
        toolResultMessage(
          toolName = "ScheduledTaskGet",
          content = "Scheduled task details.",
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to "schedule-nightly",
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.TRIGGER_KIND to "after",
            ScheduledTaskToolMetadataKeys.ENABLED to "true",
            ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT to "1",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsFind",
          content = "ui-ux-pro-max\tremote\tinstall_ref=ui-ux-pro-max\tsource=skills.sh",
          metadata = mapOf(
            "query" to "ui",
            "providerName" to "skills.sh",
            "remoteResultCount" to "1",
            "localResultCount" to "0",
            "resultCount" to "1",
          ),
        ),
      ),
      detailMode = RecentToolObservationDetailMode.MINIMAL,
    )

    assertNull(layer)
  }

  @Test
  fun findDuplicateDiscoveryCallStopsAtMutationBarrier() {
    val support = RecentToolObservationSupport()
    val duplicateCall = AgentToolCall(
      toolName = "Read",
      arguments = buildJsonObject {
        put("file_path", "README.md")
      },
    )

    val duplicateHit = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNotNull(duplicateHit)
    assertTrue(requireNotNull(duplicateHit).summaryLine.contains("Read file_path=README.md"))

    val blockedByMutation = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
        toolResultMessage(
          toolName = "Write",
          content = "Wrote README.md successfully.",
          metadata = mapOf(
            "filePath" to "README.md",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNull(blockedByMutation)
  }

  @Test
  fun buildLayerSkipsWorkspaceDiscoveryObservationsOwnedByReplay() {
    val support = RecentToolObservationSupport()

    val layer = support.buildLayer(
      listOf(
        toolResultMessage(
          toolName = "Read",
          content = "truncated body",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "12",
            "totalLineCount" to "100",
            "resultLimitApplied" to "true",
            "resultTruncated" to "true",
            "resultLimitKind" to "read_byte_budget",
          ),
        ),
        toolResultMessage(
          toolName = "Grep",
          content = "src/App.kt:12:needle",
          metadata = mapOf(
            "pattern" to "needle",
            "path" to "src",
            "matchCount" to "25",
            "resultLimitApplied" to "true",
            "resultTruncated" to "true",
            "resultLimitKind" to "search_match_limit",
          ),
        ),
      ),
    )

    assertNull(layer)
  }

  @Test
  fun duplicateReadGuardStillCarriesStableResultLimitMetadata() {
    val support = RecentToolObservationSupport()

    val duplicateHit = requireNotNull(
      support.findDuplicateDiscoveryCall(
        messages = listOf(
          toolResultMessage(
            toolName = "Read",
            content = "truncated body",
            metadata = mapOf(
              "filePath" to "README.md",
              "offset" to "1",
              "returnedLineCount" to "12",
              "totalLineCount" to "100",
              "resultLimitApplied" to "true",
              "resultTruncated" to "true",
              "resultLimitKind" to "read_byte_budget",
            ),
          ),
        ),
        call = AgentToolCall(
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "README.md")
          },
        ),
      ),
    )

    assertTrue(duplicateHit.summaryLine.contains("Read file_path=README.md"))
    assertTrue(duplicateHit.summaryLine.contains("truncated=true"))
    assertTrue(duplicateHit.summaryLine.contains("limit_kind=read_byte_budget"))
    assertTrue(duplicateHit.excerpt.contains("truncated body"))
  }

  @Test
  fun buildLayerSkipsWorkspacePackageInspectionObservationsOwnedByReplay() {
    val support = RecentToolObservationSupport()

    val layer = support.buildLayer(
      listOf(
        toolResultMessage(
          toolName = "inspect_workspace_package",
          content = "Workspace package inspection: docs/report.docx\nkind=docx entry_count=6 matched_entries=2 returned_entries=2 previews=1 media_entries=0",
          metadata = mapOf(
            "path" to "docs/report.docx",
            "packageKind" to "docx",
            "matchedEntryCount" to "2",
            "returnedEntryCount" to "2",
            "previewCount" to "1",
            "requestedGlob" to "word/**/*.xml",
            "requestedPreviewEntries" to "word/document.xml",
            "requestedMaxEntries" to "10",
            "previewChars" to "1200",
            "includeRelationshipHints" to "true",
            "resultLimitApplied" to "true",
            "resultTruncated" to "false",
            "resultLimitKind" to "directory_entry_limit",
          ),
        ),
      ),
    )

    assertNull(layer)
  }

  @Test
  fun buildLayerSkipsSkillsDiscoveryObservationsOwnedByReplay() {
    val support = RecentToolObservationSupport()

    val layer = support.buildLayer(
      listOf(
        toolResultMessage(
          toolName = "SkillsFind",
          content = "ui-ux-pro-max\tremote\tinstall_ref=ui-ux-pro-max\tsource=skills.sh",
          metadata = mapOf(
            "query" to "ui",
            "providerName" to "skills.sh",
            "remoteResultCount" to "1",
            "localResultCount" to "0",
            "resultCount" to "1",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsInspect",
          content =
            "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2\n" +
              "candidate\tui-ux-pro-max\tdescription=UI helpers\trelative_path=skills/ui-ux-pro-max\n" +
              "candidate\thumanizer\tdescription=Writing polish\trelative_path=skills/humanizer",
          metadata = mapOf(
            "sourceRef" to "github:opencray/skills",
            "sourceType" to "remote_github",
            "candidateCount" to "2",
          ),
        ),
      ),
    )

    assertNull(layer)
  }

  @Test
  fun buildLayerSkipsDelegatedTaskObservationsOwnedByReplay() {
    val support = RecentToolObservationSupport()

    val layer = support.buildLayer(
      listOf(
        toolResultMessage(
          toolName = "Task",
          content = "Subagent completed: README says hello.",
          metadata = mapOf(
            "delegationDescription" to "inspect readme",
            "delegationSubagentType" to "researcher",
            "delegationContextMode" to "minimal",
            "childExecutionState" to "completed",
            "childTurnCount" to "1",
            "childToolCallCount" to "1",
            "childSummaryHeadline" to "README says hello.",
            "childSummaryDetails" to "Read README.md\nSummarized the intro.",
          ),
        ),
      ),
    )

    assertNull(layer)
  }

  @Test
  fun workingStateEntriesSkipWorkspaceDiscoveryObservationsOwnedByReplay() {
    val support = RecentToolObservationSupport()

    val entries = support.workingStateEntries(
      listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
        toolResultMessage(
          toolName = "LS",
          content = "README.md",
          metadata = mapOf(
            "path" to ".",
            "entryCount" to "1",
          ),
        ),
      ),
    )

    assertTrue(entries.isEmpty())
  }

  @Test
  fun workingStateEntriesIncludeMutationExecutionAndTodoActions() {
    val support = RecentToolObservationSupport()

    val entries = support.workingStateEntries(
      listOf(
        toolResultMessage(
          toolName = "Write",
          content = "Wrote README.md successfully.",
          metadata = mapOf(
            "filePath" to "README.md",
            "targetSummary" to "README.md",
          ),
        ),
        toolResultMessage(
          toolName = "Bash",
          content = "Shell command finished.",
          metadata = mapOf(
            "shellCommand" to "git status",
            "workingDirectory" to ".",
          ),
        ),
        toolResultMessage(
          toolName = "ProcessWait",
          content = "process_id=proc-123\nstatus=success",
          metadata = mapOf(
            "processId" to "proc-123",
            "processStatus" to "SUCCESS",
            "command" to "npm",
            "exitCode" to "0",
          ),
        ),
        toolResultMessage(
          toolName = "TodoWrite",
          content = "[in_progress] Inspect README | active: Inspecting README",
          metadata = mapOf(
            "mutated" to "true",
            "planChanged" to "true",
            "todoCount" to "1",
            "activeTodoContent" to "Inspect README",
          ),
        ),
        toolResultMessage(
          toolName = "TodoWrite",
          content = "[in_progress] Inspect README | active: Inspecting README",
          metadata = mapOf(
            "mutated" to "false",
            "planChanged" to "false",
            "todoCount" to "1",
            "activeTodoContent" to "Inspect README",
          ),
        ),
      ),
    )

    assertEquals(4, entries.size)
    assertEquals("Write file_path=README.md", entries[0].text)
    assertEquals("workspace_mutation", entries[0].sourceType)
    assertEquals("Bash command=git status working_directory=.", entries[1].text)
    assertEquals("command_execution", entries[1].sourceType)
    assertEquals("ProcessWait process_id=proc-123 status=success command=npm exit_code=0", entries[2].text)
    assertEquals("process_execution", entries[2].sourceType)
    assertEquals("TodoWrite todos=1 changed=true active=Inspect README", entries[3].text)
    assertEquals("todo_management", entries[3].sourceType)
    assertFalse(entries.any { entry -> entry.text.contains("changed=false") })
  }

  @Test
  fun workingStateEntriesKeepManagedProcessBackendAndObservationHints() {
    val support = RecentToolObservationSupport()

    val entries = support.workingStateEntries(
      listOf(
        toolResultMessage(
          toolName = "ProcessRead",
          content = "process_id=proc-native\nstatus=running",
          metadata = mapOf(
            "processId" to "proc-native",
            "processStatus" to "RUNNING",
            "command" to "npm",
            "runtimeBackend" to "e2b_envd_native_command",
            "sandboxCommandObservationMode" to "host_managed_snapshot",
          ),
        ),
      ),
    )

    assertEquals(1, entries.size)
    assertEquals(
      "ProcessRead process_id=proc-native status=running command=npm backend=e2b_envd_native_command observation=host_managed_snapshot",
      entries.single().text,
    )
    assertEquals("process_execution", entries.single().sourceType)
  }

  @Test
  fun workingStateEntriesIncludeScheduledTaskCreateObservations() {
    val support = RecentToolObservationSupport()

    val entries = support.workingStateEntries(
      listOf(
        toolResultMessage(
          toolName = "ScheduledTaskCreate",
          content =
            """
            Scheduled task created.
            schedule_id=schedule-nightly
            session_id=session-main
            title=Nightly summary
            trigger_kind=after
            trigger_summary=after:PT1M
            enabled=true
            next_trigger_at_epoch_ms=61000
            """.trimIndent(),
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to "schedule-nightly",
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.TRIGGER_KIND to "after",
            ScheduledTaskToolMetadataKeys.ENABLED to "true",
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to "61000",
          ),
        ),
      ),
    )

    assertEquals(1, entries.size)
    assertEquals(
      "ScheduledTaskCreate trigger=after schedule=schedule-nightly session=session-main enabled=true next_at=61000",
      entries.single().text,
    )
    assertEquals("automation_scheduling", entries.single().sourceType)
  }

  @Test
  fun workingStateEntriesSkipScheduledTaskListAndGetObservationsOwnedByReplay() {
    val support = RecentToolObservationSupport()

    val entries = support.workingStateEntries(
      listOf(
        toolResultMessage(
          toolName = "ScheduledTaskList",
          content =
            """
            Listed 1 scheduled task(s) (session_mode=current_session total=1).
            schedule_id=schedule-nightly
            session_id=session-main
            title=Nightly summary
            trigger_kind=after
            trigger_summary=after:PT1M
            enabled=true
            next_trigger_at_epoch_ms=61000
            """.trimIndent(),
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.RETURNED_COUNT to "1",
            ScheduledTaskToolMetadataKeys.TOTAL_COUNT to "1",
          ),
        ),
        toolResultMessage(
          toolName = "ScheduledTaskGet",
          content = "Scheduled task details.",
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to "schedule-nightly",
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.TRIGGER_KIND to "after",
            ScheduledTaskToolMetadataKeys.ENABLED to "true",
            ScheduledTaskToolMetadataKeys.RECENT_RUN_COUNT to "1",
          ),
        ),
      ),
    )

    assertTrue(entries.isEmpty())
  }

  @Test
  fun workingStateEntriesIncludeScheduledTaskUpdateAndDeleteObservations() {
    val support = RecentToolObservationSupport()

    val entries = support.workingStateEntries(
      listOf(
        toolResultMessage(
          toolName = "ScheduledTaskUpdate",
          content = "Scheduled task updated.",
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to "schedule-weekly",
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.TRIGGER_KIND to "rrule",
            ScheduledTaskToolMetadataKeys.NEXT_TRIGGER_AT_EPOCH_MS to "171000",
          ),
        ),
        toolResultMessage(
          toolName = "ScheduledTaskDelete",
          content = "Scheduled task deleted.",
          metadata = mapOf(
            ScheduledTaskToolMetadataKeys.SCHEDULE_ID to "schedule-old",
            ScheduledTaskToolMetadataKeys.SESSION_ID to "session-main",
            ScheduledTaskToolMetadataKeys.TITLE to "Old reminder",
          ),
        ),
      ),
    )

    assertEquals(2, entries.size)
    assertEquals(
      "ScheduledTaskUpdate trigger=rrule schedule=schedule-weekly session=session-main next_at=171000",
      entries.first().text,
    )
    assertEquals(
      "ScheduledTaskDelete schedule=schedule-old session=session-main title=Old reminder",
      entries.last().text,
    )
  }

  @Test
  fun decisionAndBlockerEntriesProjectExplicitApprovalAndInterruptionSignals() {
    val support = RecentToolObservationSupport()

    val decisions = support.decisionEntries(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue the task."),
        toolResultMessage(
          toolName = "Write",
          content = "Approval required.",
          metadata = mapOf(
            "filePath" to "note.txt",
          ),
          status = AgentToolResultStatus.DENIED,
          errorCode = "APPROVAL_REQUIRED",
          errorMessage = "Approval required for Write before continuing.",
        ),
        plainReplayMessage(
          "approval_rejected task_id=task-1 run_id=run-1 tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
        ),
        plainReplayMessage(
          "approval_approved task_id=task-1 run_id=run-1 tool_name=Write outcome=user_approved executed=false next_step=agent_resumed",
        ),
        plainReplayMessage(
          "run_interrupted task_id=task-1 run_id=run-2 tool_name=Bash outcome=user_interrupted executed=false next_step=await_user_instruction",
        ),
        plainReplayMessage(
          "retry_abandoned task_id=task-1 run_id=run-3 outcome=retry_budget_exhausted attempt=2 error_code=TOOL_EXECUTION_FAILED next_step=await_user_instruction",
        ),
      ),
    )
    val blockers = support.blockerEntries(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue the task."),
        toolResultMessage(
          toolName = "Write",
          content = "Approval required.",
          metadata = mapOf(
            "filePath" to "note.txt",
          ),
          status = AgentToolResultStatus.DENIED,
          errorCode = "APPROVAL_REQUIRED",
          errorMessage = "Approval required for Write before continuing.",
        ),
        plainReplayMessage(
          "approval_rejected task_id=task-1 run_id=run-1 tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
        ),
        plainReplayMessage(
          "approval_approved task_id=task-1 run_id=run-1 tool_name=Write outcome=user_approved executed=false next_step=agent_resumed",
        ),
        plainReplayMessage(
          "run_interrupted task_id=task-1 run_id=run-2 tool_name=Bash outcome=user_interrupted executed=false next_step=await_user_instruction",
        ),
        plainReplayMessage(
          "retry_abandoned task_id=task-1 run_id=run-3 outcome=retry_budget_exhausted attempt=2 error_code=TOOL_EXECUTION_FAILED next_step=await_user_instruction",
        ),
      ),
      maxEntries = 4,
    )

    assertEquals(
      listOf(
        "Do not retry Write automatically; wait for new instruction.",
        "Approval granted for Write; resume from saved checkpoint.",
        "Do not auto-rerun from task input; wait for explicit resume or new instruction.",
      ),
      decisions.map { entry -> entry.text },
    )
    assertEquals(
      listOf(
        "Approval required for Write before continuing.",
        "User rejected approval for Write; await new instruction.",
        "Run interrupted during Bash; await user instruction.",
        "Retry path exhausted after repeated failure; await explicit resume or new instruction.",
      ),
      blockers.map { entry -> entry.text },
    )
    assertEquals("TOOL_EXECUTION_FAILED", blockers.last().rationale)
    assertEquals(
      listOf("approval_decision", "approval_decision", "retry_decision"),
      decisions.map { entry -> entry.sourceType },
    )
    assertEquals(
      listOf("approval_boundary", "approval_boundary", "execution_blocker", "execution_blocker"),
      blockers.map { entry -> entry.sourceType },
    )
  }

  @Test
  fun findDuplicateDiscoveryCallSkipsDelegationObservationButStillStopsAtMutationBarrier() {
    val support = RecentToolObservationSupport()
    val duplicateCall = AgentToolCall(
      toolName = "Read",
      arguments = buildJsonObject {
        put("file_path", "README.md")
      },
    )

    val duplicateHit = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
        toolResultMessage(
          toolName = "Task",
          content = "Subagent completed: README says hello.",
          metadata = mapOf(
            "delegationDescription" to "inspect readme",
            "delegationSubagentType" to "researcher",
            "childExecutionState" to "completed",
            "childSummaryHeadline" to "README says hello.",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNotNull(duplicateHit)
    assertTrue(requireNotNull(duplicateHit).summaryLine.contains("Read file_path=README.md"))

    val blockedByMutation = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
        toolResultMessage(
          toolName = "Task",
          content = "Subagent completed: README says hello.",
          metadata = mapOf(
            "delegationDescription" to "inspect readme",
            "delegationSubagentType" to "researcher",
            "childExecutionState" to "completed",
            "childSummaryHeadline" to "README says hello.",
          ),
        ),
        toolResultMessage(
          toolName = "Write",
          content = "Wrote README.md successfully.",
          metadata = mapOf(
            "filePath" to "README.md",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNull(blockedByMutation)
  }

  @Test
  fun findDuplicateSkillsInspectCallStopsAtSkillsMutationBarrier() {
    val support = RecentToolObservationSupport()
    val duplicateCall = AgentToolCall(
      toolName = "SkillsInspect",
      arguments = buildJsonObject {
        put("source_ref", "github:opencray/skills")
      },
    )

    val duplicateHit = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "SkillsInspect",
          content = "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2",
          metadata = mapOf(
            "sourceRef" to "github:opencray/skills",
            "sourceType" to "remote_github",
            "candidateCount" to "2",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNotNull(duplicateHit)
    assertTrue(requireNotNull(duplicateHit).summaryLine.contains("SkillsInspect source_ref=github:opencray/skills"))

    val blockedByMutation = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "SkillsInspect",
          content = "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2",
          metadata = mapOf(
            "sourceRef" to "github:opencray/skills",
            "sourceType" to "remote_github",
            "candidateCount" to "2",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsAdd",
          content = "Installed skill 'ui-ux-pro-max' from the host-managed catalog.",
          metadata = mapOf(
            "skillId" to "ui-ux-pro-max",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNull(blockedByMutation)
  }

  @Test
  fun findDuplicateWorkspacePackageInspectCallStopsAtExtractionBarrier() {
    val support = RecentToolObservationSupport()
    val duplicateCall = AgentToolCall(
      toolName = "inspect_workspace_package",
      arguments = buildJsonObject {
        put("path", "docs/report.docx")
        put("glob", "word/**/*.xml")
        put("max_entries", 10)
        put("preview_chars", 1200)
        put("include_relationship_hints", true)
      },
    )

    val duplicateHit = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "inspect_workspace_package",
          content = "Workspace package inspection: docs/report.docx",
          metadata = mapOf(
            "path" to "docs/report.docx",
            "packageKind" to "docx",
            "matchedEntryCount" to "2",
            "returnedEntryCount" to "2",
            "previewCount" to "1",
            "requestedGlob" to "word/**/*.xml",
            "requestedMaxEntries" to "10",
            "previewChars" to "1200",
            "includeRelationshipHints" to "true",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNotNull(duplicateHit)
    assertTrue(requireNotNull(duplicateHit).summaryLine.contains("inspect_workspace_package path=docs/report.docx"))

    val blockedByExtraction = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "inspect_workspace_package",
          content = "Workspace package inspection: docs/report.docx",
          metadata = mapOf(
            "path" to "docs/report.docx",
            "packageKind" to "docx",
            "matchedEntryCount" to "2",
            "returnedEntryCount" to "2",
            "previewCount" to "1",
            "requestedGlob" to "word/**/*.xml",
            "requestedMaxEntries" to "10",
            "previewChars" to "1200",
            "includeRelationshipHints" to "true",
          ),
        ),
        toolResultMessage(
          toolName = "extract_workspace_package",
          content = "Workspace package extraction: docs/report.docx",
          metadata = mapOf(
            "path" to "docs/report.docx",
            "destinationDir" to "tmp/report-docx",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNull(blockedByExtraction)
  }

  private fun toolResultMessage(
    toolName: String,
    content: String,
    metadata: Map<String, String>,
    status: AgentToolResultStatus = AgentToolResultStatus.SUCCESS,
    errorCode: String? = null,
    errorMessage: String? = null,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    content = AgentToolResult(
      toolName = toolName,
      status = status,
      content = content,
      metadata = metadata,
      errorCode = errorCode,
      errorMessage = errorMessage,
    ).toObservationText(json),
    kind = RuntimeConversationMessageKind.TOOL_RESULT,
    toolResult = RuntimeConversationToolResult(
      toolName = toolName,
      status = status.name.lowercase(),
      isError = status != AgentToolResultStatus.SUCCESS,
    ),
  )

  private fun plainReplayMessage(
    content: String,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    content = content,
  )
}
