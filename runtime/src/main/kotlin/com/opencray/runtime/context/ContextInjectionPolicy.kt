package com.opencray.runtime.context

data class ContextInjectionPolicy(
  val soulContractEnabled: Boolean = true,
  val soulTurnPolicyEnabled: Boolean = true,
  val automaticMemoryInjectionEnabled: Boolean = true,
  val memoryDerivedPolicyEnabled: Boolean = true,
)
