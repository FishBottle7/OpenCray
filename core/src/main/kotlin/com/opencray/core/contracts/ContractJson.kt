package com.opencray.core.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

object ContractJson {
  @OptIn(ExperimentalSerializationApi::class)
  val instance: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "contractType"
  }
}
