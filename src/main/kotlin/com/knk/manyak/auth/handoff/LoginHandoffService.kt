package com.knk.manyak.auth.handoff

import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResponse
import com.knk.manyak.migration.dto.MigrationResult
import com.knk.manyak.migration.dto.MigrationStatus
import com.knk.manyak.migration.service.GuestDataMigrationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 로그인 핸드오프(스펙 §4-3-5): 인앱 브라우저에서 외부 브라우저로 넘어갈 때 끊기는 두 가지 —
 * 게스트 데이터 ID 배열과 원본 디바이스 ID — 를 서버에 임시 보관했다가 로그인 계정에 잇는다.
 *
 * 저장은 Redis `login_handoff:{codeHash}` 한 키다(결정 기록: RDB 대신 Redis — TTL 만료가 곧 소멸이라
 * 청소 배치가 없다). **키는 코드 원문이 아니라 SHA-256 해시**라 저장소가 유출돼도 코드 원문은 드러나지 않는다.
 *
 * 코드는 URL이 아니라 헤더로만 오간다. 서버가 모든 요청 URI를 구조화 로그·Sentry breadcrumb에 남기므로
 * (`ApiRequestLoggingFilter`), path·쿼리에 실으면 "코드 원문을 로그에 남기지 않는다" 규칙을 첫 호출부터 어긴다.
 *
 * 소비는 별도 엔드포인트가 아니라 `POST /auth/login/google`이 겸한다([consume]). 시드를 로그인 뒤로 미루면
 * 디바이스 헤더 없는 첫 로그인이 소진 시드를 1회성·비가역으로 확정하기 때문이다(§4-3-7).
 */
@Service
class LoginHandoffService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val guestDataMigrationService: GuestDataMigrationService,
    @param:Value("\${manyak.auth.handoff.ttl:30m}")
    private val ttl: Duration,
    // 소비 결과는 인앱 복귀가 늦을 수 있어 더 길게 남긴다(스펙 §4-3-5 소비 결과 보관).
    @param:Value("\${manyak.auth.handoff.consumed-ttl:24h}")
    private val consumedTtl: Duration,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /** 게스트 데이터와 원본 디바이스 ID를 보관하고 일회용 코드를 발급한다. 코드 원문은 이 응답에서만 노출된다. */
    fun create(request: LoginHandoffCreateRequest, deviceId: String): LoginHandoffCreateResponse {
        requireUuidFormat(request.storyIds, "storyIds")
        requireUuidFormat(request.chatIds, "chatIds")
        val code = generateCode()
        val expiresAt = Instant.now().plus(ttl)
        val handoff = LoginHandoff(
            handoffId = UUID.randomUUID().toString(),
            storyIds = request.storyIds,
            chatIds = request.chatIds,
            deviceId = deviceId,
            callbackPath = request.callbackPath,
            sourceApp = request.sourceApp,
            status = LoginHandoffStatus.PENDING,
            expiresAtEpochSecond = expiresAt.epochSecond,
        )
        redisTemplate.opsForValue().set(keyFor(code), objectMapper.writeValueAsString(handoff), ttl)
        return LoginHandoffCreateResponse(
            handoffCode = code,
            handoffId = handoff.handoffId,
            expiresAt = expiresAt,
        )
    }

    /**
     * 외부 랜딩의 확인 호출. 옮길 건수와 복귀 경로를 돌려주고 `PENDING`이면 `LANDED`로 전이한다.
     * 콘텐츠(제목·본문)는 노출하지 않는다 — 코드를 쥔 사람에게 남의 게스트 데이터 내용을 보여주지 않기 위해서다.
     */
    fun land(code: String): LoginHandoffSummaryResponse {
        val handoff = requireHandoff(code)
        // 남은 TTL을 보존하며 상태만 바꾼다(확인 호출이 만료 시각을 늘려주면 안 된다).
        // 전이 판정은 스크립트 하나에 맡긴다: 여기서 읽은 스냅샷과 이 쓰기 사이에 로그인이 소비를 끝냈을 수
        // 있고, 그대로 덮어쓰면 종료 상태(이관된 ID·비운 디바이스 ID)가 LANDED로 되돌아가 인앱이 로컬
        // 정리를 못 한다. 소비권이 나간 뒤에는 쓰지 않으므로 종료 상태는 되돌아가지 않는다.
        updateIfUnclaimed(code, handoff.copy(status = LoginHandoffStatus.LANDED))
        return LoginHandoffSummaryResponse(
            storyCount = handoff.storyIds.size,
            chatCount = handoff.chatIds.size,
            callbackPath = handoff.callbackPath,
            expiresAt = Instant.ofEpochSecond(handoff.expiresAtEpochSecond),
        )
    }

    /** 인앱 복귀 정리용 상태 조회. 인앱은 여기서 받은 ID만 로컬에서 제거한다(그 외는 여전히 게스트 소유). */
    fun status(code: String): LoginHandoffStatusResponse {
        val handoff = requireHandoff(code)
        return LoginHandoffStatusResponse(
            status = handoff.status,
            migratedStoryIds = handoff.migratedStoryIds,
            migratedChatIds = handoff.migratedChatIds,
        )
    }

    /**
     * 로그인 호출이 코드를 제시했을 때 보관 내용을 읽는다. **무효·만료는 예외가 아니라 null**이다 —
     * 핸드오프가 로그인 자체를 막으면 안 되고, 그 경우 시드는 요청 헤더의 디바이스 ID로 폴백한다(§4-3-7).
     *
     * Redis 장애도 같은 이유로 null로 폴백한다(랜딩·상태 조회와 달리 여기서는 로그인 가용성이 우선이다).
     */
    fun find(code: String): LoginHandoff? = try {
        read(code)
    } catch (ex: DataAccessException) {
        logger.warn("핸드오프 조회 실패(Redis) — 헤더 디바이스로 폴백해 로그인은 계속", ex)
        null
    }

    /**
     * 보관한 ID를 기존 이관 로직에 제출하고 결과를 저장한다(로그인 호출이 소비를 겸한다).
     *
     * 이미 소비된 코드면 재이관하지 않고 저장된 결과를 유지한다(멱등) — 응답 유실 후 재시도가
     * 오류가 아니라 같은 결과를 보게 한다. 이관 1회 잠금·시도 5회 상한은 기존 로직 그대로 적용된다.
     *
     * **소비권은 SETNX로 한 번만 준다.** 같은 코드를 실은 로그인이 동시에 둘 들어오면 둘 다 PENDING 스냅샷으로
     * 상태 가드를 통과한다. 먼저 이관한 쪽이 계정을 잠그므로 나중 쪽은 migrationClosed(빈 목록)를 받는데,
     * 그 결과로 덮어쓰면 status가 실제 이관된 ID를 잃어 인앱이 로컬 정리를 못 한다(이미 회원 소유가 된
     * 데이터라 게스트 조회는 403). 소비권을 얻지 못한 요청은 이관도 기록도 하지 않는다.
     */
    fun consume(code: String, handoff: LoginHandoff, userId: Long) {
        if (handoff.status == LoginHandoffStatus.MIGRATED || handoff.status == LoginHandoffStatus.MIGRATION_CLOSED) {
            return
        }
        if (redisTemplate.opsForValue().setIfAbsent(claimKeyFor(code), CLAIMED, consumedTtl) != true) {
            return
        }
        val response = if (handoff.storyIds.isEmpty() && handoff.chatIds.isEmpty()) {
            // 옮길 항목이 없으면 이관 서비스를 부르지 않는다. 그 서비스는 배열을 보기 전에 시도 횟수를 올리므로,
            // 디바이스만 실은 인앱 로그인이 계정당 5회 상한을 갉아먹어 나중의 진짜 이관이 평가도 못 받고 닫힌다.
            MigrationResponse(stories = emptyList(), chats = emptyList())
        } else try {
            guestDataMigrationService.migrate(
                userId,
                MigrationRequest(storyIds = handoff.storyIds, chatIds = handoff.chatIds),
            )
        } catch (ex: Exception) {
            // 이관이 실패하면 소비권을 반납해 만료 전까지 재시도할 수 있게 한다(스펙 §4-3-5).
            redisTemplate.delete(claimKeyFor(code))
            throw ex
        }
        val consumed = handoff.copy(
            status = if (response.migrationClosed) LoginHandoffStatus.MIGRATION_CLOSED else LoginHandoffStatus.MIGRATED,
            migratedStoryIds = migratedIds(response.stories),
            migratedChatIds = migratedIds(response.chats),
            // 결과는 24시간 남기지만 디바이스 ID 원문은 함께 연장하지 않는다 — 원문은 핸드오프 수명 동안만
            // 서버에 남는다는 보관 규칙(스펙 §4-3-5)을 지키려면, 소비 시점에 지우는 것이 유일한 기회다.
            // 소비 후에는 시드가 이미 끝나 이 값을 쓸 곳도 없다(재로그인은 헤더 디바이스로 폴백).
            deviceId = "",
        )
        redisTemplate.opsForValue().set(keyFor(code), objectMapper.writeValueAsString(consumed), consumedTtl)
    }

    /**
     * 공개 ID가 UUID 형식인지 확인한다(형식 오류는 요청 전체를 400으로 본다 — 이관 제출과 같은 규칙).
     *
     * 형식 검사는 입력 위생만이 아니라 크기 상한이기도 하다. 이 엔드포인트는 인증이 없어, 임의 길이 문자열을
     * 100개씩 받아 그대로 보관하면 Redis 메모리 증폭 통로가 된다. UUID로 고정하면 항목당 36자로 묶인다.
     */
    private fun requireUuidFormat(rawIds: List<String>, field: String) {
        rawIds.forEach { raw ->
            try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${field}의 공개 ID 형식이 올바르지 않습니다: $raw")
            }
        }
    }

    private fun migratedIds(results: List<MigrationResult>): List<String> =
        results.filter { it.status == MigrationStatus.MIGRATED }.map { it.id }

    /** 없는 코드와 만료된 코드를 동일한 404로 통일한다(열거 오라클 방지 — 스펙 §4-3-5). */
    private fun requireHandoff(code: String): LoginHandoff =
        read(code) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "유효하지 않은 핸드오프입니다.")

    /** Redis 장애는 그대로 전파한다(랜딩·상태 조회는 없는 코드가 아니라 서버 오류로 드러나야 한다). */
    private fun read(code: String): LoginHandoff? {
        val raw = redisTemplate.opsForValue().get(keyFor(code)) ?: return null
        return try {
            objectMapper.readValue(raw, LoginHandoff::class.java)
        } catch (ex: Exception) {
            // 저장 포맷이 깨진 값은 없는 것과 같이 취급한다(500 대신 404·헤더 폴백).
            logger.warn("핸드오프 페이로드 역직렬화 실패 — 없는 코드로 취급", ex)
            null
        }
    }

    /**
     * 소비권이 아직 나가지 않았을 때만, 남은 TTL(PTTL)을 유지한 채 값을 바꾼다.
     * 소비권 확인 → TTL 조회 → 쓰기를 한 스크립트로 묶어 그 사이에 소비가 끼어들지 못하게 한다.
     */
    private fun updateIfUnclaimed(code: String, handoff: LoginHandoff) {
        redisTemplate.execute(
            UPDATE_IF_UNCLAIMED_SCRIPT,
            listOf(keyFor(code), claimKeyFor(code)),
            objectMapper.writeValueAsString(handoff),
        )
    }

    /** 256bit 무작위 코드(스펙 상한 128bit 이상)를 base64url로 만든다. */
    private fun generateCode(): String {
        val bytes = ByteArray(CODE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun keyFor(code: String): String = KEY_PREFIX + hash(code)

    /** 소비권 키. 본 키와 같은 해시를 쓰되 접두사만 달리해 코드 원문을 저장하지 않는 규칙을 유지한다. */
    private fun claimKeyFor(code: String): String = CLAIM_KEY_PREFIX + hash(code)

    /** 코드 원문의 SHA-256 해시를 base64url로 반환한다. Redis 키로만 쓴다(원문은 저장하지 않는다). */
    private fun hash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val KEY_PREFIX = "login_handoff:"
        const val CLAIM_KEY_PREFIX = "login_handoff_claim:"
        const val CLAIMED = "1"
        const val CODE_BYTES = 32 // 256bit

        // 소비권(KEYS[2])이 없을 때만 남은 PTTL을 유지하며 값을 교체한다.
        // 소비권이 이미 나갔으면 종료 상태를 되돌리게 되므로 쓰지 않고, 이미 만료(또는 무TTL)여도 쓰지 않는다.
        val UPDATE_IF_UNCLAIMED_SCRIPT = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
                local ttl = redis.call('PTTL', KEYS[1])
                if ttl < 0 then return 0 end
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
                return 1
                """.trimIndent(),
            )
        }
    }
}

/**
 * Redis에 보관하는 핸드오프 페이로드. 디바이스 ID는 **원문**이다 — 회원 체험 시드가 서버 내부에서
 * pepper 해시로 카운터 키를 만들므로 미리 해시된 값은 쓸 수 없다(이중 해시로 키 불일치). 원문은
 * 핸드오프 수명 동안만 남는다. 만료 시각은 모듈 설정에 의존하지 않도록 epoch 초로 저장한다.
 */
data class LoginHandoff(
    val handoffId: String = "",
    val storyIds: List<String> = emptyList(),
    val chatIds: List<String> = emptyList(),
    val deviceId: String = "",
    val callbackPath: String = "",
    val sourceApp: String = "",
    val status: LoginHandoffStatus = LoginHandoffStatus.PENDING,
    val expiresAtEpochSecond: Long = 0,
    val migratedStoryIds: List<String> = emptyList(),
    val migratedChatIds: List<String> = emptyList(),
)
