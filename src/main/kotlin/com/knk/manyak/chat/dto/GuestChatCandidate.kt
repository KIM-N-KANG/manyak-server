package com.knk.manyak.chat.dto

/** 게스트 이관이 판정과 엔딩 도달 백필에 쓰는 채팅 값(KNK-2080). 도달 엔딩은 이름 스냅샷이 정본이다(V70). */
data class GuestChatCandidate(
    val ownerUserId: Long?,
    val storyId: Long,
    val reachedEndingId: Long?,
    val reachedEndingNameSnapshot: String?,
)
