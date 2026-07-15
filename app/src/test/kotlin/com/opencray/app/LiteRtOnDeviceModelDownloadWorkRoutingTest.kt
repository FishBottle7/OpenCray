package com.opencray.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRtOnDeviceModelDownloadWorkRoutingTest {
  @Test
  fun proxyConvertsDownloadOperationsToStructuredCommands() {
    val commands = mutableListOf<LiteRtOnDeviceModelDownloadWorkCommand>()
    val scheduler = MainProcessLiteRtOnDeviceModelDownloadWorkSchedulerProxy(commands::add)

    scheduler.enqueue("model-1")
    scheduler.cancel("model-2")

    assertEquals(
      listOf(
        LiteRtOnDeviceModelDownloadWorkCommand.Enqueue("model-1"),
        LiteRtOnDeviceModelDownloadWorkCommand.Cancel("model-2"),
      ),
      commands,
    )
  }

  @Test
  fun commandIntentCodecRoundTripsAndNormalizesModelIds() {
    val commands = listOf(
      LiteRtOnDeviceModelDownloadWorkCommand.Enqueue("MODEL-1"),
      LiteRtOnDeviceModelDownloadWorkCommand.Cancel("model-2"),
    )

    val decoded = commands.map { command ->
      parseLiteRtModelDownloadWorkCommand(
        encodeLiteRtModelDownloadWorkCommand(RecordingIntent(), command),
      )
    }

    assertEquals(
      listOf(
        LiteRtOnDeviceModelDownloadWorkCommand.Enqueue("model-1"),
        LiteRtOnDeviceModelDownloadWorkCommand.Cancel("model-2"),
      ),
      decoded,
    )
  }

  @Test
  fun commandParserRejectsUnknownOrIncompleteCommands() {
    assertNull(
      parseLiteRtModelDownloadWorkCommand(
        action = "unknown",
        commandKind = "enqueue",
        modelId = "model-1",
      ),
    )
    assertNull(
      parseLiteRtModelDownloadWorkCommand(
        action = ACTION_LITERT_MODEL_DOWNLOAD_WORK_COMMAND,
        commandKind = "enqueue",
        modelId = " ",
      ),
    )
    assertNull(
      parseLiteRtModelDownloadWorkCommand(
        action = ACTION_LITERT_MODEL_DOWNLOAD_WORK_COMMAND,
        commandKind = "unknown",
        modelId = "model-1",
      ),
    )
  }

  @Test
  fun commandDispatcherDelegatesToMainProcessScheduler() {
    val scheduler = RecordingDownloadWorkScheduler()

    dispatchLiteRtModelDownloadWorkCommand(
      LiteRtOnDeviceModelDownloadWorkCommand.Enqueue("model-1"),
      scheduler,
    )
    dispatchLiteRtModelDownloadWorkCommand(
      LiteRtOnDeviceModelDownloadWorkCommand.Cancel("model-2"),
      scheduler,
    )

    assertEquals(listOf("model-1"), scheduler.enqueuedModelIds)
    assertEquals(listOf("model-2"), scheduler.cancelledModelIds)
  }

  private class RecordingIntent : Intent() {
    private val extras: MutableMap<String, Any?> = linkedMapOf()
    private var storedAction: String? = null

    override fun setAction(action: String?): Intent {
      storedAction = action
      return this
    }

    override fun getAction(): String? = storedAction

    override fun putExtra(
      name: String?,
      value: String?,
    ): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun getStringExtra(name: String?): String? =
      name?.let(extras::get) as? String
  }

  private class RecordingDownloadWorkScheduler : LiteRtOnDeviceModelDownloadWorkScheduler {
    val enqueuedModelIds = mutableListOf<String>()
    val cancelledModelIds = mutableListOf<String>()

    override fun enqueue(modelId: String) {
      enqueuedModelIds += modelId
    }

    override fun cancel(modelId: String) {
      cancelledModelIds += modelId
    }
  }
}
