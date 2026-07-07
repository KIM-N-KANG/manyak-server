package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * AI 응답 재생성(§4-3-9, B11)으로 교체되기 직전의 ASSISTANT 활성 출력·선택지를 보관하는 append-only 이력이다.
 *
 * 활성본은 [StoryMessage]/[StoryChoice]에 그대로 남아 상세 조회·SSE는 활성본만 노출한다(FE 계약 불변).
 * 이 표는 이전 출력↔새 출력 쌍을 남겨 재생성 원인 분석·AI 품질 평가 데이터의 원천이 된다(스펙 결정 기록).
 * 이력·분석용이라 선택지는 정규화하지 않고 [choices]에 JSON 배열 스냅샷으로 둔다.
 */
@Entity
@Table(
    name = "story_message_versions",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_message_versions",
            columnNames = ["message_id", "version_number"],
        ),
    ],
)
class StoryMessageVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 재생성 대상 ASSISTANT 메시지(story_messages.id). 이 메시지의 과거 출력들이 versionNumber 순으로 쌓인다.
    @Column(name = "message_id", nullable = false)
    val messageId: Long,

    // 이 메시지에서 보관된 순번(1-based). 1 = 최초 출력, 이후 재생성마다 직전 활성본이 다음 번호로 보관된다.
    @Column(name = "version_number", nullable = false)
    val versionNumber: Int,

    // 보관 당시의 AI 출력 본문.
    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    // 보관 당시의 선택지 텍스트 목록(JSON 배열, choice_order 오름차순).
    @Column(nullable = false, columnDefinition = "TEXT")
    val choices: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
