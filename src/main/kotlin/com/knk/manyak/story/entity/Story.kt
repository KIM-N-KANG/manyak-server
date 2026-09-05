package com.knk.manyak.story.entity

import com.knk.manyak.image.service.ImageModerationStatus
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

    // 스토리 수정(PATCH, KNK-404)으로 기본 정보를 갱신할 수 있어 var로 둔다.
    @Column(nullable = false, length = 100)
    var title: String,

    @Column(name = "one_line_intro", length = 255)
    var oneLineIntro: String? = null,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(length = 255)
    var genre: String? = null,

    // 대표 이미지(표지)의 카탈로그 키. 등록 시 장르 매칭으로 1회 확정하며, 이후 수정으로 장르가 바뀌어도
    // 재연결하지 않는다(스펙 §4-3-9). 서빙 URL은 저장하지 않고 ImageUrlResolver가 조합한다.
    @Column(name = "thumbnail_image_key", length = 64)
    val thumbnailImageKey: String? = null,

    // 생성 표지(KNK-1069) 또는 소유자가 올린 표지(KNK-1126)의 절대 URL.
    // 프리셋 키(thumbnailImageKey)와 **공존한다** — 생성·업로드 성공이어도 프리셋 연결은 지우지 않아, 이 값이
    // 비면(구버전 AI·생성 실패·일반 제작·기존 스토리·표지 삭제) 노출이 자동으로 프리셋으로 떨어진다.
    // 2단 폴백 판정은 ImageUrlResolver 한 곳이 소유한다(호출부에 분기를 흩뿌리지 않는다).
    // 업로드 교체·삭제가 생기면서 var다(KNK-1126) — 등록 시 1회 확정이던 시절의 val이 아니다.
    @Column(name = "thumbnail_image_url", columnDefinition = "TEXT")
    var thumbnailImageUrl: String? = null,

    // 표지 검수 상태(KNK-1126, V76). APPROVED가 아니면 노출이 프리셋으로 떨어진다(ImageUrlResolver의 게이트).
    // 프리셋 표지는 팀 자산이라 검수 대상이 아니다 — 이 상태는 thumbnailImageUrl에만 걸린다.
    @Enumerated(EnumType.STRING)
    @Column(name = "thumbnail_moderation_status", nullable = false, length = 20)
    var thumbnailModerationStatus: ImageModerationStatus = ImageModerationStatus.APPROVED,

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
     * 읽기 허용 여부(KNK-464):
     * - 공개(PUBLISHED∧PUBLIC) 스토리는 누구나 읽을 수 있다.
     * - 소유자가 없는(게스트 제작) 스토리는 공개 식별자(UUID)를 아는 요청자면 읽을 수 있다. 서버에 게스트 식별
     *   수단이 없어 소유권으로 가릴 수 없고, UUID 보유가 사실상 본인 서재·링크 보유를 뜻하기 때문이다(게스트 서재 유지).
     * - 소유자가 있는 스토리는 비공개(PRIVATE)라도 소유자 본인만 읽을 수 있다.
     */
    fun isReadableBy(userId: Long?): Boolean =
        isPubliclyVisible() || this.userId == null || (userId != null && userId == this.userId)

    /**
     * 이 스토리를 참조하는 카드·목록이 **현재** 제목·썸네일을 그대로 보여도 되는지(KNK-1059).
     *
     * [isReadableBy]는 삭제([deletedAt])를 보지 않으므로 여기서 함께 판정한다. 거짓이면 호출부는
     * 스토리의 현재 값 대신 참조하는 쪽이 들고 있는 스냅샷을 쓴다 — 소유자가 공개를 거둔 뒤의 제목·썸네일이
     * 이미 채팅을 시작한 남에게 계속 흘러가는 것을 막기 위해서다(PR #216 Codex P1).
     */
    fun isCurrentMetadataVisibleTo(userId: Long?): Boolean =
        deletedAt == null && isReadableBy(userId)
}
