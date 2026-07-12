package com.knk.manyak.invite.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.MonthlyRewardCap
import com.knk.manyak.credit.service.RewardOutcome
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.isActiveAccessAllowed
import com.knk.manyak.invite.dto.InviteRedeemResponse
import com.knk.manyak.invite.dto.InviteResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 초대 코드 발급과 초대 보상 적립을 담당한다(스펙 §4-3-7, KNK-567 개편).
 *
 * - 발급: [getOrCreateInvite]는 요청자의 코드를 지연 생성해 이번 달 보상 진행과 함께 돌려준다.
 * - 적립: [redeem]은 다른 회원의 코드 제출로 초대자·제출자 양쪽에 INVITE_REWARD를 준다(계정당 평생 1회).
 *   KST 월 상한(기본 10회)은 초대자 몫에만 적용해 넘으면 초대자 적립만 조용히 건너뛴다(오류 아님 — KNK-581).
 *   제출자 몫은 평생 1회 자격이 유일한 제한이라 월 상한 판정·집계 대상이 아니다.
 *
 * 지급량·월 상한은 KNK-477로 확정됐다(설정[manyak.credit.invite-reward]·[manyak.credit.invite-monthly-cap]).
 */
@Service
class InviteService(
    private val userRepository: UserRepository,
    private val creditWalletService: CreditWalletService,
    @param:Value("\${manyak.credit.invite-reward:500}") private val inviteReward: Long,
    @param:Value("\${manyak.credit.invite-monthly-cap:10}") private val inviteMonthlyCap: Long,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 요청자의 초대 코드를 반환한다. 코드가 없으면 지연 발급한다.
     *
     * 사용자 행을 비관적 락으로 잡아 발급을 직렬화한다. 같은 사용자의 동시 GET /invite가 각자 다른 코드를
     * 생성해 마지막 쓰기가 앞선 코드를 덮어쓰면, 앞서 공유한 코드가 어떤 사용자와도 매칭되지 않게 되기 때문이다.
     */
    @Transactional
    fun getOrCreateInvite(userId: Long): InviteResponse {
        val user = userRepository.findByIdForUpdate(userId)
            ?: error("초대 코드를 발급할 사용자를 찾지 못했습니다: userId=$userId")
        // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499). 사용자 행을 이미 로드했으므로 추가 조회 없이 판정한다.
        if (!isActiveAccessAllowed(user.status)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다.")
        }
        val code = user.inviteCode ?: generateUniqueCode().also { user.inviteCode = it }
        // 이번 KST 월의 초대 보상 진행을 함께 내려, 상한 도달 후 보상 없는 초대 공유의 혼란을 줄인다(스펙 §4-3-7 B22).
        // 집계는 월 상한 판정과 같은 창([현재 KST 월 시작, 다음달 시작))·같은 역할 필터(초대자 몫만 — KNK-581)를
        // 재사용하므로 "이번 달 수령 건수"가 상한 스킵 경계와 정확히 일치하고, 코드 입력으로 받은 제출자 몫은 세지 않는다.
        val (monthStart, monthEnd) = kstMonthRangeOf(clock.instant())
        val monthlyRewardCount = creditWalletService.countRewardsInWindow(
            userId = userId,
            reason = CreditReason.INVITE_REWARD,
            windowStart = monthStart,
            windowEnd = monthEnd,
            idempotencyKeyPrefix = inviterRoleKeyPrefix(userId),
        )
        return InviteResponse(
            inviteCode = code,
            monthlyRewardCount = monthlyRewardCount,
            monthlyRewardLimit = inviteMonthlyCap,
        )
    }

    /**
     * 초대 코드 입력으로 초대자·제출자 양쪽에 INVITE_REWARD를 적립한다(스펙 §4-3-7, KNK-567).
     *
     * 제출 자격은 계정당 평생 1회 — `inviter_user_id`가 non-null이면 소진으로 본다. 제출자 행을 비관적 락으로
     * 잡아 같은 계정의 동시 제출을 직렬화하므로, 자격 판정·관계 저장·양측 적립이 원자적이다(로그인 self-heal 불필요).
     *
     * 오류 계약(입력값이라 사유를 구분해 응답한다): 형식 위반 400, 매칭 없음 404,
     * 자기 코드 409 [ApiErrorCodes.INVITE_SELF_CODE], 재제출 409 [ApiErrorCodes.INVITE_ALREADY_REDEEMED].
     *
     * 월 상한(적립 시점의 KST 월 귀속)은 초대자 몫에만 적용한다(KNK-581) — 초대자가 상한이면 초대자만 건너뛰고
     * 제출자는 적립하며 응답은 성공이다(상한 사실은 응답에 싣지 않음 — 초대자 쪽 진행 표시로 충분). 제출자 몫은
     * 평생 1회 자격이 유일한 제한이라 상한 없이 적립한다(상한을 적용하면 자격만 소진하고 보상을 영영 잃는 손실 발생).
     */
    @Transactional
    fun redeem(userId: Long, rawCode: String): InviteRedeemResponse {
        val redeemer = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        if (!isActiveAccessAllowed(redeemer.status)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다.")
        }
        // 사람이 카카오톡 본문을 보고 타이핑하는 값이라 trim·대문자 정규화 후 비교한다(발급 코드는 대문자+숫자).
        val code = rawCode.trim().uppercase()
        if (!CODE_FORMAT.matches(code)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "초대 코드 형식이 올바르지 않습니다.")
        }
        // 평생 1회 소진 판정을 inviter_user_id non-null로 한다. 주의(Codex P2, 잠재 결함): 이 컬럼은 초대자 자기참조
        // FK가 ON DELETE SET NULL(V27)이라, 초대자 행이 물리 삭제되면 NULL로 되돌아가 소진 표식이 사라진다. 현재는
        // 회원을 물리 삭제하는 경로가 없어(soft delete만: UserStatus.DELETED·deleted_at) 도달 불가하지만, 하드 삭제를
        // 도입하면 이 계정이 재제출로 보상을 또 받을 수 있다. 그때는 삭제 안정적인 별도 소진 표식(예: invite_redeemed_at)을
        // 두고 그 값으로 게이트해야 한다(관계 FK와 소진 플래그의 삭제 안정성 요구가 다름).
        if (redeemer.inviterUserId != null) {
            throw CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ApiErrorCodes.INVITE_ALREADY_REDEEMED,
                "이미 초대 코드를 입력한 계정입니다.",
            )
        }
        val inviter = userRepository.findByInviteCode(code)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 초대 코드가 없습니다.")
        if (inviter.id == redeemer.id) {
            throw CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ApiErrorCodes.INVITE_SELF_CODE,
                "자기 자신의 초대 코드는 입력할 수 없습니다.",
            )
        }
        // 관계 저장(평생 1회 소진)과 양측 적립을 같은 트랜잭션에서 커밋한다. 적립 실패 시 관계도 함께 롤백된다.
        redeemer.inviterUserId = inviter.id
        val (monthStart, monthEnd) = kstMonthRangeOf(clock.instant())
        // 초대자 몫: 초대자 역할 수령분(멱등 키 접두로 식별)만 세는 월 상한 안에서 적립한다(KNK-581). 멱등(쌍당 1회)과
        // 월 상한 판정은 모두 지갑 행 락 안에서 수행돼(카운트·insert가 같은 락 구간), 동시 적립이 경계에서 상한을 넘기지 못한다.
        val rewardInviter = {
            creditWalletService.reward(
                userId = inviter.id,
                amount = inviteReward,
                reason = CreditReason.INVITE_REWARD,
                idempotencyKey = idempotencyKeyOf(inviterId = inviter.id, inviteeId = redeemer.id, rewardedUserId = inviter.id),
                monthlyCap = MonthlyRewardCap(
                    reason = CreditReason.INVITE_REWARD,
                    cap = inviteMonthlyCap,
                    windowStart = monthStart,
                    windowEnd = monthEnd,
                    idempotencyKeyPrefix = inviterRoleKeyPrefix(inviter.id),
                ),
            )
        }
        // 제출자 몫: 월 상한 없이 적립한다(KNK-581 — 평생 1회 자격이 유일한 제한).
        val rewardRedeemer = {
            creditWalletService.reward(
                userId = redeemer.id,
                amount = inviteReward,
                reason = CreditReason.INVITE_REWARD,
                idempotencyKey = idempotencyKeyOf(inviterId = inviter.id, inviteeId = redeemer.id, rewardedUserId = redeemer.id),
            )
        }
        // 두 적립은 user id 오름차순으로 실행한다(Codex P2 데드락 방지). reward가 잡는 지갑 행 락은 이 트랜잭션
        // 커밋까지 유지되므로, "초대자 먼저" 고정 순서면 서로의 코드를 동시에 제출한 두 요청이 상대 지갑을 잡은 채
        // 자기 지갑을 교차 대기(A: 지갑B→A, B: 지갑A→B)해 DB 데드락으로 한쪽이 실패한다. id 순서로 전역 결정화한다.
        val outcome = if (inviter.id < redeemer.id) {
            rewardInviter()
            rewardRedeemer()
        } else {
            rewardRedeemer().also { rewardInviter() }
        }
        return InviteRedeemResponse(
            amount = if (outcome.rewarded) inviteReward else 0,
            balance = outcome.balance,
        )
    }

    /**
     * 멱등 키 `invite:{초대자}:{피초대자}:{수혜자}`(KNK-477). 원장 idempotency_key가 전역 유니크라
     * 두 적립 행이 같은 키를 쓸 수 없어 수혜자 id를 접미사로 구분한다(쌍당 1회 보장은 유지).
     */
    private fun idempotencyKeyOf(inviterId: Long, inviteeId: Long, rewardedUserId: Long): String =
        "invite:$inviterId:$inviteeId:$rewardedUserId"

    /**
     * [userId]가 초대자 역할로 받은 원장 행의 멱등 키 접두(KNK-581). 접두 뒤 콜론까지 포함해 십진 접두 충돌이 없고,
     * 자기 코드 제출이 막혀 있어 제출자 몫 행(`invite:{타인}:{userId}:{userId}`)과 겹치지 않는다.
     */
    private fun inviterRoleKeyPrefix(userId: Long): String = "invite:$userId:"

    /** [instant]가 속한 KST 월의 [시작, 다음달 시작) 구간을 Instant로 반환한다(월 상한 집계 경계). */
    private fun kstMonthRangeOf(instant: Instant): Pair<Instant, Instant> {
        val date = instant.atZone(SEOUL_ZONE).toLocalDate()
        val monthStart = date.withDayOfMonth(1).atStartOfDay(SEOUL_ZONE).toInstant()
        val monthEnd = date.withDayOfMonth(1).plusMonths(1).atStartOfDay(SEOUL_ZONE).toInstant()
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

        // 사람이 카카오톡 본문을 보고 타이핑하는 값이라 혼동 문자(O·0, I·1·L)를 제외한 대문자+숫자만 쓴다
        // (스펙 §4-3-7 초대 코드 발급, KNK-567 — 시각 혼동이 곧 입력 실패율. 기존 발급분은 V47이 전량 재발급).
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        // 제출 코드의 형식 게이트(스펙 §4-3-7 "빈 값·형식 위반은 400"). 발급 문자 집합보다 느슨한 대문자+숫자 8자로 두어,
        // 혼동 문자 오타(0↔O 등)는 400이 아니라 404("코드를 다시 확인해 주세요")로 흐르게 한다.
        val CODE_FORMAT = Regex("^[A-Z0-9]{8}$")
        val secureRandom = SecureRandom()
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
