package com.knk.manyak.invite.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.MonthlyRewardCap
import com.knk.manyak.credit.service.RewardOutcome
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.requireActiveStatus
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
        // 정지·탈퇴 소모·쓰기 차단(스펙 §4-5 B20, KNK-499·1019). 잠금 후 재검사라 탈퇴 커밋 경합도 막는다.
        requireActiveStatus(user.status)
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
            idempotencyKeyPrefix = inviterRoleKeyPrefix(user.rewardIdentity()),
            idempotencyKeySuffix = inviterRoleKeySuffix(user.rewardIdentity()),
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
     * 자기 코드 409 [ApiErrorCodes.INVITE_SELF_CODE], 재제출 409 [ApiErrorCodes.INVITE_ALREADY_REDEEMED],
     * 초대자 탈퇴 409 [ApiErrorCodes.INVITE_INVITER_WITHDRAWN], 초대자 정지 409 [ApiErrorCodes.INVITE_INVITER_UNAVAILABLE].
     *
     * 월 상한(적립 시점의 KST 월 귀속)은 초대자 몫에만 적용한다(KNK-581) — 초대자가 상한이면 초대자만 건너뛰고
     * 제출자는 적립하며 응답은 성공이다(상한 사실은 응답에 싣지 않음 — 초대자 쪽 진행 표시로 충분). 제출자 몫은
     * 평생 1회 자격이 유일한 제한이라 상한 없이 적립한다(상한을 적용하면 자격만 소진하고 보상을 영영 잃는 손실 발생).
     */
    @Transactional
    fun redeem(userId: Long, rawCode: String): InviteRedeemResponse {
        val redeemer = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        // 정지·탈퇴 소모·쓰기 차단(스펙 §4-5 B20, KNK-499·1019). 잠금 후 재검사라 탈퇴 커밋 경합도 막는다.
        requireActiveStatus(redeemer.status)
        // 사람이 카카오톡 본문을 보고 타이핑하는 값이라 trim·대문자 정규화 후 비교한다(발급 코드는 대문자+숫자).
        val code = rawCode.trim().uppercase()
        if (!CODE_FORMAT.matches(code)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "초대 코드 형식이 올바르지 않습니다.")
        }
        // 평생 1회 소진 판정을 inviter_user_id non-null로 한다. 이 컬럼은 초대자 자기참조 FK가 ON DELETE SET NULL(V27)
        // 이라 초대자 행이 물리 삭제되면 표식이 사라지는데, 회원 물리 삭제 경로는 없다(탈퇴도 soft delete뿐).
        // 실제로 뚫렸던 건 이쪽이 아니라 **제출자 행 자체가 바뀌는** 경로였다(KNK-1053): 탈퇴가 social_accounts를
        // 하드 삭제해 같은 소셜 신원의 재가입이 새 users 행을 받으면 이 컬럼이 NULL로 초기화됐다. 이제 탈퇴는 소셜 행을
        // tombstone으로 남기고 재가입이 이전 소유자의 inviter_user_id를 승계하므로, 그 우회로는 닫혀 있다.
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
        // 초대자 상태 게이트(KNK-1053). 탈퇴·정지 회원의 코드는 보상 대상이 아니다. 탈퇴 시 invite_code를 지우지
        // 않는 이유는 코드 재발급 충돌을 피하고 여기서 사유별 메시지를 주기 위해서다(코드는 매칭되므로 404가 아니다).
        //
        // 초대자 행을 락으로 잡지 않으므로 이 검사는 **트랜잭션 경계의 보장이 아니라 정책 안내**다. 제출과 초대자 탈퇴가
        // 겹치면 in-flight 1건이 통과할 수 있다. 초대자 락을 추가하면 지갑 락과의 획득 순서를 다시 설계해야 하고
        // (KNK-587 데드락 방지), 막는 것은 경합 1건뿐이라 파밍 경로가 아니어서 비용이 이득을 넘는다.
        when (inviter.status) {
            UserStatus.DELETED -> throw CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ApiErrorCodes.INVITE_INVITER_WITHDRAWN,
                "탈퇴한 회원의 초대 코드입니다.",
            )
            UserStatus.SUSPENDED -> throw CodedResponseStatusException(
                HttpStatus.CONFLICT,
                ApiErrorCodes.INVITE_INVITER_UNAVAILABLE,
                "현재 사용할 수 없는 초대 코드입니다.",
            )
            UserStatus.ACTIVE -> Unit
        }
        // 관계 저장(평생 1회 소진)과 양측 적립을 같은 트랜잭션에서 커밋한다. 적립 실패 시 관계도 함께 롤백된다.
        redeemer.inviterUserId = inviter.id
        val (monthStart, monthEnd) = kstMonthRangeOf(clock.instant())
        // 초대자 몫: 초대자 역할 수령분(멱등 키 접두로 식별)만 세는 월 상한 안에서 적립한다(KNK-581). 멱등(쌍당 1회)과
        // 월 상한 판정은 모두 지갑 행 락 안에서 수행돼(카운트·insert가 같은 락 구간), 동시 적립이 경계에서 상한을 넘기지 못한다.
        // 키는 user_id가 아니라 **보상 신원**으로 만든다(KNK-1053). 재가입은 user_id를 갈아치우므로 user_id 키면
        // 초대자가 상한을 채운 뒤 탈퇴·재가입하는 것만으로 월 상한이 0으로 리셋된다(크레딧을 미리 쓰고 나가면 지갑
        // 소멸도 페널티가 아니다). 기존 회원·신규 가입은 신원 = 자기 자신이라 키 문자열이 종전과 같아 원장과 호환된다.
        val inviterIdentity = inviter.rewardIdentity()
        val redeemerIdentity = redeemer.rewardIdentity()
        val rewardInviter = {
            creditWalletService.reward(
                userId = inviter.id,
                amount = inviteReward,
                reason = CreditReason.INVITE_REWARD,
                idempotencyKey = idempotencyKeyOf(inviterIdentity, redeemerIdentity, rewardedIdentity = inviterIdentity),
                monthlyCap = MonthlyRewardCap(
                    reason = CreditReason.INVITE_REWARD,
                    cap = inviteMonthlyCap,
                    windowStart = monthStart,
                    windowEnd = monthEnd,
                    idempotencyKeyPrefix = inviterRoleKeyPrefix(inviterIdentity),
                    idempotencyKeySuffix = inviterRoleKeySuffix(inviterIdentity),
                ),
            )
        }
        // 제출자 몫: 월 상한 없이 적립한다(KNK-581 — 평생 1회 자격이 유일한 제한).
        val rewardRedeemer = {
            creditWalletService.reward(
                userId = redeemer.id,
                amount = inviteReward,
                reason = CreditReason.INVITE_REWARD,
                idempotencyKey = idempotencyKeyOf(inviterIdentity, redeemerIdentity, rewardedIdentity = redeemerIdentity),
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
     * 멱등 키 `invite:{초대자}:{피초대자}:{수혜자}`(KNK-477). 세 자리 모두 **보상 신원**이다(KNK-1053).
     * 원장 idempotency_key가 전역 유니크라 두 적립 행이 같은 키를 쓸 수 없어 수혜자를 접미사로 구분한다.
     * 신원 기준이라 쌍당 1회 보장이 오히려 강해진다 — 어느 쪽이 재가입해도 같은 쌍은 같은 키다.
     */
    private fun idempotencyKeyOf(inviterIdentity: Long, inviteeIdentity: Long, rewardedIdentity: Long): String =
        "invite:$inviterIdentity:$inviteeIdentity:$rewardedIdentity"

    /**
     * 초대자 역할 행의 키 접두·접미(KNK-581). 접두 `invite:{신원}:`는 "초대자가 이 신원", 접미 `:{신원}`는
     * "수혜자가 이 신원"이라, 둘을 함께 걸어야 제출자 몫 행(`invite:{신원}:{제출자}:{제출자}`)이 빠진다.
     * 콜론을 포함해 매칭하므로 십진 접두·접미 충돌(1 vs 12, 1 vs 11)이 없다.
     */
    private fun inviterRoleKeyPrefix(rewardIdentity: Long): String = "invite:$rewardIdentity:"

    private fun inviterRoleKeySuffix(rewardIdentity: Long): String = ":$rewardIdentity"

    /** 1회성·상한 대상 보상의 스코프(KNK-1053). NULL이면 자기 자신이라 재가입 없는 계정은 종전 값과 같다. */
    private fun User.rewardIdentity(): Long = rewardIdentityUserId ?: id

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
