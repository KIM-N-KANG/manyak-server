package com.knk.manyak.story.dto

/** 스토리별 좋아요 수 배치 집계 결과. */
interface StoryLikeCountProjection {
    val storyId: Long
    val likeCount: Long
}
