package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationSessionTag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface StoryCreationSessionTagRepository : JpaRepository<StoryCreationSessionTag, Long> {
    // 저장 순서(st.id ASC) 고정: 회수 재구성 응답의 genreTags·features 배열 순서를 최초 응답과 결정적으로 일치시킨다(KNK-848).
    // 846 컴파일 경로의 distinctBy·groupBy 결과 순서도 함께 결정화된다(무해).
    @Query(
        "SELECT st FROM StoryCreationSessionTag st JOIN FETCH st.tag WHERE st.creationSession.id = :sessionId ORDER BY st.id ASC",
    )
    fun findAllWithTagByCreationSessionId(sessionId: Long): List<StoryCreationSessionTag>
}
