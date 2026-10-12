package com.knk.manyak.story.service

import com.knk.manyak.story.repository.ArtStyleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ArtStyleCatalogServiceTests {
    @Test
    fun `형식이 잘못된 화풍 코드는 NUL을 포함해 저장소 조회 전에 400으로 거절한다`() {
        for (code in listOf("ANIME\u0000", "", "anime", " ANIME", "ANIME ", "ANIME\n", "ANIME1", "A".repeat(51))) {
            val repository = Mockito.mock(ArtStyleRepository::class.java)
            val service = ArtStyleCatalogService(repository)

            val error = assertThrows(ResponseStatusException::class.java) { service.resolve(code) }

            assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(error.reason).isEqualTo("활성 화풍 코드를 선택해야 합니다.")
            Mockito.verifyNoInteractions(repository)
        }
    }
}
