package com.knk.manyak.story.entity

import com.knk.manyak.story.dto.SimpleStoryTagCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

enum class StoryCreationTagSource {
    PREDEFINED,
    CUSTOM,
}

@Entity
@Table(
    name = "story_creation_tags",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_creation_tags_source_type_normalized_name",
            columnNames = ["tag_source", "tag_type", "normalized_name"],
        ),
    ],
)
class StoryCreationTag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 용어집(KNK-360) 기준 코드 표기는 category. DB 컬럼은 rename 비용 때문에 tag_type을 유지한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "tag_type", nullable = false, length = 50)
    val category: SimpleStoryTagCategory,

    @Column(nullable = false, length = 30)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_source", nullable = false, length = 20)
    val tagSource: StoryCreationTagSource,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    /**
     * 태그 동일성 판정 키(KNK-717, 스펙 §4-3-2). 표시명 [name]은 최초 입력 원문을 유지하고,
     * 대소문자·공백 변형(BL / Bl / b l)은 이 키로 같은 태그로 묶인다.
     *
     * 길이 상한은 표시명(30)의 2배다. [normalize]는 문자 단위 매핑이라 키가 30자를 넘지 않지만,
     * 매핑 규칙이 전체 케이스 매핑으로 바뀌면 코드포인트가 늘어 저장이 깨지므로 여유를 둔다.
     */
    @Column(name = "normalized_name", nullable = false, length = 60)
    val normalizedName: String = normalize(name)

    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }

    companion object {
        /**
         * trim → 내부 공백 전부 제거 → lowercase. 마이그레이션 백필 SQL과 같은 규칙이어야 한다.
         *
         * 소문자화는 [String.lowercase]가 아니라 문자 단위 [Char.lowercaseChar]를 쓴다. 전체 케이스 매핑은
         * `İ`(U+0130)를 `i` + U+0307 두 코드포인트로 늘리는데, 백필이 쓰는 SQL `lower()`는 `i` 한 글자를 낸다.
         * 규칙이 갈리면 백필된 키와 런타임 조회 키가 어긋나 같은 이름이 중복 행으로 갈린다.
         */
        fun normalize(name: String): String =
            name.filterNot(Char::isWhitespace).map(Char::lowercaseChar).joinToString("")
    }
}
