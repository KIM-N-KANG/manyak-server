package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 스토리 엔딩(시작 설정 소유 1:N, 스펙 §4-3-10). 유형 없이 이름으로 식별한다(KNK-462).
 *
 * 엔딩 조건은 2파라미터다(KNK-463): [minTurns](정수)는 백엔드가 결정적으로 판정하고, [achievementCondition](자연어)은
 * AI가 정성 판정한다. 둘 다 충족(AND)해야 도달하는 하드 조건이며, 도달 시 [epilogue] 가이드로 엔딩 응답을 생성한다.
 *
 * 레거시 행(title·content·condition_text, KNK-419)은 새 구조로 자동 변환하지 않고 enabled=false로 비활성 보존한다.
 * 레거시 컬럼은 DB에 nullable로 남아 있으며(V33) 이 엔티티는 매핑하지 않는다. 활성(enabled=true) 행만 조회한다.
 */
@Entity
@Table(
    name = "story_endings",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_endings_order",
            columnNames = ["start_setting_id", "sort_order"],
        ),
    ],
)
class StoryEnding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "start_setting_id", nullable = false)
    val startSetting: StoryStartSetting,

    @Column(nullable = false, length = 100)
    val name: String,

    // 최소 턴 수(백엔드 결정적 판정). 충족한 엔딩만 AI 요청에 싣는다(런타임은 §4-3-10, 별도 범위).
    @Column(name = "min_turns", nullable = false)
    val minTurns: Int,

    // 달성 조건(자연어, AI 정성 판정). 목적·거쳐온 주요 사건을 한 필드에 자유 서술한다.
    @Column(name = "achievement_condition", nullable = false, columnDefinition = "TEXT")
    val achievementCondition: String,

    // 도달 시 엔딩 응답 생성을 위한 출력 가이드.
    @Column(nullable = false, columnDefinition = "TEXT")
    val epilogue: String,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Short,

    @Column(nullable = false)
    val enabled: Boolean = true,

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
