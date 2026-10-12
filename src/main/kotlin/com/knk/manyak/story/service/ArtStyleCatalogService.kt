package com.knk.manyak.story.service

import com.knk.manyak.story.dto.ArtStyleCatalogItem
import com.knk.manyak.story.repository.ArtStyleRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
@Transactional(readOnly = true)
class ArtStyleCatalogService(private val artStyles: ArtStyleRepository) {
    fun list(): List<ArtStyleCatalogItem> = artStyles.findByIsActiveTrueOrderBySortOrderAsc().map {
        ArtStyleCatalogItem(it.code, it.name, it.description, it.previewImageUrl)
    }

    fun resolve(code: String?): String {
        // 누락·null 기본값은 카탈로그 조회 없이 허용한다(기존 클라이언트 호환).
        if (code == null) return "ANIME"
        if (!CODE_PATTERN.matches(code) || !artStyles.existsByCodeAndIsActiveTrue(code)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "활성 화풍 코드를 선택해야 합니다.")
        }
        return code
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Z_]{1,50}$")
    }
}
