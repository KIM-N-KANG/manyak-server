package com.knk.manyak.push.entity

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

/** 지원 플랫폼. iOS 앱이 생기면 enum·CHECK 제약과 함께 FCM 메시지의 ApnsConfig도 같이 추가한다. */
enum class PushPlatform {
    ANDROID,
}

/**
 * 회원 기기의 FCM 등록 토큰(KNK-1131, V72). 토큰은 "이 기기의 이 앱 설치본" 주소라 전역 유일하며,
 * 한 회원이 기기 여러 대를 가질 수 있다. 게스트 기기는 저장하지 않는다.
 */
@Entity
@Table(
    name = "device_push_tokens",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_device_push_tokens_token", columnNames = ["token"]),
    ],
    indexes = [
        Index(name = "idx_device_push_tokens_user", columnList = "user_id"),
    ],
)
class DevicePushToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 소유 회원의 내부 PK. JPA 관계 대신 평문 컬럼으로 둔다(SocialAccount 선례).
    // 한 기기에서 계정을 바꾸면 같은 토큰의 소유자가 옮겨가므로 var다.
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(nullable = false, length = 512)
    val token: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var platform: PushPlatform,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    // 마지막 등록(갱신) 시각. 재등록이 값을 바꾸지 않아도 서비스가 직접 갱신한다.
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
