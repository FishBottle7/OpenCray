package com.opencray.runtime.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptLayerTest {
  @Test
  fun renderReturnsBlankWhenNothingWasRecalled() {
    val layer = MemoryPromptLayer()

    assertEquals("", layer.render(MemoryRecallResult()))
  }

  @Test
  fun renderFormatsMemoriesAndBudgetNotice() {
    val layer = MemoryPromptLayer()

    val rendered = layer.render(
      MemoryRecallResult(
        memories = listOf(
          RetrievedMemory(
            id = "memory-1",
            kind = MemoryKind.USER_PREFERENCE,
            scope = MemoryScope.USER,
            status = MemoryStatus.ACTIVE,
            content = "Default to concise Chinese replies.",
            lastConfirmedAtEpochMs = 10L,
            score = 420,
          ),
          RetrievedMemory(
            id = "memory-2",
            kind = MemoryKind.DURABLE_INSTRUCTION,
            scope = MemoryScope.WORKSPACE,
            status = MemoryStatus.ACTIVE,
            content = "Do not revert user changes without approval.",
            lastConfirmedAtEpochMs = 11L,
            score = 410,
          ),
        ),
        matchedRecordCount = 3,
        omittedRecordCount = 1,
      ),
    )

    assertTrue(rendered.contains("Use recalled durable context"))
    assertTrue(rendered.contains("kind=user_preference scope=user content=Default to concise Chinese replies."))
    assertTrue(rendered.contains("kind=durable_instruction scope=workspace content=Do not revert user changes without approval."))
    assertTrue(rendered.contains("Omitted 1 additional memory record(s) due to recall budget."))
  }

  @Test
  fun renderMinimalKeepsOnlyHighestPriorityMemoryWithoutRecallBudgetNotice() {
    val layer = MemoryPromptLayer()

    val rendered = layer.render(
      result = MemoryRecallResult(
        memories = listOf(
          RetrievedMemory(
            id = "memory-1",
            kind = MemoryKind.USER_PREFERENCE,
            scope = MemoryScope.USER,
            status = MemoryStatus.ACTIVE,
            content = "Keep replies concise.",
            lastConfirmedAtEpochMs = 10L,
            score = 420,
          ),
          RetrievedMemory(
            id = "memory-2",
            kind = MemoryKind.DURABLE_INSTRUCTION,
            scope = MemoryScope.WORKSPACE,
            status = MemoryStatus.ACTIVE,
            content = "Do not revert user changes without approval.",
            lastConfirmedAtEpochMs = 11L,
            score = 410,
          ),
        ),
        omittedRecordCount = 3,
      ),
      detailMode = MemoryPromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.contains("Keep replies concise."))
    assertFalse(rendered.contains("Do not revert user changes without approval."))
    assertFalse(rendered.contains("Omitted 3 additional memory record(s) due to recall budget."))
  }
}
