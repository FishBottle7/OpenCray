package com.opencray.runtime.soul

import com.opencray.runtime.context.RuntimeSoulProfile

class RuntimeSoulTurnPolicyComposer(
  private val seedFactory: RuntimeSoulProfileSeedFactory = RuntimeSoulProfileSeedFactory(),
  private val resolver: SoulProfileResolver = SoulProfileResolver(),
  private val policyBuilder: SoulTurnResponsePolicyBuilder = SoulTurnResponsePolicyBuilder(),
  private val renderer: SoulTurnResponsePolicyRenderer = SoulTurnResponsePolicyRenderer(),
) {
  fun compose(
    profile: RuntimeSoulProfile?,
    signal: SoulTurnSemanticSignal?,
  ): String {
    if (signal == null) {
      return ""
    }
    val seed = seedFactory.create(profile) ?: return ""
    val resolved = resolver.resolve(seed) ?: return ""
    return renderer.render(
      policyBuilder.build(
        profile = resolved,
        signal = signal,
      ),
    )
  }
}
