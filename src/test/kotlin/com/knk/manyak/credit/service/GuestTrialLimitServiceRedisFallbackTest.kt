package com.knk.manyak.credit.service

import com.knk.manyak.global.observability.DeviceIdHasher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 체험 카운터 Redis 장애 시 회원 흐름 폴백 검증(스펙 §4-3-7 B13).
 *
 * B13 이전 회원 흐름은 Redis에 의존하지 않았으므로, 잔여 조회 불가가 크레딧 보유 회원의 유료 사용을 막으면 회귀다.
 * 커넥션 획득이 실패하는 팩토리로 실제 `DataAccessException`을 유발해, 예약은 false 폴백, 복원은 예외를 삼키는지 본다.
 */
class GuestTrialLimitServiceRedisFallbackTest {

    private fun serviceWithFailingRedis(): GuestTrialLimitService {
        val factory = mock(RedisConnectionFactory::class.java)
        `when`(factory.connection).thenThrow(RedisConnectionFailureException("redis down"))
        val template = StringRedisTemplate(factory)
        template.afterPropertiesSet()
        return GuestTrialLimitService(template, mock(DeviceIdHasher::class.java), 5, 1, 5)
    }

    @Test
    fun `Redis 장애 시 회원 예약은 false로 폴백해 크레딧 경로를 막지 않는다`() {
        assertThat(serviceWithFailingRedis().reserveMember(1L, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
    }

    @Test
    fun `Redis 장애 시 회원 복원은 예외를 삼킨다`() {
        assertThatCode {
            serviceWithFailingRedis().restoreMember(1L, GuestTrialLimitService.Counter.CHAT_TURN)
        }.doesNotThrowAnyException()
    }
}
