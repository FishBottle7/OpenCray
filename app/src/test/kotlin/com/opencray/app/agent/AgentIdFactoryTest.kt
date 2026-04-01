package com.opencray.app.agent

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIdFactoryTest {
  @Test
  fun generatedIdsUseExpectedPrefixesAndStayUniqueAcrossTypes() {
    val factory = AgentIdFactory(
      nowEpochMs = { 42L },
      randomToken = { "A-B_C!" },
    )

    val agentId = factory.newAgentId()
    val sessionId = factory.newSessionId()
    val runId = factory.newRunId()
    val taskId = factory.newTaskId()

    assertTrue(agentId.startsWith("agent-42-1-abc"))
    assertTrue(sessionId.startsWith("session-42-2-abc"))
    assertTrue(runId.startsWith("run-42-3-abc"))
    assertTrue(taskId.startsWith("task-42-4-abc"))
    assertNotEquals(agentId, sessionId)
    assertNotEquals(sessionId, runId)
    assertNotEquals(runId, taskId)
  }
}
