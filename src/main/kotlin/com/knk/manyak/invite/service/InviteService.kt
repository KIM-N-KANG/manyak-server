package com.knk.manyak.invite.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.invite.dto.InviteResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

/**
 * 초대 코드 발급과 초대 보상 적립을 담당한다(스펙 §4-3-7).
 *
 * - 발급: [getOrCreateInvite]는 요청자의 코드를 지연 생성해 공유 링크와 함께 돌려준다.
 * - 적립: [rewardInviteSignup]은 피초대자의 최초 가입 시 초대자·피초대자 양쪽에 INVITE_REWARD를 준다.
 *
 * 지급량은 스펙상 미정(계획)이라 플레이스홀더 기본값을 두고 설정([manyak.credit.invite-reward])으로 덮어쓴다.
 */
@Service
class InviteService(
    private val userRepository: UserRepository,
    private val creditWalletService: CreditWalletService,
    @param:Value("\${manyak.credit.invite-reward:50}") private val inviteReward: Long,
    @param:Value("\${manyak.invite.base-url:https://manyak.app/invite}") private val inviteBaseUrl: String,
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
     * 피초대자의 최초 가입 시 초대 보상을 적립한다. 초대자·피초대자 양쪽에 INVITE_REWARD를 준다.
     *
     * 이 메서드는 **신규 생성(create) 경로에서만** 호출되므로 "이미 가입된 계정의 코드 제출은 무시"가
     * 호출 구조로 보장된다. 여기서는 코드 자체의 유효성만 거른다:
     * - 코드가 없거나(미제출) 공백뿐이면 무시.
     * - 코드가 어떤 사용자와도 매칭되지 않으면 무시.
     * - 자기 자신의 코드면 무시(자가 초대 방지).
     *
     * 멱등 키 `invite:{초대자}:{피초대자}:{수혜자}`로 같은 (초대자, 피초대자) 쌍의 각 수혜자당 1회만 적립한다.
     * 스펙은 쌍 키 하나로 적지만, 원장 idempotency_key가 전역 유니크라 두 적립 행이 같은 키를 쓸 수 없어
     * 수혜자를 접미사로 구분한다(쌍당 1회 보장은 유지). 두 적립은 한 트랜잭션이라 함께 커밋·롤백된다(반쪽 지급 방지).
     */
    @Transactional
    fun rewardInviteSignup(inviteCode: String?, newUserId: Long) {
        val code = inviteCode?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val inviter = userRepository.findByInviteCode(code) ?: return
        if (inviter.id == newUserId) return

        val pair = "invite:${inviter.id}:$newUserId"
        creditWalletService.reward(inviter.id, inviteReward, CreditReason.INVITE_REWARD, "$pair:inviter")
        creditWalletService.reward(newUserId, inviteReward, CreditReason.INVITE_REWARD, "$pair:invitee")
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
    }
}
