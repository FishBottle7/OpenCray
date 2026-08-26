package com.opencray.core.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

object ContractJson {
  @OptIn(ExperimentalSerializationApi::class)
  val instance: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "contractType"
  }

  fun <T> decodeValidatingSchemaVersion(json: String, serializer: KSerializer<T>): T {
    val element = instance.parseToJsonElement(json)
    val schemaVersion = (element as? JsonObject)
      ?.get("schemaVersion")
      ?.jsonPrimitive
      ?.intOrNull
      ?: ContractSchemaVersion.CURRENT
    if (schemaVersion > ContractSchemaVersion.CURRENT) {
      throw IllegalArgumentException(
        "Unsupported contract schemaVersion $schemaVersion; this build supports up to " +
          "${ContractSchemaVersion.CURRENT}.",
      )
    }
    return instance.decodeFromJsonElement(serializer, element)
  }

  inline fun <reified T> decodeFromStringGated(json: String): T =
    decodeValidatingSchemaVersion(json, serializer())
}
