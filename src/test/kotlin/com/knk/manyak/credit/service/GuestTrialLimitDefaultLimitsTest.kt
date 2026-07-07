package com.knk.manyak.credit.service

import com.knk.manyak.global.observability.DeviceIdHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import redis.embedded.RedisServer
import java.net.ServerSocket

/**
 * `application.yml`의 게스트 체험 한도 **기본값**이 스펙 §4-3-7(KNK-480 축소)대로 5·1·5로 배선되는지 검증한다.
 *
 * [GuestTrialLimitServiceIntegrationTest]가 `@TestPropertySource`로 한도를 3으로 고정해 예약·복원 *메커니즘*을
 * 검증하는 것과 달리, 이 테스트는 오버라이드 없이 실 설정값을 그대로 써서 카운터별 **기본 한도 경계**를 고정한다.
 */
@DataRedisTest
@Import(GuestTrialLimitService::class, DeviceIdHasher::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuestTrialLimitDefaultLimitsTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var service: GuestTrialLimitService

    @BeforeEach
    fun flush() {
        redisTemplate.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `스토리라인 생성 기본 한도는 5다`() {
        assertReservableExactly(GuestTrialLimitService.Counter.STORYLINE_GENERATION, limit = 5)
    }

    @Test
    fun `스토리 생성 기본 한도는 1이다`() {
        assertReservableExactly(GuestTrialLimitService.Counter.STORY_CREATION, limit = 1)
    }

    @Test
    fun `채팅 턴 기본 한도는 5다`() {
        assertReservableExactly(GuestTrialLimitService.Counter.CHAT_TURN, limit = 5)
    }

    /** [limit]번은 예약에 성공하고 그다음 한 번은 거절돼야 한다(경계값). 카운터별로 독립 디바이스를 쓴다. */
    private fun assertReservableExactly(counter: GuestTrialLimitService.Counter, limit: Int) {
        val deviceId = "device-${counter.key}"
        repeat(limit) {
            assertThat(service.reserve(deviceId, counter))
                .describedAs("한도 미만 %d번째 예약", it + 1)
                .isTrue()
        }
        assertThat(service.reserve(deviceId, counter))
            .describedAs("한도(%d) 초과 예약", limit)
            .isFalse()
    }

    companion object {
        private val redisPort = findFreePort()
        private val redisServer: RedisServer = RedisServer.newRedisServer()
            .port(redisPort)
            .build()

        @BeforeAll
        @JvmStatic
        fun startRedis() {
            redisServer.start()
        }

        @AfterAll
        @JvmStatic
        fun stopRedis() {
            redisServer.stop()
        }

        @JvmStatic
        @DynamicPropertySource
        fun redisProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { "localhost" }
            registry.add("spring.data.redis.port") { redisPort }
        }

        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
