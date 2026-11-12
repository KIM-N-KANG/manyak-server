package com.knk.manyak.story.dto

/**
 * 게스트 이관이 판정에 쓰는 스토리 값(ACCOUNT 4-1 이관, KNK-2080). [recordedDeviceHashes]는 소유자 없는 스토리만 채우며,
 * 그 스토리를 만든 완성 요청에 기록된 기기 해시다. 판정 규칙은 이관(migration)이 가진다.
 */
data class GuestStoryCandidate(
    val storyId: Long,
    val ownerUserId: Long?,
    val publiclyListed: Boolean,
    val recordedDeviceHashes: List<String>,
)
