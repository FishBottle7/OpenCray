package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile

private const val DEFAULT_SAFETY_SETTINGS_PREFERENCES = "opencray.safety-settings"

internal object SafetySettingsStoreKeys {
  const val AUTOMATION_MODE_ID = "automation_mode_id"
  const val ROLLBACK_JOURNAL_ENABLED = "rollback_journal_enabled"
  const val MAX_FILES_PER_BATCH = "max_files_per_batch"
  const val UNDO_WINDOW_HOURS = "undo_window_hours"
  const val FILE_CHANGES_POLICY_ID = "file_changes_policy_id"
  const val FILE_DELETES_POLICY_ID = "file_deletes_policy_id"
  const val SHELL_COMMANDS_POLICY_ID = "shell_commands_policy_id"
  const val EXTERNAL_ACCESS_MODE_ID = "external_access_mode_id"
  const val PHOTO_LIBRARY_ENABLED = "photo_library_enabled"
  const val DOWNLOADS_ENABLED = "downloads_enabled"
  const val DOCUMENTS_ENABLED = "documents_enabled"
  const val RECORDINGS_ENABLED = "recordings_enabled"
  const val WORKSPACE_ACCESS_PROFILE_ID = "workspace_access_profile_id"
  const val READ_ONLY_OUTSIDE_WORKSPACE = "read_only_outside_workspace"
}

internal data class SafetySettingsState(
  val automationMode: SafetyAutomationMode = SafetyAutomationMode.AUTO,
  val rollbackJournalEnabled: Boolean = true,
  val maxFilesPerBatch: Int = DEFAULT_MAX_FILES_PER_BATCH,
  val undoWindowHours: Int = DEFAULT_UNDO_WINDOW_HOURS,
  val fileChangesPolicy: ToolPolicyOverride = ToolPolicyOverride.INHERIT,
  val fileDeletesPolicy: ToolPolicyOverride = ToolPolicyOverride.INHERIT,
  val shellCommandsPolicy: ToolPolicyOverride = ToolPolicyOverride.INHERIT,
  val externalAccessMode: ExternalAccessMode = ExternalAccessMode.SELECT_PATHS,
  val photoLibraryEnabled: Boolean = true,
  val downloadsEnabled: Boolean = true,
  val documentsEnabled: Boolean = false,
  val recordingsEnabled: Boolean = false,
  val workspaceAccessProfile: WorkspaceAccessProfile = WorkspaceAccessProfile.WORK,
  val readOnlyOutsideWorkspace: Boolean = true,
) {
  fun sanitized(): SafetySettingsState = copy(
    maxFilesPerBatch = maxFilesPerBatch.coerceAtLeast(1),
    undoWindowHours = undoWindowHours.coerceAtLeast(1),
  )

  companion object {
    const val DEFAULT_MAX_FILES_PER_BATCH: Int = 20
    const val DEFAULT_UNDO_WINDOW_HOURS: Int = 24
  }
}

internal interface SafetySettingsKeyValueStore {
  fun getBoolean(key: String): Boolean?

  fun putBoolean(
    key: String,
    value: Boolean,
  )

  fun getInt(key: String): Int?

  fun putInt(
    key: String,
    value: Int,
  )

  fun getString(key: String): String?

  fun putString(
    key: String,
    value: String,
  )

  fun clear()
}

internal class InMemorySafetySettingsKeyValueStore(
  initialValues: Map<String, String> = emptyMap(),
) : SafetySettingsKeyValueStore {
  private val values = linkedMapOf<String, String>().apply {
    putAll(initialValues)
  }

  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    values[key] = value.toString()
  }

  override fun getInt(key: String): Int? = values[key]?.toIntOrNull()

  override fun putInt(
    key: String,
    value: Int,
  ) {
    values[key] = value.toString()
  }

  override fun getString(key: String): String? = values[key]

  override fun putString(
    key: String,
    value: String,
  ) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesSafetySettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : SafetySettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? =
    if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    sharedPreferences.edit().putBoolean(key, value).apply()
  }

  override fun getInt(key: String): Int? =
    if (sharedPreferences.contains(key)) sharedPreferences.getInt(key, 0) else null

  override fun putInt(
    key: String,
    value: Int,
  ) {
    sharedPreferences.edit().putInt(key, value).apply()
  }

  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(
    key: String,
    value: String,
  ) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }
}

internal class SafetySettingsStore(
  private val keyValueStore: SafetySettingsKeyValueStore,
) {
  fun load(defaults: SafetySettingsState = SafetySettingsState()): SafetySettingsState =
    defaults.copy(
      automationMode = SafetyAutomationMode.fromWireValue(
        keyValueStore.getString(SafetySettingsStoreKeys.AUTOMATION_MODE_ID),
      ),
      rollbackJournalEnabled =
        keyValueStore.getBoolean(SafetySettingsStoreKeys.ROLLBACK_JOURNAL_ENABLED)
          ?: defaults.rollbackJournalEnabled,
      maxFilesPerBatch =
        keyValueStore.getInt(SafetySettingsStoreKeys.MAX_FILES_PER_BATCH)
          ?: defaults.maxFilesPerBatch,
      undoWindowHours =
        keyValueStore.getInt(SafetySettingsStoreKeys.UNDO_WINDOW_HOURS)
          ?: defaults.undoWindowHours,
      fileChangesPolicy = ToolPolicyOverride.fromWireValue(
        keyValueStore.getString(SafetySettingsStoreKeys.FILE_CHANGES_POLICY_ID),
      ),
      fileDeletesPolicy = ToolPolicyOverride.fromWireValue(
        keyValueStore.getString(SafetySettingsStoreKeys.FILE_DELETES_POLICY_ID),
      ),
      shellCommandsPolicy = ToolPolicyOverride.fromWireValue(
        keyValueStore.getString(SafetySettingsStoreKeys.SHELL_COMMANDS_POLICY_ID),
      ),
      externalAccessMode = ExternalAccessMode.fromWireValue(
        keyValueStore.getString(SafetySettingsStoreKeys.EXTERNAL_ACCESS_MODE_ID),
      ),
      photoLibraryEnabled =
        keyValueStore.getBoolean(SafetySettingsStoreKeys.PHOTO_LIBRARY_ENABLED)
          ?: defaults.photoLibraryEnabled,
      downloadsEnabled =
        keyValueStore.getBoolean(SafetySettingsStoreKeys.DOWNLOADS_ENABLED)
          ?: defaults.downloadsEnabled,
      documentsEnabled =
        keyValueStore.getBoolean(SafetySettingsStoreKeys.DOCUMENTS_ENABLED)
          ?: defaults.documentsEnabled,
      recordingsEnabled =
        keyValueStore.getBoolean(SafetySettingsStoreKeys.RECORDINGS_ENABLED)
          ?: defaults.recordingsEnabled,
      workspaceAccessProfile = WorkspaceAccessProfile.fromWireValue(
        keyValueStore.getString(SafetySettingsStoreKeys.WORKSPACE_ACCESS_PROFILE_ID),
      ),
      readOnlyOutsideWorkspace =
        keyValueStore.getBoolean(SafetySettingsStoreKeys.READ_ONLY_OUTSIDE_WORKSPACE)
          ?: defaults.readOnlyOutsideWorkspace,
    ).sanitized()

  fun save(state: SafetySettingsState) {
    val sanitized = state.sanitized()
    keyValueStore.putString(
      SafetySettingsStoreKeys.AUTOMATION_MODE_ID,
      sanitized.automationMode.wireValue,
    )
    keyValueStore.putBoolean(
      SafetySettingsStoreKeys.ROLLBACK_JOURNAL_ENABLED,
      sanitized.rollbackJournalEnabled,
    )
    keyValueStore.putInt(
      SafetySettingsStoreKeys.MAX_FILES_PER_BATCH,
      sanitized.maxFilesPerBatch,
    )
    keyValueStore.putInt(
      SafetySettingsStoreKeys.UNDO_WINDOW_HOURS,
      sanitized.undoWindowHours,
    )
    keyValueStore.putString(
      SafetySettingsStoreKeys.FILE_CHANGES_POLICY_ID,
      sanitized.fileChangesPolicy.wireValue,
    )
    keyValueStore.putString(
      SafetySettingsStoreKeys.FILE_DELETES_POLICY_ID,
      sanitized.fileDeletesPolicy.wireValue,
    )
    keyValueStore.putString(
      SafetySettingsStoreKeys.SHELL_COMMANDS_POLICY_ID,
      sanitized.shellCommandsPolicy.wireValue,
    )
    keyValueStore.putString(
      SafetySettingsStoreKeys.EXTERNAL_ACCESS_MODE_ID,
      sanitized.externalAccessMode.wireValue,
    )
    keyValueStore.putBoolean(
      SafetySettingsStoreKeys.PHOTO_LIBRARY_ENABLED,
      sanitized.photoLibraryEnabled,
    )
    keyValueStore.putBoolean(
      SafetySettingsStoreKeys.DOWNLOADS_ENABLED,
      sanitized.downloadsEnabled,
    )
    keyValueStore.putBoolean(
      SafetySettingsStoreKeys.DOCUMENTS_ENABLED,
      sanitized.documentsEnabled,
    )
    keyValueStore.putBoolean(
      SafetySettingsStoreKeys.RECORDINGS_ENABLED,
      sanitized.recordingsEnabled,
    )
    keyValueStore.putString(
      SafetySettingsStoreKeys.WORKSPACE_ACCESS_PROFILE_ID,
      sanitized.workspaceAccessProfile.wireValue,
    )
    keyValueStore.putBoolean(
      SafetySettingsStoreKeys.READ_ONLY_OUTSIDE_WORKSPACE,
      sanitized.readOnlyOutsideWorkspace,
    )
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_SAFETY_SETTINGS_PREFERENCES,
    ): SafetySettingsStore = SafetySettingsStore(
      keyValueStore = SharedPreferencesSafetySettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
