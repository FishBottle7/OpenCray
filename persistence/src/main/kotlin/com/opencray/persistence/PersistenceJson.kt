package com.opencray.persistence

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

object PersistenceJson {
  @OptIn(ExperimentalSerializationApi::class)
  val instance: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "persistenceType"
  }
}
