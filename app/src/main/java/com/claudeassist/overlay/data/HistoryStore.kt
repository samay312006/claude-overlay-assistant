package com.claudeassist.overlay.data

import kotlinx.coroutines.flow.Flow

class HistoryStore(private val dao: HistoryDao) {

    suspend fun add(question: String, answer: String, brainId: String, thumbnailPath: String? = null) {
        dao.insert(
            HistoryEntry(
                question = question,
                answer = answer,
                brainId = brainId,
                timestampMs = System.currentTimeMillis(),
                thumbnailPath = thumbnailPath,
            )
        )
    }

    fun observeRecent(): Flow<List<HistoryEntry>> = dao.observeRecent()

    suspend fun clear() = dao.clear()
}
