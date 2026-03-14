package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
  @Test
  fun flutterShellBootstrapKeepsRootRoute() {
    assertEquals("/", OpenCrayFlutterActivity.Destination.SHELL.route)
  }
}
