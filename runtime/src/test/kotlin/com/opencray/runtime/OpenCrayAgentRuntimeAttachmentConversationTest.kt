package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredFinalAttachment
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OpenCrayAgentRuntimeAttachmentConversationTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskSeedsStoredConversationIntoFirstLlmTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-history-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"延续了之前的对话"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          sessionPolicyText = "Keep the session coherent with earlier decisions.",
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
            RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "Earlier answer."),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What changed since then?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("延续了之前的对话", result.stdout)
    assertEquals("3", result.metadata["contextMessageCount"])
    assertFalse(result.metadata["contextLayerNames"].orEmpty().contains("Task Metadata"))
    assertTrue(result.metadata["contextLayerNames"].orEmpty().contains("Conversation"))
    assertTrue(gateway.requests[0].systemPrompt.orEmpty().contains("[Session Policy]"))
    assertTrue(gateway.requests[0].prompt.contains("Earlier question."))
    assertTrue(gateway.requests[0].prompt.contains("Earlier answer."))
    assertTrue(gateway.requests[0].prompt.contains("What changed since then?"))
  }

  @Test
  fun runPromptTaskCarriesFinalAttachmentsIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-final-attachments-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {
          "type": "final",
          "answer": "",
          "attachments": [
            {
              "kind": "image",
              "relative_path": "outputs/result.png",
              "display_name": "result.png",
              "mime_type": "image/png"
            },
            {
              "kind": "audio",
              "path": "outputs/voice-note.m4a",
              "duration_ms": 4200,
              "waveform_bars": [12, 48, 80],
              "transcript_text": "Voice summary"
            }
          ]
        }
        """.trimIndent(),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 2_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send the generated media only."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("", result.stdout)
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(2, attachments.size)
    assertEquals("outputs/result.png", attachments.first().relativePath)
    assertEquals("image", attachments.first().kind)
    assertEquals("outputs/voice-note.m4a", attachments.last().path)
    assertEquals("audio", attachments.last().kind)
    assertEquals(4_200L, attachments.last().durationMs)
    assertEquals(listOf(12, 48, 80), attachments.last().waveformBars)
    assertEquals("Voice summary", attachments.last().transcriptText)
  }

  @Test
  fun runPromptTaskCarriesNativeFinalAttachmentsIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-native-final-attachments-workspace")
    val gateway = ScriptedGateway(
      results = listOf(
        gatewaySuccessResult(
          outputText = "",
          completion = LiteLlmStructuredCompletion(
            finalText = "Attached native media.",
            finalAttachments = listOf(
              LiteLlmStructuredFinalAttachment(
                kind = " image ",
                artifactId = " artifact-native-image-1 ",
                displayName = " native.png ",
                mimeType = " image/png ",
              ),
              LiteLlmStructuredFinalAttachment(
                kind = "voice",
                relativePath = "outputs/native-voice.m4a",
                durationMs = 3_200L,
                waveformBars = listOf(10, 20, 30),
                transcriptText = " Native voice summary ",
              ),
            ),
          ),
        ),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 2_350L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send native media attachments."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Attached native media.", result.stdout)
    assertEquals("native_structured_final", result.metadata["responseFormat"])
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(2, attachments.size)
    assertEquals("artifact-native-image-1", attachments.first().artifactId)
    assertEquals("image", attachments.first().kind)
    assertEquals("native.png", attachments.first().displayName)
    assertEquals("image/png", attachments.first().mimeType)
    assertEquals("outputs/native-voice.m4a", attachments.last().relativePath)
    assertEquals("voice", attachments.last().kind)
    assertEquals(3_200L, attachments.last().durationMs)
    assertEquals(listOf(10, 20, 30), attachments.last().waveformBars)
    assertEquals("Native voice summary", attachments.last().transcriptText)
  }

  @Test
  fun runPromptTaskParsesChatAttachmentIdsFromFinalAttachments() {
    val workspaceRoot = temporaryFolder.newFolder("agent-chat-attachment-id-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {
          "type": "final",
          "answer": "Attached the uploaded image.",
          "attachments": [
            {
              "chat_attachment_id": "user-image-1",
              "kind": "image"
            }
          ]
        }
        """.trimIndent(),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 3_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send the uploaded image back."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Attached the uploaded image.", result.stdout)
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(1, attachments.size)
    assertEquals("user-image-1", attachments.single().chatAttachmentId)
    assertEquals("image", attachments.single().kind)
  }

  @Test
  fun runPromptTaskRebuildsGatewayAttachmentsFromHiddenPromptMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-hidden-attachment-metadata").toPath()
    val attachmentPath = workspaceRoot.resolve("imports").resolve("camera-first.png")
    Files.createDirectories(attachmentPath.parent)
    Files.write(attachmentPath, byteArrayOf(1, 2, 3, 4))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Saw the uploaded image."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      clock = IncrementingClock(start = 3_300L)::next,
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(RuntimeConversationAttachment.serializer()),
      listOf(
        RuntimeConversationAttachment(
          attachmentId = "user-image-1",
          kind = RuntimeConversationAttachmentKind.IMAGE,
          displayName = "camera-first.png",
          filePath = attachmentPath.toString().replace('\\', '/'),
          mimeType = "image/png",
        ),
      ),
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Attachment fallback placeholder",
        metadata = mapOf(
          "_host.promptUserText" to "",
          "_host.promptRuntimeAttachmentsJson" to attachmentsJson,
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val userMessage = gateway.requests.single().messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertEquals("", userMessage.content)
    assertEquals(1, userMessage.attachments.size)
    assertEquals("user-image-1", userMessage.attachments.single().attachmentId)
    assertEquals("camera-first.png", userMessage.attachments.single().displayName)
    assertEquals("image/png", userMessage.attachments.single().mimeType)
    assertEquals(attachmentPath.toString().replace('\\', '/'), userMessage.attachments.single().filePath)
  }

  @Test
  fun viewWorkspaceImageInjectsAttachmentIntoNextModelTurnAndInterruptsCurrentBatch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-image").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_image","arguments":{"path":"screens/camera-first.png"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace image after it was attached."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 3_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in screens/camera-first.png?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace image after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertTrue(attachedUserMessage.content.orEmpty().contains("screens/camera-first.png"))
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("camera-first.png", attachedUserMessage.attachments.single().displayName)
    assertEquals("image/png", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("post_tool_pre_model", supplementEvent.checkpoint)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = supplementEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    val supplementMessage = resumeState.transcript.last()
    assertEquals(RuntimeConversationRole.USER, supplementMessage.role)
    assertEquals(1, supplementMessage.attachments.size)
    assertEquals("camera-first.png", supplementMessage.attachments.single().displayName)
    assertEquals("image/png", supplementMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(supplementMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspacePdfInjectsAttachmentIntoNextModelTurnAndInterruptsCurrentBatch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-pdf").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_pdf","arguments":{"path":"docs/report.pdf"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace PDF after it was attached."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 4_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in docs/report.pdf?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace PDF after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertTrue(attachedUserMessage.content.orEmpty().contains("docs/report.pdf"))
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("report.pdf", attachedUserMessage.attachments.single().displayName)
    assertEquals("application/pdf", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("post_tool_pre_model", supplementEvent.checkpoint)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = supplementEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    val supplementMessage = resumeState.transcript.last()
    assertEquals(RuntimeConversationRole.USER, supplementMessage.role)
    assertEquals(1, supplementMessage.attachments.size)
    assertEquals("report.pdf", supplementMessage.attachments.single().displayName)
    assertEquals("application/pdf", supplementMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(supplementMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspaceDocumentInjectsImageIntoNextModelTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-document-image").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_document","arguments":{"path":"screens/camera-first.png"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace document image after it was attached."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      clock = IncrementingClock(start = 4_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in screens/camera-first.png?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace document image after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("camera-first.png", attachedUserMessage.attachments.single().displayName)
    assertEquals("image/png", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspaceDocumentInjectsPdfIntoNextModelTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-document-pdf").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_document","arguments":{"path":"docs/report.pdf"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace document PDF after it was attached."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      clock = IncrementingClock(start = 5_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in docs/report.pdf?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace document PDF after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("report.pdf", attachedUserMessage.attachments.single().displayName)
    assertEquals("application/pdf", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
  }
}
