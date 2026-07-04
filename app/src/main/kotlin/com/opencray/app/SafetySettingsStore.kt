package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile
import com.opencray.runtime.subagent.SubAgentContextMode
import java.io.File
import kotlinx.serialization.Serializable

private const val DEFAULT_SAFETY_SETTINGS_PREFERENCES = "opencray.safety-settings"
private const val SAFETY_SETTINGS_FILE_NAME = "safety-settings.json"

internal object SafetySettingsStoreKeys {
  const val AUTOMATION_MODE_ID = "automation_mode_id"
  const val ROLLBACK_JOURNAL_ENABLED = "rollback_journal_enabled"
  const val MAX_FILES_PER_BATCH = "max_files_per_batch"
  const val MAX_AGENT_TURNS = "max_agent_turns"
  const val MAX_TOOL_CALLS = "max_tool_calls"
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
  const val MEMORY_TOOLS_ENABLED = "memory_tools_enabled"
  const val SUB_AGENT_CONTEXT_DEFAULT_MODE_ID = "sub_agent_context_default_mode_id"
  const val SUB_AGENT_CONTEXT_PROFILE_OVERRIDES = "sub_agent_context_profile_overrides"
}

internal data class SafetySettingsState(
  val automationMode: SafetyAutomationMode = SafetyAutomationMode.AUTO,
  val rollbackJournalEnabled: Boolean = true,
  val maxFilesPerBatch: Int = DEFAULT_MAX_FILES_PER_BATCH,
  val maxAgentTurns: Int = DEFAULT_MAX_AGENT_TURNS,
  val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
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
  val memoryToolsEnabled: Boolean = true,
  val subAgentContextDefaultMode: SubAgentContextMode? = null,
  val subAgentContextProfileOverrides: Map<String, SubAgentContextMode> = emptyMap(),
) {
  fun sanitized(): SafetySettingsState = copy(
    maxFilesPerBatch = maxFilesPerBatch.coerceAtLeast(1),
    maxAgentTurns = maxAgentTurns.coerceAtLeast(0),
    maxToolCalls = maxToolCalls.coerceAtLeast(0),
    undoWindowHours = undoWindowHours.coerceAtLeast(1),
    subAgentContextDefaultMode = subAgentContextDefaultMode
      ?.takeIf { mode -> mode.publicControlPlaneEnabled },
    subAgentContextProfileOverrides =
      PublicSubAgentContextPolicySettings.sanitizeOverrides(subAgentContextProfileOverrides),
  )

  companion object {
    const val DEFAULT_MAX_FILES_PER_BATCH: Int = 20
    const val DEFAULT_MAX_AGENT_TURNS: Int = 0
    const val DEFAULT_MAX_TOOL_CALLS: Int = 0
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

  fun loadState(defaults: SafetySettingsState = SafetySettingsState()): SafetySettingsState =
    defaults.copy(
      automationMode = SafetyAutomationMode.fromWireValue(
        getString(SafetySettingsStoreKeys.AUTOMATION_MODE_ID),
      ),
      rollbackJournalEnabled =
        getBoolean(SafetySettingsStoreKeys.ROLLBACK_JOURNAL_ENABLED)
          ?: defaults.rollbackJournalEnabled,
      maxFilesPerBatch =
        getInt(SafetySettingsStoreKeys.MAX_FILES_PER_BATCH)
          ?: defaults.maxFilesPerBatch,
      maxAgentTurns =
        getInt(SafetySettingsStoreKeys.MAX_AGENT_TURNS)
          ?: defaults.maxAgentTurns,
      maxToolCalls =
        getInt(SafetySettingsStoreKeys.MAX_TOOL_CALLS)
          ?: defaults.maxToolCalls,
      undoWindowHours =
        getInt(SafetySettingsStoreKeys.UNDO_WINDOW_HOURS)
          ?: defaults.undoWindowHours,
      fileChangesPolicy = ToolPolicyOverride.fromWireValue(
        getString(SafetySettingsStoreKeys.FILE_CHANGES_POLICY_ID),
      ),
      fileDeletesPolicy = ToolPolicyOverride.fromWireValue(
        getString(SafetySettingsStoreKeys.FILE_DELETES_POLICY_ID),
      ),
      shellCommandsPolicy = ToolPolicyOverride.fromWireValue(
        getString(SafetySettingsStoreKeys.SHELL_COMMANDS_POLICY_ID),
      ),
      externalAccessMode = ExternalAccessMode.fromWireValue(
        getString(SafetySettingsStoreKeys.EXTERNAL_ACCESS_MODE_ID),
      ),
      photoLibraryEnabled =
        getBoolean(SafetySettingsStoreKeys.PHOTO_LIBRARY_ENABLED)
          ?: defaults.photoLibraryEnabled,
      downloadsEnabled =
        getBoolean(SafetySettingsStoreKeys.DOWNLOADS_ENABLED)
          ?: defaults.downloadsEnabled,
      documentsEnabled =
        getBoolean(SafetySettingsStoreKeys.DOCUMENTS_ENABLED)
          ?: defaults.documentsEnabled,
      recordingsEnabled =
        getBoolean(SafetySettingsStoreKeys.RECORDINGS_ENABLED)
          ?: defaults.recordingsEnabled,
      workspaceAccessProfile = WorkspaceAccessProfile.fromWireValue(
        getString(SafetySettingsStoreKeys.WORKSPACE_ACCESS_PROFILE_ID),
      ),
      readOnlyOutsideWorkspace =
        getBoolean(SafetySettingsStoreKeys.READ_ONLY_OUTSIDE_WORKSPACE)
          ?: defaults.readOnlyOutsideWorkspace,
      memoryToolsEnabled =
        getBoolean(SafetySettingsStoreKeys.MEMORY_TOOLS_ENABLED)
          ?: defaults.memoryToolsEnabled,
      subAgentContextDefaultMode =
        PublicSubAgentContextPolicySettings.publicModeFromWireValue(
          getString(SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_DEFAULT_MODE_ID),
        ) ?: defaults.subAgentContextDefaultMode,
      subAgentContextProfileOverrides =
        PublicSubAgentContextPolicySettings.decodeOverridesFromPreferenceValue(
          getString(SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_PROFILE_OVERRIDES),
        ).ifEmpty { defaults.subAgentContextProfileOverrides },
    ).sanitized()

  fun saveState(state: SafetySettingsState) {
    val sanitized = state.sanitized()
    putString(
      SafetySettingsStoreKeys.AUTOMATION_MODE_ID,
      sanitized.automationMode.wireValue,
    )
    putBoolean(
      SafetySettingsStoreKeys.ROLLBACK_JOURNAL_ENABLED,
      sanitized.rollbackJournalEnabled,
    )
    putInt(
      SafetySettingsStoreKeys.MAX_FILES_PER_BATCH,
      sanitized.maxFilesPerBatch,
    )
    putInt(
      SafetySettingsStoreKeys.MAX_AGENT_TURNS,
      sanitized.maxAgentTurns,
    )
    putInt(
      SafetySettingsStoreKeys.MAX_TOOL_CALLS,
      sanitized.maxToolCalls,
    )
    putInt(
      SafetySettingsStoreKeys.UNDO_WINDOW_HOURS,
      sanitized.undoWindowHours,
    )
    putString(
      SafetySettingsStoreKeys.FILE_CHANGES_POLICY_ID,
      sanitized.fileChangesPolicy.wireValue,
    )
    putString(
      SafetySettingsStoreKeys.FILE_DELETES_POLICY_ID,
      sanitized.fileDeletesPolicy.wireValue,
    )
    putString(
      SafetySettingsStoreKeys.SHELL_COMMANDS_POLICY_ID,
      sanitized.shellCommandsPolicy.wireValue,
    )
    putString(
      SafetySettingsStoreKeys.EXTERNAL_ACCESS_MODE_ID,
      sanitized.externalAccessMode.wireValue,
    )
    putBoolean(
      SafetySettingsStoreKeys.PHOTO_LIBRARY_ENABLED,
      sanitized.photoLibraryEnabled,
    )
    putBoolean(
      SafetySettingsStoreKeys.DOWNLOADS_ENABLED,
      sanitized.downloadsEnabled,
    )
    putBoolean(
      SafetySettingsStoreKeys.DOCUMENTS_ENABLED,
      sanitized.documentsEnabled,
    )
    putBoolean(
      SafetySettingsStoreKeys.RECORDINGS_ENABLED,
      sanitized.recordingsEnabled,
    )
    putString(
      SafetySettingsStoreKeys.WORKSPACE_ACCESS_PROFILE_ID,
      sanitized.workspaceAccessProfile.wireValue,
    )
    putBoolean(
      SafetySettingsStoreKeys.READ_ONLY_OUTSIDE_WORKSPACE,
      sanitized.readOnlyOutsideWorkspace,
    )
    putBoolean(
      SafetySettingsStoreKeys.MEMORY_TOOLS_ENABLED,
      sanitized.memoryToolsEnabled,
    )
    putString(
      SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_DEFAULT_MODE_ID,
      sanitized.subAgentContextDefaultMode?.wireValue.orEmpty(),
    )
    putString(
      SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_PROFILE_OVERRIDES,
      PublicSubAgentContextPolicySettings.encodeOverridesToPreferenceValue(
        sanitized.subAgentContextProfileOverrides,
      ),
    )
  }
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

  fun hasAnyPersistedSetting(): Boolean =
    SAFETY_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedSafetySettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : SafetySettingsKeyValueStore {
  private val lock = Any()

  override fun getBoolean(key: String): Boolean? =
    getString(key)?.toBooleanStrictOrNull()

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    putString(key, value.toString())
  }

  override fun getInt(key: String): Int? =
    getString(key)?.toIntOrNull()

  override fun putInt(
    key: String,
    value: Int,
  ) {
    putString(key, value.toString())
  }

  override fun getString(key: String): String? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putString(
    key: String,
    value: String,
  ) {
    synchronized(lock) {
      updateValues { values ->
        values + (key to value)
      }
    }
  }

  override fun loadState(defaults: SafetySettingsState): SafetySettingsState =
    synchronized(lock) {
      loadRecord().toState(defaults)
    }

  override fun saveState(state: SafetySettingsState) {
    synchronized(lock) {
      val values = valuesForState(state.sanitized())
      updateValues { values }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(SAFETY_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(
    legacyStore: SafetySettingsKeyValueStore,
    defaults: SafetySettingsState = SafetySettingsState(),
  ) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      updateValues { valuesForState(legacyStore.loadState(defaults)) }
    }
  }

  private fun hasPersistedRecord(): Boolean =
    !storage.readText(SAFETY_SETTINGS_FILE_NAME).isNullOrBlank()

  private fun loadRecord(): PersistedSafetySettingsRecord =
    storage.updateRecord(
      name = SAFETY_SETTINGS_FILE_NAME,
      serializer = PersistedSafetySettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedSafetySettingsRecord()
      val repaired = existing.normalized()
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
        write = persisted != null && repaired != existing,
      )
    }

  private fun updateValues(
    update: (Map<String, String>) -> Map<String, String>,
  ) {
    val now = clock()
    storage.updateRecord(
      name = SAFETY_SETTINGS_FILE_NAME,
      serializer = PersistedSafetySettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedSafetySettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(SAFETY_SETTING_KEYS::contains)
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = now,
          values = updatedValues,
        ),
        result = Unit,
      )
    }
  }
}

internal class SafetySettingsStore(
  private val keyValueStore: SafetySettingsKeyValueStore,
) {
  fun load(defaults: SafetySettingsState = SafetySettingsState()): SafetySettingsState =
    keyValueStore.loadState(defaults)

  fun save(state: SafetySettingsState) {
    keyValueStore.saveState(state)
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_SAFETY_SETTINGS_PREFERENCES,
    ): SafetySettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedSafetySettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesSafetySettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return SafetySettingsStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedSafetySettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedSafetySettingsRecord = copy(
    values = values.filterKeys(SAFETY_SETTING_KEYS::contains),
  )

  fun toState(defaults: SafetySettingsState): SafetySettingsState {
    val legacy = PersistedSafetySettingsKeyValueStore(values)
    return legacy.loadState(defaults)
  }
}

private class PersistedSafetySettingsKeyValueStore(
  private val values: Map<String, String>,
) : SafetySettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) = Unit

  override fun getInt(key: String): Int? = values[key]?.toIntOrNull()

  override fun putInt(
    key: String,
    value: Int,
  ) = Unit

  override fun getString(key: String): String? = values[key]

  override fun putString(
    key: String,
    value: String,
  ) = Unit

  override fun clear() = Unit
}

private fun valuesForState(state: SafetySettingsState): Map<String, String> = linkedMapOf(
  SafetySettingsStoreKeys.AUTOMATION_MODE_ID to state.automationMode.wireValue,
  SafetySettingsStoreKeys.ROLLBACK_JOURNAL_ENABLED to state.rollbackJournalEnabled.toString(),
  SafetySettingsStoreKeys.MAX_FILES_PER_BATCH to state.maxFilesPerBatch.toString(),
  SafetySettingsStoreKeys.MAX_AGENT_TURNS to state.maxAgentTurns.toString(),
  SafetySettingsStoreKeys.MAX_TOOL_CALLS to state.maxToolCalls.toString(),
  SafetySettingsStoreKeys.UNDO_WINDOW_HOURS to state.undoWindowHours.toString(),
  SafetySettingsStoreKeys.FILE_CHANGES_POLICY_ID to state.fileChangesPolicy.wireValue,
  SafetySettingsStoreKeys.FILE_DELETES_POLICY_ID to state.fileDeletesPolicy.wireValue,
  SafetySettingsStoreKeys.SHELL_COMMANDS_POLICY_ID to state.shellCommandsPolicy.wireValue,
  SafetySettingsStoreKeys.EXTERNAL_ACCESS_MODE_ID to state.externalAccessMode.wireValue,
  SafetySettingsStoreKeys.PHOTO_LIBRARY_ENABLED to state.photoLibraryEnabled.toString(),
  SafetySettingsStoreKeys.DOWNLOADS_ENABLED to state.downloadsEnabled.toString(),
  SafetySettingsStoreKeys.DOCUMENTS_ENABLED to state.documentsEnabled.toString(),
  SafetySettingsStoreKeys.RECORDINGS_ENABLED to state.recordingsEnabled.toString(),
  SafetySettingsStoreKeys.WORKSPACE_ACCESS_PROFILE_ID to state.workspaceAccessProfile.wireValue,
  SafetySettingsStoreKeys.READ_ONLY_OUTSIDE_WORKSPACE to state.readOnlyOutsideWorkspace.toString(),
  SafetySettingsStoreKeys.MEMORY_TOOLS_ENABLED to state.memoryToolsEnabled.toString(),
  SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_DEFAULT_MODE_ID to
    state.subAgentContextDefaultMode?.wireValue.orEmpty(),
  SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_PROFILE_OVERRIDES to
    PublicSubAgentContextPolicySettings.encodeOverridesToPreferenceValue(
      state.subAgentContextProfileOverrides,
    ),
)

private val SAFETY_SETTING_KEYS: Set<String> = setOf(
  SafetySettingsStoreKeys.AUTOMATION_MODE_ID,
  SafetySettingsStoreKeys.ROLLBACK_JOURNAL_ENABLED,
  SafetySettingsStoreKeys.MAX_FILES_PER_BATCH,
  SafetySettingsStoreKeys.MAX_AGENT_TURNS,
  SafetySettingsStoreKeys.MAX_TOOL_CALLS,
  SafetySettingsStoreKeys.UNDO_WINDOW_HOURS,
  SafetySettingsStoreKeys.FILE_CHANGES_POLICY_ID,
  SafetySettingsStoreKeys.FILE_DELETES_POLICY_ID,
  SafetySettingsStoreKeys.SHELL_COMMANDS_POLICY_ID,
  SafetySettingsStoreKeys.EXTERNAL_ACCESS_MODE_ID,
  SafetySettingsStoreKeys.PHOTO_LIBRARY_ENABLED,
  SafetySettingsStoreKeys.DOWNLOADS_ENABLED,
  SafetySettingsStoreKeys.DOCUMENTS_ENABLED,
  SafetySettingsStoreKeys.RECORDINGS_ENABLED,
  SafetySettingsStoreKeys.WORKSPACE_ACCESS_PROFILE_ID,
  SafetySettingsStoreKeys.READ_ONLY_OUTSIDE_WORKSPACE,
  SafetySettingsStoreKeys.MEMORY_TOOLS_ENABLED,
  SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_DEFAULT_MODE_ID,
  SafetySettingsStoreKeys.SUB_AGENT_CONTEXT_PROFILE_OVERRIDES,
)
