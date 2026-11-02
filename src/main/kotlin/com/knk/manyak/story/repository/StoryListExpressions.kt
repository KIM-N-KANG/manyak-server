package com.knk.manyak.story.repository

import com.knk.manyak.story.service.StoryListSort.Companion.POPULAR_LIKE_WEIGHT

/** 공개 목록과 홈 선정이 공유하는 JPQL 집계식. 스토리 별칭은 s, 채팅 별칭은 c다. */
object StoryListExpressions {
    // 삭제된 채팅과 입력 수정으로 숨긴 턴, 재생성 횟수를 모두 포함한다.
    const val TURN_COUNT = "(SELECT COALESCE(SUM(c.currentTurn + c.hiddenTurnCount + c.regeneratedCount), 0) FROM StoryChat c WHERE c.storyId = s.id)"
    const val LIKE_COUNT = "(SELECT COUNT(l) FROM StoryLike l WHERE l.storyId = s.id)"
    const val POPULAR_SCORE = "($LIKE_COUNT * $POPULAR_LIKE_WEIGHT + $TURN_COUNT)"
}
