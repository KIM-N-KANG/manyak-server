package com.knk.manyak.credit.service

import com.knk.manyak.global.observability.DeviceIdHasher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * 게스트 체험 한도(스펙 §4-3-7, KNK-477): 회원 크레딧과 별개로 디바이스 ID별 Redis 카운터 3종을 관리한다.
 *
 * AI 호출·스트림 시작 **전에** [reserve]로 1회 예약하고(한도 소진이면 예약 없이 false → 호출부가 402로 변환),
 * 실패·미완료 트리거를 만나면 [restore]로 되돌린다(크레딧 환불과 대응되는 카운터 복원).
 * 카운터는 TTL·일일 리셋을 두지 않는다(스펙 명시, Phase 1).
 *
 * B13(스펙 §4-3-7): 회원도 잔여 체험을 크레딧보다 먼저 소진한다. 회원 카운터는 userId 기반([reserveMember]·
 * [restoreMember])이며, 가입 시 디바이스 사용량을 계정당 1회 스냅샷한다([snapshotTrialAtSignup]).
 */
@Service
class GuestTrialLimitService(
    private val redisTemplate: StringRedisTemplate,
    private val deviceIdHasher: DeviceIdHasher,
    @param:Value("\${manyak.guest-trial.storyline-generation-limit:5}")
    private val storylineGenerationLimit: Long,
    @param:Value("\${manyak.guest-trial.story-creation-limit:1}")
    private val storyCreationLimit: Long,
    @param:Value("\${manyak.guest-trial.chat-turn-limit:5}")
    private val chatTurnLimit: Long,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    enum class Counter(val key: String) {
        STORYLINE_GENERATION("storyline_generation"),
        STORY_CREATION("story_creation"),
        CHAT_TURN("chat_turn"),
    }

    /**
     * 게스트 요청의 디바이스 헤더를 검증한다. 체험 한도 대상 엔드포인트는 게스트 요청에 device 헤더가
     * 필수다(스펙 §4-3-7 — 현행 best-effort에서 강화). 없거나 공백이면 400.
     */
    fun requireDeviceId(deviceId: String?): String =
        deviceId?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "게스트의 체험 한도 대상 요청은 X-Manyak-Device-Id 헤더가 필요합니다.",
            )

    /**
     * [counter]가 한도 미만이면 1을 예약하고 true를, 한도 소진이면 예약 없이 false를 반환한다
     * (호출부가 402로 변환). 조회+증가를 Lua 스크립트로 원자 실행해 동시 요청의 한도 초과 통과를 막는다.
     */
    fun reserve(deviceId: String, counter: Counter): Boolean {
        val result = redisTemplate.execute(
            RESERVE_SCRIPT,
            listOf(keyFor(deviceId, counter)),
            limitFor(counter).toString(),
        )
        return result == 1L
    }

    /** 실패·미완료 트리거로 예약을 되돌린다. 0 아래로는 내려가지 않는다(중복 복원·경합 방지). */
    fun restore(deviceId: String, counter: Counter) {
        redisTemplate.execute(RESTORE_SCRIPT, listOf(keyFor(deviceId, counter)))
    }

    /**
     * 회원 체험 잔여를 1 예약한다(스펙 §4-3-7 B13). 회원도 잔여 체험을 크레딧보다 먼저 소진한다 — 성공(true)이면
     * 무료로 처리하고, 소진(false)이면 호출부가 크레딧으로 차감한다. 게스트 카운터와 같은 Lua로 원자 실행하되
     * 키는 userId 기반이라 디바이스 헤더 없이도 동작한다(가입 시 [snapshotTrialAtSignup]로 시드).
     *
     * **체험 카운터 Redis 장애는 예약 실패(false)로 폴백해 크레딧 결제 경로를 막지 않는다**: B13 이전 회원 흐름은
     * Redis에 의존하지 않았으므로, 잔여 조회 불가가 크레딧 보유 회원의 유료 사용을 5xx로 막으면 가용성 회귀다.
     */
    fun reserveMember(userId: Long, counter: Counter): Boolean = try {
        redisTemplate.execute(
            RESERVE_SCRIPT,
            listOf(memberKeyFor(userId, counter)),
            limitFor(counter).toString(),
        ) == 1L
    } catch (ex: DataAccessException) {
        logger.warn("회원 체험 예약 실패(Redis) — 크레딧 경로로 폴백: userId={}, counter={}", userId, counter, ex)
        false
    }

    /**
     * 회원 요청이 실패·미완료로 끝나면 예약한 체험 잔여를 되돌린다(크레딧 환불에 대응). 0 아래로 내려가지 않는다.
     * 복원은 best-effort다 — Redis 장애는 삼켜 SSE 종료·응답을 막지 않는다(예약이 성공했던 카운터라 사후 정합은 별개).
     */
    fun restoreMember(userId: Long, counter: Counter) {
        try {
            redisTemplate.execute(RESTORE_SCRIPT, listOf(memberKeyFor(userId, counter)))
        } catch (ex: DataAccessException) {
            logger.warn("회원 체험 복원 실패(Redis) — 무시: userId={}, counter={}", userId, counter, ex)
        }
    }

    /**
     * 가입(신규 계정 생성) 시 게스트 시절 디바이스 사용량을 회원 카운터로 옮긴다(스펙 §4-3-7 B13 — 게스트로 소진 후
     * 가입해 체험을 초기화하는 파밍 차단). 회원이 소비하는 카운터([MEMBER_SHARED_COUNTERS] — 스토리라인 생성은
     * 회원 무료라 제외)만 다룬다.
     *
     * - [deviceId]가 있으면 디바이스 사용량을 시드한다(사용 없던 카운터는 미설정=full 체험으로 남긴다).
     * - [deviceId]가 없으면(헤더 누락 — 우회 시도) 한도값으로 시드해 소진 상태로 만든다(무료 체험 미부여, B13 우회 차단).
     *
     * **미설정 시에만**(`SETNX`) 써서 동시 첫 로그인 경합에도 안전하다. 호출부([com.knk.manyak.auth.social.GoogleLoginService])가
     * **신규 계정 생성 경로에서만** 호출하므로, 이후 로그인·기존 회원의 잔여 체험을 훼손하지 않는다(계정당 1회성은 호출부가 보장).
     */
    fun snapshotTrialAtSignup(userId: Long, deviceId: String?) {
        for (counter in MEMBER_SHARED_COUNTERS) {
            val seed = if (deviceId.isNullOrBlank()) {
                limitFor(counter).toString() // 디바이스 미증명 → 소진 시드(무료 체험 미부여)
            } else {
                redisTemplate.opsForValue().get(keyFor(deviceId, counter)) ?: continue // 사용 없던 카운터는 full로 남김
            }
            redisTemplate.opsForValue().setIfAbsent(memberKeyFor(userId, counter), seed)
        }
    }

    /**
     * [userId]가 없으면(게스트) [deviceId]를 검증하고([requireDeviceId]) [counter]를 예약한다.
     * 한도 소진이면 402. 예약에 성공하면 실패 시 [restore]에 쓸 디바이스 id를 반환하고,
     * 회원([userId] != null)이면 이 카운터를 쓰지 않으므로 아무것도 하지 않고 null을 반환한다.
     */
    fun reserveForGuestOrNull(userId: Long?, deviceId: String?, counter: Counter): String? {
        if (userId != null) return null
        val validDeviceId = requireDeviceId(deviceId)
        if (!reserve(validDeviceId, counter)) {
            throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "게스트 체험 한도를 모두 사용했습니다.")
        }
        return validDeviceId
    }

    private fun limitFor(counter: Counter): Long = when (counter) {
        Counter.STORYLINE_GENERATION -> storylineGenerationLimit
        Counter.STORY_CREATION -> storyCreationLimit
        Counter.CHAT_TURN -> chatTurnLimit
    }

    private fun keyFor(deviceId: String, counter: Counter): String =
        "guest_trial:${deviceIdHasher.hash(deviceId)}:${counter.key}"

    // 회원 카운터는 userId 기반(디바이스 해시 아님) — 가입 시 디바이스에서 스냅샷된 계정 귀속 체험 잔여.
    private fun memberKeyFor(userId: Long, counter: Counter): String =
        "member_trial:$userId:${counter.key}"

    private companion object {
        // 회원이 크레딧 대신 소비하는 체험 카운터. 스토리라인 생성은 회원 무료라 제외한다.
        val MEMBER_SHARED_COUNTERS = listOf(Counter.STORY_CREATION, Counter.CHAT_TURN)

        // 한도 미만이면 INCR 후 1(예약 성공), 이상이면 그대로 0(예약 거절). ARGV[1]=한도.
        val RESERVE_SCRIPT = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                local limit = tonumber(ARGV[1])
                if current >= limit then return 0 end
                redis.call('INCR', KEYS[1])
                return 1
                """.trimIndent(),
            )
        }

        // 0 아래로 내려가지 않게 감소한다.
        val RESTORE_SCRIPT = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                if current > 0 then redis.call('DECR', KEYS[1]) end
                return 1
                """.trimIndent(),
            )
        }
    }
}
