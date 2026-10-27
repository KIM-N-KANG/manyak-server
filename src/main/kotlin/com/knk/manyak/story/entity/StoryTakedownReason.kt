package com.knk.manyak.story.entity

enum class StoryTakedownReason(val displayName: String) {
    SEXUAL_CONTENT("선정성"),
    COPYRIGHT_INFRINGEMENT("저작권 침해"),
    HATE_SPEECH("혐오 표현"),
    ETC("기타"),
}
