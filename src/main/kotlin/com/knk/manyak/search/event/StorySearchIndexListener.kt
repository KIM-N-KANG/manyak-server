package com.knk.manyak.search.event

import com.knk.manyak.search.config.StorySearchProperties
import com.knk.manyak.search.service.StorySearchIndexAdmin
import com.knk.manyak.search.service.StorySearchIndexer
import com.knk.manyak.story.event.StoriesIndexRequestedEvent
import com.knk.manyak.story.event.StoryIndexRequestedEvent
import org.opensearch.client.opensearch.OpenSearchClient
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
            log.warn("스토리 색인 실패 (error={})", ex.javaClass.simpleName)
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStoriesIndexRequested(event: StoriesIndexRequestedEvent) {
        try {
            indexer.indexAll(event.storyIds)
        } catch (ex: Exception) {
            // 묶음별 실패는 색인기가 남긴다. 여기는 인덱스 확인처럼 묶음에 들어가기 전의 실패다.
            log.warn("스토리 묶음 색인 실패 (stories={}, error={})", event.storyIds.size, ex.javaClass.simpleName)
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

@Component
class StorySearchIndexBootstrap(
    private val client: OpenSearchClient?,
    private val admin: StorySearchIndexAdmin,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (client == null) return
        try {
            admin.ensureIndex()
        } catch (ex: Exception) {
            log.warn("스토리 검색 인덱스 초기화 실패 (error={})", ex.javaClass.simpleName)
        }
    }
}
