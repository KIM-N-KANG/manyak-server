package com.knk.manyak.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class UserStatus {
    ACTIVE,
    SUSPENDED,
    DELETED,
}

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부에 노출하는 추측 불가능한 식별자. 순차 PK 열거(IDOR)를 막기 위해 API는 이 값만 입출력한다.
    // 내부 PK(id)는 FK·조인·성능용으로만 사용한다.
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 50)
    var nickname: String,

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    var profileImageUrl: String? = null,

    // 목록·미리보기용 저해상도 썸네일(Base64). 원본은 profile_image_url(외부 스토리지)로 참조한다.
    @Column(name = "profile_thumbnail_base64", columnDefinition = "TEXT")
    var profileThumbnailBase64: String? = null,

    // 사용자별 고유 초대 코드(스펙 §4-3-7). 최초 GET /users/me/invite 시 지연 발급하므로 그 전까지 null이다.
    // unique: 코드로 초대자를 역해석하므로 전역 유일. null(미발급)은 유니크 충돌 대상이 아니다.
    @Column(name = "invite_code", unique = true, length = 16)
    var inviteCode: String? = null,

    // 이 회원을 초대한 사용자(초대 코드 입력 성공 시 저장 — 스펙 §4-3-7, KNK-567). 입력한 적 없으면 null.
    // redeem 트랜잭션에서 양측 적립과 원자적으로 커밋되며, non-null이면 평생 1회 자격을 소진한 것으로 본다.
    @Column(name = "inviter_user_id")
    var inviterUserId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: UserStatus = UserStatus.ACTIVE,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    // 게스트 데이터 이관(POST /api/v1/auth/migrate)을 완료한 시각(스펙 §4-3-5, KNK-480). 이관은 계정당 1회만 허용한다.
    // 한 요청으로 한 건이라도 소유권을 얻으면 이 값을 기록해 계정을 잠그고, 값이 있으면 이후 이관 호출은
    // 평가 없이 migrationClosed=true로 닫는다. null이면 아직 이관하지 않음(최초 이관 가능).
    @Column(name = "migrated_at")
    var migratedAt: Instant? = null,

    // 이관 시도(POST /api/v1/auth/migrate 호출) 누적 횟수(스펙 §4-3-5 B19, KNK-500). 성공 0건 호출도 포함해 세며,
    // 상한(5회) 도달 후 추가 호출은 평가 없이 닫힌 계정처럼 처리해 소유 상태 열거 오라클을 제한한다.
    @Column(name = "migration_attempts", nullable = false)
    var migrationAttempts: Int = 0,

    // 회원 체험 스냅샷 완료 시각(스펙 §4-3-7 B13, KNK-504). NULL이면 미스냅샷(신규 가입) — 로그인이 게스트 디바이스
    // 사용량을 회원 카운터로 1회 시드하고 이 값을 기록한다. Redis 장애로 실패하면 NULL로 남아 다음 로그인이 재시도한다.
    // 기존(롤아웃 이전) 회원은 마이그레이션(V40)이 채워 스냅샷 대상에서 제외한다.
    @Column(name = "member_trial_seeded_at")
    var memberTrialSeededAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
