package com.knk.manyak.story.entity

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

@Entity
@Table(name = "stories")
class Story(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부에 노출하는 추측 불가능한 식별자. 순차 PK 열거(IDOR)를 막기 위해 API는 이 값만 입출력한다.
    // 내부 PK(id)는 FK·조인·성능용으로만 사용한다. (채팅 KNK-178 선례)
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    // 게스트(비로그인) 생성 시 NULL. 로그인 후 마이그레이션(KNK-389)이 조건부 UPDATE로 요청자 user_id를 클레임한다.
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(nullable = false, length = 100)
    val title: String,

    @Column(name = "one_line_intro", length = 255)
    val oneLineIntro: String? = null,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Column(length = 255)
    val genre: String? = null,

    // 등록 상태(초안/발행). 일반 모드 초안 저장은 DRAFT로 시작하고, 발행 시 PUBLISHED가 된다(KNK-401).
    // 기존 행·간편 제작 스토리는 기본값 PUBLISHED다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: StoryStatus = StoryStatus.PUBLISHED,

    // 공개 범위. 공개 조회는 PUBLISHED이면서 PUBLIC인 스토리만 노출한다. 초안은 PRIVATE로 생성한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: StoryVisibility = StoryVisibility.PUBLIC,

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

    /** 공개 조회 노출 조건: 발행(PUBLISHED)이면서 공개(PUBLIC)인 스토리(KNK-401). */
    fun isPubliclyVisible(): Boolean =
        status == StoryStatus.PUBLISHED && visibility == StoryVisibility.PUBLIC

    /**
     * 읽기 허용 여부: 공개 스토리이거나 요청자가 소유자이면 허용한다(비공개 초안은 소유자만 접근).
     * 비회원(userId=null)은 공개 스토리만 읽을 수 있다.
     */
    fun isReadableBy(userId: Long?): Boolean =
        isPubliclyVisible() || (userId != null && userId == this.userId)
}
