package com.opencray.app.facade.llm

internal class ProviderStreamErrorException(
  val providerErrorCode: String?,
  message: String,
) : RuntimeException(message)
