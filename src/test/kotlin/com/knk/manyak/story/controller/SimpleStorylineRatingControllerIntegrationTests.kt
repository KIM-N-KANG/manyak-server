package com.knk.manyak.story.controller

import com.knk.manyak.story.dto.StorylineRating
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationStorylineRatingRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
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
class SimpleStorylineRatingControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var sessionRepository: StoryCreationSessionRepository

    @Autowired
    private lateinit var storylineRepository: StoryCreationStorylineRepository

    @Autowired
    private lateinit var ratingRepository: StoryCreationStorylineRatingRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun persistStoryline(): StoryCreationStoryline {
        val session = sessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        return storylineRepository.save(
            StoryCreationStoryline(
                creationSession = session,
                storylineText = "기억을 잃은 주인공이 금지된 숲에서 과거를 추적한다.",
                storylineOrder = 1,
            ),
        )
    }

    private fun rate(storylineId: Long, rating: String) =
        restTestClient.put()
            .uri("/api/v1/stories/simple/storylines/$storylineId/rating")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"rating":"$rating"}""")
            .exchange()

    @Test
    fun `평가를 처음 보내면 200과 함께 저장된다`() {
        val storyline = persistStoryline()

        rate(storyline.id, "GOOD")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(storyline.id)
            .jsonPath("$.rating").isEqualTo("GOOD")

        val saved = ratingRepository.findByStorylineId(storyline.id)
        assertThat(saved).isNotNull
        assertThat(saved!!.rating).isEqualTo(StorylineRating.GOOD)
    }

    @Test
    fun `같은 스토리라인에 다시 평가하면 값이 변경되고 행은 하나만 유지된다`() {
        val storyline = persistStoryline()

        rate(storyline.id, "GOOD").expectStatus().isOk
        val first = ratingRepository.findByStorylineId(storyline.id)!!
        val firstCreatedAt = first.createdAt
        val firstUpdatedAt = first.updatedAt

        // updatedAt 비교가 동일 시각 충돌로 흔들리지 않도록 최소 간격을 둔다.
        Thread.sleep(10)

        rate(storyline.id, "BAD")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.rating").isEqualTo("BAD")

        assertThat(ratingRepository.count()).isEqualTo(1)
        val second = ratingRepository.findByStorylineId(storyline.id)!!
        assertThat(second.rating).isEqualTo(StorylineRating.BAD)
        // @PreUpdate로 updatedAt은 갱신되고 createdAt은 보존된다.
        assertThat(second.updatedAt).isAfter(firstUpdatedAt)
        assertThat(second.createdAt).isEqualTo(firstCreatedAt)
    }

    @Test
    fun `같은 값으로 다시 평가해도 200이고 행은 하나만 유지된다`() {
        val storyline = persistStoryline()

        rate(storyline.id, "GOOD").expectStatus().isOk
        rate(storyline.id, "GOOD")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(storyline.id)
            .jsonPath("$.rating").isEqualTo("GOOD")

        assertThat(ratingRepository.count()).isEqualTo(1)
        assertThat(ratingRepository.findByStorylineId(storyline.id)!!.rating).isEqualTo(StorylineRating.GOOD)
    }

    @Test
    fun `평가를 취소하면 204와 함께 행이 삭제된다`() {
        val storyline = persistStoryline()
        rate(storyline.id, "GOOD").expectStatus().isOk

        restTestClient.delete()
            .uri("/api/v1/stories/simple/storylines/${storyline.id}/rating")
            .exchange()
            .expectStatus().isNoContent

        assertThat(ratingRepository.findByStorylineId(storyline.id)).isNull()
    }

    @Test
    fun `평가가 없는 스토리라인을 취소해도 204로 멱등 처리된다`() {
        val storyline = persistStoryline()

        restTestClient.delete()
            .uri("/api/v1/stories/simple/storylines/${storyline.id}/rating")
            .exchange()
            .expectStatus().isNoContent
    }

    @Test
    fun `존재하지 않는 스토리라인을 평가하면 404로 응답한다`() {
        rate(999999, "GOOD")
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("스토리라인을 찾을 수 없습니다.")
    }

    @Test
    fun `존재하지 않는 스토리라인의 평가를 취소하면 404로 응답한다`() {
        restTestClient.delete()
            .uri("/api/v1/stories/simple/storylines/999999/rating")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("스토리라인을 찾을 수 없습니다.")
    }

    @Test
    fun `잘못된 평가 값은 400으로 응답한다`() {
        val storyline = persistStoryline()

        rate(storyline.id, "AWESOME")
            .expectStatus().isBadRequest
    }

    @Test
    fun `깨진 JSON 본문은 400으로 응답한다`() {
        val storyline = persistStoryline()

        restTestClient.put()
            .uri("/api/v1/stories/simple/storylines/${storyline.id}/rating")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"rating":""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `숫자가 아닌 스토리라인 ID 경로는 보안 매처에 막혀 401을 반환한다`() {
        // SecurityConfig가 {storylineId:\d+}로만 permitAll 하므로, 비숫자 경로는 인증 대상으로 떨어진다.
        // JWT 리소스 서버(KNK-56) 도입 후, 인증 없는 요청은 Bearer 챌린지(401)로 응답한다(이전 403에서 변경).
        restTestClient.put()
            .uri("/api/v1/stories/simple/storylines/abc/rating")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"rating":"GOOD"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `평가 값이 없으면 400으로 응답한다`() {
        val storyline = persistStoryline()

        restTestClient.put()
            .uri("/api/v1/stories/simple/storylines/${storyline.id}/rating")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange()
            .expectStatus().isBadRequest
    }
}
