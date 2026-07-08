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
    fun `회원 예약도 한도까지 되고 소진하면 거절한다`() {
        repeat(3) {
            assertThat(service.reserveMember(100L, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        }
        assertThat(service.reserveMember(100L, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
    }

    @Test
    fun `회원 복원하면 다시 예약할 수 있다`() {
        repeat(3) { service.reserveMember(200L, GuestTrialLimitService.Counter.STORY_CREATION) }
        assertThat(service.reserveMember(200L, GuestTrialLimitService.Counter.STORY_CREATION)).isFalse()

        service.restoreMember(200L, GuestTrialLimitService.Counter.STORY_CREATION)

        assertThat(service.reserveMember(200L, GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
    }

    @Test
    fun `회원 카운터는 디바이스 카운터와 독립적이다`() {
        // 같은 식별 문자열이라도 게스트(device)와 회원(userId) 키가 분리돼 서로의 잔여에 영향을 주지 않는다.
        repeat(3) { service.reserve("300", GuestTrialLimitService.Counter.CHAT_TURN) }
        assertThat(service.reserve("300", GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()

        // 디바이스가 소진돼도 회원 카운터는 그대로 한도까지 예약된다.
        repeat(3) {
            assertThat(service.reserveMember(300L, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        }
    }

    @Test
    fun `가입 동기화는 디바이스 사용량을 회원 카운터로 옮긴다`() {
        // 디바이스로 story_creation 2회, chat_turn 1회 사용한 뒤 가입한다.
        repeat(2) { service.reserve("dev-sync", GuestTrialLimitService.Counter.STORY_CREATION) }
        service.reserve("dev-sync", GuestTrialLimitService.Counter.CHAT_TURN)

        service.syncTrialFromDeviceIfUnset(700L, "dev-sync")

        // 회원은 남은 만큼만 예약할 수 있다: story_creation 3-2=1, chat_turn 3-1=2.
        assertThat(service.reserveMember(700L, GuestTrialLimitService.Counter.STORY_CREATION)).isTrue()
        assertThat(service.reserveMember(700L, GuestTrialLimitService.Counter.STORY_CREATION)).isFalse()
        assertThat(service.reserveMember(700L, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(service.reserveMember(700L, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue()
        assertThat(service.reserveMember(700L, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
    }

    @Test
    fun `가입 동기화는 미설정 시에만 시드해 재시도해도 소진 잔여를 덮어쓰지 않는다`() {
        // 디바이스로 story_creation 2회 사용 후 첫 스냅샷 → 회원은 1회 남는다.
        repeat(2) { service.reserve("dev-idem", GuestTrialLimitService.Counter.STORY_CREATION) }
        service.syncTrialFromDeviceIfUnset(800L, "dev-idem")
        assertThat(service.reserveMember(800L, GuestTrialLimitService.Counter.STORY_CREATION)).isTrue() // 남은 1회 소진 → 회원 카운터 설정됨

        // 디바이스를 더 쓰고 재동기화해도, 이미 설정된 회원 카운터는 덮어쓰지 않는다(SETNX). 회원은 여전히 소진 상태.
        service.reserve("dev-idem", GuestTrialLimitService.Counter.STORY_CREATION)
        service.syncTrialFromDeviceIfUnset(800L, "dev-idem")

        assertThat(service.reserveMember(800L, GuestTrialLimitService.Counter.STORY_CREATION)).isFalse()
    }

    @Test
    fun `디바이스 미증명 로그인은 회원 체험을 소진 상태로 시드해 부여하지 않는다`() {
        service.denyMemberTrialIfUnset(900L)

        // 회원 카운터가 한도값으로 차 있어 무료 예약이 되지 않는다(크레딧 경로로 넘어감).
        assertThat(service.reserveMember(900L, GuestTrialLimitService.Counter.STORY_CREATION)).isFalse()
        assertThat(service.reserveMember(900L, GuestTrialLimitService.Counter.CHAT_TURN)).isFalse()
    }

    @Test
    fun `이미 체험이 시드된 회원은 디바이스 미증명 재로그인에도 잔여가 유지된다`() {
        // 정상 가입으로 회원 체험이 아직 미사용(미설정)인 상태.
        assertThat(service.reserveMember(950L, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue() // 1 사용 → 카운터 설정됨

        // 이후 헤더 없는 로그인이 와도 SETNX라 기존 잔여를 소진 상태로 덮어쓰지 않는다.
        service.denyMemberTrialIfUnset(950L)

        assertThat(service.reserveMember(950L, GuestTrialLimitService.Counter.CHAT_TURN)).isTrue() // 2회차도 예약 가능(한도 3)
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
