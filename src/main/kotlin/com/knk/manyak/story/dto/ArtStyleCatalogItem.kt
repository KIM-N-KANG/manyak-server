package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.Schema

data class ArtStyleCatalogItem(
    @field:Schema(description = "공개 화풍 코드. AI 컴파일 art_style 계약값")
    val code: String,
    val name: String,
    val description: String,
    @field:Schema(description = "미리보기 이미지 URL. 초기 카탈로그는 null", nullable = true)
    val previewImageUrl: String?,
)
