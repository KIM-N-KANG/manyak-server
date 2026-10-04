package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.Schema

data class GenreCatalogResponse(
    val genres: List<GenreCatalogItem>,
    val featuredGenres: List<GenreCatalogItem>,
)

data class GenreCatalogItem(
    @field:Schema(description = "공개 카탈로그 태그 ID. genreTagIds와 같은 ID")
    val id: Long,
    val name: String,
)
