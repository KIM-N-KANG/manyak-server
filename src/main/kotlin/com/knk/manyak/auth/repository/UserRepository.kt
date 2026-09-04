package com.knk.manyak.auth.repository

import com.knk.manyak.auth.entity.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface UserRepository : JpaRepository<User, Long> {
    // API 외부 식별자(public_id) 기준 조회. 순차 PK 열거(IDOR) 차단.
    fun findByPublicId(publicId: UUID): User?

    // 초대 코드로 초대자를 역해석한다(POST /auth/login/google의 inviteCode → 초대자 User). 없으면 null(무시).
    fun findByInviteCode(inviteCode: String): User?

    // 초대 코드 지연 발급 시 후보의 유일성을 확인한다(유니크 제약이 최종 방어, 이건 재시도용 사전 점검).
    fun existsByInviteCode(inviteCode: String): Boolean

    /**
     * 사용자 행을 비관적 쓰기 락으로 조회한다. 초대 코드 지연 발급에서 같은 사용자의 동시 GET /invite가
     * 서로 다른 코드로 덮어쓰지 않도록 발급을 직렬화한다(먼저 잡은 쪽이 발급하면 뒤는 그 값을 읽는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): User?

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
     */
    @Query(
        value = """
        SELECT u.* FROM users u
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
    fun findAttendanceReminderTargets(@Param("attendanceDate") attendanceDate: String): List<User>

    /**
     * [id] 회원의 **보상 신원**(`coalesce(reward_identity_user_id, id)`)을 돌려준다(KNK-1053).
     * 1회성 보상의 멱등 키를 user_id가 아니라 이 값으로 만들어야, 재가입이 user_id를 갈아치워도 키가 리셋되지 않는다.
     * 회원이 없으면 null(호출부가 원래 id로 폴백한다).
     */
    @Query("SELECT coalesce(u.rewardIdentityUserId, u.id) FROM User u WHERE u.id = :id")
    fun findRewardIdentityUserId(@Param("id") id: Long): Long?

    /**
     * 회원 체험 스냅샷 완료를 기록한다(스펙 §4-3-7 B13, KNK-504). 아직 미스냅샷(NULL)인 계정만 채워, 동시 첫
     * 로그인 경합·재시도에도 최초 1회만 유효하게 남는다(이미 채워진 값은 덮지 않는다). 갱신 행 수를 반환한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.memberTrialSeededAt = :seededAt WHERE u.id = :id AND u.memberTrialSeededAt IS NULL")
    fun markMemberTrialSeeded(@Param("id") id: Long, @Param("seededAt") seededAt: Instant): Int
}
