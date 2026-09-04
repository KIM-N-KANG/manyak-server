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
import java.time.ZoneId
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

    // 탈퇴 계정의 소셜 신원으로 재가입해 만들어진 계정임을 표시한다(KNK-1053). NULL이면 순수 신규 가입이다.
    // 회원 무료 체험 미부여 게이트(SocialLoginService)와 진단·분석에 쓴다. 1회성 보상 차단은 이 플래그가 아니라
    // [rewardIdentityUserId]가 담당한다 — "재가입이면 무조건 스킵"은 최초 계정이 크래시로 못 받은 경우까지 막지만,
    // 루트 키로 판정하면 "이 신원이 실제로 받은 적 있는가"를 원장이 답해 자가 복구 구조가 그대로 산다.
    @Column(name = "rejoined_at")
    var rejoinedAt: Instant? = null,

    /**
     * 1회성 보상 멱등 키의 스코프(KNK-1053). NULL이면 자기 자신이라 `rewardIdentityUserId ?: id`가 보상 신원이다.
     *
     * 재가입은 user_id를 갈아치우므로, 키가 user_id에 매여 있으면 `signup:{id}`·`attendance:{id}:{날짜}` 같은
     * 1회성 키가 전부 리셋된다(출석 250은 하루에도 무제한 반복 가능했다). 재가입 계정에 **최초 계정의 id**를
     * 심어 키를 신원에 묶는다. 재가입을 반복해도 루트를 복사하므로 체인이 길어지지 않는다.
     *
     * 기존 회원·순수 신규 가입은 NULL이라 키 문자열이 종전과 동일하다 — 이미 쌓인 원장 행과 호환된다.
     */
    @Column(name = "reward_identity_user_id")
    var rewardIdentityUserId: Long? = null,

    /**
     * 탈퇴 직전 계정 상태(KNK-1053). 탈퇴하지 않은 계정은 NULL이다.
     *
     * 탈퇴는 [status]를 DELETED로 덮어써 "정지였다"는 사실을 지운다. 재가입 계정이 제재를 물려받으려면
     * 그 사실이 어딘가 남아 있어야 해서 탈퇴 시점에 여기 기록한다 — 없으면 정지는 탈퇴·재가입 한 번으로 풀린다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawn_from_status", length = 20)
    var withdrawnFromStatus: UserStatus? = null,

    /** 서비스 알림(스토리 완성·검수 완료) 수신 여부(KNK-1132, V73). 사전 동의가 필요 없어 기본 켜짐 옵트아웃이다. */
    @Column(name = "service_push_enabled", nullable = false)
    var servicePushEnabled: Boolean = true,

    /**
     * 광고 알림 동의 시각(KNK-1132, V73). NULL이면 미동의·철회다.
     *
     * boolean이 아니라 시각인 이유는 **값 자체가 동의 증빙**이기 때문이다(정보통신망법 제50조).
     * 재동의는 이 값을 덮지 않는다 — 증빙은 최초 동의 시점이다.
     */
    @Column(name = "marketing_push_agreed_at")
    var marketingPushAgreedAt: Instant? = null,

    /** 야간(21~08시 KST) 광고 알림 동의 시각(KNK-1132, V73). 광고 동의와 별개이며 단독으로 켤 수 없다. */
    @Column(name = "marketing_push_night_agreed_at")
    var marketingPushNightAgreedAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }

    /**
     * [at] 시점에 광고 푸시를 보낼 수 있는지(KNK-1132, 정책 KNK-1129). 발송기(KNK-1116·1117)가 쓴다.
     *
     * 광고 동의가 있어야 하고, 야간(21~08시 KST)이면 야간 동의까지 있어야 한다. 서비스 알림은 야간 제한이
     * 없고 필드를 그대로 보면 되므로 헬퍼를 두지 않는다.
     */
    fun canReceiveMarketingPush(at: Instant): Boolean {
        if (marketingPushAgreedAt == null) {
            return false
        }
        return !isMarketingNightHour(at) || marketingPushNightAgreedAt != null
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val NIGHT_START_HOUR = 21
        const val NIGHT_END_HOUR = 8

        /** 야간 광고 제한 구간 [21:00, 08:00) KST. 자정을 넘기므로 OR로 판정한다. */
        fun isMarketingNightHour(at: Instant): Boolean {
            val hour = at.atZone(SEOUL_ZONE).hour
            return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        }
    }
}
