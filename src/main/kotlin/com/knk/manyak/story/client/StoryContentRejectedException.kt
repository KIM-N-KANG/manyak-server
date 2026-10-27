package com.knk.manyak.story.client

/** AI가 422와 문자열 detail로 응답한 내용 거절. 호출 성공으로 기록하되 생성은 중단한다. */
class StoryContentRejectedException(message: String, cause: Throwable) : RuntimeException(message, cause)
