package com.knk.manyak.story.service

/**
 * 스토리별 누적 턴 수(카드·상세의 `turnCount`).
 *
 * 집계 원천인 `story_chats`는 chat 모듈 소유라 chat이 구현한다(`chat.service.ChatStoryTurnCounts`). story가 chat
 * 리포지토리를 직접 부르면 story→chat 간선이 생겨 chat↔story 순환이 되므로, story는 이 인터페이스만 안다(KNK-2055).
 * 구현이 하나뿐이지만 의존 방향을 뒤집는 것이 존재 이유라 합치지 않는다.
 *
 * 트랜잭션을 따로 열지 않는다. 호출자의 트랜잭션(목록의 REPEATABLE_READ 등)에 합류해 같은 스냅샷에서 읽는다.
 */
interface StoryTurnCounts {

    /** 채팅이 없으면 0. */
    fun countByStoryId(storyId: Long): Long

    /** 채팅이 없는 스토리는 결과에 빠진다. 호출부가 0으로 보정한다. */
    fun countByStoryIds(storyIds: Collection<Long>): Map<Long, Long>
}
