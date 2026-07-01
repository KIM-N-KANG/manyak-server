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

@Entity
@Table(
    name = "social_accounts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_social_accounts_provider_user",
            columnNames = ["provider", "provider_user_id"],
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

    // 소유 사용자의 내부 PK. JPA 관계 매핑 대신 평문 컬럼으로 둔다(StoryPlaySession.userId 선례).
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: SocialProvider,

    // 소셜 제공자가 발급한 사용자 식별자. (provider, provider_user_id)가 계정 유일성을 보장한다.
    @Column(name = "provider_user_id", nullable = false, length = 255)
    val providerUserId: String,

    @Column(length = 255)
    var email: String? = null,

    @Column(name = "connected_at", nullable = false)
    val connectedAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
