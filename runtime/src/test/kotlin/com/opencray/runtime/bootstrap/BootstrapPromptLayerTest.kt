package com.opencray.runtime.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapPromptLayerTest {
  @Test
  fun renderMinimalKeepsSourceIdentityWhileTruncatingBody() {
    val layer = BootstrapPromptLayer(
      config = BootstrapPromptLayerConfig(
        maxCompactChars = 128,
        maxMinimalChars = 64,
      ),
    )
    val snippet = BootstrapSnippet(
      name = "AGENTS.md",
      relativePath = "workspace/AGENTS.md",
      content = "Use repo conventions carefully.\n" + "Follow the active workspace policy. ".repeat(8).trim(),
      sourceCharCount = 320,
      truncated = true,
    )

    val rendered = layer.render(
      snippet = snippet,
      detailMode = BootstrapPromptDetailMode.MINIMAL,
    )

    assertEquals("Bootstrap workspace/AGENTS.md", rendered.layerName)
    assertTrue(rendered.text.contains("source_file=workspace/AGENTS.md"))
    assertTrue(rendered.text.contains("truncated=true"))
    assertTrue(rendered.text.contains("source_chars=320"))
    assertTrue(rendered.text.contains("prompt_truncated=true"))
    assertTrue(rendered.text.contains("Use repo conventions carefully."))
    assertFalse(rendered.text.contains("Follow the active workspace policy. Follow the active workspace policy. Follow"))
  }
}
