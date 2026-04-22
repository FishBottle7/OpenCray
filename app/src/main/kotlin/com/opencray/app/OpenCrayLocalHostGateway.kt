package com.opencray.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.opencray.app.agent.AppAgentHostServices
import com.opencray.runtime.OpenCrayImageReference
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

internal interface OpenCrayLocalHostGateway {
  fun loadFilesSnapshot(): Map<String, Any?>

  fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?>

  fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?>

  fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?>

  fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?>

  fun openWorkspaceEntry(relativePath: String)

  fun openExternalUri(uri: String)

  fun copyRichTextToClipboard(plainText: String, htmlText: String?)

  fun createWorkspaceFolder(parentRelativePath: String, name: String): Map<String, Any?>

  fun createWorkspaceTextFile(parentRelativePath: String, name: String): Map<String, Any?>

  fun renameWorkspaceEntry(targetRelativePath: String, newName: String): Map<String, Any?>

  fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?>

  fun saveWorkspaceTextDocument(targetRelativePath: String, content: String): Map<String, Any?>

  fun pasteWorkspaceEntries(
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ): Map<String, Any?>

  fun shareWorkspaceEntries(relativePaths: List<String>)

  fun showNativeToast(message: String)

  fun resolveSandboxPreviewEmbedConfig(previewUrl: String): Map<String, Any?> =
    throw UnsupportedOperationException("Sandbox preview embedding is unavailable.")

  fun importDraftChatAttachments(
    requestedKind: String,
    uriStrings: List<String>,
  ): List<Map<String, Any?>>

  fun probeTwinImportSource(filePath: String): Map<String, Any?>

  fun listSettingsImageAssets(): List<Map<String, Any?>> =
    throw UnsupportedOperationException("Settings image asset access is unavailable.")

  fun importSettingsImageAssets(uriStrings: List<String>): List<Map<String, Any?>> =
    throw UnsupportedOperationException("Settings image asset import is unavailable.")

  fun listAgents(): List<Map<String, Any?>> =
    throw UnsupportedOperationException("Agent management is unavailable.")

  fun loadActiveAgent(): Map<String, Any?>? =
    throw UnsupportedOperationException("Agent management is unavailable.")

  fun createAgent(payload: Map<String, Any?>): Map<String, Any?> =
    throw UnsupportedOperationException("Agent management is unavailable.")

  fun selectAgent(agentId: String): Map<String, Any?>? =
    throw UnsupportedOperationException("Agent management is unavailable.")

  fun loadSoulVisualIdentity(): Map<String, Any?>? =
    throw UnsupportedOperationException("Soul visual identity access is unavailable.")

  fun saveSoulPrimaryPortrait(source: Map<String, Any?>): Map<String, Any?>? =
    throw UnsupportedOperationException("Soul visual identity updates are unavailable.")

  fun saveSoulReferenceImage(
    refId: String,
    source: Map<String, Any?>,
  ): Map<String, Any?>? = throw UnsupportedOperationException("Soul visual identity updates are unavailable.")

  fun attachMemoryImageReference(
    memoryId: String,
    source: Map<String, Any?>,
    preferredMode: String? = null,
  ): Map<String, Any?>? = throw UnsupportedOperationException("Memory image reference updates are unavailable.")

  fun listMemoryImageReferences(memoryId: String): List<Map<String, Any?>> =
    throw UnsupportedOperationException("Memory image reference access is unavailable.")
}

internal class DefaultOpenCrayLocalHostGateway(
  private val appContext: Context?,
  private val workspaceRootProvider: (() -> Path)?,
  private val workspaceEntryOpener: ((Path, String) -> Unit)? = null,
  private val externalUriOpener: ((String) -> Unit)? = null,
  private val workspaceSnapshotProvider: () -> Map<String, Any?>,
  private val sandboxPreviewEmbedConfigServiceProvider: (() -> SandboxPreviewEmbedConfigService?)? = null,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCrayLocalHostGateway {
  private val lock = Any()
  private val imageReferenceHostServices: AppImageReferenceHostServices? by lazy(LazyThreadSafetyMode.NONE) {
    appContext?.let { context ->
      AppImageReferenceHostServices.fromContext(context) {
        workspaceRootProvider?.invoke()
      }
    }
  }
  private val agentHostServices: AppAgentHostServices? by lazy(LazyThreadSafetyMode.NONE) {
    appContext?.let(AppAgentHostServices::fromContext)
  }
  private val sandboxPreviewEmbedConfigService: SandboxPreviewEmbedConfigService? by lazy(LazyThreadSafetyMode.NONE) {
    sandboxPreviewEmbedConfigServiceProvider?.invoke()
      ?: appContext?.let(::defaultSandboxPreviewEmbedConfigService)
  }

  override fun loadFilesSnapshot(): Map<String, Any?> = synchronized(lock) {
    workspaceSnapshotProvider()
  }

  override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceImagePreviewer.loadPreview(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextPreviewer.loadPreview(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceVoicePlaybackLoader.loadSource(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextDocumentStore.loadDocument(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  override fun openWorkspaceEntry(relativePath: String) {
    synchronized(lock) {
      val workspaceRoot = requireWorkspaceRoot()
      workspaceEntryOpener?.invoke(workspaceRoot, relativePath)
        ?: AppAgentWorkspaceOpener.openEntry(
          appContext = requireNotNull(appContext) {
            "Workspace file operations are unavailable."
          },
          workspaceRoot = workspaceRoot,
          relativePath = relativePath,
        )
    }
  }

  override fun openExternalUri(uri: String) {
    synchronized(lock) {
      externalUriOpener?.invoke(uri)
        ?: AppExternalUriOpener.openUri(
          appContext = requireNotNull(appContext) {
            "External link handling is unavailable."
          },
          uri = uri,
        )
    }
  }

  override fun copyRichTextToClipboard(plainText: String, htmlText: String?) {
    if (plainText.isEmpty() && htmlText.isNullOrBlank()) {
      return
    }
    val context = requireNotNull(appContext) {
      "Clipboard access is unavailable."
    }
    val copyAction = {
      val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: throw IllegalStateException("Clipboard access is unavailable.")
      val clip = htmlText?.takeIf(String::isNotBlank)?.let { richHtml ->
        ClipData.newHtmlText("OpenCray", plainText, richHtml)
      } ?: ClipData.newPlainText("OpenCray", plainText)
      clipboard.setPrimaryClip(clip)
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      copyAction()
      return
    }
    val completion = CountDownLatch(1)
    var failure: Throwable? = null
    mainThreadPoster.post {
      runCatching(copyAction)
        .onFailure { throwable -> failure = throwable }
      completion.countDown()
    }
    completion.await()
    failure?.let { throwable -> throw throwable }
  }

  override fun createWorkspaceFolder(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.createDirectory(
      workspaceRoot = requireWorkspaceRoot(),
      parentRelativePath = parentRelativePath,
      name = name,
    )
    workspaceSnapshotProvider()
  }

  override fun createWorkspaceTextFile(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextDocumentStore.createFile(
      workspaceRoot = requireWorkspaceRoot(),
      parentRelativePath = parentRelativePath,
      name = name,
    )
    workspaceSnapshotProvider()
  }

  override fun renameWorkspaceEntry(
    targetRelativePath: String,
    newName: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.renameEntry(
      workspaceRoot = requireWorkspaceRoot(),
      targetRelativePath = targetRelativePath,
      newName = newName,
    )
    workspaceSnapshotProvider()
  }

  override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.deleteEntries(
      workspaceRoot = requireWorkspaceRoot(),
      relativePaths = relativePaths,
    )
    workspaceSnapshotProvider()
  }

  override fun saveWorkspaceTextDocument(
    targetRelativePath: String,
    content: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextDocumentStore.saveDocument(
      workspaceRoot = requireWorkspaceRoot(),
      targetRelativePath = targetRelativePath,
      content = content,
    )
    workspaceSnapshotProvider()
  }

  override fun pasteWorkspaceEntries(
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.pasteEntries(
      workspaceRoot = requireWorkspaceRoot(),
      sourceRelativePaths = sourceRelativePaths,
      destinationRelativePath = destinationRelativePath,
      move = move,
    )
    workspaceSnapshotProvider()
  }

  override fun shareWorkspaceEntries(relativePaths: List<String>) {
    val context = requireNotNull(appContext) {
      "Workspace sharing is unavailable."
    }
    val shareAction = {
      synchronized(lock) {
        AppAgentWorkspaceSharer.shareEntries(
          appContext = context,
          workspaceRoot = requireWorkspaceRoot(),
          relativePaths = relativePaths,
        )
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      shareAction()
      return
    }
    val completion = CountDownLatch(1)
    var failure: Throwable? = null
    mainThreadPoster.post {
      runCatching(shareAction)
        .onFailure { throwable -> failure = throwable }
      completion.countDown()
    }
    completion.await()
    failure?.let { throwable -> throw throwable }
  }

  override fun showNativeToast(message: String) {
    val normalizedMessage = message.trim()
    if (normalizedMessage.isEmpty()) {
      return
    }
    val context = appContext ?: return
    val showAction = {
      Toast.makeText(context, normalizedMessage, Toast.LENGTH_SHORT).show()
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      showAction()
      return
    }
    val completion = CountDownLatch(1)
    var failure: Throwable? = null
    mainThreadPoster.post {
      runCatching(showAction)
        .onFailure { throwable -> failure = throwable }
      completion.countDown()
    }
    completion.await()
    failure?.let { throwable -> throw throwable }
  }

  override fun resolveSandboxPreviewEmbedConfig(previewUrl: String): Map<String, Any?> = synchronized(lock) {
    val workspaceRoot = requireWorkspaceRoot()
    return sandboxPreviewEmbedConfigService
      ?.resolve(
        previewUrl = previewUrl,
        workspaceRoot = workspaceRoot,
      )
      ?.toMap()
      ?: SandboxPreviewEmbedConfig(
        previewUrl = previewUrl.trim(),
        providerId = "",
        sessionMatched = false,
        accessTokenConfigured = false,
        unavailableReason = "Sandbox preview embedding is unavailable.",
      ).toMap()
  }

  override fun importDraftChatAttachments(
    requestedKind: String,
    uriStrings: List<String>,
  ): List<Map<String, Any?>> {
    val workspaceRoot = workspaceRootProvider?.invoke()
      ?: throw IllegalStateException("Attachment import is unavailable because the workspace is not ready.")
    val context = appContext
      ?: throw IllegalStateException("Attachment import is unavailable on this host.")
    val imported = AppChatAttachmentDraftImporter.importAttachments(
      appContext = context,
      workspaceRoot = workspaceRoot,
      requestedKind = requestedKind,
      uriStrings = uriStrings,
    )
    if (uriStrings.isNotEmpty() && imported.isEmpty()) {
      throw IllegalStateException("Unable to import the selected attachments.")
    }
    return imported.map(::chatDraftAttachmentMap)
  }

  override fun probeTwinImportSource(filePath: String): Map<String, Any?> =
    TwinImportSourceProbe.inspect(filePath).toMap()

  override fun listSettingsImageAssets(): List<Map<String, Any?>> = synchronized(lock) {
    requireImageReferenceHostServices()
      .listSettingsImageAssets()
      .map(AppSettingsImageAsset::toMap)
  }

  override fun importSettingsImageAssets(uriStrings: List<String>): List<Map<String, Any?>> = synchronized(lock) {
    requireImageReferenceHostServices()
      .importSettingsImageAssets(uriStrings)
      .map(AppSettingsImageAsset::toMap)
  }

  override fun listAgents(): List<Map<String, Any?>> = synchronized(lock) {
    requireAgentHostServices().listAgents()
  }

  override fun loadActiveAgent(): Map<String, Any?>? = synchronized(lock) {
    requireAgentHostServices().loadActiveAgent()
  }

  override fun createAgent(payload: Map<String, Any?>): Map<String, Any?> = synchronized(lock) {
    requireAgentHostServices().createAgent(payload)
  }

  override fun selectAgent(agentId: String): Map<String, Any?>? = synchronized(lock) {
    requireAgentHostServices().selectAgent(agentId)
  }

  override fun loadSoulVisualIdentity(): Map<String, Any?>? = synchronized(lock) {
    requireImageReferenceHostServices()
      .loadSoulVisualIdentity()
      ?.toMap()
  }

  override fun saveSoulPrimaryPortrait(source: Map<String, Any?>): Map<String, Any?>? = synchronized(lock) {
    val parsedSource = parseOpenCrayImageReferenceSource(source)
      ?: throw IllegalArgumentException("Invalid image reference source payload.")
    return requireImageReferenceHostServices()
      .saveSoulPrimaryPortrait(parsedSource)
      ?.toMap()
  }

  override fun saveSoulReferenceImage(
    refId: String,
    source: Map<String, Any?>,
  ): Map<String, Any?>? = synchronized(lock) {
    val parsedSource = parseOpenCrayImageReferenceSource(source)
      ?: throw IllegalArgumentException("Invalid image reference source payload.")
    return requireImageReferenceHostServices()
      .saveSoulReferenceImage(
        refId = refId,
        source = parsedSource,
      )
      ?.toMap()
  }

  override fun attachMemoryImageReference(
    memoryId: String,
    source: Map<String, Any?>,
    preferredMode: String?,
  ): Map<String, Any?>? = synchronized(lock) {
    val parsedSource = parseOpenCrayImageReferenceSource(source)
      ?: throw IllegalArgumentException("Invalid image reference source payload.")
    return requireImageReferenceHostServices()
      .attachMemoryImageReference(
        memoryId = memoryId,
        source = parsedSource,
        preferredMode = parseAppImageReferencePromotionMode(preferredMode),
      )
      ?.toMemoryImageReferenceResultMap()
  }

  override fun listMemoryImageReferences(memoryId: String): List<Map<String, Any?>> = synchronized(lock) {
    return requireImageReferenceHostServices()
      .listMemoryImageReferences(memoryId)
      .map(OpenCrayImageReference::toMap)
  }

  private fun requireWorkspaceRoot(): Path =
    requireNotNull(workspaceRootProvider?.invoke()) {
      "Workspace file operations are unavailable."
    }.toAbsolutePath().normalize()

  private fun requireImageReferenceHostServices(): AppImageReferenceHostServices =
    requireNotNull(imageReferenceHostServices) {
      "Image reference host services are unavailable."
    }

  private fun requireAgentHostServices(): AppAgentHostServices =
    requireNotNull(agentHostServices) {
      "Agent host services are unavailable."
    }
}

internal object OpenCrayLocalHostGatewayRegistry {
  @Volatile
  private var instance: OpenCrayLocalHostGateway? = null

  fun fromContext(context: Context): OpenCrayLocalHostGateway {
    val appContext = context.applicationContext
    return instance ?: synchronized(this) {
      instance ?: createFromContext(appContext).also { created ->
        instance = created
      }
    }
  }

  private fun createFromContext(appContext: Context): OpenCrayLocalHostGateway {
    val dependencies = loadOpenCrayRuntimeContextDependencies(appContext)
    return DefaultOpenCrayLocalHostGateway(
      appContext = appContext,
      workspaceRootProvider = dependencies.workspaceRootProvider,
      workspaceSnapshotProvider = dependencies.workspaceSnapshotProvider,
      sandboxPreviewEmbedConfigServiceProvider = {
        defaultSandboxPreviewEmbedConfigService(appContext)
      },
      mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
    )
  }
}

internal fun openCrayLocalHostGateway(context: Context): OpenCrayLocalHostGateway =
  OpenCrayLocalHostGatewayRegistry.fromContext(context.applicationContext)

private fun defaultSandboxPreviewEmbedConfigService(
  appContext: Context,
): SandboxPreviewEmbedConfigService {
  val sandboxSettingsRepository = SandboxSettingsRepository.fromContext(appContext)
  val sessionStore = E2BSandboxSessionStore.fromContext(appContext)
  return E2BSandboxPreviewEmbedConfigService(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = sessionStore,
  )
}

private fun chatDraftAttachmentMap(
  attachment: ImportedChatAttachmentDraft,
): Map<String, Any?> = buildMap {
  put("kind", attachment.kind)
  put("displayName", attachment.displayName)
  put("relativePath", attachment.relativePath)
  attachment.mimeType?.let { mimeType -> put("mimeType", mimeType) }
  attachment.sizeBytes?.let { sizeBytes -> put("sizeBytes", sizeBytes) }
}
