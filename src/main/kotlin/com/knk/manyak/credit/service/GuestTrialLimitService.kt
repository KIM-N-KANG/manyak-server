package com.knk.manyak.credit.service

import com.knk.manyak.global.observability.DeviceIdHasher
import org.springframework.beans.factory.annotation.Value
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
 * 카운터는 TTL·일일 리셋을 두지 않는다(스펙 명시, Phase 1). 회원 요청은 이 서비스를 거치지 않는다(무료 또는 크레딧 소모).
 */
@Service
class GuestTrialLimitService(
    private val redisTemplate: StringRedisTemplate,
    private val deviceIdHasher: DeviceIdHasher,
    @param:Value("\${manyak.guest-trial.storyline-generation-limit:10}")
    private val storylineGenerationLimit: Long,
    @param:Value("\${manyak.guest-trial.story-creation-limit:3}")
    private val storyCreationLimit: Long,
    @param:Value("\${manyak.guest-trial.chat-turn-limit:15}")
    private val chatTurnLimit: Long,
) {

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

    private companion object {
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
