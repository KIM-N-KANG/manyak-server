package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * 채팅 공유 링크(스펙 §4-3-11, KNK-706). 발급 시점까지의 채팅을 읽기 전용으로 여는 토큰이다.
 *
 * 메시지를 복사하지 않고 발급 시점의 `story_chats.current_turn`만 [turnCutoff]로 기록해 시점을 고정한다
 * — 이후 원본이 진행돼도 열람 내용은 변하지 않고, 커트라인 이내 턴의 재생성 결과(활성본)는 반영된다.
 * 삭제 컬럼이 없어 유효성은 원본 채팅의 `deleted_at`에 종속된다(공유 해지 기능 없음).
 */
@Entity
@Table(
    name = "story_chat_shares",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_chat_shares_chat_cutoff",
            columnNames = ["chat_id", "turn_cutoff"],
        ),
    ],
)
class StoryChatShare(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 공유 열람 토큰. 추측 불가 UUID 보유가 곧 접근 수단이므로 채팅 public_id와 무관한 별도 값을 발급한다.
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    // 발급 시점의 진행 턴 수. 열람은 이 값 이하의 턴만 구성한다.
    @Column(name = "turn_cutoff", nullable = false)
    val turnCutoff: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
