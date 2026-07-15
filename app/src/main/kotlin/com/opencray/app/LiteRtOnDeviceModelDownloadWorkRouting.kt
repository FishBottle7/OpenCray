package com.opencray.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object ProcessSafeLiteRtOnDeviceModelDownloadWorkSchedulerFactory {
  fun fromContext(
    context: Context,
    processName: String? = currentProcessNameOrNull(),
  ): LiteRtOnDeviceModelDownloadWorkScheduler {
    val appContext = context.applicationContext
    return when (workManagerClientRoute(appContext.packageName, processName)) {
      WorkManagerClientRoute.MAIN_PROCESS ->
        WorkManagerLiteRtOnDeviceModelDownloadWorkScheduler.fromContext(appContext)
      WorkManagerClientRoute.MAIN_PROCESS_PROXY ->
        MainProcessLiteRtOnDeviceModelDownloadWorkSchedulerProxy.fromContext(appContext)
    }
  }
}

internal sealed interface LiteRtOnDeviceModelDownloadWorkCommand {
  data class Enqueue(val modelId: String) : LiteRtOnDeviceModelDownloadWorkCommand

  data class Cancel(val modelId: String) : LiteRtOnDeviceModelDownloadWorkCommand
}

internal class MainProcessLiteRtOnDeviceModelDownloadWorkSchedulerProxy(
  private val commandSender: (LiteRtOnDeviceModelDownloadWorkCommand) -> Unit,
) : LiteRtOnDeviceModelDownloadWorkScheduler {
  override fun enqueue(modelId: String) {
    commandSender(LiteRtOnDeviceModelDownloadWorkCommand.Enqueue(modelId))
  }

  override fun cancel(modelId: String) {
    commandSender(LiteRtOnDeviceModelDownloadWorkCommand.Cancel(modelId))
  }

  companion object {
    fun fromContext(context: Context): MainProcessLiteRtOnDeviceModelDownloadWorkSchedulerProxy {
      val appContext = context.applicationContext
      return MainProcessLiteRtOnDeviceModelDownloadWorkSchedulerProxy { command ->
        appContext.sendBroadcast(liteRtModelDownloadWorkCommandIntent(appContext, command))
      }
    }
  }
}

internal class LiteRtOnDeviceModelDownloadWorkCommandReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    val command = parseLiteRtModelDownloadWorkCommand(intent) ?: return
    dispatchLiteRtModelDownloadWorkCommand(
      command = command,
      scheduler = WorkManagerLiteRtOnDeviceModelDownloadWorkScheduler.fromContext(
        context.applicationContext,
      ),
    )
  }
}

internal fun liteRtModelDownloadWorkCommandIntent(
  context: Context,
  command: LiteRtOnDeviceModelDownloadWorkCommand,
): Intent = encodeLiteRtModelDownloadWorkCommand(
  intent = Intent(context, LiteRtOnDeviceModelDownloadWorkCommandReceiver::class.java)
    .setPackage(context.packageName),
  command = command,
)

internal fun encodeLiteRtModelDownloadWorkCommand(
  intent: Intent,
  command: LiteRtOnDeviceModelDownloadWorkCommand,
): Intent {
  intent.setAction(ACTION_LITERT_MODEL_DOWNLOAD_WORK_COMMAND)
  when (command) {
    is LiteRtOnDeviceModelDownloadWorkCommand.Enqueue -> intent
      .putExtra(EXTRA_LITERT_MODEL_DOWNLOAD_WORK_COMMAND_KIND, COMMAND_ENQUEUE)
      .putExtra(EXTRA_LITERT_MODEL_DOWNLOAD_WORK_MODEL_ID, command.modelId)
    is LiteRtOnDeviceModelDownloadWorkCommand.Cancel -> intent
      .putExtra(EXTRA_LITERT_MODEL_DOWNLOAD_WORK_COMMAND_KIND, COMMAND_CANCEL)
      .putExtra(EXTRA_LITERT_MODEL_DOWNLOAD_WORK_MODEL_ID, command.modelId)
  }
  return intent
}

internal fun parseLiteRtModelDownloadWorkCommand(
  intent: Intent?,
): LiteRtOnDeviceModelDownloadWorkCommand? = parseLiteRtModelDownloadWorkCommand(
  action = runCatching { intent?.action }.getOrNull(),
  commandKind = runCatching {
    intent?.getStringExtra(EXTRA_LITERT_MODEL_DOWNLOAD_WORK_COMMAND_KIND)
  }.getOrNull(),
  modelId = runCatching {
    intent?.getStringExtra(EXTRA_LITERT_MODEL_DOWNLOAD_WORK_MODEL_ID)
  }.getOrNull(),
)

internal fun parseLiteRtModelDownloadWorkCommand(
  action: String?,
  commandKind: String?,
  modelId: String?,
): LiteRtOnDeviceModelDownloadWorkCommand? {
  if (action != ACTION_LITERT_MODEL_DOWNLOAD_WORK_COMMAND) {
    return null
  }
  val normalizedModelId = normalizeOnDeviceModelId(modelId) ?: return null
  return when (commandKind) {
    COMMAND_ENQUEUE -> LiteRtOnDeviceModelDownloadWorkCommand.Enqueue(normalizedModelId)
    COMMAND_CANCEL -> LiteRtOnDeviceModelDownloadWorkCommand.Cancel(normalizedModelId)
    else -> null
  }
}

internal fun dispatchLiteRtModelDownloadWorkCommand(
  command: LiteRtOnDeviceModelDownloadWorkCommand,
  scheduler: LiteRtOnDeviceModelDownloadWorkScheduler,
) {
  when (command) {
    is LiteRtOnDeviceModelDownloadWorkCommand.Enqueue -> scheduler.enqueue(command.modelId)
    is LiteRtOnDeviceModelDownloadWorkCommand.Cancel -> scheduler.cancel(command.modelId)
  }
}

internal const val ACTION_LITERT_MODEL_DOWNLOAD_WORK_COMMAND: String =
  "com.opencray.app.action.LITERT_MODEL_DOWNLOAD_WORK_COMMAND"
internal const val EXTRA_LITERT_MODEL_DOWNLOAD_WORK_COMMAND_KIND: String =
  "litert_model_download_work_command_kind"
internal const val EXTRA_LITERT_MODEL_DOWNLOAD_WORK_MODEL_ID: String =
  "litert_model_download_work_model_id"

private const val COMMAND_ENQUEUE: String = "enqueue"
private const val COMMAND_CANCEL: String = "cancel"
