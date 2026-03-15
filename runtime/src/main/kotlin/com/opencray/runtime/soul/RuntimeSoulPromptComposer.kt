package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile

class RuntimeSoulPromptComposer(
  private val seedFactory: RuntimeSoulProfileSeedFactory = RuntimeSoulProfileSeedFactory(),
  private val resolver: SoulProfileResolver = SoulProfileResolver(),
  private val renderer: SoulPromptRenderer = SoulPromptRenderer(),
) {
  fun compose(profile: RuntimeSoulProfile?): String {
    val seed = seedFactory.create(profile) ?: return ""
    val resolved = resolver.resolve(seed) ?: return ""
    return renderer.render(resolved)
  }
}
