package com.opencray.persistence.store

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.model.SoulRecord

interface SessionStore {
  fun load(): SessionRecord?
  fun save(record: SessionRecord)
  fun clear(): Boolean
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
