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
     * 길이는 표시명 상한(30)의 2배다. lowercase는 코드포인트를 늘릴 수 있어(`İ`(U+0130) → `i` + 결합 점, 2배가 상한)
     * 30자 입력이 60자 키가 될 수 있다. 30으로 두면 상한을 지킨 요청이 저장 단계에서 깨진다.
     */
    @Column(name = "normalized_name", nullable = false, length = 60)
    val normalizedName: String = normalize(name)

    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }

    companion object {
        /** trim → 내부 공백 전부 제거 → lowercase. 마이그레이션 백필 SQL과 같은 규칙이어야 한다. */
        fun normalize(name: String): String = name.filterNot(Char::isWhitespace).lowercase()
    }
}
