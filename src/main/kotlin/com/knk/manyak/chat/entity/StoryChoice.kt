package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "story_choices",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_choices_order",
            columnNames = ["message_id", "choice_order"],
        ),
    ],
)
class StoryChoice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Column(name = "message_id", nullable = false)
    val messageId: Long,

    @Column(name = "choice_text", nullable = false, columnDefinition = "TEXT")
    val choiceText: String,

    @Column(name = "choice_order", nullable = false)
    val choiceOrder: Short,

    @Column(name = "is_selected", nullable = false)
    var isSelected: Boolean = false,

    @Column(name = "selected_at")
    var selectedAt: Instant? = null,

    // 선택한 선택지 원문과 최종 사용자 입력의 정규화 비교 결과(KNK-819, V55). 고쳐 보냈으면 true, 그대로면 false.
    // nullable이 의미를 갖는다 — NULL은 "선택 기록 없음"(직접 입력·구버전 클라이언트·기록 스킵)이고 false와 다른 사실이다.
    @Column(name = "is_edited")
    var isEdited: Boolean? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
