package com.opencray.core.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@Serializable
private data class SchemaVersionProbe(
  val schemaVersion: Int = ContractSchemaVersion.CURRENT,
  val payload: String = "",
)

class ContractJsonSchemaVersionGateTest {

  @Test
  fun futureSchemaVersionIsRejectedWithExplicitVersionMessage() {
    val encoded = """{"schemaVersion":99,"payload":"from-a-future-build"}"""

    try {
      ContractJson.decodeFromStringGated<SchemaVersionProbe>(encoded)
      fail("Expected IllegalArgumentException for future schemaVersion.")
    } catch (expected: IllegalArgumentException) {
      val message = requireNotNull(expected.message)
      assertTrue(message.contains("99"))
      assertTrue(message.contains(ContractSchemaVersion.CURRENT.toString()))
    }
  }

  @Test
  fun currentSchemaVersionDecodesUnchanged() {
    val probe = SchemaVersionProbe(payload = "current-payload")

    val encoded = ContractJson.instance.encodeToString(probe)
    val decoded = ContractJson.decodeFromStringGated<SchemaVersionProbe>(encoded)

    assertEquals(probe, decoded)
    assertTrue(encoded.contains("\"schemaVersion\":${ContractSchemaVersion.CURRENT}"))
  }

  @Test
  fun missingSchemaVersionStillDecodesAsCurrentVersion() {
    val encoded = """{"payload":"legacy-writer"}"""

    val decoded = ContractJson.decodeFromStringGated<SchemaVersionProbe>(encoded)

    assertEquals(SchemaVersionProbe(payload = "legacy-writer"), decoded)
  }
}
