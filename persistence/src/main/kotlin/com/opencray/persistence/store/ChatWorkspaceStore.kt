package com.opencray.persistence.store

import com.opencray.persistence.model.ChatWorkspaceRecord

interface ChatWorkspaceStore {
  fun load(): ChatWorkspaceRecord?
  fun save(record: ChatWorkspaceRecord)
  fun clear(): Boolean
}
