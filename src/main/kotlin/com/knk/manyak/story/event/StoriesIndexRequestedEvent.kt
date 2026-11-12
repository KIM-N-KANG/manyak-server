package com.knk.manyak.story.event

/** 여러 스토리의 색인 요청. 검색이 커밋 후 500건씩 나눠 묶음마다 한 번에 읽고 bulk 요청으로 색인한다(STORY 4-1-27, KNK-2078). */
data class StoriesIndexRequestedEvent(val storyIds: List<Long>)
