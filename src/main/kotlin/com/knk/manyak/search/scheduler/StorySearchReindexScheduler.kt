package com.knk.manyak.search.scheduler

import com.knk.manyak.search.service.StorySearchIndexer
import org.opensearch.client.opensearch.OpenSearchClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * 매일 04:30(KST) 스토리 검색 인덱스를 전체 재색인한다(STORY 4-1-27, 결정 28). 단건, 묶음 색인 실패와 홈서버 장애로 빠진 문서를 채운다.
 * 인덱스가 없으면 [StorySearchIndexer.reindex]가 만든다.
 *
 * 같은 날 1회는 Redis 날짜 키 `SET NX`(24시간)로 보장한다(출석 리마인드 스케줄러와 같은 방식). 실행 중 종료돼도 키가 남아 그날은
 * 다시 돌지 않는다. Redis 장애로 선점 여부를 모르면 건너뛴다. 저장소가 설정되지 않았으면 선점도 하지 않는다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.search.reindex-schedule.enabled"], havingValue = "true", matchIfMissing = true)
class StorySearchReindexScheduler(
    private val client: OpenSearchClient?,
    private val indexer: StorySearchIndexer,
    private val redisTemplate: StringRedisTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${manyak.search.reindex-schedule.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun run() {
        if (client == null) return
        // 예외를 밖으로 내보내지 않는다. 한 번의 실패로 이후 실행이 멈추면 안 된다. 로그는 예외 클래스 이름만 남긴다(STORY 5-4).
        try {
            if (!claimToday()) return
            indexer.reindex()
        } catch (ex: Exception) {
            log.warn("스토리 정기 재색인 실패 (error={})", ex.javaClass.simpleName)
        }
    }

    private fun claimToday(): Boolean = try {
        val today = LocalDate.now(clock.withZone(SEOUL))
        redisTemplate.opsForValue().setIfAbsent("$CLAIM_KEY_PREFIX$today", "1", CLAIM_TTL) == true
    } catch (ex: Exception) {
        log.warn("스토리 정기 재색인 선점 실패 (error={})", ex.javaClass.simpleName)
        false
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val CLAIM_KEY_PREFIX = "search:story-reindex:"
        val CLAIM_TTL: Duration = Duration.ofHours(24)
    }
}
