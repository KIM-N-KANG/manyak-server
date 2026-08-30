package com.knk.manyak.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

enum class SocialProvider {
    GOOGLE,
    KAKAO,
    APPLE,
    NAVER,
}

/**
 * 외부 계약(API 응답·분석 이벤트 프로퍼티)에 싣는 표기. **소문자 고정**이다(KNK-739).
 * 프론트엔드 경로와 NextAuth provider ID가 소문자라, enum 직렬화(대문자)를 그대로 쓰면 계약이 갈린다.
 */
val SocialProvider.wireValue: String
    get() = name.lowercase()

@Entity
@Table(
    name = "social_accounts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_social_accounts_provider_user",
            columnNames = ["provider", "provider_user_id"],
        ),
        // 계정 연동(KNK-739, V52): 한 회원에게 같은 provider 연동은 하나뿐이다. 동시 연동 요청 경합의 최종 방어선.
        UniqueConstraint(
            name = "uq_social_accounts_user_provider",
            columnNames = ["user_id", "provider"],
        ),
    ],
    indexes = [
        Index(name = "idx_social_accounts_user", columnList = "user_id"),
    ],
)
class SocialAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 소유 사용자의 내부 PK. JPA 관계 매핑 대신 평문 컬럼으로 둔다(StoryChat.userId 선례).
    // 재가입·계정 연동이 tombstone 행을 claim할 때 새 소유자로 갱신하므로 var다(KNK-1053).
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: SocialProvider,

    // 소셜 제공자가 발급한 사용자 식별자. (provider, provider_user_id)가 계정 유일성을 보장한다.
    @Column(name = "provider_user_id", nullable = false, length = 255)
    val providerUserId: String,

    @Column(length = 255)
    var email: String? = null,

    @Column(name = "connected_at", nullable = false)
    var connectedAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    /**
     * 탈퇴로 끊긴 연동의 tombstone 시각(KNK-1053). NULL이면 살아 있는 연동이다.
     *
     * 행을 하드 삭제하지 않는 이유: `(provider, provider_user_id)` 유니크를 남겨 두어야 같은 소셜 신원의 재가입이
     * **이 행의 재사용**을 강제받는다. 삭제하면 재가입마다 완전히 새 `users` 행이 생겨 user_id에 매달린
     * 1회성 혜택(가입 보상·초대 제출 자격)이 통째로 리셋된다.
     *
     * 개인정보 파기는 [email]을 NULL로 지워 충족한다. [providerUserId]는 제공자 없이는 식별 불가한
     * pseudonymous ID이고 재가입 매칭 키라 남긴다.
     */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
