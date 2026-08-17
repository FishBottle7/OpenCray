package com.opencray.app

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeForegroundServiceTypeResolverTest {
  @Test
  fun returnsSpecialUseForegroundTypeForAndroid14AndNewer() {
    val resolver = RuntimeForegroundServiceTypeResolver {
      Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      resolver.foregroundServiceType(notificationModel()),
    )
  }

  @Test
  fun omitsForegroundTypeBeforeAndroid14() {
    val resolver = RuntimeForegroundServiceTypeResolver {
      Build.VERSION_CODES.TIRAMISU
    }

    assertNull(resolver.foregroundServiceType(notificationModel()))
  }

  private fun notificationModel(): RuntimeForegroundNotificationModel =
    RuntimeForegroundNotificationModel(
      activeRunCount = 1,
      activeSessionCount = 1,
      liveManagedProcessSessionCount = 0,
      liveSubAgentSessionCount = 0,
      keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
    )
}
