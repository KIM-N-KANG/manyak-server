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

    // 이 회원을 초대한 사용자(최초 가입 시 제출한 유효 초대 코드의 주인). 초대 없이 가입했으면 null.
    // 계정 생성 트랜잭션에 함께 커밋해, 초대 보상 유실 시 매 로그인 멱등 재적립으로 자가 복구하는 근거로 쓴다.
    @Column(name = "inviter_user_id")
    val inviterUserId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: UserStatus = UserStatus.ACTIVE,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
