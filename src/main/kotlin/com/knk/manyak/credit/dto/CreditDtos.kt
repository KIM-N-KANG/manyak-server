package com.knk.manyak.credit.dto

import com.knk.manyak.credit.entity.CreditReason
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "이프 잔액")
data class CreditBalanceResponse(
    @Schema(description = "현재 이프 잔액. 지갑이 없으면 0")
    val balance: Long,
)

@Schema(description = "출석체크 보상 결과")
data class CreditAttendanceResponse(
    @Schema(description = "이번 요청으로 보상을 지급했는지. 오늘 이미 받았으면 false(멱등)")
    val rewarded: Boolean,
    @Schema(description = "이번 요청으로 적립한 이프. 이미 받았으면 0")
    val amount: Long,
    @Schema(description = "지급 후 현재 잔액")
    val balance: Long,
)

/**
 * 이용내역 화면의 필터 칩 분류(KNK-1044). 원장 사유([CreditReason])를 화면 3분류로 접는다.
 *
 * 매핑은 [of]의 `when` 하나가 정본이고 else 가지가 없다 — [CreditReason]에 사유가 추가되면 컴파일이 깨져,
 * 새 사유가 조용히 이용내역에서 빠지는 일을 막는다.
 */
enum class CreditTransactionType {
    SPEND,
    EARN,
    EXPIRE,
    ;

    /** 이 분류에 속하는 원장 사유(쿼리 필터용). [of]에서 파생하므로 분류와 어긋날 수 없다. */
    val reasons: Set<CreditReason> by lazy {
        CreditReason.entries.filterTo(mutableSetOf()) { of(it) == this }
    }

    companion object {
        /**
         * 원장 사유 → 화면 분류. **null이면 이용내역에서 제외**한다.
         *
         * REFUND는 획득이다 — 생성·턴 실패 시 자동 환불이라 사용자 눈엔 크레딧이 되돌아온 사건이다(2026-08-30 결정).
         */
        fun of(reason: CreditReason): CreditTransactionType? = when (reason) {
            CreditReason.STORY_CREATION, CreditReason.CHAT_TURN -> SPEND
            CreditReason.SIGNUP_REWARD,
            CreditReason.ATTENDANCE_REWARD,
            CreditReason.INVITE_REWARD,
            CreditReason.REFUND,
            -> EARN
            CreditReason.EXPIRE -> EXPIRE
            // 결제 도입 시 구매내역 탭이 따로 가져간다. 지금 EARN에 섞어두면 나중에 중복 노출된다.
            CreditReason.PURCHASE -> null
        }

        /** 이용내역에 노출하는 사유 전체(= `type=ALL`). PURCHASE는 빠져 있다. */
        val historyReasons: Set<CreditReason> =
            CreditReason.entries.filterTo(mutableSetOf()) { of(it) != null }
    }
}

@Schema(description = "이프 이용내역 한 건")
data class CreditTransactionResponse(
    @Schema(description = "화면 분류. 필터 칩과 같은 값")
    val type: CreditTransactionType,
    @Schema(description = "원장 사유 원문. 한국어 라벨은 클라이언트가 붙인다")
    val reason: CreditReason,
    @Schema(description = "부호 있는 증감액. 소모·소멸은 음수")
    val amount: Long,
    @Schema(description = "관련 스토리 제목. 보상·소멸 행이거나 스토리가 삭제됐으면 null")
    val title: String?,
    @Schema(description = "획득 행은 그 적립분의 만료 예정일, 소멸 행은 실제 만료일. 소모 행은 null")
    val expiresAt: Instant?,
    @Schema(description = "원장 기록 시각. 소멸 행은 만료일이 아니라 회수가 기록된 시각이다")
    val createdAt: Instant,
)

@Schema(description = "이프 이용내역 페이지")
data class CreditTransactionPageResponse(
    val items: List<CreditTransactionResponse>,
    @Schema(description = "다음 페이지 커서. 더 없으면 null")
    val nextCursor: String?,
)

@Schema(description = "현재 유효한 이프 적립·소모 수치")
data class CreditPolicyResponse(
    @Schema(description = "가입 보상 이프")
    val signupReward: Long,
    @Schema(description = "초대 보상 이프. 초대자·제출자가 각각 받는다")
    val inviteReward: Long,
    @Schema(description = "초대 보상의 계정별 KST 월 한도. 이프가 아니라 **횟수**다")
    val inviteMonthlyCap: Long,
    @Schema(description = "출석체크 보상 이프. KST 자정 기준 1일 1회")
    val attendanceReward: Long,
    @Schema(description = "스토리 생성 소모 이프")
    val storyCreationCost: Long,
    @Schema(description = "채팅 턴 소모 이프. 재생성도 같은 값")
    val chatTurnCost: Long,
)
