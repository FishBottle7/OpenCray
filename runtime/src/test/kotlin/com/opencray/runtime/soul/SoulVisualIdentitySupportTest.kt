package com.opencray.runtime.soul

import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import com.opencray.runtime.OpenCraySoulVisualIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SoulVisualIdentitySupportTest {
  @Test
  fun encodeAndDecodeRoundTripsVisualIdentity() {
    val encoded = SoulVisualIdentitySupport.encodeIntoExtensions(
      extensions = mapOf(SoulProfileExtensionKeys.TONE to "warm"),
      visualIdentity = OpenCraySoulVisualIdentity(
        portraitSummary = "Short dark hair, practical coat, calm expression.",
        primaryPortrait = OpenCrayImageReference(
          refId = "portrait-1",
          role = OpenCrayImageReferenceRole.PORTRAIT,
          storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
          relativePath = "soul-assets/portrait/portrait-1.png",
          mimeType = "image/png",
          sha256 = "b".repeat(64),
          widthPx = 1024,
          heightPx = 1024,
          caption = "Primary portrait",
          summary = "Front-facing portrait with restrained expression.",
          sourceLabel = "settings_asset",
          createdAtEpochMs = 120L,
        ),
      ),
    )

    val decoded = SoulVisualIdentitySupport.decodeFromExtensions(encoded)

    assertNotNull(decoded)
    assertEquals("Short dark hair, practical coat, calm expression.", decoded?.portraitSummary)
    assertEquals("portrait-1", decoded?.primaryPortrait?.refId)
    assertEquals("warm", encoded[SoulProfileExtensionKeys.TONE])
  }

  @Test
  fun encodeRemovesKeyWhenVisualIdentityIsNull() {
    val encoded = SoulVisualIdentitySupport.encodeIntoExtensions(
      extensions = mapOf(
        SoulProfileExtensionKeys.TONE to "warm",
        SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON to """{"portraitSummary":"old"}""",
      ),
      visualIdentity = null,
    )

    assertEquals("warm", encoded[SoulProfileExtensionKeys.TONE])
    assertFalse(encoded.containsKey(SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON))
  }

  @Test
  fun decodeReturnsNullForInvalidPayload() {
    val decoded = SoulVisualIdentitySupport.decodeFromExtensions(
      mapOf(SoulProfileExtensionKeys.VISUAL_IDENTITY_JSON to "{oops"),
    )

    assertNull(decoded)
  }
}
