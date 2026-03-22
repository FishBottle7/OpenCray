package com.opencray.app

import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayMediaToolSettings
import com.opencray.runtime.context.RuntimeSoulProfile

internal fun persistedRecordForTest(
  event: OpenCrayAgentRunEvent,
): PersistedAgentRunEvent = invokeKtStatic(
  className = "com.opencray.app.AgentRunRecordStoreFactoryKt",
  methodName = "toPersistedRecord",
  args = arrayOf(event),
)

internal fun runtimeEventForTest(
  event: PersistedAgentRunEvent,
): OpenCrayAgentRunEvent = invokeKtStatic(
  className = "com.opencray.app.AgentRunRecordStoreFactoryKt",
  methodName = "toRuntimeEvent",
  args = arrayOf(event),
)

internal fun mediaToolSettingsForTest(
  mediaSettings: MediaSpeechSettingsState,
  llmSettings: LlmSettingsState,
): OpenCrayMediaToolSettings = invokeKtStatic(
  className = "com.opencray.app.AppAgentSessionTaskRuntimeFactoryKt",
  methodName = "mediaToolSettingsFor",
  args = arrayOf(mediaSettings, llmSettings),
)

internal fun runtimeSoulProfileForTest(
  profile: WorkspaceSoulProfile,
): RuntimeSoulProfile = invokeKtStatic(
  className = "com.opencray.app.WorkspaceSoulProfileKt",
  methodName = "toRuntimeSoulProfile",
  args = arrayOf(profile),
)

internal fun inMemoryPromptCheckpointStoreFactoryForTest(): PromptCheckpointStoreFactory =
  invokeKtStatic(
    className = "com.opencray.app.PromptCheckpointStoreFactoryKt",
    methodName = "inMemoryPromptCheckpointStoreFactory",
  )

internal fun inMemoryRunEventJournalStoreFactoryForTest(): RunEventJournalStoreFactory =
  invokeKtStatic(
    className = "com.opencray.app.RunEventJournalStoreFactoryKt",
    methodName = "inMemoryRunEventJournalStoreFactory",
  )

internal val errorManagedProcessInterruptedOnRestoreForTest: String
  get() = readKtStaticString(
    className = "com.opencray.app.AgentSessionRuntimeManagerKt",
    fieldName = "ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE",
  )

internal val metadataRestoredFromDurableStoreForTest: String
  get() = readKtStaticString(
    className = "com.opencray.app.AgentSessionRuntimeManagerKt",
    fieldName = "METADATA_RESTORED_FROM_DURABLE_STORE",
  )

internal val metadataRestoredTerminalStateForTest: String
  get() = readKtStaticString(
    className = "com.opencray.app.AgentSessionRuntimeManagerKt",
    fieldName = "METADATA_RESTORED_TERMINAL_STATE",
  )

internal val restoredTerminalStateInterruptedForTest: String
  get() = readKtStaticString(
    className = "com.opencray.app.AgentSessionRuntimeManagerKt",
    fieldName = "RESTORED_TERMINAL_STATE_INTERRUPTED",
  )

@Suppress("UNCHECKED_CAST")
private fun <T> invokeKtStatic(
  className: String,
  methodName: String,
  args: Array<out Any?> = emptyArray(),
): T {
  val method = Class.forName(className)
    .methods
    .firstOrNull { candidate ->
      candidate.name == methodName && candidate.parameterCount == args.size
    }
    ?: error("Missing method $className::$methodName with ${args.size} args")
  return method.invoke(null, *args) as T
}

private fun readKtStaticString(
  className: String,
  fieldName: String,
): String = Class.forName(className)
  .fields
  .firstOrNull { field -> field.name == fieldName }
  ?.get(null) as? String
  ?: error("Missing field $className::$fieldName")
