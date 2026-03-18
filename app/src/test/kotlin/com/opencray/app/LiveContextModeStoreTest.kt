package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveContextModeStoreTest {
  @Test
  fun loadDefaultsToFullMode() {
    val store = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore())

    assertEquals(LiveContextMode.FULL, store.load())
  }

  @Test
  fun saveAndLoadRoundTripsLiveContextMode() {
    val store = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore())

    store.save(LiveContextMode.NO_MEMORY_OR_SOUL)

    assertEquals(LiveContextMode.NO_MEMORY_OR_SOUL, store.load())
  }
}
