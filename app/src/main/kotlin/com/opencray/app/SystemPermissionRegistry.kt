package com.opencray.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Declarative registry of runtime permissions consumed by agent system ability
 * tools. Each spec states what the permission unlocks, why the user should
 * grant it, and which tool results surface the request. The chat UI projects
 * these specs into permission cards; SystemPermissionActivity turns the
 * grantPurpose into the purpose line above the system dialog.
 */
internal data class SystemPermissionSpec(
  val id: String,
  val androidPermissions: List<String>,
  val title: String,
  val grantPurpose: String,
  val grantBenefits: List<String>,
  val requestingTools: List<String>,
  val requestSupported: Boolean = true,
)

internal object SystemPermissionIds {
  const val CALENDAR: String = "calendar"
  const val CONTACTS: String = "contacts"
  const val NOTIFICATIONS: String = "notifications"
}

internal object SystemPermissionActionIds {
  const val REQUEST: String = "request"
  const val RECHECK: String = "recheck"
}

/**
 * Result contract for the bridge-level permission request action. `launched`
 * means the system permission activity was brought to the foreground; the
 * refusal reasons are stable strings the chat UI can branch on.
 */
internal fun systemPermissionActionResult(
  actionId: String,
  launched: Boolean,
  reason: String? = null,
  permissionIds: List<String> = emptyList(),
): Map<String, Any?> = buildMap {
  put("source", "system-permission-action")
  put("actionId", actionId)
  put("launched", launched)
  if (permissionIds.isNotEmpty()) {
    put("permissionIds", permissionIds)
  }
  if (!launched && reason != null) {
    put("reason", reason)
  }
}

/**
 * Launches the transparent permission activity for the given registry ids.
 * Both gateway implementations delegate here so the no-duplicate-prompt rule
 * and result shape stay in one place.
 */
internal interface SystemPermissionRequestLauncher {
  fun launchRequest(permissionIds: List<String>): Map<String, Any?>
}

internal object NoOpSystemPermissionRequestLauncher : SystemPermissionRequestLauncher {
  override fun launchRequest(permissionIds: List<String>): Map<String, Any?> =
    systemPermissionActionResult(
      actionId = SystemPermissionActionIds.REQUEST,
      launched = false,
      reason = "unavailable",
    )
}

internal class AndroidSystemPermissionRequestLauncher(
  private val appContext: Context,
) : SystemPermissionRequestLauncher {
  override fun launchRequest(permissionIds: List<String>): Map<String, Any?> {
    val validIds = SystemPermissionRegistry.specs
      .map { it.id }
      .filter { it in permissionIds }
    if (validIds.isEmpty()) {
      return systemPermissionActionResult(
        actionId = SystemPermissionActionIds.REQUEST,
        launched = false,
        reason = "unknown_permission_ids",
      )
    }
    if (SystemPermissionRequestGate.isActive(validIds)) {
      return systemPermissionActionResult(
        actionId = SystemPermissionActionIds.REQUEST,
        launched = false,
        reason = "request_already_active",
      )
    }
    SystemPermissionRequestGate.markActive(validIds)
    SystemPermissionActivity.launch(appContext, validIds)
    return systemPermissionActionResult(
      actionId = SystemPermissionActionIds.REQUEST,
      launched = true,
      permissionIds = validIds,
    )
  }
}

internal object SystemPermissionRegistry {

  val specs: List<SystemPermissionSpec> = listOf(
    SystemPermissionSpec(
      id = SystemPermissionIds.CALENDAR,
      androidPermissions = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
      ),
      title = "日历",
      grantPurpose = "用于读取与创建设备日历事件，例如安排日程或确认你最近的时间安排。",
      grantBenefits = listOf(
        "读取未来日程（system_calendar_upcoming）",
        "在主日历创建事件（system_calendar_create）",
      ),
      requestingTools = listOf(
        "system_calendar_upcoming",
        "system_calendar_create",
      ),
    ),
    SystemPermissionSpec(
      id = SystemPermissionIds.CONTACTS,
      androidPermissions = listOf(Manifest.permission.READ_CONTACTS),
      title = "联系人",
      grantPurpose = "用于按姓名、电话或邮箱查找联系人，例如帮你补充收件人或来电人信息。",
      grantBenefits = listOf(
        "搜索联系人及号码、邮箱（system_contact_search）",
      ),
      requestingTools = listOf(
        "system_contact_search",
      ),
    ),
    SystemPermissionSpec(
      id = SystemPermissionIds.NOTIFICATIONS,
      androidPermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
      title = "通知",
      grantPurpose = "用于在后台任务完成或定时提醒到达时发送本地通知。",
      grantBenefits = listOf(
        "发送本地通知（system_notification_post）",
      ),
      requestingTools = listOf(
        "system_notification_post",
      ),
    ),
  )

  fun specById(id: String): SystemPermissionSpec? = specs.firstOrNull { it.id == id }

  fun specForAndroidPermission(permission: String): SystemPermissionSpec? =
    specs.firstOrNull { it.androidPermissions.contains(permission) }
}

internal data class SystemPermissionState(
  val spec: SystemPermissionSpec,
  val granted: Boolean,
)

internal interface SystemPermissionSnapshotAccess {
  fun loadSnapshot(): Map<String, Any?>
}

internal class AndroidSystemPermissionSnapshotAccess private constructor(
  private val permissionGrantedProvider: (String) -> Boolean,
) : SystemPermissionSnapshotAccess {
  override fun loadSnapshot(): Map<String, Any?> {
    val states = SystemPermissionRegistry.specs.map { spec ->
      SystemPermissionState(
        spec = spec,
        granted = spec.androidPermissions.all(permissionGrantedProvider),
      )
    }
    return SystemPermissionSnapshotProjector.project(states)
  }

  fun permissionIdsByMissingAndroidPermission(missingPermissions: List<String>): List<String> =
    SystemPermissionRegistry.specs
      .filter { spec ->
        spec.androidPermissions.any { it in missingPermissions }
      }
      .map { it.id }

  fun requestableSpecs(ids: List<String>): List<SystemPermissionSpec> =
    SystemPermissionRegistry.specs.filter { spec ->
      spec.id in ids && spec.requestSupported
    }

  companion object {
    fun fromContext(context: Context): AndroidSystemPermissionSnapshotAccess =
      AndroidSystemPermissionSnapshotAccess(
        permissionGrantedProvider = { permission ->
          ContextCompat.checkSelfPermission(
            context.applicationContext,
            permission,
          ) == PackageManager.PERMISSION_GRANTED
        },
      )
  }
}

/**
 * JVM-test fallback: no Android context means no runtime permission state, so
 * report every spec as not granted and the snapshot as unavailable. The chat UI
 * hides permission cards when available=false.
 */
internal object UnavailableSystemPermissionSnapshotAccess : SystemPermissionSnapshotAccess {
  override fun loadSnapshot(): Map<String, Any?> =
    SystemPermissionSnapshotProjector.project(
      SystemPermissionRegistry.specs.map { spec ->
        SystemPermissionState(spec = spec, granted = false)
      },
    ) + mapOf("available" to false)
}

internal object SystemPermissionSnapshotProjector {

  fun project(states: List<SystemPermissionState>): Map<String, Any?> = mapOf(
    "source" to "system-permissions",
    "available" to true,
    "permissions" to states.map(::permissionDescriptor),
  )

  fun permissionDescriptor(state: SystemPermissionState): Map<String, Any?> = mapOf(
    "id" to state.spec.id,
    "title" to state.spec.title,
    "granted" to state.granted,
    "requestSupported" to state.spec.requestSupported,
    "grantPurpose" to state.spec.grantPurpose,
    "grantBenefits" to state.spec.grantBenefits,
    "requestingTools" to state.spec.requestingTools,
    "androidPermissions" to state.spec.androidPermissions,
  )
}
