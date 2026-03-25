package com.opencray.app

import com.opencray.persistence.PersistenceSchemaVersion
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeNotificationDeliveryStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsDeliveredFingerprintAcrossReloads() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-notification-delivery-persist")
    val firstStore = FileBackedRuntimeNotificationDeliveryStoreFactory(runtimeRoot).create()

    assertFalse(firstStore.wasDelivered("terminal:run-1", "fingerprint-1"))

    firstStore.markDelivered("terminal:run-1", "fingerprint-1")

    val restoredStore = FileBackedRuntimeNotificationDeliveryStoreFactory(runtimeRoot).create()

    assertTrue(restoredStore.wasDelivered("terminal:run-1", "fingerprint-1"))
  }

  @Test
  fun fileBackedStoreReplacesFingerprintForExistingNotificationKey() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-notification-delivery-persist")
    val firstStore = FileBackedRuntimeNotificationDeliveryStoreFactory(runtimeRoot).create()

    firstStore.markDelivered("terminal:run-1", "fingerprint-1")
    firstStore.markDelivered("terminal:run-1", "fingerprint-2")

    val restoredStore = FileBackedRuntimeNotificationDeliveryStoreFactory(runtimeRoot).create()

    assertFalse(restoredStore.wasDelivered("terminal:run-1", "fingerprint-1"))
    assertTrue(restoredStore.wasDelivered("terminal:run-1", "fingerprint-2"))
  }

  @Test
  fun fileBackedStoreTrimsOldestEntriesWhenCapacityExceeded() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-notification-delivery-trim")
    val store = FileBackedRuntimeNotificationDeliveryStoreFactory(
      runtimeRootDirectory = runtimeRoot,
      config = RuntimeNotificationDeliveryStoreConfig(maxTrackedEntries = 2),
    ).create()

    store.markDelivered("terminal:run-1", "fingerprint-1")
    store.markDelivered("terminal:run-2", "fingerprint-2")
    store.markDelivered("terminal:run-3", "fingerprint-3")

    val restoredStore = FileBackedRuntimeNotificationDeliveryStoreFactory(
      runtimeRootDirectory = runtimeRoot,
      config = RuntimeNotificationDeliveryStoreConfig(maxTrackedEntries = 2),
    ).create()

    assertFalse(restoredStore.wasDelivered("terminal:run-1", "fingerprint-1"))
    assertTrue(restoredStore.wasDelivered("terminal:run-2", "fingerprint-2"))
    assertTrue(restoredStore.wasDelivered("terminal:run-3", "fingerprint-3"))
  }

  @Test
  fun fileBackedStoreNormalizesInvalidAndDuplicatePersistedEntriesOnLoad() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-notification-delivery-normalize")
    File(runtimeRoot, "runtime-notification-delivery.json").writeText(
      """
      {
        "schemaVersion": ${PersistenceSchemaVersion.CURRENT},
        "recordVersion": 7,
        "updatedAtEpochMs": 300,
        "entries": [
          {
            "notificationKey": "terminal:run-a",
            "fingerprint": "fingerprint-old",
            "deliveredAtEpochMs": 100
          },
          {
            "notificationKey": "terminal:run-a",
            "fingerprint": "fingerprint-new",
            "deliveredAtEpochMs": 200
          },
          {
            "notificationKey": "",
            "fingerprint": "ignored-empty-key",
            "deliveredAtEpochMs": 300
          },
          {
            "notificationKey": "terminal:run-b",
            "fingerprint": "",
            "deliveredAtEpochMs": 400
          },
          {
            "notificationKey": "terminal:run-c",
            "fingerprint": "fingerprint-c",
            "deliveredAtEpochMs": 150
          }
        ]
      }
      """.trimIndent(),
    )

    val store = FileBackedRuntimeNotificationDeliveryStoreFactory(runtimeRoot).create()

    assertFalse(store.wasDelivered("terminal:run-a", "fingerprint-old"))
    assertTrue(store.wasDelivered("terminal:run-a", "fingerprint-new"))
    assertFalse(store.wasDelivered("terminal:run-b", ""))
    assertTrue(store.wasDelivered("terminal:run-c", "fingerprint-c"))

    val restoredStore = FileBackedRuntimeNotificationDeliveryStoreFactory(runtimeRoot).create()

    assertTrue(restoredStore.wasDelivered("terminal:run-a", "fingerprint-new"))
    assertTrue(restoredStore.wasDelivered("terminal:run-c", "fingerprint-c"))
  }
}
