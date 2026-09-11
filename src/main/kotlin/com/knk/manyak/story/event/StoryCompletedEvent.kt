package com.knk.manyak.story.event

/**
 * 스토리 완성(간편 제작 STORY_COMPLETION)이 COMPLETED로 마킹된 사실(KNK-1115).
 *
 * 요청 행을 COMPLETED로 표시하는 **그 트랜잭션 안에서** 발행하고, 수신은 커밋 뒤(AFTER_COMMIT)에 한다.
 * 멱등 replay는 COMPLETED 마킹에 도달하지 않으므로 재요청으로 이 이벤트가 다시 나가지 않는다.
 *
 * [userId]는 요청 소유 회원이다. 게스트 제작은 보낼 대상이 없어 발행하지 않는다.
 */
data class StoryCompletedEvent(
    val userId: Long,
    /** 스토리 공개 식별자(UUID 문자열). 내부 PK는 싣지 않는다. */
    val storyPublicId: String,
    val title: String,
)
