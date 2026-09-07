package com.knk.manyak.search.event

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.service.StorySearchIndexer
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class StorySearchIndexListener(private val indexer: StorySearchIndexer) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onIndexRequested(event: StoryIndexRequestedEvent) {
        try {
            indexer.index(event.storyId)
        } catch (ex: Exception) {
            log.warn("스토리 색인 실패 (storyId={}, error={})", event.storyId, ex.javaClass.simpleName)
        }
    }
}

@Component
class StorySearchReindexRunner(private val properties: StorySearchProperties, private val indexer: StorySearchIndexer) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!properties.reindexOnStartup) return
        try {
            indexer.reindex()
        } catch (ex: Exception) {
            log.warn("스토리 재색인 실패 (error={})", ex.javaClass.simpleName)
        }
    }
}
