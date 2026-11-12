package com.knk.manyak.push.service

import com.knk.manyak.auth.dto.PushRecipient
import com.knk.manyak.auth.service.AccountCommandService
import com.knk.manyak.auth.service.AccountLockService
import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.auth.security.requireActiveStatus
import com.knk.manyak.push.dto.PushSettingsResponse
import com.knk.manyak.push.dto.PushSettingsUpdateRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 알림 수신 동의 조회·변경(KNK-1132, 정책 KNK-1129). 발송 측 연동은 여기 없다(KNK-1116·1117).
 *
 * 조회도 사용자 행을 **잠그고** 상태를 재검사한다(디바이스 푸시 토큰 API와 같은 관례). 정지 계정은 설정 화면
 * 자체를 쓸 수 없어야 하므로 조회도 403이고, 탈퇴 경합에서는 잠금 후 재검사가 마지막 방어선이다.
 */
@Service
class PushSettingsService(
    private val locks: AccountLockService,
    private val accounts: AccountQueryService,
    private val commands: AccountCommandService,
) {

    // 조회지만 readOnly를 쓰지 않는다 — PostgreSQL은 read-only 트랜잭션의 SELECT ... FOR UPDATE를 거부하고
    // (pgjdbc 기본 readOnlyMode=transaction), 아래 잠금 후 재검사 관례는 조회에도 그대로 적용한다.
    @Transactional
    fun getSettings(userId: Long): PushSettingsResponse = lockActiveUser(userId).toSettingsResponse()

    /**
     * 전체 교체다. 세 값을 모두 받아 그대로 반영하되 광고성 두 값은 **시각**으로 저장한다.
     *
     * - `marketingPush=true`이고 아직 미동의면 지금을 기록하고, **이미 동의한 상태면 최초 시각을 유지**한다.
     *   증빙은 "언제부터 동의했는가"라 같은 값을 다시 보낼 때마다 갱신하면 그 사실이 매 요청마다 밀린다.
     * - `marketingPush=false`면 광고·야간 둘 다 NULL로 지운다(철회).
     * - 야간만 켜는 요청은 400이다 — 야간 동의는 광고 동의의 확장이라 광고를 끈 채로는 발송 판정에 쓰이지 않고,
     *   조용히 무시하면 사용자가 켰다고 믿는 토글이 실제로는 꺼져 있게 된다.
     */
    @Transactional
    fun updateSettings(userId: Long, request: PushSettingsUpdateRequest): PushSettingsResponse {
        if (request.marketingNightPush && !request.marketingPush) {
            throw CodedResponseStatusException(
                HttpStatus.BAD_REQUEST,
                ApiErrorCodes.NIGHT_PUSH_REQUIRES_MARKETING,
                "야간 광고 알림은 광고 알림 수신에 동의해야 켤 수 있습니다.",
            )
        }
        val user = lockActiveUser(userId)
        val now = Instant.now()

        val updated = if (request.marketingPush) {
            // 이미 값이 있으면 그대로 둔다(최초 동의 시각 유지).
            user.copy(servicePushEnabled = request.servicePush, marketingPushAgreedAt = user.marketingPushAgreedAt ?: now,
                marketingPushNightAgreedAt = if (request.marketingNightPush) user.marketingPushNightAgreedAt ?: now else null)
        } else {
            // 철회는 광고·야간을 함께 지운다. 야간만 남으면 발송 판정에 쓰이지 않는 유령 동의가 된다.
            user.copy(servicePushEnabled = request.servicePush, marketingPushAgreedAt = null, marketingPushNightAgreedAt = null)
        }
        commands.updatePushConsent(userId, updated.servicePushEnabled, updated.marketingPushAgreedAt, updated.marketingPushNightAgreedAt)
        return updated.toSettingsResponse()
    }

    /**
     * 사용자 행을 비관적 쓰기 락으로 잡고 상태를 재검사한다(스펙 §4-5 B20, KNK-499·1019 선례).
     * 사용자가 없으면 401, 정지는 403, 탈퇴는 401이다.
     */
    private fun lockActiveUser(userId: Long): PushRecipient {
        val locked = locks.lock(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        requireActiveStatus(locked.status)
        return requireNotNull(accounts.pushRecipientOf(userId))
    }

    private fun PushRecipient.toSettingsResponse() = PushSettingsResponse(
        servicePush = servicePushEnabled,
        marketingPush = marketingPushAgreedAt != null,
        marketingNightPush = marketingPushNightAgreedAt != null,
    )
}
