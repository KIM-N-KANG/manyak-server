package com.knk.manyak.push.repository

import com.knk.manyak.push.entity.DevicePushToken
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

/**
 * 출석 리마인드, 프로모션, 공지 회차의 대상 id와 캠페인 대상 수(KNK-2056에서 `UserRepository`에서 옮김).
 *
 * `users`의 상태와 푸시 수신 동의 컬럼, 보상 신원을 읽는다. 동의 컬럼은 NOTIFICATION이 정하고, 이 조회는 NOTIFICATION 3-2의
 * "참조하는 다른 도메인의 컬럼" 표에 적힌 컬럼만 쓴다. 쿼리 문장은 옮기기 전과 같다.
 */
interface PushTargetRepository : Repository<DevicePushToken, Long> {
    /**
     * 출석 리마인드 푸시 대상(KNK-1116, 광고성 알림). 다음을 모두 만족하는 회원이다.
     * - `ACTIVE`(정지·탈퇴 제외)
     * - 광고 수신에 동의함(`marketing_push_agreed_at IS NOT NULL` — V73, 정책 KNK-1129)
     * - 등록된 기기 토큰이 하나라도 있음(없으면 보낼 곳이 없다)
     * - **오늘(KST) 출석 보상을 아직 받지 않음**
     *
     * 마지막 조건을 새 상태 컬럼이 아니라 원장의 멱등 키 부재로 판정한다 — 적립(`AttendanceRewardService`)이
     * 쓰는 바로 그 키라 두 경로가 어긋날 수 없다. 키의 신원은 `user_id`가 아니라 **보상 신원**
     * `coalesce(reward_identity_user_id, id)`다(KNK-1053): 재가입 계정의 출석은 최초 계정 id로 기록되므로,
     * user_id로 판정하면 이미 받은 사람에게 리마인드가 간다.
     *
     * [attendanceDate]는 `AttendanceRewardService`와 같은 KST 날짜 문자열(`YYYY-MM-DD`)이다.
     * 네이티브 쿼리인 이유: JPQL은 숫자를 문자열에 이어 붙이는 표현이 방언마다 갈려 키 조립이 불안정하다.
     *
     * **엔티티가 아니라 id만 돌려준다**(Codex 리뷰 P1). 회차가 도는 동안 동의가 바뀔 수 있어 호출부가 발송
     * 직전에 회원을 다시 읽어야 하므로, 여기서 스냅샷을 만들어 들고 있을 이유가 없다(메모리도 준다).
     */
    @Query(
        value = """
        SELECT u.id FROM users u
        WHERE u.status = 'ACTIVE'
          AND u.marketing_push_agreed_at IS NOT NULL
          AND EXISTS (SELECT 1 FROM device_push_tokens t WHERE t.user_id = u.id)
          AND NOT EXISTS (
            SELECT 1 FROM credit_transactions c
            WHERE c.idempotency_key =
              'attendance:' || COALESCE(u.reward_identity_user_id, u.id) || ':' || :attendanceDate
          )
        """,
        nativeQuery = true,
    )
    fun findAttendanceReminderTargetIds(@Param("attendanceDate") attendanceDate: String): List<Long>

    /**
     * 프로모션 푸시 대상 id(KNK-1117, 스펙 §4-3-5). 조건은 셋뿐이다.
     *
     * - `ACTIVE`(정지·탈퇴 제외)
     * - 광고 수신에 동의함(`marketing_push_agreed_at IS NOT NULL` — V73, 정책 KNK-1129)
     * - 등록된 기기 토큰이 하나라도 있음(없으면 보낼 곳이 없다)
     *
     * 출석 리마인드([findAttendanceReminderTargetIds])에서 "오늘 출석 미수령" 조건만 뺀 모양이다. 공통
     * 조각으로 묶지 않은 이유: 두 쿼리의 수명이 다르다(출석 조건은 원장 스키마를 따라가고 이쪽은 따라가지
     * 않는다). 한쪽 조건이 바뀔 때 다른 쪽 발송이 조용히 달라지는 결합이 중복보다 비싸다.
     *
     * **야간 판정은 여기서 하지 않는다.** 야간 동의 여부는 회원마다 발송 직전에
     * [com.knk.manyak.auth.dto.PushRecipient.canReceiveMarketingPush]로 본다 — 야간에 예약된 캠페인은 거부·연기가
     * 아니라 **야간 동의자에게만** 나가고, 나머지는 `skipped_count`로 남아야 하기 때문이다.
     *
     * 엔티티가 아니라 id만 돌려주는 이유도 출석과 같다: 호출부가 발송 직전에 다시 읽으므로 스냅샷이 쓸모없다.
     */
    @Query(
        value = """
        SELECT u.id FROM users u
        WHERE u.status = 'ACTIVE'
          AND u.marketing_push_agreed_at IS NOT NULL
          AND EXISTS (SELECT 1 FROM device_push_tokens t WHERE t.user_id = u.id)
        ORDER BY u.id
        """,
        nativeQuery = true,
    )
    fun findMarketingPushTargetIds(): List<Long>

    @Query("""SELECT u.id FROM User u WHERE u.status = com.knk.manyak.auth.dto.UserStatus.ACTIVE
        AND u.servicePushEnabled = true
        AND EXISTS (SELECT t.id FROM DevicePushToken t WHERE t.userId = u.id)
        ORDER BY u.id""")
    fun findServicePushTargetIds(): List<Long>

    @Query("""SELECT COUNT(u) FROM User u WHERE u.status = com.knk.manyak.auth.dto.UserStatus.ACTIVE
        AND EXISTS (SELECT t.id FROM DevicePushToken t WHERE t.userId = u.id)
        AND ((:marketing = false AND u.servicePushEnabled = true)
          OR (:marketing = true AND u.marketingPushAgreedAt IS NOT NULL))
        AND (:nightOnly = false OR u.marketingPushNightAgreedAt IS NOT NULL)""")
    fun countPushTargets(marketing: Boolean, nightOnly: Boolean): Long
}
