package com.knk.manyak.credit.port

/**
 * 이용내역 제목과 정책값의 턴 가격처럼 크레딧이 화면에 싣는 다른 모듈의 값을 크레딧 쪽에서 정의한다(KNK-2066).
 * chat과 story가 구현하고 credit은 그 타입을 직접 참조하지 않는다. 구현은 트랜잭션을 새로 정하지 않는다.
 */
interface ChatStoryLookup {

    /** 채팅 id → 스토리 id를 한 번에 조회한다. 없는 채팅은 빠진다. */
    fun storyIdsByChatId(chatIds: Collection<Long>): Map<Long, Long>
}

interface StoryTitleLookup {

    /**
     * 이용내역 제목을 한 번에 해석한다. 진행, 스토리, 스냅샷을 각각 한 번씩만 조회한다.
     * - [chatStoryIds]: 채팅 참조 행의 스토리. [userId]가 지금 읽을 수 있으면 현재 제목, 아니면 마지막 공개 스냅샷의 제목
     * - [sessionIds]: 간편 제작 진행. 만든 스토리가 삭제되지 않았으면 현재 제목
     * 제목이 없으면 맵에서 빠진다.
     */
    fun titles(userId: Long, chatStoryIds: Collection<Long>, sessionIds: Collection<Long>): StoryTitles
}

data class StoryTitles(val byChatStoryId: Map<Long, String>, val bySessionId: Map<Long, String>)

/** 정책값 응답과 체험 사용량이 읽는 라이트(`LITE`) 모델의 가격. 요청마다 읽는다. */
interface ChatTurnPricing {

    fun litePrice(): Long

    fun litePromotionFree(): Boolean
}
