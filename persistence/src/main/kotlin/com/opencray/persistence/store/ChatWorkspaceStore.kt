package com.opencray.persistence.store

import com.opencray.persistence.model.ChatWorkspaceRecord

interface ChatWorkspaceStore {
  fun load(): ChatWorkspaceRecord?
  fun save(record: ChatWorkspaceRecord)
  fun <R> update(
    transform: (ChatWorkspaceRecord?) -> ChatWorkspaceStoreUpdate<R>,
  ): R {
    val updated = transform(load())
    if (updated.write) {
      val record = updated.record
      if (record == null) {
        clear()
      } else {
        save(record)
      }
    }
    return updated.result
  }

  fun clear(): Boolean
}

data class ChatWorkspaceStoreUpdate<R>(
  val record: ChatWorkspaceRecord?,
  val result: R,
  val write: Boolean = true,
)
