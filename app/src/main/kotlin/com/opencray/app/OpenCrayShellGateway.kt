package com.opencray.app

internal interface OpenCrayShellGateway {
  fun loadShellSnapshot(): Map<String, Any?>

  fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit
}
