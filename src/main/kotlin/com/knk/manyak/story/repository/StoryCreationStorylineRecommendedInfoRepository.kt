package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationStorylineRecommendedInfo
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationStorylineRecommendedInfoRepository : JpaRepository<StoryCreationStorylineRecommendedInfo, Long> {
    /** 여러 스토리라인의 추천 정보를 스토리라인·info_order 순으로 조회한다(KNK-848 회수 재구성). */
    fun findByStorylineIdInOrderByStorylineIdAscInfoOrderAsc(storylineIds: List<Long>): List<StoryCreationStorylineRecommendedInfo>
}
