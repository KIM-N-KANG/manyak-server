package com.knk.manyak.invite.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.invite.dto.InviteResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 초대 코드 발급과 초대 보상 적립을 담당한다(스펙 §4-3-7).
 *
 * - 발급: [getOrCreateInvite]는 요청자의 코드를 지연 생성해 공유 링크와 함께 돌려준다.
 * - 적립: [rewardInvitePair]는 피초대자의 최초 가입 시 초대자·피초대자 양쪽에 INVITE_REWARD를 준다.
 *   보상 수령 계정별 KST 월 상한(기본 10회)을 넘으면 그 요청은 조용히 건너뛴다(오류 아님).
 *
 * 지급량·월 상한은 KNK-477로 확정됐다(설정[manyak.credit.invite-reward]·[manyak.credit.invite-monthly-cap]).
 */
@Service
class InviteService(
    private val userRepository: UserRepository,
    private val creditWalletService: CreditWalletService,
    private val creditTransactionRepository: CreditTransactionRepository,
    @param:Value("\${manyak.credit.invite-reward:500}") private val inviteReward: Long,
    @param:Value("\${manyak.credit.invite-monthly-cap:10}") private val inviteMonthlyCap: Long,
    @param:Value("\${manyak.invite.base-url:https://manyak.app/invite}") private val inviteBaseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 요청자의 초대 코드·공유 링크를 반환한다. 코드가 없으면 지연 발급한다.
     *
     * 사용자 행을 비관적 락으로 잡아 발급을 직렬화한다. 같은 사용자의 동시 GET /invite가 각자 다른 코드를
     * 생성해 마지막 쓰기가 앞선 코드를 덮어쓰면, 앞서 받은 링크가 어떤 사용자와도 매칭되지 않게 되기 때문이다.
     */
    @Transactional
    fun getOrCreateInvite(userId: Long): InviteResponse {
        val user = userRepository.findByIdForUpdate(userId)
            ?: error("초대 코드를 발급할 사용자를 찾지 못했습니다: userId=$userId")
        val code = user.inviteCode ?: generateUniqueCode().also { user.inviteCode = it }
        return InviteResponse(inviteCode = code, inviteUrl = "$inviteBaseUrl/$code")
    }

    /**
     * 제출한 초대 코드에서 초대자의 내부 id를 해석한다(신규 가입 경로에서 생성 전에 호출).
     * 코드가 없거나(미제출) 공백뿐이거나 어떤 사용자와도 매칭되지 않으면 null(무시).
     *
     * 자가 초대는 여기서 걸러지지 않지만 구조상 불가능하다 — 신규 가입자는 아직 자기 코드가 없어
     * 자기 코드를 제출할 수 없다. 방어적 최종 차단은 [rewardInvitePair]가 담당한다.
     */
    fun resolveInviterId(inviteCode: String?): Long? {
        val code = inviteCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return userRepository.findByInviteCode(code)?.id
    }

    /**
     * 초대자·피초대자 양쪽에 INVITE_REWARD를 적립한다(스펙 §4-3-7). 영속된 초대자 관계를 근거로
     * **매 로그인마다** 멱등하게 호출해, 계정 생성 후 적립 전 실패로 유실된 보상을 자가 복구한다
     * (가입 보상과 동일한 패턴). 멱등 키가 중복을 막아 실제 적립은 쌍당 각 1회다.
     *
     * 멱등 키 `invite:{초대자}:{피초대자}:{수혜자}`(KNK-477). 원장 idempotency_key가 전역 유니크라
     * 두 적립 행이 같은 키를 쓸 수 없어 수혜자 id를 접미사로 구분한다(쌍당 1회 보장은 유지).
     * 초대자·피초대자의 월 상한은 [rewardIfUnderMonthlyCap]이 독립적으로 판정한다(한쪽만 초과해도 다른 쪽은 적립).
     */
    @Transactional
    fun rewardInvitePair(inviterId: Long, inviteeId: Long) {
        if (inviterId == inviteeId) return // 방어적: 영속 경로상 발생 불가하지만 자가 지급을 최종 차단한다.

        rewardIfUnderMonthlyCap(rewardedUserId = inviterId, inviterId = inviterId, inviteeId = inviteeId)
        rewardIfUnderMonthlyCap(rewardedUserId = inviteeId, inviterId = inviterId, inviteeId = inviteeId)
    }

    /**
     * [rewardedUserId]에게 초대 보상을 적립하되, 이번 KST 월 INVITE_REWARD 건수가 상한([inviteMonthlyCap]) 이상이면
     * 건너뛴다(오류 아님 — 스펙 §4-3-7 "월 한도 초과분은 무시"). 이미 이 쌍으로 적립된 적이 있으면(멱등 키 존재)
     * 월 집계 조회 없이 조기 반환한다 — 매 로그인 재시도가 흔한 no-op 경로라 값싸게 처리한다.
     */
    private fun rewardIfUnderMonthlyCap(rewardedUserId: Long, inviterId: Long, inviteeId: Long) {
        val idempotencyKey = "invite:$inviterId:$inviteeId:$rewardedUserId"
        if (creditTransactionRepository.existsByIdempotencyKey(idempotencyKey)) return
        val (monthStart, monthEnd) = currentKstMonthRange()
        val countThisMonth = creditTransactionRepository
            .countByUserIdAndReasonAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                rewardedUserId,
                CreditReason.INVITE_REWARD,
                monthStart,
                monthEnd,
            )
        if (countThisMonth >= inviteMonthlyCap) return
        creditWalletService.reward(rewardedUserId, inviteReward, CreditReason.INVITE_REWARD, idempotencyKey)
    }

    /** 현재 KST 월의 [시작, 다음달 시작) 구간을 Instant로 반환한다(월 상한 집계 경계). */
    private fun currentKstMonthRange(): Pair<Instant, Instant> {
        val today = LocalDate.now(clock.withZone(SEOUL_ZONE))
        val monthStart = today.withDayOfMonth(1).atStartOfDay(SEOUL_ZONE).toInstant()
        val monthEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(SEOUL_ZONE).toInstant()
        return monthStart to monthEnd
    }

    /** 유니크 제약을 최종 방어로 두고, 사전 존재 확인으로 충돌을 피해 코드를 생성한다. */
    private fun generateUniqueCode(): String {
        repeat(MAX_CODE_ATTEMPTS) {
            val candidate = randomCode()
            if (!userRepository.existsByInviteCode(candidate)) return candidate
        }
        error("초대 코드 생성에 반복 실패했습니다(충돌 과다).")
    }

    private fun randomCode(): String =
        buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]) }
        }

    private companion object {
        const val CODE_LENGTH = 8
        const val MAX_CODE_ATTEMPTS = 10
        const val CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val secureRandom = SecureRandom()
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
