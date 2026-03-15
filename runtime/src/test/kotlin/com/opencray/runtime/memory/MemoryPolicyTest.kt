package com.opencray.runtime.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryPolicyTest {
  private val policy = MemoryPolicy()

  @Test
  fun resolveScopeKeepsPreferencesCrossSessionByDefaultButUpgradesWorkspaceSpecificOnes() {
    assertEquals(
      MemoryScope.USER,
      policy.resolveScope(
        kind = MemoryKind.USER_PREFERENCE,
        content = "Default to Simplified Chinese for explanations",
      ),
    )
    assertEquals(
      MemoryScope.WORKSPACE,
      policy.resolveScope(
        kind = MemoryKind.USER_PREFERENCE,
        content = "Prefer PowerShell commands in this repo",
      ),
    )
  }

  @Test
  fun policyAssignsFiniteTtlOnlyToProjectFactsAndTaskCommitments() {
    assertNull(policy.ttlMsFor(MemoryKind.USER_PREFERENCE))
    assertNull(policy.ttlMsFor(MemoryKind.DURABLE_INSTRUCTION))
    assertEquals(90L * 24L * 60L * 60L * 1000L, policy.ttlMsFor(MemoryKind.PROJECT_FACT))
    assertEquals(14L * 24L * 60L * 60L * 1000L, policy.ttlMsFor(MemoryKind.TASK_COMMITMENT))
    assertEquals(MemoryStatus.OPEN, policy.defaultStatusFor(MemoryKind.TASK_COMMITMENT))
  }
}
