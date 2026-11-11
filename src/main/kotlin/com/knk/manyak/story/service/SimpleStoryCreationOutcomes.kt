package com.knk.manyak.story.service

// 간편 제작 완성 타이머(manyak.story.creation.duration)와 스토리라인 카운터(manyak.storyline.creation.result)가
// 공유하는 outcome 태그 값(유한 enum 3종, 의미는 SimpleStoryCompletionService.recordCreationDuration과
// SimpleStorylineService.recordStorylineResult의 KDoc). 두 서비스가 같은 값을 쓰도록 한 곳에 둔다(KNK-2056).
internal const val OUTCOME_SUCCESS = "success"
internal const val OUTCOME_FAILURE = "failure"
internal const val OUTCOME_REJECTED = "rejected"
