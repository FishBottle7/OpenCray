package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPermissionRegistryTest {

  @Test
  fun registryCoversAllRuntimePermissionSpecsStably() {
    val ids = SystemPermissionRegistry.specs.map { it.id }
    assertEquals(listOf("calendar", "contacts", "notifications"), ids)
    val calendar = SystemPermissionRegistry.specById("calendar")
    assertNotNull(calendar)
    assertEquals(
      listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
      calendar?.androidPermissions,
    )
    assertEquals(
      listOf("system_calendar_upcoming", "system_calendar_create"),
      calendar?.requestingTools,
    )
    val contacts = SystemPermissionRegistry.specById("contacts")
    assertEquals("android.permission.READ_CONTACTS", contacts?.androidPermissions?.single())
    assertEquals("system_contact_search", contacts?.requestingTools?.single())
    val notifications = SystemPermissionRegistry.specById("notifications")
    assertEquals("android.permission.POST_NOTIFICATIONS", notifications?.androidPermissions?.single())
    assertEquals("system_notification_post", notifications?.requestingTools?.single())
  }

  @Test
  fun projectorEmitsDescriptorWithPurposeAndToolTrace() {
    val snapshot = SystemPermissionSnapshotProjector.project(
      listOf(
        SystemPermissionState(
          spec = SystemPermissionRegistry.specById("contacts")!!,
          granted = false,
        ),
        SystemPermissionState(
          spec = SystemPermissionRegistry.specById("notifications")!!,
          granted = true,
        ),
      ),
    )

    assertEquals("system-permissions", snapshot["source"])
    assertEquals(true, snapshot["available"])
    val permissions = snapshot["permissions"] as List<Map<String, Any?>>
    assertEquals(2, permissions.size)
    val contactsDescriptor = permissions.first()
    assertEquals("contacts", contactsDescriptor["id"])
    assertEquals(false, contactsDescriptor["granted"])
    assertNotNull(contactsDescriptor["grantPurpose"])
    assertTrue(
      (contactsDescriptor["grantBenefits"] as List<*>).isNotEmpty(),
    )
    assertEquals("system_contact_search", (contactsDescriptor["requestingTools"] as List<*>).single())
    val notificationsDescriptor = permissions[1]
    assertEquals(true, notificationsDescriptor["granted"])
  }

  @Test
  fun unavailableAccessReportsAllSpecsDeniedAndMarksSnapshotUnavailable() {
    val snapshot = UnavailableSystemPermissionSnapshotAccess.loadSnapshot()

    assertEquals(false, snapshot["available"])
    val permissions = snapshot["permissions"] as List<Map<String, Any?>>
    assertEquals(SystemPermissionRegistry.specs.size, permissions.size)
    assertTrue(permissions.all { it["granted"] == false })
  }

  @Test
  fun launcherRejectsUnknownIdsWithoutLaunching() {
    val launcher = RecordingSystemPermissionRequestLauncher()
    val outcome = launcher.launchRequest(listOf("made-up-id"))

    assertEquals(false, outcome["launched"])
    assertEquals("unknown_permission_ids", outcome["reason"])
    assertEquals(0, launcher.launchCount)
  }

  @Test
  fun requestGateBlocksDuplicateConcurrentRequests() {
    val permissionIds = listOf(SystemPermissionIds.CALENDAR)
    assertFalse(SystemPermissionRequestGate.isActive(permissionIds))
    SystemPermissionRequestGate.markActive(permissionIds)
    assertTrue(SystemPermissionRequestGate.isActive(permissionIds))
    SystemPermissionRequestGate.clearActive(permissionIds)
    assertFalse(SystemPermissionRequestGate.isActive(permissionIds))
  }

  @Test
  fun reporterRecordsLastGrantOutcomeForBridgePolling() {
    SystemPermissionRequestReporter.record(listOf("android.permission.READ_CALENDAR"))
    val outcome = SystemPermissionRequestReporter.lastOutcome()

    assertEquals(
      listOf("android.permission.READ_CALENDAR"),
      outcome["grantedPermissions"],
    )
    assertTrue((outcome["requestCompletedAtEpochMs"] as Long) > 0)
  }

  private class RecordingSystemPermissionRequestLauncher : SystemPermissionRequestLauncher {
    var launchCount: Int = 0
      private set

    override fun launchRequest(permissionIds: List<String>): Map<String, Any?> {
      // Mirror AndroidSystemPermissionRequestLauncher's unknown-id refusal so
      // the test exercises the shared result contract.
      val validIds = SystemPermissionRegistry.specs.map { it.id }.filter { it in permissionIds }
      if (validIds.isEmpty()) {
        return systemPermissionActionResult(
          actionId = SystemPermissionActionIds.REQUEST,
          launched = false,
          reason = "unknown_permission_ids",
        )
      }
      launchCount += 1
      return systemPermissionActionResult(
        actionId = SystemPermissionActionIds.REQUEST,
        launched = true,
        permissionIds = validIds,
      )
    }
  }
}
