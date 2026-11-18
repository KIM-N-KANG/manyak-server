package com.knk.manyak.search.scheduler

import com.knk.manyak.search.service.StorySearchIndexer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.opensearch.client.opensearch.OpenSearchClient
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class StorySearchReindexSchedulerTests {
    // 2026-11-18 19:30 UTC = 2026-11-19 04:30 KST
    private val clock = Clock.fixed(Instant.parse("2026-11-18T19:30:00Z"), ZoneOffset.UTC)
    private val indexer = mock(StorySearchIndexer::class.java)
    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST") // Mockito가 제네릭 인터페이스의 목을 원시 타입으로 만든다.
    private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>

    init {
        `when`(redis.opsForValue()).thenReturn(values)
    }

    private fun scheduler(client: OpenSearchClient? = mock(OpenSearchClient::class.java)) = StorySearchReindexScheduler(client, indexer, redis, clock)

    @Test
    fun `KST 날짜 키를 24시간 선점한 인스턴스 하나만 전체 재색인한다`() {
        `when`(values.setIfAbsent("search:story-reindex:2026-11-19", "1", Duration.ofHours(24))).thenReturn(true, false)
        scheduler().run()
        scheduler().run()
        verify(indexer, times(1)).reindex()
    }

    @Test
    fun `Redis 장애로 선점 여부를 모르면 그날은 건너뛴다`() {
        `when`(values.setIfAbsent(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration::class.java)))
            .thenThrow(RedisConnectionFailureException("down"))
        scheduler().run()
        verify(indexer, never()).reindex()
    }

    @Test
    fun `저장소가 없으면 선점도 재색인도 하지 않는다`() {
        scheduler(client = null).run()
        verifyNoInteractions(indexer, redis)
    }

    @Test
    fun `재색인 예외는 스케줄러 밖으로 내보내지 않는다`() {
        `when`(values.setIfAbsent("search:story-reindex:2026-11-19", "1", Duration.ofHours(24))).thenReturn(true)
        org.mockito.Mockito.doThrow(IllegalStateException("bulk")).`when`(indexer).reindex()
        scheduler().run()
        verify(indexer).reindex()
    }
}
