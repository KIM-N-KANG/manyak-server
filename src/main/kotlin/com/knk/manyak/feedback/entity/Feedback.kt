package com.knk.manyak.feedback.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "feedbacks")
class Feedback(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 로그인 유저가 남긴 경우 서버가 채운다. 인증 도입 전까지는 항상 null이며, 익명 피드백을 허용한다.
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    val body: String,

    // 응답을 원하는 경우에만 선택적으로 입력한다.
    @Column(length = 320)
    val email: String? = null,

    // 클라이언트(앱)가 화면 입력 없이 자동으로 첨부하는 분석용 메타.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    val platform: Platform? = null,

    @Column(name = "app_version", length = 50)
    val appVersion: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

enum class Platform {
    IOS,
    ANDROID,
    WEB,
}
