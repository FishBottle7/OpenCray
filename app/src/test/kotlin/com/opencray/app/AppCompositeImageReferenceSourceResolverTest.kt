package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppCompositeImageReferenceSourceResolverTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun resolveUsesChatAttachmentLookupForUserSentImages() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-workspace-chat").toPath()
    val privateRoot = temporaryFolder.newFolder("resolver-private-chat").toPath()
    val attachment = ChatAttachmentEntry(
      attachmentId = "chat-image-1",
      kind = ChatAttachmentKind.IMAGE,
      displayName = "whiteboard.png",
      localPath = ".opencray/chat-media/session-1/sha/whiteboard.png",
      mimeType = "image/png",
    )
    val attachmentPath = writeFile(workspaceRoot, attachment.localPath)
    val resolver = AppCompositeImageReferenceSourceResolver(
      workspaceRoot = workspaceRoot,
      privateRoot = privateRoot,
      chatAttachmentLookup = { source ->
        if (source.chatAttachmentId == attachment.attachmentId) {
          attachment.toAppResolvedImageAssetHandle(
            workspaceRoot = workspaceRoot,
            sourceSessionId = "session-1",
            sourceMessageId = "message-1",
          )
        } else {
          null
        }
      },
    )

    val resolved = resolver.resolve(
      OpenCrayImageReferenceSource(
        sourceKind = OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT,
        chatAttachmentId = "chat-image-1",
      ),
    )

    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals(attachmentPath.toAbsolutePath().normalize(), resolved.path)
    assertEquals("whiteboard.png", resolved.displayName)
    assertEquals("image/png", resolved.mimeType)
    assertEquals("session-1", resolved.sourceSessionId)
    assertEquals("message-1", resolved.sourceMessageId)
  }

  @Test
  fun resolveUsesArtifactLookupForAgentGeneratedImages() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-workspace-artifact").toPath()
    val privateRoot = temporaryFolder.newFolder("resolver-private-artifact").toPath()
    val artifact = OpenCrayAttachmentArtifact(
      artifactId = "artifact-render-1",
      relativePath = "media/generated/agent/render.png",
      displayName = "render.png",
      kindHint = "image",
      mimeType = "image/png",
    )
    val artifactPath = writeFile(workspaceRoot, artifact.relativePath)
    val resolver = AppCompositeImageReferenceSourceResolver(
      workspaceRoot = workspaceRoot,
      privateRoot = privateRoot,
      runArtifactLookup = { source ->
        if (source.artifactId == artifact.artifactId) {
          artifact.toAppResolvedImageAssetHandle(workspaceRoot)
        } else {
          null
        }
      },
    )

    val resolved = resolver.resolve(
      OpenCrayImageReferenceSource(
        sourceKind = OpenCrayImageReferenceSourceKind.RUN_ARTIFACT,
        artifactId = "artifact-render-1",
      ),
    )

    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals(artifactPath.toAbsolutePath().normalize(), resolved.path)
    assertEquals("render.png", resolved.displayName)
    assertEquals("image/png", resolved.mimeType)
  }

  @Test
  fun resolveUsesSettingsLookupForAgentSettingsImage() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-workspace-settings").toPath()
    val privateRoot = temporaryFolder.newFolder("resolver-private-settings").toPath()
    val settingsPath = writeFile(workspaceRoot, ".opencray/settings/avatar.png")
    val resolver = AppCompositeImageReferenceSourceResolver(
      workspaceRoot = workspaceRoot,
      privateRoot = privateRoot,
      settingsAssetLookup = { source ->
        if (source.settingsAssetId == "settings-avatar-1") {
          AppResolvedImageAssetHandle(
            path = settingsPath,
            displayName = "avatar.png",
            mimeType = "image/png",
          )
        } else {
          null
        }
      },
    )

    val resolved = resolver.resolve(
      OpenCrayImageReferenceSource(
        sourceKind = OpenCrayImageReferenceSourceKind.SETTINGS_ASSET,
        settingsAssetId = "settings-avatar-1",
      ),
    )

    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals(settingsPath.toAbsolutePath().normalize(), resolved.path)
    assertEquals("avatar.png", resolved.displayName)
  }

  @Test
  fun artifactHelperRejectsNonImageArtifacts() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-workspace-non-image").toPath()
    writeFile(workspaceRoot, "docs/spec.txt")

    val resolved = OpenCrayAttachmentArtifact(
      artifactId = "artifact-spec-1",
      relativePath = "docs/spec.txt",
      displayName = "spec.txt",
      kindHint = "file",
      mimeType = "text/plain",
    ).toAppResolvedImageAssetHandle(workspaceRoot)

    assertNull(resolved)
  }

  private fun writeFile(
    root: Path,
    relativePath: String,
  ): Path {
    val target = root.resolve(relativePath).normalize()
    Files.createDirectories(requireNotNull(target.parent))
    Files.write(target, byteArrayOf(1, 2, 3, 4))
    return target
  }
}
