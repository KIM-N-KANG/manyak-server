package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface StoryRepository : JpaRepository<Story, Long> {

    // KNK-447: 회원 서재(내 스토리 목록). 요청자 소유·미삭제만 생성 최신순으로 조회한다. limit은 Pageable로 상한을 건다.
    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(userId: Long, pageable: Pageable): List<Story>
    // 소프트 삭제된 스토리(deleted_at IS NOT NULL)는 조회·삭제 대상에서 제외한다.
    fun findByIdAndDeletedAtIsNull(id: Long): Story?

    fun findAllByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<Story>

    // 인물 이미지 보상 삭제 판정(KNK-966): 커밋이 반영됐는데 응답만 유실된 모호한 실패에서 스토리 행이 남았는지 본다.
    // 삭제 여부를 가리지 않는다 — 행이 남아 있으면 image_url도 남아 있으므로 객체를 지우면 안 된다.
    fun existsByPublicId(publicId: UUID): Boolean

    // KNK-256: API 외부 식별자(public_id) 기준 조회. 순차 PK 열거(IDOR) 차단.
    // 삭제된 스토리 제외라 KNK-257(삭제 스토리 채팅 생성 차단)도 이 조회로 함께 해결된다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): Story?

    /**
     * 스토리 행을 비관적 쓰기 락으로 조회한다(KNK-418). 자식 리소스 전체 교체(주요 사건 교체 등)를 스토리 단위로
     * 직렬화해, 동시 교체가 delete 후 같은 sort_order로 동시 insert하다 유니크 위반(500)이 나는 것을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Story s WHERE s.publicId = :publicId AND s.deletedAt IS NULL")
    fun findByPublicIdAndDeletedAtIsNullForUpdate(@Param("publicId") publicId: UUID): Story?

    fun findAllByPublicIdInAndDeletedAtIsNull(publicIds: Collection<UUID>): List<Story>

    // KNK-975: 마냑 오리지널 스토리 목록. 공식 계정 소유의 공개(PUBLISHED∧PUBLIC)·미삭제 스토리를 등록순으로 조회한다.
    fun findByUserIdAndStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
        userId: Long,
        status: StoryStatus,
        visibility: StoryVisibility,
    ): List<Story>

    /**
     * 공개 스토리 목록(KNK-149) 최신순 첫 페이지. 노출 조건은 네 가지를 모두 만족해야 한다 —
     * 발행·공개·미삭제·**회원 소유**. 게스트(소유자 없음) 스토리를 피드에 싣지 않는 이유는 작성자 신원이
     * 없어 카드에 작성자를 표기할 수 없고, 좋아요·신고 같은 소셜 기능의 책임 주체가 없기 때문이다.
     * 간편 제작은 게스트도 PRIVATE으로 저장되므로([SimpleStoryCreationService], KNK-464) 실제로 걸러지는
     * 것은 일반 제작에서 PUBLIC을 지정한 게스트 스토리와 향후 경로다. 게스트가 로그인해 이관(소유권 획득)하면
     * 회원 소유가 되어 자연히 노출된다.
     *
     * 2차 정렬 키는 내부 PK가 아니라 `public_id`다(순차 PK를 커서에 실으면 IDOR).
     */
    @Query(
        """
        SELECT s FROM Story s
        WHERE s.status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED
          AND s.visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC
          AND s.deletedAt IS NULL
          AND s.userId IS NOT NULL
        ORDER BY s.createdAt DESC, s.publicId DESC
        """,
    )
    fun findPublicLatest(pageable: Pageable): List<Story>

    /** 최신순 다음 페이지. keyset 조건이라 페이지 사이에 행이 끼어들어도 중복·누락이 없다. */
    @Query(
        """
        SELECT s FROM Story s
        WHERE s.status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED
          AND s.visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC
          AND s.deletedAt IS NULL
          AND s.userId IS NOT NULL
          AND (s.createdAt < :createdAt OR (s.createdAt = :createdAt AND s.publicId < :publicId))
        ORDER BY s.createdAt DESC, s.publicId DESC
        """,
    )
    fun findPublicLatestAfter(
        @Param("createdAt") createdAt: Instant,
        @Param("publicId") publicId: UUID,
        pageable: Pageable,
    ): List<Story>

    /**
     * 공개 스토리 목록 인기순 첫 페이지. `likeCount`는 컬럼이 아니라 `story_likes` 집계라 상관 서브쿼리로 센다.
     * JPQL은 SELECT 별칭을 WHERE·ORDER BY에서 쓸 수 없어 같은 서브쿼리를 반복한다. 비정규화 컬럼은 두지
     * 않는다 — 마이그레이션과 좋아요 등록·취소 양쪽의 동기화 비용이 목록 하나보다 크다.
     */
    @Query(
        """
        SELECT s FROM Story s
        WHERE s.status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED
          AND s.visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC
          AND s.deletedAt IS NULL
          AND s.userId IS NOT NULL
        ORDER BY (SELECT COUNT(l) FROM StoryLike l WHERE l.storyId = s.id) DESC, s.publicId DESC
        """,
    )
    fun findPublicPopular(pageable: Pageable): List<Story>

    /** 인기순 다음 페이지. 1차 키가 좋아요 수라 동률 구간은 `public_id`로 갈라 결정적으로 이어진다. */
    @Query(
        """
        SELECT s FROM Story s
        WHERE s.status = com.knk.manyak.story.entity.StoryStatus.PUBLISHED
          AND s.visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC
          AND s.deletedAt IS NULL
          AND s.userId IS NOT NULL
          AND ((SELECT COUNT(l) FROM StoryLike l WHERE l.storyId = s.id) < :likeCount
               OR ((SELECT COUNT(l) FROM StoryLike l WHERE l.storyId = s.id) = :likeCount
                   AND s.publicId < :publicId))
        ORDER BY (SELECT COUNT(l) FROM StoryLike l WHERE l.storyId = s.id) DESC, s.publicId DESC
        """,
    )
    fun findPublicPopularAfter(
        @Param("likeCount") likeCount: Long,
        @Param("publicId") publicId: UUID,
        pageable: Pageable,
    ): List<Story>

    /**
     * 게스트 스토리(user_id NULL)의 소유권을 원자적으로 클레임한다(KNK-389). 갱신한 행 수를 반환한다(1=성공, 0=이미 소유됨/선점됨).
     *
     * `WHERE user_id IS NULL` 조건을 DB가 갱신 시점에 재평가하므로, 동시 요청이 같은 행을 클레임해도 한 트랜잭션만 1을 받는다.
     */
    @Modifying
    @Query("UPDATE Story s SET s.userId = :userId WHERE s.publicId = :publicId AND s.userId IS NULL AND s.deletedAt IS NULL")
    fun claimByPublicId(@Param("publicId") publicId: UUID, @Param("userId") userId: Long): Int

    /**
     * public_id로 현재 소유자(user_id)를 조회한다. 클레임 실패(0건) 후 실제 소유자를 재확인하는 용도(동시 요청 멱등 판정).
     *
     * 엔티티 로드가 아닌 스칼라 조회라 영속성 컨텍스트 캐시를 우회해 DB의 최신 커밋 값을 읽는다.
     */
    @Query("SELECT s.userId FROM Story s WHERE s.publicId = :publicId")
    fun findUserIdByPublicId(@Param("publicId") publicId: UUID): Long?
}
