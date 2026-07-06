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
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import redis.embedded.RedisServer
import java.net.ServerSocket

/**
 * embedded-redis로 실제 Redis를 띄워 GuestTrialLimitService의 예약·복원 계약을 검증한다(스펙 §4-3-7, KNK-477).
 *
 * 테스트는 한도를 3으로 좁혀(카운터 종류 무관) 경계를 빠르게 확인한다. 카운터는 TTL이 없다(스펙 명시).
 */
@DataRedisTest
@Import(GuestTrialLimitService::class, DeviceIdHasher::class)
@TestPropertySource(
    properties = [
        "manyak.guest-trial.storyline-generation-limit=3",
        "manyak.guest-trial.story-creation-limit=3",
        "manyak.guest-trial.chat-turn-limit=3",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuestTrialLimitServiceIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var service: GuestTrialLimitService

    @BeforeEach
    fun flush() {
        redisTemplate.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `헤더 없거나 공백이면 400을 던진다`() {
        assertThatBadRequest { service.requireDeviceId(null) }
        assertThatBadRequest { service.requireDeviceId("   ") }
    }

    @Test
    fun `헤더가 있으면 그대로 반환한다`() {
        assertThat(service.requireDeviceId("device-1")).isEqualTo("device-1")
    }

    @Test
    fun `한도 미만이면 예약에 성공하고 카운터가 증가한다`() {
        assertThat(service.reserve("device-A", GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
        assertThat(service.reserve("device-A", GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
        assertThat(service.reserve("device-A", GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
    }

    @Test
    fun `한도에 도달하면 더 이상 예약되지 않는다`() {
        repeat(3) {
            assertThat(service.reserve("device-B", GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
        }

        assertThat(service.reserve("device-B", GuestTrialLimitService.Counter.STORY_CREATION)).isFalse()
    }

    @Test
    fun `복원하면 다시 예약할 수 있다`() {
        repeat(3) { service.reserve("device-C", GuestTrialLimitService.Counter.CHAT_TURN) }
        assertThat(service.reserve("device-C", GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()

        service.restore("device-C", GuestTrialLimitService.Counter.CHAT_TURN)

        assertThat(service.reserve("device-C", GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
    }

    @Test
    fun `예약한 적 없어도 복원은 0 아래로 내려가지 않는다`() {
        service.restore("device-D", GuestTrialLimitService.Counter.STORYLINE_GENERATION)

        // 0에서 더 내려가지 않았다면 한도(3)까지 정확히 3번 예약할 수 있어야 한다.
        repeat(3) {
            assertThat(service.reserve("device-D", GuestTrialLimitService.Counter.STORYLINE_GENERATION)).isTrue()
        }
        assertThat(service.reserve("device-D", GuestTrialLimitService.Counter.STORYLINE_GENERATION)).isFalse()
    }

    @Test
    fun `카운터 종류·디바이스별로 독립적으로 집계한다`() {
        repeat(3) { service.reserve("device-E", GuestTrialLimitService.Counter.STORY_CREATION) }

        // 같은 디바이스의 다른 카운터, 다른 디바이스의 같은 카운터는 영향받지 않는다.
        assertThat(service.reserve("device-E", GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(service.reserve("device-F", GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
    }

    private fun assertThatBadRequest(block: () -> Unit) {
        val exception = org.junit.jupiter.api.assertThrows<ResponseStatusException> { block() }
        assertThat(exception.statusCode.value()).isEqualTo(400)
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
