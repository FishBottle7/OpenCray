package com.opencray.app

internal class HostLocalHostGatewayImpl(
  private val host: OpenCrayHostRuntime,
) : OpenCrayLocalHostGateway {
  override fun loadFilesSnapshot(): Map<String, Any?> =
    host.localHostGateway.loadFilesSnapshot()

  override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> =
    host.localHostGateway.loadWorkspaceImagePreview(relativePath)

  override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> =
    host.localHostGateway.loadWorkspaceTextPreview(relativePath)

  override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> =
    host.localHostGateway.loadWorkspaceVoicePlaybackSource(relativePath)

  override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> =
    host.localHostGateway.loadWorkspaceTextDocument(relativePath)

  override fun openWorkspaceEntry(relativePath: String) {
    host.localHostGateway.openWorkspaceEntry(relativePath)
  }

  override fun openExternalUri(uri: String) {
    host.localHostGateway.openExternalUri(uri)
  }

  override fun copyRichTextToClipboard(plainText: String, htmlText: String?) {
    host.localHostGateway.copyRichTextToClipboard(plainText = plainText, htmlText = htmlText)
  }

  override fun createWorkspaceFolder(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = host.localHostGateway.createWorkspaceFolder(parentRelativePath, name)

  override fun createWorkspaceTextFile(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = host.localHostGateway.createWorkspaceTextFile(parentRelativePath, name)

  override fun renameWorkspaceEntry(
    targetRelativePath: String,
    newName: String,
  ): Map<String, Any?> = host.localHostGateway.renameWorkspaceEntry(targetRelativePath, newName)

  override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> =
    host.localHostGateway.deleteWorkspaceEntries(relativePaths)

  override fun saveWorkspaceTextDocument(
    targetRelativePath: String,
    content: String,
  ): Map<String, Any?> = host.localHostGateway.saveWorkspaceTextDocument(targetRelativePath, content)

  override fun pasteWorkspaceEntries(
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ): Map<String, Any?> = host.localHostGateway.pasteWorkspaceEntries(
    sourceRelativePaths = sourceRelativePaths,
    destinationRelativePath = destinationRelativePath,
    move = move,
  )

  override fun shareWorkspaceEntries(relativePaths: List<String>) {
    host.localHostGateway.shareWorkspaceEntries(relativePaths)
  }

  override fun saveWorkspaceMediaAttachment(
    relativePath: String,
    kind: String,
  ): Map<String, Any?> = host.localHostGateway.saveWorkspaceMediaAttachment(relativePath, kind)

  override fun showNativeToast(message: String) {
    host.localHostGateway.showNativeToast(message)
  }

  override fun importDraftChatAttachments(
    requestedKind: String,
    uriStrings: List<String>,
  ): List<Map<String, Any?>> = host.localHostGateway.importDraftChatAttachments(
    requestedKind = requestedKind,
    uriStrings = uriStrings,
  )

  override fun probeTwinImportSource(filePath: String): Map<String, Any?> =
    host.localHostGateway.probeTwinImportSource(filePath)
}
