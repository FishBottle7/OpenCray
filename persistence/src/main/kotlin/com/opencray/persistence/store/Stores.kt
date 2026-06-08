package com.opencray.persistence.store

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.model.SoulRecord

data class SessionStoreUpdate<T>(
  val record: SessionRecord?,
  val result: T,
)

interface SessionStore {
  fun load(): SessionRecord?
  fun save(record: SessionRecord)
  fun clear(): Boolean

  fun <T> update(update: (SessionRecord?) -> SessionStoreUpdate<T>): T {
    val current = load()
    val updated = update(current)
    if (updated.record == null) {
      clear()
    } else {
      save(updated.record)
    }
    return updated.result
  }
}

interface SoulStore {
  fun load(): SoulRecord?
  fun save(record: SoulRecord)
  fun clear(): Boolean
}

interface MemoryStore {
  fun list(): List<MemoryRecord>
  fun upsert(record: MemoryRecord)
  fun delete(id: String): Boolean
  fun clear(): Boolean
}
