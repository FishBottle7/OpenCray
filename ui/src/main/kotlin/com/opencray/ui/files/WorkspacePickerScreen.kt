package com.opencray.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.opencray.filesystem.DefaultSafWorkspaceBridge
import com.opencray.filesystem.InMemorySafWorkspaceGrantStore
import com.opencray.filesystem.PersistedSafGrantSnapshot
import com.opencray.filesystem.SafAccessRequest
import com.opencray.filesystem.SafAccessState
import com.opencray.filesystem.SafGrantPermissionState
import com.opencray.filesystem.SafWorkspaceBridge
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DateFormat
import java.util.Locale
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.opencray.ui.design.OpenCrayButtonTone
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocBodyText
import com.opencray.ui.design.ocButton
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocMetaText
import com.opencray.ui.design.ocSectionTitleText
import com.opencray.ui.design.ocSurfaceBackground
import com.opencray.ui.design.ocTextInput
import org.opencray.ui.R

private const val DEFAULT_PLACEHOLDER_WORKSPACE_ID = "workspace"
private const val LOCAL_WORKBENCH_FOLDER = "opencray-files-workbench"
private const val MAX_PREVIEW_FILE_SIZE_BYTES = 128 * 1024L
private const val FILE_OPS_SERVICE_CLASS_NAME = "com.opencray.filesystem.FileOpsService"
private const val FILE_MUTATION_CREATE_CLASS_NAME = "com.opencray.filesystem.FileMutationOperation\$Create"
private const val FILE_MUTATION_DELETE_CLASS_NAME = "com.opencray.filesystem.FileMutationOperation\$Delete"
private const val FILE_MUTATION_MOVE_CLASS_NAME = "com.opencray.filesystem.FileMutationOperation\$Move"

private fun formatWorkbenchTimestamp(epochMillis: Long): String = DateFormat
  .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
  .format(epochMillis)

private fun formatWorkbenchSize(byteCount: Long): String = when {
  byteCount < 1024L -> "$byteCount B"
  else -> "${byteCount / 1024L} KB"
}

private fun workbenchItemCountLabel(context: Context, count: Int): String = context.resources.getQuantityString(
  R.plurals.workspace_workbench_item_count,
  count,
  count,
)

data class WorkspacePickerScreenState(
  val title: String,
  val subtitle: String,
  val accessState: SafAccessState = SafAccessState.NotGranted(
    workspaceId = DEFAULT_PLACEHOLDER_WORKSPACE_ID,
    request = SafAccessRequest.RelativePath(""),
  ),
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
  }

  companion object {
    fun localized(context: Context): WorkspacePickerScreenState = WorkspacePickerScreenState(
      title = context.getString(R.string.workspace_picker_title),
      subtitle = context.getString(R.string.workspace_picker_subtitle),
    )

    fun fromBridge(
      bridge: SafWorkspaceBridge,
      workspaceId: String,
      request: SafAccessRequest,
      title: String,
      subtitle: String,
    ): WorkspacePickerScreenState {
      val accessState = when (request) {
        is SafAccessRequest.RelativePath -> bridge.checkRelativePath(workspaceId, request.rawValue)
        is SafAccessRequest.DocumentUri -> bridge.checkDocumentUri(workspaceId, request.rawValue)
      }

      return WorkspacePickerScreenState(
        title = title,
        subtitle = subtitle,
        accessState = accessState,
      )
    }
  }
}

class WorkspacePickerScreen @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
  interface Listener {
    fun onPickWorkspaceRequested(workspaceId: String)

    fun onReauthorizeWorkspaceRequested(workspaceId: String)

    fun onClearGrantRequested(workspaceId: String)

    fun onManageWorkspaceAccessRequested()
  }

  private data class WorkbenchEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val childCount: Int? = null,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMillis: Long,
  )

  private data class WorkbenchFeedback(
    val message: String,
    val isError: Boolean,
  )

  private data class WorkbenchPreview(
    val text: String,
    val isMetadataOnly: Boolean,
    val isTextEditable: Boolean,
    val editorText: String? = null,
  )

  private class WorkbenchSession private constructor(
    private val context: Context,
    private val accessState: SafAccessState,
    private val snapshot: PersistedSafGrantSnapshot,
    private val grantedRootPath: Path,
  ) {
    private val bridge: SafWorkspaceBridge = DefaultSafWorkspaceBridge(
      store = InMemorySafWorkspaceGrantStore(initialGrants = listOf(snapshot.asGranted())),
    )
    private val fileOpsService: Any = instantiateFileOpsService(grantedRootPath)

    var currentDirectoryRelativePath: String = initialDirectoryRelativePath()
      private set
    var selectedRelativePath: String? = initialSelectionRelativePath()
      private set

    fun canNavigateUp(): Boolean = currentDirectoryRelativePath.isNotBlank()

    fun isAtRoot(): Boolean = currentDirectoryRelativePath.isBlank()

    fun currentFolderLabel(): String = displayRelativePath(currentDirectoryRelativePath)

    fun refresh() {
      ensureValidCurrentDirectory()
    }

    fun openWorkspaceRoot() {
      currentDirectoryRelativePath = ""
      selectedRelativePath = preferredRootSelection()
    }

    fun navigateUp() {
      if (currentDirectoryRelativePath.isBlank()) {
        return
      }

      currentDirectoryRelativePath = parentRelativePath(currentDirectoryRelativePath)
      selectedRelativePath = if (currentDirectoryRelativePath.isBlank()) {
        preferredRootSelection()
      } else {
        currentDirectoryRelativePath
      }
    }

    fun openEntry(relativePath: String) {
      val entryPath = resolveRelativePath(relativePath)
      if (Files.isDirectory(entryPath)) {
        currentDirectoryRelativePath = relativePath
        selectedRelativePath = relativePath
      } else {
        selectedRelativePath = relativePath
      }
    }

    fun listCurrentEntries(): List<WorkbenchEntry> {
      ensureValidCurrentDirectory()
      val entries = mutableListOf<Path>()
      Files.newDirectoryStream(resolveRelativePath(currentDirectoryRelativePath)).use { directoryStream ->
        for (path in directoryStream) {
          entries.add(path)
        }
      }

      return entries
        .sortedWith(
          compareBy<Path>({ !Files.isDirectory(it) }, { it.fileName.toString().lowercase(Locale.ROOT) }),
        )
        .map(::toWorkbenchEntry)
    }

    fun createFile(name: String): String {
      val candidateName = normalizeEntryName(name)
      val targetRelativePath = joinRelativePath(currentDirectoryRelativePath, candidateName)
      executeFileOpsBatch(
        operations = listOf(
          createFileMutation(
            className = FILE_MUTATION_CREATE_CLASS_NAME,
            args = arrayOf(
              Paths.get(targetRelativePath),
              buildString {
                append("Created inside the lightweight granted-root workbench.\n")
                append("Relative path: ")
                append(targetRelativePath)
              },
            ),
            parameterTypes = arrayOf(Path::class.java, String::class.java),
          ),
        ),
      )
      selectedRelativePath = targetRelativePath
      return targetRelativePath
    }

    fun createFolder(name: String): String {
      val candidateName = normalizeEntryName(name)
      val targetRelativePath = joinRelativePath(currentDirectoryRelativePath, candidateName)
      Files.createDirectories(resolveRelativePath(targetRelativePath))
      selectedRelativePath = targetRelativePath
      return targetRelativePath
    }

    fun renameSelected(newName: String): String {
      val sourceRelativePath = requireSelection()
      val destinationRelativePath = joinRelativePath(
        parentRelativePath(sourceRelativePath),
        normalizeEntryName(newName),
      )
      val sourcePath = resolveRelativePath(sourceRelativePath)

      if (Files.isDirectory(sourcePath)) {
        Files.move(sourcePath, resolveRelativePath(destinationRelativePath))
        if (currentDirectoryRelativePath == sourceRelativePath) {
          currentDirectoryRelativePath = destinationRelativePath
        }
      } else {
        executeFileOpsBatch(
          operations = listOf(
            createFileMutation(
              className = FILE_MUTATION_MOVE_CLASS_NAME,
              args = arrayOf(Paths.get(sourceRelativePath), Paths.get(destinationRelativePath)),
              parameterTypes = arrayOf(Path::class.java, Path::class.java),
            ),
          ),
        )
      }

      selectedRelativePath = destinationRelativePath
      return destinationRelativePath
    }

    fun deleteSelected(): String {
      val targetRelativePath = requireSelection()
      val targetPath = resolveRelativePath(targetRelativePath)
      val targetParent = parentRelativePath(targetRelativePath)

      if (Files.isDirectory(targetPath)) {
        deleteDirectoryRecursively(targetRelativePath)
        currentDirectoryRelativePath = targetParent
        selectedRelativePath = if (currentDirectoryRelativePath.isBlank()) {
          preferredRootSelection()
        } else {
          currentDirectoryRelativePath.takeIf { Files.exists(resolveRelativePath(it)) }
        }
      } else {
        executeFileOpsBatch(
          operations = listOf(
            createFileMutation(
              className = FILE_MUTATION_DELETE_CLASS_NAME,
              args = arrayOf(Paths.get(targetRelativePath)),
              parameterTypes = arrayOf(Path::class.java),
            ),
          ),
        )
        selectedRelativePath = listCurrentEntries().firstOrNull { !it.isDirectory }?.relativePath
          ?: listCurrentEntries().firstOrNull()?.relativePath
          ?: currentDirectoryRelativePath.takeIf { it.isNotBlank() }
      }

      return targetRelativePath
    }

    fun copiedRelativePath(): String = requireSelection()

    fun selectedSummary(): String {
      val selectedPath = requireSelection()
      val resolvedPath = resolveRelativePath(selectedPath)
      return if (Files.isDirectory(resolvedPath)) {
        val childCount = Files.newDirectoryStream(resolvedPath).use { stream ->
          var count = 0
          for (ignored in stream) {
            count += 1
          }
          count
        }
        context.getString(
          R.string.workspace_workbench_folder_summary,
          workbenchItemCountLabel(context, childCount),
          formatWorkbenchTimestamp(Files.getLastModifiedTime(resolvedPath).toMillis()),
        )
      } else {
        val size = Files.size(resolvedPath)
        context.getString(
          R.string.workspace_workbench_file_summary,
          formatWorkbenchSize(size),
          formatWorkbenchTimestamp(Files.getLastModifiedTime(resolvedPath).toMillis()),
        )
      }
    }

    fun selectedEntryIsDirectory(): Boolean? = selectedRelativePath?.let { relativePath ->
      Files.isDirectory(resolveRelativePath(relativePath))
    }

    fun selectedDisplayName(): String? = selectedRelativePath?.let { relativePath ->
      relativePath.substringAfterLast('/', missingDelimiterValue = relativePath)
    }

    fun selectedExternalPath(): Path? {
      val selectedPath = selectedRelativePath ?: return null
      val resolvedPath = resolveRelativePath(selectedPath)
      return resolvedPath.takeUnless { Files.isDirectory(it) }
    }

    fun saveSelectedText(content: String): String {
      val selectedPath = requireSelection()
      val resolvedPath = resolveRelativePath(selectedPath)
      require(!Files.isDirectory(resolvedPath)) {
        context.getString(R.string.workspace_workbench_validation_select_file)
      }
      Files.write(resolvedPath, content.toByteArray(StandardCharsets.UTF_8))
      selectedRelativePath = selectedPath
      return selectedPath
    }

    fun selectedPreview(): WorkbenchPreview {
      val selectedPath = requireSelection()
      val resolvedPath = resolveRelativePath(selectedPath)
      return if (Files.isDirectory(resolvedPath)) {
        val names = mutableListOf<String>()
        Files.newDirectoryStream(resolvedPath).use { stream ->
          for (path in stream) {
            names.add(path.fileName.toString())
          }
        }
        if (names.isEmpty()) {
          WorkbenchPreview(
            text = context.getString(R.string.workspace_workbench_folder_empty),
            isMetadataOnly = false,
            isTextEditable = false,
            editorText = null,
          )
        } else {
          WorkbenchPreview(
            text = context.getString(
              R.string.workspace_workbench_preview_contains,
              names.sorted().take(3).joinToString(separator = ", "),
              if (names.size > 3) context.getString(R.string.workspace_workbench_preview_more_suffix) else "",
            ),
            isMetadataOnly = false,
            isTextEditable = false,
            editorText = null,
          )
        }
      } else {
        val fileSize = Files.size(resolvedPath)
        if (fileSize > MAX_PREVIEW_FILE_SIZE_BYTES) {
          WorkbenchPreview(
            text = context.getString(
              R.string.workspace_workbench_preview_metadata_large,
              MAX_PREVIEW_FILE_SIZE_BYTES / 1024L,
            ),
            isMetadataOnly = true,
            isTextEditable = false,
            editorText = null,
          )
        } else {
          val previewText = decodeUtf8TextPreview(Files.readAllBytes(resolvedPath))
          if (previewText == null) {
            WorkbenchPreview(
              text = context.getString(R.string.workspace_workbench_preview_metadata_binary),
              isMetadataOnly = true,
              isTextEditable = false,
              editorText = null,
            )
          } else {
            val normalizedPreviewText = previewText.trim()
            WorkbenchPreview(
              text = if (normalizedPreviewText.isBlank()) {
                context.getString(R.string.workspace_workbench_preview_empty_text)
              } else {
                previewText
              },
              isMetadataOnly = false,
              isTextEditable = true,
              editorText = previewText,
            )
          }
        }
      }
    }

    private fun preferredRootSelection(): String? {
      val rootEntries = listCurrentEntries()
      return rootEntries.firstOrNull { !it.isDirectory }?.relativePath
        ?: rootEntries.firstOrNull()?.relativePath
    }

    private fun initialDirectoryRelativePath(): String {
      val initialRequestRelativePath = requestedRelativePathInsideRoot() ?: return ""
      val parentRelativePath = parentRelativePath(initialRequestRelativePath)
      return parentRelativePath.takeIf { Files.exists(resolveRelativePath(it)) && Files.isDirectory(resolveRelativePath(it)) }
        ?: ""
    }

    private fun initialSelectionRelativePath(): String? {
      val initialRequestRelativePath = requestedRelativePathInsideRoot()
      return initialRequestRelativePath?.takeIf { Files.exists(resolveRelativePath(it)) }
        ?: if (currentDirectoryRelativePath.isBlank()) preferredRootSelection() else currentDirectoryRelativePath
    }

    private fun requestedRelativePathInsideRoot(): String? {
      val request = accessState.request as? SafAccessRequest.RelativePath ?: return null
      val normalizedRequest = normalizeRelativeCandidate(request.rawValue) ?: return null
      val normalizedRoot = snapshot.normalizedWorkspaceRelativeRootPath
      return when {
        normalizedRoot.isBlank() -> normalizedRequest
        normalizedRequest == normalizedRoot -> ""
        normalizedRequest.startsWith("$normalizedRoot/") -> normalizedRequest.removePrefix("$normalizedRoot/")
        else -> null
      }
    }

    private fun ensureValidCurrentDirectory() {
      if (currentDirectoryRelativePath.isBlank()) {
        return
      }

      val currentPath = resolveRelativePath(currentDirectoryRelativePath)
      if (!Files.exists(currentPath) || !Files.isDirectory(currentPath)) {
        currentDirectoryRelativePath = parentRelativePath(currentDirectoryRelativePath)
      }
    }

    private fun toWorkbenchEntry(path: Path): WorkbenchEntry {
      val relativePath = grantedRootPath.relativize(path).toString().replace('\\', '/')
      return if (Files.isDirectory(path)) {
        val childCount = Files.newDirectoryStream(path).use { stream ->
          var count = 0
          for (ignored in stream) {
            count += 1
          }
          count
        }
        WorkbenchEntry(
          name = path.fileName.toString(),
          relativePath = relativePath,
          isDirectory = true,
          childCount = childCount,
          modifiedAtEpochMillis = Files.getLastModifiedTime(path).toMillis(),
        )
      } else {
        WorkbenchEntry(
          name = path.fileName.toString(),
          relativePath = relativePath,
          isDirectory = false,
          sizeBytes = Files.size(path),
          modifiedAtEpochMillis = Files.getLastModifiedTime(path).toMillis(),
        )
      }
    }

    private fun deleteDirectoryRecursively(relativePath: String) {
      val paths = mutableListOf<Path>()
      Files.walk(resolveRelativePath(relativePath)).use { stream ->
        stream.forEach { path ->
          paths.add(path)
        }
      }

      for (path in paths.sortedByDescending { it.nameCount }) {
        if (Files.isDirectory(path)) {
          Files.delete(path)
        } else {
          val fileRelativePath = grantedRootPath.relativize(path).toString().replace('\\', '/')
          executeFileOpsBatch(
            operations = listOf(
              createFileMutation(
                className = FILE_MUTATION_DELETE_CLASS_NAME,
                args = arrayOf(Paths.get(fileRelativePath)),
                parameterTypes = arrayOf(Path::class.java),
              ),
            ),
          )
        }
      }
    }

    private fun requireSelection(): String = checkNotNull(selectedRelativePath) {
      context.getString(R.string.workspace_workbench_validation_select_inside_root)
    }

    private fun resolveRelativePath(relativePath: String): Path {
      val normalizedRelativePath = normalizeRelativeCandidate(relativePath)
        ?: throw IllegalArgumentException(context.getString(R.string.workspace_workbench_validation_invalid_path))
      val fullRelativeRequest = joinRelativePath(snapshot.normalizedWorkspaceRelativeRootPath, normalizedRelativePath)
      when (bridge.checkRelativePath(snapshot.workspaceId, fullRelativeRequest)) {
        is SafAccessState.Granted -> Unit
        is SafAccessState.InvalidPath -> throw IllegalArgumentException(
          context.getString(R.string.workspace_workbench_validation_invalid_path),
        )
        is SafAccessState.OutsideGrantedRoot -> throw IllegalArgumentException(
          context.getString(R.string.workspace_workbench_validation_outside_root),
        )
        is SafAccessState.NotGranted -> throw IllegalStateException(
          context.getString(R.string.workspace_workbench_validation_no_grant),
        )
        is SafAccessState.Revoked -> throw IllegalStateException(
          context.getString(R.string.workspace_workbench_validation_saved_grant_revoked),
        )
      }

      return if (normalizedRelativePath.isBlank()) grantedRootPath else grantedRootPath.resolve(normalizedRelativePath).normalize()
    }

    companion object {
      fun create(context: Context, accessState: SafAccessState): WorkbenchSession? {
        val snapshot = when (accessState) {
          is SafAccessState.Granted -> accessState.snapshot
          is SafAccessState.OutsideGrantedRoot -> accessState.snapshot
          is SafAccessState.InvalidPath -> accessState.snapshot
          is SafAccessState.NotGranted,
          is SafAccessState.Revoked -> null
        } ?: return null

        if (snapshot.permissionState != SafGrantPermissionState.GRANTED) {
          return null
        }

        val rootKey = snapshot.normalizedWorkspaceRelativeRootPath.ifBlank { "workspace-root" }
          .replace('/', '_')
        val grantedRootPath = context.cacheDir.toPath()
          .resolve(LOCAL_WORKBENCH_FOLDER)
          .resolve(snapshot.workspaceId)
          .resolve(rootKey)
        deleteRecursivelyIfPresent(grantedRootPath)
        seedGrantedRoot(grantedRootPath, snapshot)

        return WorkbenchSession(
          context = context,
          accessState = accessState,
          snapshot = snapshot,
          grantedRootPath = grantedRootPath,
        )
      }

      private fun seedGrantedRoot(
        grantedRootPath: Path,
        snapshot: PersistedSafGrantSnapshot,
      ) {
        Files.createDirectories(grantedRootPath.resolve("docs"))
        Files.createDirectories(grantedRootPath.resolve("notes"))
        Files.createDirectories(grantedRootPath.resolve("drafts"))

        Files.write(
          grantedRootPath.resolve("workspace-notes.txt"),
          buildString {
            append("This lightweight workbench stays inside ")
            append(snapshot.normalizedWorkspaceRelativeRootPath.ifBlank { "the granted root" })
            append(" only.")
          }.toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          grantedRootPath.resolve("docs/report.md"),
          "Quarterly demo report stays inside the granted root. Refresh, create, rename, delete, copy path, and preview all remain bounded here.".toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          grantedRootPath.resolve("docs/checklist.txt"),
          "Checklist: browse, refresh, create file, create folder, rename, delete, copy path, preview.".toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          grantedRootPath.resolve("notes/ideas.md"),
          "Future device-wide browsing stays out of scope for this lightweight workbench.".toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          grantedRootPath.resolve("docs/oversized-preview.txt"),
          buildString {
            repeat(4_000) {
              append("Oversized preview content stays metadata-only inside the granted root. ")
            }
          }.toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          grantedRootPath.resolve("docs/binary-preview.bin"),
          byteArrayOf(0x00, 0x42, 0x13, 0x7F, 0x2A),
        )
      }

      private fun deleteRecursivelyIfPresent(path: Path) {
        if (!Files.exists(path)) {
          return
        }

        val paths = mutableListOf<Path>()
        Files.walk(path).use { stream ->
          stream.forEach { candidate ->
            paths.add(candidate)
          }
        }
        for (candidate in paths.sortedByDescending { it.nameCount }) {
          Files.deleteIfExists(candidate)
        }
      }

      private fun instantiateFileOpsService(grantedRootPath: Path): Any {
        val serviceClass = Class.forName(FILE_OPS_SERVICE_CLASS_NAME)
        val constructor = serviceClass.declaredConstructors.firstOrNull { candidate ->
          candidate.parameterTypes.size == 5
        } ?: error("Expected FileOpsService synthetic default constructor.")
        constructor.isAccessible = true
        return constructor.newInstance(
          setOf(grantedRootPath),
          null,
          null,
          6,
          null,
        )
      }

      private fun createFileMutation(
        className: String,
        args: Array<Any>,
        parameterTypes: Array<Class<*>>,
      ): Any = Class.forName(className)
        .getConstructor(*parameterTypes)
        .newInstance(*args)

      private fun executeFileOpsBatch(
        fileOpsService: Any,
        operations: List<Any>,
      ) {
        fileOpsService.javaClass.getMethod("executeBatch", List::class.java).invoke(fileOpsService, operations)
      }

      private fun normalizeRelativeCandidate(rawValue: String): String? {
        val normalized = rawValue.trim().replace('\\', '/')
        if (normalized.isEmpty()) {
          return ""
        }
        if (normalized.startsWith('/') || hasWindowsDrivePrefix(normalized)) {
          return null
        }

        val segments = mutableListOf<String>()
        for (segment in normalized.split('/')) {
          when (segment) {
            "", "." -> Unit
            ".." -> return null
            else -> segments.add(segment)
          }
        }

        return segments.joinToString(separator = "/")
      }

      private fun joinRelativePath(
        parent: String,
        child: String,
      ): String = when {
        parent.isBlank() -> child
        child.isBlank() -> parent
        else -> "$parent/$child"
      }

      private fun parentRelativePath(relativePath: String): String =
        relativePath.substringBeforeLast('/', missingDelimiterValue = "")

      private fun decodeUtf8TextPreview(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) {
          return null
        }

        val decoded = try {
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        } catch (_: CharacterCodingException) {
          return null
        }

        return decoded.takeUnless { text ->
          text.any { character ->
            character.isISOControl() && character != '\n' && character != '\r' && character != '\t'
          }
        }
      }

      private fun hasWindowsDrivePrefix(value: String): Boolean =
        value.length >= 2 && value[0].isLetter() && value[1] == ':'
    }

    private fun executeFileOpsBatch(operations: List<Any>) {
      executeFileOpsBatch(fileOpsService = fileOpsService, operations = operations)
    }

    private fun normalizeEntryName(rawValue: String): String {
      val candidate = rawValue.trim()
      require(candidate.isNotEmpty()) { context.getString(R.string.workspace_workbench_validation_enter_name) }
      require('/' !in candidate && '\\' !in candidate) {
        context.getString(R.string.workspace_workbench_validation_single_name)
      }
      require(candidate != "." && candidate != "..") {
        context.getString(R.string.workspace_workbench_validation_reserved_name)
      }
      require(!hasWindowsDrivePrefix(candidate)) {
        context.getString(R.string.workspace_workbench_validation_name_not_allowed)
      }
      return candidate
    }

    private fun displayRelativePath(relativePath: String): String = relativePath.ifBlank {
      context.getString(R.string.workspace_workbench_root_label)
    }
  }

  private val surfaceColor = OpenCrayUiTokens.surface
  private val backgroundColor = OpenCrayUiTokens.shellBackground
  private val borderColor = OpenCrayUiTokens.border
  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val textSecondary = OpenCrayUiTokens.textSecondary
  private val accentColor = OpenCrayUiTokens.primary
  private val successColor = OpenCrayUiTokens.success
  private val warningColor = OpenCrayUiTokens.warning
  private val dangerColor = OpenCrayUiTokens.danger

  private var listener: Listener? = null
  private var state: WorkspacePickerScreenState = WorkspacePickerScreenState.localized(context)
  private var workbenchSession: WorkbenchSession? = null
  private var workbenchFeedback: WorkbenchFeedback? = null
  private var workbenchSearchQuery: String = ""
  private var editorSourcePath: String? = null
  private var editorSourceText: String = ""
  private var renameSourcePath: String? = null
  private var isSyncingEditor: Boolean = false

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(12), dp(20), dp(28))
  }

  private val headerTitleView = titleText(textSizeSp = 28f)
  private val headerSubtitleView = helperText()
  private val accessNoticeCard = sectionCard()
  private val accessNoticeTitleView = titleText(textSizeSp = 18f)
  private val accessNoticeMessageView = bodyText()
  private val manageAccessButton = secondaryButton(context.getString(R.string.workspace_manage_access))

  private val workbenchCard = sectionCard()
  private val workbenchTitleView = titleText(textSizeSp = 18f)
  private val workbenchSubtitleView = helperText()
  private val workbenchScopeView = helperText()
  private val workbenchUnavailableView = bodyText()
  private val workbenchInteractiveContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val openWorkspaceRootButton = secondaryButton(context.getString(R.string.workspace_workbench_open_root))
  private val upOneFolderButton = secondaryButton(context.getString(R.string.workspace_workbench_up_one_folder))
  private val refreshButton = actionButton(context.getString(R.string.workspace_workbench_refresh_list))
  private val currentFolderView = bodyText()
  private val workbenchFeedbackView = helperText()
  private val searchTitleView = titleText(context.getString(R.string.workspace_workbench_search_title), 16f)
  private val searchInput = textInput(context.getString(R.string.workspace_workbench_search_hint))
  private val searchButton = actionButton(context.getString(R.string.workspace_workbench_search_button))
  private val clearSearchButton = secondaryButton(context.getString(R.string.workspace_workbench_clear_search))
  private val searchSummaryView = helperText()
  private val entriesTitleView = titleText(context.getString(R.string.workspace_workbench_entries_title), 16f)
  private val entriesEmptyView = helperText()
  private val entriesContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val createNameInput = textInput(context.getString(R.string.workspace_workbench_create_name_hint))
  private val createFileButton = actionButton(context.getString(R.string.workspace_workbench_create_file))
  private val createFolderButton = secondaryButton(context.getString(R.string.workspace_workbench_create_folder))
  private val selectionTitleView = titleText(context.getString(R.string.workspace_workbench_selection_title), 16f)
  private val selectionPathView = bodyText()
  private val selectionMetaView = helperText()
  private val editorTitleView = titleText(context.getString(R.string.workspace_workbench_editor_title), 16f)
  private val selectionPreviewView = bodyText()
  private val editorInput = textAreaInput(context.getString(R.string.workspace_workbench_editor_hint))
  private val editorNoteView = helperText()
  private val saveFileButton = actionButton(context.getString(R.string.workspace_workbench_save_file))
  private val openExternalButton = secondaryButton(context.getString(R.string.workspace_workbench_open_external))
  private val renameInput = textInput(context.getString(R.string.workspace_workbench_rename_hint))
  private val renameButton = actionButton(context.getString(R.string.workspace_workbench_rename_button))
  private val deleteButton = secondaryButton(context.getString(R.string.workspace_workbench_delete_button))
  private val copyRelativePathButton = secondaryButton(context.getString(R.string.workspace_workbench_copy_relative_path))

  init {
    isFillViewport = true
    setBackgroundColor(backgroundColor)

    addView(
      contentContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )

    contentContainer.addView(buildHeaderCard())
    contentContainer.addView(accessNoticeCard, blockParams(topDp = 20))
    contentContainer.addView(workbenchCard, blockParams(topDp = 24))

    setupAccessNoticeCard()
    setupWorkbenchCard()
    bindActions()
    submitState(state)
  }

  fun setListener(listener: Listener?) {
    this.listener = listener
  }

  fun submitState(newState: WorkspacePickerScreenState) {
    state = newState
    workbenchSession = WorkbenchSession.create(context, newState.accessState)
    workbenchFeedback = null
    workbenchSearchQuery = ""
    editorSourcePath = null
    editorSourceText = ""
    renameSourcePath = null
    searchInput.setText("")
    renderHeader()
    renderAccessNotice()
    renderWorkbench()
  }

  fun snapshotState(): WorkspacePickerScreenState = state

  private fun buildHeaderCard(): View = sectionCard().apply {
    background = ColorDrawable(Color.TRANSPARENT)
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
  }

  private fun setupAccessNoticeCard() {
    accessNoticeCard.addView(accessNoticeTitleView)
    accessNoticeCard.addView(accessNoticeMessageView, blockParams(topDp = 6))
    accessNoticeCard.addView(manageAccessButton, blockParams(topDp = 12))
  }

  private fun setupWorkbenchCard() {
    workbenchCard.addView(workbenchTitleView)
    workbenchCard.addView(workbenchSubtitleView, blockParams(topDp = 6))
    workbenchCard.addView(workbenchScopeView, blockParams(topDp = 10))
    workbenchCard.addView(workbenchUnavailableView, blockParams(topDp = 12))
    workbenchCard.addView(workbenchInteractiveContainer, blockParams(topDp = 12))

    workbenchInteractiveContainer.addView(openWorkspaceRootButton)
    workbenchInteractiveContainer.addView(upOneFolderButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(refreshButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(currentFolderView, blockParams(topDp = 12))
    workbenchInteractiveContainer.addView(workbenchFeedbackView, blockParams(topDp = 6))
    workbenchInteractiveContainer.addView(searchTitleView, blockParams(topDp = 14))
    workbenchInteractiveContainer.addView(searchInput, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(searchButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(clearSearchButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(searchSummaryView, blockParams(topDp = 6))
    workbenchInteractiveContainer.addView(entriesTitleView, blockParams(topDp = 14))
    workbenchInteractiveContainer.addView(entriesEmptyView, blockParams(topDp = 6))
    workbenchInteractiveContainer.addView(entriesContainer, blockParams(topDp = 10))
    workbenchInteractiveContainer.addView(
      titleText(context.getString(R.string.workspace_workbench_create_title), 16f),
      blockParams(topDp = 16),
    )
    workbenchInteractiveContainer.addView(createNameInput, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(createFileButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(createFolderButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(selectionTitleView, blockParams(topDp = 16))
    workbenchInteractiveContainer.addView(selectionPathView, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(selectionMetaView, blockParams(topDp = 6))
    workbenchInteractiveContainer.addView(editorTitleView, blockParams(topDp = 12))
    workbenchInteractiveContainer.addView(selectionPreviewView, blockParams(topDp = 10))
    workbenchInteractiveContainer.addView(editorInput, blockParams(topDp = 10))
    workbenchInteractiveContainer.addView(editorNoteView, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(saveFileButton, blockParams(topDp = 10))
    workbenchInteractiveContainer.addView(openExternalButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(renameInput, blockParams(topDp = 12))
    workbenchInteractiveContainer.addView(renameButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(deleteButton, blockParams(topDp = 8))
    workbenchInteractiveContainer.addView(copyRelativePathButton, blockParams(topDp = 8))
  }

  private fun bindActions() {
    manageAccessButton.setOnClickListener { listener?.onManageWorkspaceAccessRequested() }

    openWorkspaceRootButton.setOnClickListener {
      performWorkbenchAction {
        workbenchSession?.openWorkspaceRoot()
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_returned_root),
          isError = false,
        )
      }
    }
    upOneFolderButton.setOnClickListener {
      performWorkbenchAction {
        workbenchSession?.navigateUp()
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_moved_up),
          isError = false,
        )
      }
    }
    refreshButton.setOnClickListener {
      performWorkbenchAction {
        workbenchSession?.refresh()
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_refreshed),
          isError = false,
        )
      }
    }
    searchButton.setOnClickListener {
      dismissKeyboard(searchInput)
      performWorkbenchAction {
        workbenchSearchQuery = searchInput.text?.toString().orEmpty().trim()
        WorkbenchFeedback(
          message = if (workbenchSearchQuery.isBlank()) {
            context.getString(R.string.workspace_workbench_feedback_showing_all)
          } else {
            context.getString(R.string.workspace_workbench_feedback_filtering_name, workbenchSearchQuery)
          },
          isError = false,
        )
      }
    }
    clearSearchButton.setOnClickListener {
      dismissKeyboard(searchInput)
      performWorkbenchAction {
        workbenchSearchQuery = ""
        searchInput.setText("")
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_cleared_search),
          isError = false,
        )
      }
    }
    createFileButton.setOnClickListener {
      performWorkbenchAction {
        val relativePath = requireNotNull(workbenchSession).createFile(createNameInput.text?.toString().orEmpty())
        createNameInput.setText("")
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_created_file, relativePath),
          isError = false,
        )
      }
    }
    createFolderButton.setOnClickListener {
      performWorkbenchAction {
        val relativePath = requireNotNull(workbenchSession).createFolder(createNameInput.text?.toString().orEmpty())
        createNameInput.setText("")
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_created_folder, relativePath),
          isError = false,
        )
      }
    }
    renameButton.setOnClickListener {
      performWorkbenchAction {
        val relativePath = requireNotNull(workbenchSession).renameSelected(renameInput.text?.toString().orEmpty())
        renameInput.setText("")
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_renamed, relativePath),
          isError = false,
        )
      }
    }
    deleteButton.setOnClickListener {
      performWorkbenchAction {
        val relativePath = requireNotNull(workbenchSession).deleteSelected()
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_deleted, relativePath),
          isError = false,
        )
      }
    }
    copyRelativePathButton.setOnClickListener {
      performWorkbenchAction {
        val relativePath = requireNotNull(workbenchSession).copiedRelativePath()
        copyRelativePath(relativePath)
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_copied_relative_path, relativePath),
          isError = false,
        )
      }
    }
    saveFileButton.setOnClickListener {
      performWorkbenchAction {
        dismissKeyboard(editorInput)
        val relativePath = requireNotNull(workbenchSession).saveSelectedText(editorInput.text?.toString().orEmpty())
        editorSourcePath = relativePath
        editorSourceText = editorInput.text?.toString().orEmpty()
        syncEditorActionState()
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_saved, relativePath),
          isError = false,
        )
      }
    }
    openExternalButton.setOnClickListener {
      performWorkbenchAction {
        val targetPath = requireNotNull(workbenchSession).selectedExternalPath()
          ?: throw IllegalStateException(context.getString(R.string.workspace_workbench_validation_select_file))
        openFileExternally(targetPath)
        WorkbenchFeedback(
          message = context.getString(R.string.workspace_workbench_feedback_opened_external, targetPath.fileName.toString()),
          isError = false,
        )
      }
    }
    editorInput.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
          if (!isSyncingEditor) {
            syncEditorActionState()
          }
        }
      },
    )
  }

  private fun performWorkbenchAction(action: () -> WorkbenchFeedback) {
    workbenchFeedback = runCatching(action).getOrElse { error ->
      val rootCause = generateSequence(error) { current -> current.cause }.last()
      val message = rootCause.message ?: context.getString(R.string.workspace_workbench_feedback_action_failed)
      WorkbenchFeedback(message = message, isError = true)
    }
    renderWorkbench()
  }

  private fun dismissKeyboard(input: EditText) {
    input.clearFocus()
    context.getSystemService(InputMethodManager::class.java)
      ?.hideSoftInputFromWindow(input.windowToken, 0)
  }

  private fun renderHeader() {
    headerTitleView.text = state.title
    headerSubtitleView.text = state.subtitle
  }

  private fun renderAccessNotice() {
    when (val accessState = state.accessState) {
      is SafAccessState.Granted -> {
        accessNoticeCard.visibility = View.GONE
      }

      is SafAccessState.NotGranted -> {
        accessNoticeCard.visibility = View.VISIBLE
        accessNoticeCard.background = surfaceBackground(Color.parseColor("#EAF0FF"))
        accessNoticeTitleView.text = context.getString(R.string.workspace_status_no_grant_title)
        accessNoticeMessageView.text = context.getString(R.string.workspace_access_notice_no_grant)
      }

      is SafAccessState.Revoked -> {
        accessNoticeCard.visibility = View.VISIBLE
        accessNoticeCard.background = surfaceBackground(Color.parseColor("#FFF0F0"))
        accessNoticeTitleView.text = context.getString(R.string.workspace_attention_revoked_title)
        accessNoticeMessageView.text = context.getString(
          R.string.workspace_access_notice_revoked,
          formatRelativeRoot(accessState.recoverableGrant()),
        )
      }

      is SafAccessState.OutsideGrantedRoot -> {
        accessNoticeCard.visibility = View.VISIBLE
        accessNoticeCard.background = surfaceBackground(Color.parseColor("#FFF5E3"))
        accessNoticeTitleView.text = context.getString(R.string.workspace_attention_outside_root_title)
        accessNoticeMessageView.text = context.getString(
          R.string.workspace_access_notice_outside_root,
          formatRelativeRoot(accessState.snapshot),
        )
      }

      is SafAccessState.InvalidPath -> {
        accessNoticeCard.visibility = View.VISIBLE
        accessNoticeCard.background = surfaceBackground(Color.parseColor("#FFF0F0"))
        accessNoticeTitleView.text = context.getString(R.string.workspace_attention_invalid_path_title)
        accessNoticeMessageView.text = context.getString(
          R.string.workspace_access_notice_invalid_path,
          accessState.reasonCode,
        )
      }
    }
  }

  private fun renderWorkbench() {
    workbenchTitleView.text = context.getString(R.string.workspace_workbench_title)
    workbenchScopeView.text = context.getString(R.string.workspace_workbench_scope)

    val accessState = state.accessState
    val session = workbenchSession
    workbenchCard.background = surfaceBackground(Color.WHITE)
    workbenchSubtitleView.text = when (accessState) {
      is SafAccessState.NotGranted -> context.getString(R.string.workspace_workbench_subtitle_no_grant)
      is SafAccessState.Granted -> context.getString(R.string.workspace_workbench_subtitle_granted)
      is SafAccessState.Revoked -> context.getString(R.string.workspace_workbench_subtitle_revoked)
      is SafAccessState.OutsideGrantedRoot -> context.getString(R.string.workspace_workbench_subtitle_outside_root)
      is SafAccessState.InvalidPath -> context.getString(R.string.workspace_workbench_subtitle_invalid_path)
    }

    if (session == null) {
      workbenchUnavailableView.visibility = View.VISIBLE
      workbenchInteractiveContainer.visibility = View.GONE
      workbenchUnavailableView.text = when (accessState) {
        is SafAccessState.NotGranted -> context.getString(R.string.workspace_workbench_unavailable_no_grant)
        is SafAccessState.Revoked -> context.getString(R.string.workspace_workbench_unavailable_revoked)
        else -> context.getString(R.string.workspace_workbench_unavailable_generic)
      }
      return
    }

    workbenchUnavailableView.visibility = View.GONE
    workbenchInteractiveContainer.visibility = View.VISIBLE
    openWorkspaceRootButton.isEnabled = !session.isAtRoot()
    upOneFolderButton.isEnabled = session.canNavigateUp()
    refreshButton.isEnabled = true
    searchButton.isEnabled = true
    clearSearchButton.isEnabled = workbenchSearchQuery.isNotBlank()
    currentFolderView.text = context.getString(R.string.workspace_workbench_current_folder, session.currentFolderLabel())

    val feedback = workbenchFeedback
    workbenchFeedbackView.text = feedback?.message ?: when (accessState) {
      is SafAccessState.OutsideGrantedRoot -> context.getString(R.string.workspace_workbench_feedback_outside_root)
      is SafAccessState.InvalidPath -> context.getString(R.string.workspace_workbench_feedback_invalid_path)
      else -> context.getString(R.string.workspace_workbench_feedback_default)
    }
    workbenchFeedbackView.setTextColor(if (feedback?.isError == true) dangerColor else textSecondary)
    searchSummaryView.text = if (workbenchSearchQuery.isBlank()) {
      context.getString(R.string.workspace_workbench_search_summary_default)
    } else {
      context.getString(R.string.workspace_workbench_search_summary_filtered, workbenchSearchQuery)
    }

    renderEntryList(session)
    renderSelection(session)
  }

  private fun renderEntryList(session: WorkbenchSession) {
    val allEntries = session.listCurrentEntries()
    val entries = allEntries.filter { entry ->
      workbenchSearchQuery.isBlank() || entry.name.contains(workbenchSearchQuery, ignoreCase = true)
    }
    entriesEmptyView.text = if (allEntries.isEmpty()) {
      context.getString(R.string.workspace_workbench_folder_empty)
    } else if (entries.isEmpty()) {
      context.getString(R.string.workspace_workbench_entries_no_matches, workbenchSearchQuery)
    } else {
      context.getString(R.string.workspace_workbench_entries_instruction)
    }
    entriesContainer.removeAllViews()

    entries.forEachIndexed { index, entry ->
      entriesContainer.addView(
        buildEntryRow(entry, isSelected = session.selectedRelativePath == entry.relativePath),
        blockParams(topDp = if (index == 0) 0 else 8),
      )
    }
  }

  private fun buildEntryRow(
    entry: WorkbenchEntry,
    isSelected: Boolean,
  ): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceBackground(if (isSelected) Color.parseColor("#EAF0FF") else Color.WHITE)
    setPadding(dp(14), dp(14), dp(14), dp(14))
    isClickable = true
    isFocusable = true

    setOnClickListener {
      performWorkbenchAction {
        requireNotNull(workbenchSession).openEntry(entry.relativePath)
        WorkbenchFeedback(
          message = if (entry.isDirectory) {
            context.getString(R.string.workspace_workbench_feedback_opened_folder, entry.relativePath)
          } else {
            context.getString(R.string.workspace_workbench_feedback_selected_file, entry.relativePath)
          },
          isError = false,
        )
      }
    }
    val nameView = titleText(if (entry.isDirectory) "${entry.name}/" else entry.name, 15f)
    val baseSummary = if (entry.isDirectory) {
      context.getString(
        R.string.workspace_workbench_folder_summary,
        workbenchItemCountLabel(context, entry.childCount ?: 0),
        formatWorkbenchTimestamp(entry.modifiedAtEpochMillis),
      )
    } else {
      context.getString(
        R.string.workspace_workbench_file_summary,
        formatWorkbenchSize(entry.sizeBytes ?: 0L),
        formatWorkbenchTimestamp(entry.modifiedAtEpochMillis),
      )
    }
    val summaryView = helperText(
      if (isSelected) {
        context.getString(R.string.workspace_workbench_entry_summary_selected, baseSummary)
      } else {
        baseSummary
      },
    )

    addView(nameView)
    addView(summaryView, blockParams(topDp = 6))
  }

  private fun renderSelection(session: WorkbenchSession) {
    val selectedRelativePath = session.selectedRelativePath
    val hasSelection = selectedRelativePath != null
    val preview = selectedRelativePath?.let { session.selectedPreview() }
    val isDirectorySelection = session.selectedEntryIsDirectory() == true
    val hasFileSelection = hasSelection && !isDirectorySelection
    selectionPathView.text = if (hasSelection) {
      context.getString(R.string.workspace_workbench_selected_path, selectedRelativePath)
    } else {
      context.getString(R.string.workspace_workbench_selected_path_none)
    }
    selectionMetaView.text = if (hasSelection) {
      session.selectedSummary()
    } else {
      context.getString(R.string.workspace_workbench_selection_empty)
    }

    renameInput.isEnabled = hasSelection
    renameButton.isEnabled = hasSelection
    deleteButton.isEnabled = hasSelection
    copyRelativePathButton.isEnabled = hasSelection
    openExternalButton.visibility = if (hasFileSelection) View.VISIBLE else View.GONE
    openExternalButton.isEnabled = hasFileSelection

    if (hasSelection && renameSourcePath != selectedRelativePath) {
      renameInput.setText(session.selectedDisplayName().orEmpty())
      renameSourcePath = selectedRelativePath
    } else if (!hasSelection) {
      renameInput.setText("")
      renameSourcePath = null
    }

    if (preview?.isTextEditable == true && selectedRelativePath != null) {
      editorTitleView.visibility = View.VISIBLE
      editorTitleView.text = context.getString(R.string.workspace_workbench_editor_title)
      editorInput.visibility = View.VISIBLE
      selectionPreviewView.visibility = View.GONE
      editorNoteView.visibility = View.VISIBLE
      editorNoteView.text = context.getString(R.string.workspace_workbench_editor_note_text)
      val nextEditorText = preview.editorText.orEmpty()
      if (editorSourcePath != selectedRelativePath || editorSourceText != nextEditorText) {
        isSyncingEditor = true
        editorInput.setText(nextEditorText)
        editorInput.setSelection(editorInput.text?.length ?: 0)
        isSyncingEditor = false
        editorSourcePath = selectedRelativePath
        editorSourceText = nextEditorText
      }
    } else {
      editorSourcePath = null
      editorSourceText = ""
      editorTitleView.visibility = if (hasSelection) View.VISIBLE else View.GONE
      editorTitleView.text = context.getString(
        if (preview?.isMetadataOnly == true) {
          R.string.workspace_workbench_preview_title
        } else {
          R.string.workspace_workbench_selection_title
        },
      )
      editorInput.visibility = View.GONE
      selectionPreviewView.visibility = View.VISIBLE
      selectionPreviewView.text = preview?.text ?: context.getString(R.string.workspace_workbench_preview_unavailable)
      selectionPreviewView.setTextColor(if (preview?.isMetadataOnly == true) textSecondary else textPrimary)
      editorNoteView.visibility = if (hasSelection) View.VISIBLE else View.GONE
      editorNoteView.text = if (hasSelection && hasFileSelection) {
        context.getString(R.string.workspace_workbench_editor_note_external)
      } else {
        context.getString(R.string.workspace_workbench_editor_note_folder)
      }
      isSyncingEditor = true
      editorInput.setText("")
      isSyncingEditor = false
    }

    saveFileButton.visibility = if (preview?.isTextEditable == true) View.VISIBLE else View.GONE
    syncEditorActionState()
  }

  private fun copyRelativePath(relativePath: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
      ClipData.newPlainText(context.getString(R.string.workspace_workbench_clipboard_relative_path), relativePath),
    )
  }

  private fun syncEditorActionState() {
    val isEditable = editorInput.visibility == View.VISIBLE && editorSourcePath != null
    val currentText = editorInput.text?.toString().orEmpty()
    saveFileButton.isEnabled = isEditable && currentText != editorSourceText
  }

  private fun openFileExternally(path: Path) {
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      path.toFile(),
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, mimeTypeForPath(path))
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  }

  private fun mimeTypeForPath(path: Path): String {
    val extension = path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty().ifBlank { "*/*" }
  }

  private fun formatRelativeRoot(snapshot: PersistedSafGrantSnapshot): String =
    snapshot.normalizedWorkspaceRelativeRootPath.ifBlank {
      context.getString(R.string.workspace_entire_selected_tree)
    }

  private fun sectionCard(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = context.ocCardBackground(OpenCraySurfaceTone.NEUTRAL)
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  private fun titleText(
    value: String,
    textSizeSp: Float,
  ): TextView = context.ocSectionTitleText(value, textSizeSp)

  private fun titleText(textSizeSp: Float): TextView = context.ocSectionTitleText(textSizeSp = textSizeSp)

  private fun bodyText(value: String = ""): TextView = context.ocBodyText(value)

  private fun helperText(value: String = ""): TextView = context.ocMetaText(value)

  private fun textInput(hint: String): EditText = context.ocTextInput(hint = hint, singleLine = true)

  private fun textAreaInput(hint: String): EditText = context.ocTextInput(
    hint = hint,
    singleLine = false,
    minLines = 8,
  ).apply {
    minLines = 8
    gravity = android.view.Gravity.TOP or android.view.Gravity.START
  }

  private fun actionButton(label: String): Button = context.ocButton(label, OpenCrayButtonTone.PRIMARY)

  private fun secondaryButton(label: String): Button = context.ocButton(label, OpenCrayButtonTone.SECONDARY)

  private fun surfaceBackground(fillColor: Int) = context.ocSurfaceBackground(
    fillColor = fillColor,
    radiusDp = OpenCrayUiTokens.radiusCard,
  )

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun dp(value: Int): Int = context.ocDp(value)
}

// Learning: A screen-local session can surface real granted-root browsing and lightweight edits without widening app-module dependencies beyond the existing reflected state seam.
// Issue: FileOpsService covers bounded file mutations well, but directory rename/delete still need tiny local helpers until folder-specific mutations exist in the shared backend.
