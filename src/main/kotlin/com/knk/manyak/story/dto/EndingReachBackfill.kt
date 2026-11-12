package com.knk.manyak.story.dto

/** 게스트 이관이 클레임한 채팅 하나의 엔딩 도달(KNK-2080). 이름 스냅샷이 정본이고 없으면 [endingId]의 라이브 엔딩 이름을 쓴다. */
data class EndingReachBackfill(val storyId: Long, val endingId: Long?, val endingNameSnapshot: String?)
