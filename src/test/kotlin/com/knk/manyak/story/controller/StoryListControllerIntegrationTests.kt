package com.knk.manyak.story.controller

import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryListControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `스토리 ID 목록으로 요청 순서대로 조회하고 없는 ID는 스킵한다`() {
        val first = storyRepository.save(
            Story(title = "잿빛 왕관", oneLineIntro = "무너진 왕국 이야기", genre = "다크 판타지, 정치극"),
        )
        val second = storyRepository.save(
            Story(title = "달빛 아래의 계약", oneLineIntro = "기억을 잃은 마법사", genre = "로맨스"),
        )

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            // 요청 순서: second → 존재하지 않는 공개 식별자 → first
            .body(
                """{"storyIds":["${second.publicId}", "00000000-0000-0000-0000-000000000000", "${first.publicId}"]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo(second.publicId.toString())
            .jsonPath("$[0].title").isEqualTo("달빛 아래의 계약")
            .jsonPath("$[0].oneLineIntro").isEqualTo("기억을 잃은 마법사")
            .jsonPath("$[0].genres.length()").isEqualTo(1)
            .jsonPath("$[0].genres[0]").isEqualTo("로맨스")
            .jsonPath("$[0].author").isEmpty
            .jsonPath("$[0].turnCount").isEqualTo(0)
            .jsonPath("$[0].likeCount").isEqualTo(0)
            .jsonPath("$[0].status").isEqualTo("PUBLISHED")
            .jsonPath("$[1].id").isEqualTo(first.publicId.toString())
            .jsonPath("$[1].title").isEqualTo("잿빛 왕관")
            .jsonPath("$[1].genres.length()").isEqualTo(2)
            .jsonPath("$[1].genres[0]").isEqualTo("다크 판타지")
            .jsonPath("$[1].genres[1]").isEqualTo("정치극")
    }

    @Test
    fun `모든 ID가 존재하지 않으면 빈 목록을 반환한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"storyIds":["00000000-0000-0000-0000-000000000000", "11111111-1111-1111-1111-111111111111"]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `storyIds가 비어 있으면 400으로 응답한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":[]}""")
            .exchange()
            .expectStatus().isBadRequest
    }
}
