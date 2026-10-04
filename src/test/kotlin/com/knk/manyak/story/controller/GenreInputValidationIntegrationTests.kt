package com.knk.manyak.story.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.submission.*
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenreInputValidationIntegrationTests {
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    @Autowired lateinit var client: RestTestClient
    @Autowired lateinit var tags: StoryCreationTagRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var rows: StorySubmissionRepository
    @Autowired lateinit var service: StorySubmissionService
    @Autowired lateinit var worker: SubmissionTransactions
    @Autowired lateinit var forms: SubmissionFormAssembler
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var cleaner: DatabaseCleaner
    @MockitoBean(name = "storyModerationExecutor") lateinit var executor: java.util.concurrent.Executor
    private lateinit var user: User
    private lateinit var auth: String

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        user = users.save(User(nickname = "작가"))
        auth = "Bearer ${tokens.issueAccessToken(user.publicId)}"
        listOf("현대판타지", "로맨스판타지", "BL", "판타지").forEach { tag(it) }
    }
    @AfterEach fun cleanup() = cleaner.cleanAll()

    private fun tag(name: String, active: Boolean = true, source: StoryCreationTagSource = StoryCreationTagSource.PREDEFINED,
                    category: SimpleStoryTagCategory = SimpleStoryTagCategory.GENRE) = tags.save(StoryCreationTag(
        name = name, isActive = active, tagSource = source, category = category))
    private fun request(genres: List<String>) = mapper.readValue("""{
      "title":"제목","oneLineIntro":"소개","genres":[],
      "storySettings":{"worldSetting":"세계","characterSetting":"인물","userRoleSetting":"역할","ruleSetting":"규칙"},
      "startSettings":[{"name":"시작","prologue":"도입","startSituation":"상황","suggestedInputs":["하나","둘","셋"]}]
    }""", CreateGeneralStoryRequest::class.java).copy(genres = genres)
    private fun row(id: String) = rows.findByPublicId(UUID.fromString(id))!!
    private fun approve(r: StorySubmission) = worker.finish(r.id, r.attempt, ModerationResult("APPROVED", emptyList(), null))
    private fun post(genres: List<String>) = client.post().uri("/api/v1/stories/general").header("Authorization", auth)
        .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request(genres))).exchange()

    @Test fun `정식 이름을 정규화하고 최초 선택 순서로 중복 제거하여 payload 폼과 승인 결과에 저장한다`() {
        val accepted = service.create(request(listOf(" b l ", "현대 판타지", "BL", "판타지")), user.id)
        val r = row(accepted.submissionId)
        val expected = listOf("BL", "현대판타지", "판타지")
        assertEquals(expected, mapper.readTree(r.payload)["genres"].toList().map { it.asText() })
        assertEquals(expected, mapper.readTree(r.inputForm)["genres"].toList().map { it.asText() })
        approve(r)
        assertEquals("BL, 현대판타지, 판타지", stories.findAll().single().genre)
    }

    @Test fun `별칭 비활성 CUSTOM 인물 특징과 공백은 원본 인덱스별 오류이며 일부 저장도 없다`() {
        tag("비활성", active = false); tag("개인장르", source = StoryCreationTagSource.CUSTOM)
        tag("회귀", category = SimpleStoryTagCategory.PROTAGONIST)
        post(listOf("BL", "로판", "비활성", "개인장르", "회귀", " ")).expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_GENRE")
            .jsonPath("$.message").isEqualTo("요청 값이 올바르지 않습니다.")
            .jsonPath("$.details.length()").isEqualTo(5)
            .jsonPath("$.details[0].field").isEqualTo("genres[1]")
            .jsonPath("$.details[4].field").isEqualTo("genres[5]")
        assertEquals(0, rows.count()); assertEquals(0, stories.count())
    }

    @Test fun `중복 제거 전 9개 요청과 31자 원소는 BAD_REQUEST다`() {
        post(List(9) { "BL" }).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST")
        post(listOf("가".repeat(31))).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST")
        post(emptyList()).expectStatus().isBadRequest
    }

    @Test fun `PATCH는 기존 커스텀 장르를 보존하면서 새 정식 장르를 추가할 수 있다`() {
        val story = stories.save(Story(userId = user.id, title = "기존", genre = "나만의 장르, 헌터"))
        val result = service.update(story.publicId.toString(), UpdateStoryRequest(genres = listOf(" 나만의장르 ", " b l ", "헌터", "BL")), user.id) as SubmissionAccepted
        val r = row(result.submissionId)
        assertEquals(listOf("나만의 장르", "BL", "헌터"), mapper.readTree(r.payload)["genres"].toList().map { it.asText() })
        approve(r)
        assertEquals("나만의 장르, BL, 헌터", stories.findById(story.id).orElseThrow().genre)
    }

    @Test fun `PATCH 장르 누락과 null은 기존 문자열을 그대로 보존한다`() {
        val story = stories.save(Story(userId = user.id, title = "기존", genre = "옛 표기, 커스텀"))
        for (body in listOf("""{"title":"수정"}""", """{"title":"수정2","genres":null}""")) {
            client.patch().uri("/api/v1/stories/${story.publicId}").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectStatus().isAccepted
            approve(rows.findAll().last())
            assertEquals("옛 표기, 커스텀", stories.findById(story.id).orElseThrow().genre)
        }
    }

    @Test fun `PATCH는 다른 스토리나 반려 제출본의 커스텀을 허용하지 않는다`() {
        val story = stories.save(Story(userId = user.id, title = "기존", genre = "BL"))
        stories.save(Story(userId = user.id, title = "다른", genre = "다른커스텀"))
        val rejected = rows.save(StorySubmission(userId = user.id, storyId = story.id, kind = SubmissionKind.UPDATE,
            payload = """{"genres":["반려커스텀"]}""", inputForm = "{}", status = SubmissionStatus.REJECTED))
        client.patch().uri("/api/v1/stories/${story.publicId}").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).body("""{"genres":["BL","다른커스텀","반려커스텀"]}""")
            .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_GENRE")
            .jsonPath("$.details[0].field").isEqualTo("genres[1]").jsonPath("$.details[1].field").isEqualTo("genres[2]")
        assertEquals(SubmissionStatus.REJECTED, rows.findById(rejected.id).orElseThrow().status)
    }

    @Test fun `반려 신규 제출본의 재제출도 검증과 정식 표시명 확정을 한다`() {
        val accepted = service.create(request(listOf("BL")), user.id)
        val r = row(accepted.submissionId)
        worker.finish(r.id, r.attempt, ModerationResult("REJECTED", listOf(ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
        client.put().uri("/api/v1/stories/submissions/${r.publicId}").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request(listOf("로판"))))
            .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_GENRE")
        service.resubmit(accepted.submissionId, request(listOf("현대 판타지")), user.id)
        assertEquals("현대판타지", mapper.readTree(row(accepted.submissionId).payload)["genres"][0].asText())
    }

    @Test fun `배포 전 커스텀 제출본은 조회와 CREATE UPDATE 승인이 재검증 없이 동작한다`() {
        val old = request(listOf("배포전커스텀"))
        val create = rows.save(StorySubmission(userId = user.id, kind = SubmissionKind.CREATE,
            payload = mapper.writeValueAsString(old), inputForm = mapper.writeValueAsString(forms.create(old, user.id, false))))
        service.get(create.publicId.toString(), user.id)
        approve(create)
        val story = stories.findAll().single()
        assertEquals("배포전커스텀", story.genre)
        val patch = UpdateStoryRequest(genres = listOf("예전수정커스텀"))
        val update = rows.save(StorySubmission(userId = user.id, storyId = story.id, kind = SubmissionKind.UPDATE,
            payload = mapper.writeValueAsString(patch), inputForm = mapper.writeValueAsString(forms.update(story, patch, user.id, false))))
        service.editForm(story.publicId.toString(), user.id)
        approve(update)
        assertEquals("예전수정커스텀", stories.findById(story.id).orElseThrow().genre)
    }

    @Test fun `접수 후 마스터 비활성화도 승인을 막지 않는다`() {
        val accepted = service.create(request(listOf("BL")), user.id)
        jdbc.update("UPDATE story_creation_tags SET is_active=false") // 접수한 값은 재검증하지 않는다.
        approve(row(accepted.submissionId))
        assertEquals("BL", stories.findAll().single().genre)
    }

    @Test fun `PATCH에서 제거한 커스텀 장르는 다시 새로 추가할 수 없다`() {
        val story = stories.save(Story(userId = user.id, title = "기존", genre = "기존커스텀, BL"))
        val accepted = service.update(story.publicId.toString(), UpdateStoryRequest(genres = listOf("BL")), user.id) as SubmissionAccepted
        approve(row(accepted.submissionId))
        client.patch().uri("/api/v1/stories/${story.publicId}").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).body("""{"genres":["BL","기존커스텀"]}""")
            .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_GENRE")
    }

    @Test fun `직접 입력 장르는 정식 이름 공백 빈 문자열 과길이 모두 전용 코드로 거절한다`() {
        for (value in listOf("BL", "로판", " ", "", "가".repeat(31))) {
            client.post().uri("/api/v1/stories/simple/storylines").header("X-Manyak-Device-Id", "genre-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"requestId":"${UUID.randomUUID()}","customGenreTags":["$value"],"protagonist":{}}""")
                .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("CUSTOM_GENRE_NOT_ALLOWED")
                .jsonPath("$.details[0].field").isEqualTo("customGenreTags")
        }
    }
}
