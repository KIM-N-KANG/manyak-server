package com.knk.manyak.credit.dto

/** limit=null은 회원 스토리라인의 무제한 계약이다. */
data class TrialUsage(val used: Long, val limit: Long?)
data class TrialsResponse(
    val chatTurn: TrialUsage,
    val chatImage: TrialUsage,
    val storyCreation: TrialUsage,
    val storylineGeneration: TrialUsage,
)
