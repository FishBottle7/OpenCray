package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNotificationKeysTest {
  @Test
  fun approvalNotificationsWithCollidingHashBucketKeepDistinctTagsAndCancelIndependently() {
    val (taskA, taskB) = collidingPair(modulo = 5_000, prefix = "approval-task")

    val keyA = RuntimeNotificationKeys.approvalKey(
      sessionId = "session-a",
      runId = "run-a",
      taskId = taskA,
      executionBinding = RuntimeApprovalExecutionBinding(
        executionId = "execution-a",
        executionOrdinal = 1,
      ),
    )
    val keyB = RuntimeNotificationKeys.approvalKey(
      sessionId = "session-b",
      runId = "run-b",
      taskId = taskB,
      executionBinding = RuntimeApprovalExecutionBinding(
        executionId = "execution-b",
        executionOrdinal = 1,
      ),
    )

    assertEquals(keyA.id, keyB.id)
    assertNotEquals(keyA.tag, keyB.tag)
    assertNotEquals(keyA, keyB)

    val taggedStore = LinkedHashMap<Pair<String?, Int>, String>()
    fun notifyTagged(key: RuntimeNotificationKey, label: String) {
      taggedStore[key.tag to key.id] = label
    }
    fun cancelTagged(key: RuntimeNotificationKey) {
      taggedStore.remove(key.tag to key.id)
    }
    notifyTagged(keyA, "approval-a")
    notifyTagged(keyB, "approval-b")
    assertEquals(2, taggedStore.size)
    cancelTagged(keyA)
    assertEquals("approval-b", taggedStore[keyB.tag to keyB.id])

    val legacyStore = linkedMapOf<Int, String>()
    legacyStore[keyA.id] = "legacy-a"
    legacyStore.remove(keyB.id)
    assertTrue(legacyStore.isEmpty())
  }

  @Test
  fun approvalNotificationsForSameTaskAcrossExecutionsShareSlotOnlyViaTag() {
    val keyFirst = RuntimeNotificationKeys.approvalKey(
      sessionId = "session-x",
      runId = "run-1",
      taskId = "task-x",
      executionBinding = RuntimeApprovalExecutionBinding(executionId = "execution-1"),
    )
    val keyRetried = RuntimeNotificationKeys.approvalKey(
      sessionId = "session-x",
      runId = "run-2",
      taskId = "task-x",
      executionBinding = RuntimeApprovalExecutionBinding(executionId = "execution-2"),
    )

    assertEquals(keyFirst.id, keyRetried.id)
    assertNotEquals(keyFirst.tag, keyRetried.tag)
    assertNotEquals(
      RuntimeNotificationKeys.approvalActionRequestKey(
        action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
        sessionId = "session-x",
        runId = "run-1",
        taskId = "task-x",
        executionBinding = RuntimeApprovalExecutionBinding(executionId = "execution-1"),
      ),
      RuntimeNotificationKeys.approvalActionRequestKey(
        action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
        sessionId = "session-x",
        runId = "run-2",
        taskId = "task-x",
        executionBinding = RuntimeApprovalExecutionBinding(executionId = "execution-2"),
      ),
    )
  }

  @Test
  fun approvalActionRequestCodesMayCollideWhileRequestPayloadsStayDistinct() {
    val action = RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL
    var baselineToken: String? = null
    var collidingToken: String? = null
    val seen = HashMap<Int, String>()
    for (index in 0 until 2_000_000) {
      val token = "execution-collision-$index"
      val code = RuntimeNotificationKeys.stableRequestCode(
        RuntimeNotificationKeys.approvalActionRequestKey(
          action = action,
          sessionId = "session-c",
          runId = "run-c",
          taskId = "task-c",
          executionBinding = RuntimeApprovalExecutionBinding(executionId = token),
        ),
      )
      val previous = seen.put(code, token)
      if (previous != null) {
        baselineToken = previous
        collidingToken = token
        break
      }
    }
    val firstToken = requireNotNull(baselineToken)
    val secondToken = requireNotNull(collidingToken)

    fun requestKey(token: String): String = RuntimeNotificationKeys.approvalActionRequestKey(
      action = action,
      sessionId = "session-c",
      runId = "run-c",
      taskId = "task-c",
      executionBinding = RuntimeApprovalExecutionBinding(executionId = token),
    )

    assertEquals(
      RuntimeNotificationKeys.stableRequestCode(requestKey(firstToken)),
      RuntimeNotificationKeys.stableRequestCode(requestKey(secondToken)),
    )
    assertNotEquals(requestKey(firstToken), requestKey(secondToken))
    assertNotEquals(
      RuntimeNotificationKeys.approvalKey(
        sessionId = "session-c",
        runId = "run-c",
        taskId = "task-c",
        executionBinding = RuntimeApprovalExecutionBinding(executionId = firstToken),
      ),
      RuntimeNotificationKeys.approvalKey(
        sessionId = "session-c",
        runId = "run-c",
        taskId = "task-c",
        executionBinding = RuntimeApprovalExecutionBinding(executionId = secondToken),
      ),
    )
  }

  @Test
  fun terminalScheduleAndRecoveredKeysSeparateUnrelatedNotifications() {
    assertEquals(
      RuntimeNotificationKeys.terminalKey(runId = "run-t", taskId = "task-t", interrupted = true),
      RuntimeNotificationKeys.terminalKey(runId = "run-t", taskId = "task-t", interrupted = true),
    )
    assertNotEquals(
      RuntimeNotificationKeys.terminalKey(runId = "run-t", taskId = "task-t", interrupted = true),
      RuntimeNotificationKeys.terminalKey(runId = "run-t", taskId = "task-t", interrupted = false),
    )
    assertNotEquals(
      RuntimeNotificationKeys.terminalKey(runId = "run-t", taskId = "task-t", interrupted = false),
      RuntimeNotificationKeys.terminalKey(runId = "run-u", taskId = "task-t", interrupted = false),
    )
    assertNotEquals(
      RuntimeNotificationKeys.scheduleKey("schedule-s", ScheduledTaskRunResult.ACCEPTED.name),
      RuntimeNotificationKeys.scheduleKey("schedule-s", ScheduledTaskRunResult.FAILED_DISPATCH.name),
    )
    assertNotEquals(
      RuntimeNotificationKeys.scheduleKey("schedule-s", ScheduledTaskRunResult.ACCEPTED.name),
      RuntimeNotificationKeys.scheduleKey("schedule-t", ScheduledTaskRunResult.ACCEPTED.name),
    )
    assertNotEquals(
      RuntimeNotificationKeys.recoveredKey("process-1"),
      RuntimeNotificationKeys.recoveredKey("process-2"),
    )
  }

  @Test
  fun executionBindingMatchingDistinguishesLegacyAndFreshIdentities() {
    val fresh = RuntimeApprovalExecutionBinding(executionId = "execution-1", executionOrdinal = 1)
    val freshCopy = RuntimeApprovalExecutionBinding(executionId = "execution-1", executionOrdinal = 1)
    val retried = RuntimeApprovalExecutionBinding(executionId = "execution-2", executionOrdinal = 1)
    val ordinalOnly = RuntimeApprovalExecutionBinding(executionOrdinal = 1)
    val legacy = RuntimeApprovalExecutionBinding()

    assertTrue(fresh.matches(freshCopy))
    assertTrue(legacy.matches(legacy))
    assertTrue(!fresh.matches(retried))
    assertTrue(!fresh.matches(legacy))
    assertTrue(!legacy.matches(fresh))
    assertTrue(!fresh.matches(ordinalOnly))
    assertTrue(!retried.matches(fresh))
    assertEquals("execution-1", fresh.identityToken())
    assertEquals("ordinal-3", RuntimeApprovalExecutionBinding(executionOrdinal = 3).identityToken())
    assertEquals("unknown", legacy.identityToken())
  }

  private fun collidingPair(modulo: Int, prefix: String): Pair<String, String> {
    val seen = HashMap<Int, String>()
    for (index in 0 until 5_000_000) {
      val candidate = "$prefix-$index"
      val bucket = RuntimeNotificationKeys.notificationStableHash(candidate, modulo)
      val previous = seen.put(bucket, candidate)
      if (previous != null) {
        return previous to candidate
      }
    }
    error("Unable to locate deterministic hash collision for modulo $modulo.")
  }
}
