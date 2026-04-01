package com.opencray.app

import android.content.Context
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCraySoulVisualIdentity
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

internal class AppImageReferenceHostServices(
  private val workspaceRootProvider: () -> Path?,
  private val filesDir: File,
  private val settingsImageAssetStore: AppSettingsImageAssetStore,
  private val settingsImageAssetImportService: AppSettingsImageAssetImportService,
  private val chatSessionStore: ChatSessionLocalStore,
  private val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  private val memoryStore: MemoryStore,
  private val soulProfileStore: WorkspaceSoulProfileStore,
  private val summaryExtractor: AppImageSummaryExtractor,
) {
  fun listSettingsImageAssets(): List<AppSettingsImageAsset> = settingsImageAssetStore.list()

  fun importSettingsImageAssets(uriStrings: List<String>): List<AppSettingsImageAsset> =
    settingsImageAssetImportService.import(uriStrings)

  fun loadSoulVisualIdentity(): OpenCraySoulVisualIdentity? =
    soulProfileStore.loadSoulVisualIdentity(requireWorkspaceRoot())

  fun saveSoulPrimaryPortrait(
    source: OpenCrayImageReferenceSource,
  ): OpenCraySoulVisualIdentity? = soulVisualIdentityService().savePrimaryPortrait(
    workspaceRoot = requireWorkspaceRoot(),
    source = source,
  )

  fun saveSoulReferenceImage(
    refId: String,
    source: OpenCrayImageReferenceSource,
  ): OpenCraySoulVisualIdentity? = soulVisualIdentityService().saveReferenceImage(
    workspaceRoot = requireWorkspaceRoot(),
    refId = refId,
    source = source,
  )

  fun attachMemoryImageReference(
    memoryId: String,
    source: OpenCrayImageReferenceSource,
    preferredMode: AppImageReferencePromotionMode? = null,
  ): MemoryRecord? = memoryImageReferencePromotionService().attachSource(
    memoryId = memoryId,
    source = source,
    preferredMode = preferredMode,
  )

  fun listMemoryImageReferences(
    memoryId: String,
  ) = memoryImageReferenceService().listImageReferences(memoryId)

  private fun requireWorkspaceRoot(): Path =
    requireNotNull(workspaceRootProvider()) {
      "Workspace root is unavailable for soul image persistence."
    }.toAbsolutePath().normalize()

  private fun memoryImageReferenceService(): AppMemoryImageReferenceService =
    AppMemoryImageReferenceService(memoryStore = memoryStore)

  private fun memoryImageReferencePromotionService(): AppMemoryImageReferencePromotionService =
    AppMemoryImageReferencePromotionService(
      promotionService = promotionService(),
      memoryImageReferenceService = memoryImageReferenceService(),
    )

  private fun soulVisualIdentityService(): AppSoulVisualIdentityService =
    AppSoulVisualIdentityService(
      soulProfileStore = soulProfileStore,
      promotionService = promotionService(),
    )

  private fun promotionService(): AppImageReferencePromotionService {
    val privateRoot = resolvePrivateRoot()
    val resolverFactory = AppImageReferenceSourceResolverFactory(
      workspaceRootProvider = workspaceRootProvider,
      privateRootProvider = { privateRoot },
      chatSessionStore = chatSessionStore,
      runArtifactCatalog = AppRunArtifactCatalog(
        workspaceRootProvider = workspaceRootProvider,
        runtimeEventsProvider = { sessionId ->
          runEventJournalStoreFactory.forChatSession(sessionId).listRuntimeEvents()
        },
      ),
      settingsImageAssetStore = settingsImageAssetStore,
    )
    return AppImageReferencePromotionService(
      privateRoot = privateRoot,
      workspaceRoot = workspaceRootProvider(),
      sourceResolver = resolverFactory.create(),
      summaryExtractor = summaryExtractor,
    )
  }

  private fun resolvePrivateRoot(): Path {
    val workspaceRoot = workspaceRootProvider()?.toAbsolutePath()?.normalize()
    val workspaceDescriptor = workspaceRoot?.toString()?.replace('\\', '/') ?: "default"
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(workspaceDescriptor.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }
      .take(12)
    val workspaceLabel = workspaceRoot?.fileName?.toString()
      ?.trim()
      ?.replace(Regex("""[\\/:*?"<>|]"""), "_")
      ?.ifBlank { "workspace" }
      ?: "default"
    return File(filesDir, PRIVATE_ROOT_DIRECTORY_NAME)
      .toPath()
      .toAbsolutePath()
      .normalize()
      .resolve("$workspaceLabel-$digest")
      .normalize()
  }

  companion object {
    private const val PRIVATE_ROOT_DIRECTORY_NAME: String = "opencray-image-reference-private"

    fun fromContext(
      context: Context,
      workspaceRootProvider: () -> Path?,
    ): AppImageReferenceHostServices {
      val appContext = context.applicationContext
      val llmSettingsStore = LlmSettingsStore.fromContext(appContext)
      val settingsImageAssetStore = AppSettingsImageAssetStore.fromFilesDir(appContext.filesDir)
      return AppImageReferenceHostServices(
        workspaceRootProvider = workspaceRootProvider,
        filesDir = appContext.filesDir,
        settingsImageAssetStore = settingsImageAssetStore,
        settingsImageAssetImportService = AppSettingsImageAssetImportService(
          candidateImporter = ContextBackedSettingsImageCandidateImporter(appContext),
          assetStore = settingsImageAssetStore,
        ),
        chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
        runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
        memoryStore = PersonalizationLocalStore.fromContext(appContext).asMemoryStore(),
        soulProfileStore = WorkspaceSoulProfileStore(),
        summaryExtractor = LiteLlmImageSummaryExtractor(
          llmSettingsProvider = llmSettingsStore::load,
          providerClient = OpenAiCompatibleLiteLlmProviderClient(
            userAgent = OpenCrayUserAgent.fromContext(appContext),
          ),
        ),
      )
    }
  }
}
