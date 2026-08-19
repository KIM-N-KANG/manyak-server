package com.knk.manyak.story.controller

import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationCharacter
import com.knk.manyak.story.entity.StoryCreationCharacterRole
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationStorylineRecommendedInfoRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationCharacterRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.support.TransactionSynchronizationManager

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var tagRepository: StoryCreationTagRepository

    @Autowired
    private lateinit var sessionRepository: StoryCreationSessionRepository

    @Autowired
    private lateinit var sessionTagRepository: StoryCreationSessionTagRepository

    @Autowired
    private lateinit var characterRepository: StoryCreationCharacterRepository

    @Autowired
    private lateinit var storylineRepository: StoryCreationStorylineRepository

    @Autowired
    private lateinit var recommendedInfoRepository: StoryCreationStorylineRecommendedInfoRepository

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storySuggestedInputRepository: StorySuggestedInputRepository

    @Autowired
    private lateinit var storyAiClient: CapturingStoryAiClient

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        storyAiClient.reset()
    }

    @Test
    fun `간편 제작 태그 목록을 조회한다`() {
        seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "비밀스러운 조력자", 10)
        seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)
        tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "사용자 입력",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/simple/tags")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[0].name").isEqualTo("판타지")
            .jsonPath("$[0].category").isEqualTo("GENRE")
            .jsonPath("$[1].name").isEqualTo("기억상실")
            .jsonPath("$[1].category").isEqualTo("PROTAGONIST")
            .jsonPath("$[2].name").isEqualTo("비밀스러운 조력자")
            .jsonPath("$[2].category").isEqualTo("SUPPORTING_CHARACTER")
    }

    @Test
    fun `로컬 프론트 개발 서버의 CORS preflight를 허용한다`() {
        listOf(
            "http://localhost:3000",
            "http://192.168.0.12:3000",
        ).forEach { origin ->
            restTestClient.options()
                .uri("/api/v1/stories/simple/tags")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
        }
    }

    @Test
    fun `선택 태그로 스토리라인을 생성한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        val protagonist = seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [${genre.id}],
                  "protagonist": {"featureTagIds": [${protagonist.id}]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.simpleCreationId").isNumber
            .jsonPath("$.selectedTags.genreTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.genreTags[0].name").isEqualTo("판타지")
            .jsonPath("$.selectedTags.protagonist.features[0].name").isEqualTo("기억상실")
            .jsonPath("$.storylines.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].storyline").isEqualTo("생성 스토리 1")
            .jsonPath("$.storylines[0].recommendedInfos.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].recommendedInfos[0].text").isEqualTo("추가 정보 1-1")

        val aiRequest = storyAiClient.lastRequest
        requireNotNull(aiRequest)
        check(storyAiClient.transactionActiveDuringCall == false)
        check(aiRequest.genreTags == listOf("판타지"))
        check(aiRequest.protagonist.features == listOf("기억상실"))
        check(aiRequest.supportingCharacters.isEmpty())
        check(storylineRepository.count() == 3L)
        check(recommendedInfoRepository.count() == 9L)
    }

    @Test
    fun `인물 단위 입력으로 스토리라인을 생성하고 인물과 귀속 태그를 저장한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        val protagonistFeature = seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)
        val supportingFeature = seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "비밀스러운 조력자", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [${genre.id}],
                  "protagonist": {
                    "name": "  아린  ",
                    "gender": "FEMALE",
                    "featureTagIds": [${protagonistFeature.id}],
                    "customTags": ["용감한"]
                  },
                  "supportingCharacters": [
                    {
                      "name": "레온",
                      "gender": "MALE",
                      "featureTagIds": [${supportingFeature.id}],
                      "customTags": ["헌신적인"]
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.genreTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.genreTags[0].name").isEqualTo("판타지")
            .jsonPath("$.selectedTags.protagonist.name").isEqualTo("아린")
            .jsonPath("$.selectedTags.protagonist.gender").isEqualTo("FEMALE")
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(2)
            .jsonPath("$.selectedTags.supportingCharacters[0].name").isEqualTo("레온")
            .jsonPath("$.selectedTags.supportingCharacters[0].features.length()").isEqualTo(2)

        val characters = jdbcTemplate.queryForList(
            """
            SELECT role, name, gender, sort_order
            FROM story_creation_characters
            ORDER BY CASE role WHEN 'PROTAGONIST' THEN 0 ELSE 1 END, sort_order
            """.trimIndent(),
        )
        check(characters.size == 2)
        check(characters[0]["ROLE"] == "PROTAGONIST")
        check(characters[0]["NAME"] == "아린")
        check(characters[0]["GENDER"] == "FEMALE")
        check(characters[0]["SORT_ORDER"] == 1.toShort() || characters[0]["SORT_ORDER"] == 1)
        check(characters[1]["ROLE"] == "SUPPORTING_CHARACTER")
        check(characters[1]["NAME"] == "레온")

        val savedTags = jdbcTemplate.queryForList(
            """
            SELECT t.name, c.role
            FROM story_creation_session_tags st
            JOIN story_creation_tags t ON t.id = st.tag_id
            LEFT JOIN story_creation_characters c ON c.id = st.character_id
            ORDER BY st.id
            """.trimIndent(),
        )
        check(savedTags.map { it["NAME"] } == listOf("판타지", "기억상실", "용감한", "비밀스러운 조력자", "헌신적인"))
        check(savedTags.map { it["ROLE"] } == listOf(null, "PROTAGONIST", "PROTAGONIST", "SUPPORTING_CHARACTER", "SUPPORTING_CHARACTER"))

        // AI 인물 단위 계약(KNK-846): 인물별 이름·성별·특징을 그대로 싣는다.
        val storylineRequest = requireNotNull(storyAiClient.lastRequest)
        check(storylineRequest.genreTags == listOf("판타지"))
        check(storylineRequest.protagonist.name == "아린")
        check(storylineRequest.protagonist.gender == "FEMALE")
        check(storylineRequest.protagonist.features == listOf("기억상실", "용감한"))
        check(storylineRequest.supportingCharacters.single().name == "레온")
        check(storylineRequest.supportingCharacters.single().gender == "MALE")
        check(storylineRequest.supportingCharacters.single().features == listOf("비밀스러운 조력자", "헌신적인"))
    }

    @Test
    fun `주변 인물이 5명을 초과하면 스토리라인 생성을 거절한다`() {
        val supportingCharacters = (1..6).joinToString(",") { "{\"name\":\"인물 $it\"}" }

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","protagonist":{},"supportingCharacters":[$supportingCharacters]}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.details[0].message").isEqualTo("주변 인물은 최대 5명까지 입력할 수 있습니다.")
    }

    @Test
    fun `인물의 특징이 3개를 초과하면 스토리라인 생성을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {
                    "featureTagIds": [1, 2],
                    "customTags": ["하나", "둘"]
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.details[0].message").isEqualTo("인물당 특징은 최대 3개까지 입력할 수 있습니다.")
    }

    @Test
    fun `공백과 대소문자를 제거한 인물 이름이 중복이면 스토리라인 생성을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"name": "Alice"},
                  "supportingCharacters": [{"name": " a L i c e "}]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.details[0].message").isEqualTo("인물 이름은 중복될 수 없습니다.")
    }

    @Test
    fun `인물 이름의 유니코드 공백을 일반 공백으로 축약해 저장한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"name": "A\u2003B"}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.name").isEqualTo("A B")

        val savedName = jdbcTemplate.queryForObject(
            "SELECT name FROM story_creation_characters WHERE role = 'PROTAGONIST'",
            String::class.java,
        )
        check(savedName == "A B")
    }

    @Test
    fun `AI가 추천 추가 정보를 비워 응답해도 빈 목록으로 스토리라인을 생성한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        storyAiClient.emptyRecommendedInfos = true

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [${genre.id}],
                  "protagonist": {}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.storylines.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].recommendedInfos.length()").isEqualTo(0)

        check(recommendedInfoRepository.count() == 0L)
    }

    @Test
    fun `직접 추가 태그만으로 스토리라인을 생성한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {},
                  "supportingCharacters": [
                    {"customTags": ["비밀스러운 조력자"]}
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.supportingCharacters[0].features.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.supportingCharacters[0].features[0].name").isEqualTo("비밀스러운 조력자")
            .jsonPath("$.selectedTags.supportingCharacters[0].features[0].category").isEqualTo("SUPPORTING_CHARACTER")

        val aiRequest = storyAiClient.lastRequest
        requireNotNull(aiRequest)
        check(aiRequest.protagonist.features.isEmpty())
        check(aiRequest.supportingCharacters.single().features == listOf("비밀스러운 조력자"))
    }

    @Test
    fun `이미 저장된 직접 추가 태그는 재사용한다`() {
        tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.PROTAGONIST,
                name = "마법 학교",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"customTags": ["마법 학교"]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.protagonist.features[0].name").isEqualTo("마법 학교")

        check(tagRepository.count() == 1L)
    }

    @Test
    fun `대소문자와 공백만 다른 직접 추가 태그는 기존 행을 재사용한다`() {
        val existing = tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.PROTAGONIST,
                name = "BL",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"customTags": [" b l "]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(1)
            // 표시명은 최초 입력 원문을 유지한다.
            .jsonPath("$.selectedTags.protagonist.features[0].name").isEqualTo("BL")
            .jsonPath("$.selectedTags.protagonist.features[0].id").isEqualTo(existing.id)

        check(tagRepository.count() == 1L)
    }

    @Test
    fun `한 요청 안의 표기 변형 직접 추가 태그는 하나로 합친다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"customTags": ["BL", "Bl", "b l"]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.protagonist.features[0].name").isEqualTo("BL")

        check(tagRepository.count() == 1L)
        check(sessionTagRepository.count() == 1L)
        check(storyAiClient.lastRequest?.protagonist?.features == listOf("BL"))
    }

    @Test
    fun `직접 입력한 장르를 저장하고 응답과 AI 요청에 함께 싣는다`() {
        // KNK-859: 인물 단위 계약(KNK-845) 교체 때 함께 사라진 장르 직접 입력을 복원한다.
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [${genre.id}],
                  "customGenreTags": [" 학원물 ", "학 원 물"],
                  "protagonist": {"customTags": ["용감한"]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            // 표기 변형은 정규화 키로 하나로 합쳐, 사전 정의 장르 뒤에 붙는다.
            .jsonPath("$.selectedTags.genreTags.length()").isEqualTo(2)
            .jsonPath("$.selectedTags.genreTags[0].name").isEqualTo("판타지")
            .jsonPath("$.selectedTags.genreTags[1].name").isEqualTo("학원물")
            .jsonPath("$.selectedTags.genreTags[1].category").isEqualTo("GENRE")

        val savedTags = jdbcTemplate.queryForList(
            """
            SELECT t.name, t.tag_type, t.tag_source, st.character_id
            FROM story_creation_session_tags st
            JOIN story_creation_tags t ON t.id = st.tag_id
            ORDER BY st.id
            """.trimIndent(),
        )
        check(savedTags.map { it["NAME"] } == listOf("판타지", "학원물", "용감한"))
        // 장르는 세션 스코프(character_id NULL), 인물 특징만 인물에 귀속된다.
        check(savedTags.map { it["CHARACTER_ID"] == null } == listOf(true, true, false))
        check(savedTags[1]["TAG_TYPE"] == "GENRE")
        check(savedTags[1]["TAG_SOURCE"] == "CUSTOM")

        // AI 요청은 제공 장르를 앞에 두고 직접 입력 장르를 뒤에 붙인다.
        check(storyAiClient.lastRequest?.genreTags == listOf("판타지", "학원물"))

        // 컴파일 경로도 세션의 GENRE 행을 그대로 읽으므로 직접 입력 장르가 최종 스토리까지 이어진다.
        val storylineId = jdbcTemplate.queryForObject(
            "SELECT MIN(id) FROM story_creation_storylines",
            Long::class.java,
        )
        val creationId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM story_creation_sessions", Long::class.java)
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":$creationId,"storylineId":$storylineId,"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.genres").value<List<String>> { genres ->
                check(genres.contains("학원물")) { "최종 스토리 genres에 직접 입력 장르가 없습니다: $genres" }
                check(genres.contains("판타지")) { "최종 스토리 genres에 제공 장르가 없습니다: $genres" }
                // 직접 입력 장르는 사전 정의 장르 뒤에 온다. CUSTOM은 sortOrder 기본값이 0이라 정렬 기준에
                // tagSource가 없으면 시드 sortOrder(10 이상)를 가진 제공 장르를 앞질러, 스토리라인 응답 순서와
                // 어긋나고 썸네일 매칭도 직접 입력 장르를 첫 장르로 보게 된다.
                check(genres.first() == "판타지") { "최종 스토리 genres 첫 원소가 제공 장르가 아닙니다: $genres" }
            }

        // 컴파일 AI 요청에도 직접 입력 장르가 실려야 한다. 최종 genres 단정만으로는 컴파일 경로에서만
        // CUSTOM 행이 빠지는 회귀를 놓친다(응답 genres는 다른 코드가 만든다).
        val compileGenres = requireNotNull(storyAiClient.lastCompileRequest).genreTags
        check(compileGenres.contains("학원물")) { "컴파일 AI 요청에 직접 입력 장르가 없습니다: $compileGenres" }
        check(compileGenres.contains("판타지")) { "컴파일 AI 요청에 제공 장르가 없습니다: $compileGenres" }
        check(compileGenres.first() == "판타지") { "컴파일 AI 요청 첫 장르가 제공 장르가 아닙니다: $compileGenres" }
    }

    @Test
    fun `정규화 키가 같은 제공 장르가 있으면 직접 입력 장르로 커스텀 행을 만들지 않는다`() {
        // 직접 입력만 보낸다(genreTagIds는 비움). 제공 장르를 함께 고르면 직접 입력을 통째로 무시해도 통과해
        // 무엇도 증명하지 못한다 — 연결 여부는 직접 입력 단독일 때만 드러난다.
        val predefined = seedTag(SimpleStoryTagCategory.GENRE, "현대 판타지", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [],
                  "customGenreTags": ["현대판타지"],
                  "protagonist": {}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.genreTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.genreTags[0].id").isEqualTo(predefined.id)
            .jsonPath("$.selectedTags.genreTags[0].name").isEqualTo("현대 판타지")

        check(tagRepository.count() == 1L)
        check(sessionTagRepository.count() == 1L)
        // 스토리라인 AI 요청은 태그 해석(트랜잭션) 이전에 조립되므로 사용자가 입력한 표기 그대로 나간다.
        // 저장·응답만 제공 태그 표시명으로 수렴한다(인물 특징의 기존 동작과 같다).
        check(storyAiClient.lastRequest?.genreTags == listOf("현대판타지"))
    }

    @Test
    fun `제공 장르와 직접 입력 장르의 합이 20이면 스토리라인을 생성한다`() {
        // 옛 계약(selectedTagIds 20 + customTags 20)이 아니라 장르 총량 20을 상한으로 둔다.
        val genreIds = (1..10).map { seedTag(SimpleStoryTagCategory.GENRE, "장르 $it", it).id }
        val customGenres = (1..10).joinToString(",") { "\"직접 장르 $it\"" }

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [${genreIds.joinToString(",")}],
                  "customGenreTags": [$customGenres],
                  "protagonist": {}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.genreTags.length()").isEqualTo(20)
    }

    @Test
    fun `제공 장르와 직접 입력 장르의 합이 20을 넘으면 거절한다`() {
        val genreIds = (1..10).map { seedTag(SimpleStoryTagCategory.GENRE, "장르 $it", it).id }
        val customGenres = (1..11).joinToString(",") { "\"직접 장르 $it\"" }

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "genreTagIds": [${genreIds.joinToString(",")}],
                  "customGenreTags": [$customGenres],
                  "protagonist": {}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.details[0].message").isEqualTo("장르는 선택과 직접 입력을 합쳐 최대 20개까지 입력할 수 있습니다.")

        check(storyAiClient.lastRequest == null)
    }

    @Test
    fun `같은 정규화 키를 장르와 인물 특징으로 함께 보내면 분류별로 각각 저장한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "customGenreTags": ["회귀"],
                  "protagonist": {"customTags": ["회귀"]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.genreTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.genreTags[0].category").isEqualTo("GENRE")
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.protagonist.features[0].category").isEqualTo("PROTAGONIST")

        // 태그 동일성은 (분류, 정규화 키)라 장르 행과 특징 행은 별개다.
        check(tagRepository.count() == 2L)
        check(sessionTagRepository.count() == 2L)
    }

    @Test
    fun `빈 문자열이나 30자를 넘는 직접 입력 장르는 거절한다`() {
        listOf("", " ", "가".repeat(31)).forEach { invalid ->
            restTestClient.post()
                .uri("/api/v1/stories/simple/storylines")
                .header("X-Manyak-Device-Id", "test-device")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """{"requestId":"${java.util.UUID.randomUUID()}","customGenreTags":["$invalid"],"protagonist":{}}""",
                )
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST")
        }

        check(storyAiClient.lastRequest == null)
    }

    @Test
    fun `빈 문자열이나 30자를 넘는 인물 특징은 거절한다`() {
        // KNK-862: `List<@NotBlank @Size(max = 30) String>` 원소 제약은 `-Xemit-jvm-type-annotations` 없이는
        // 클래스 파일에 나가지 않아 발동하지 않았다. 31자는 `story_creation_tags.name`(길이 30) 저장에서
        // 터져 400이 아니라 500이 됐다.
        listOf("", " ", "가".repeat(31)).forEach { invalid ->
            restTestClient.post()
                .uri("/api/v1/stories/simple/storylines")
                .header("X-Manyak-Device-Id", "test-device")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """{"requestId":"${java.util.UUID.randomUUID()}","protagonist":{"customTags":["$invalid"]}}""",
                )
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST")
        }

        check(storyAiClient.lastRequest == null)
        check(tagRepository.count() == 0L)
    }

    @Test
    fun `0 이하의 장르 태그 ID는 요청 검증 단계에서 거절한다`() {
        // KNK-862: `List<@Min(1) Long>` 원소 제약도 같은 이유로 죽어 있었다. 다만 0은 조회에서도 못 찾아
        // 어차피 400(`사용할 수 없는 태그 ID`)이 나므로, 상태 코드만 보면 제약이 죽어 있어도 통과한다.
        // 제약이 실제로 발동했음은 details의 field 경로(`genreTagIds[0]`)로만 드러난다.
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","genreTagIds":[0],"protagonist":{}}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.details[0].field").isEqualTo("genreTagIds[0]")

        check(storyAiClient.lastRequest == null)
    }

    @Test
    fun `정규화 키가 같은 제공 태그가 있으면 커스텀 태그를 만들지 않는다`() {
        val predefined = seedTag(SimpleStoryTagCategory.PROTAGONIST, "현대 판타지", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"customTags": ["현대판타지"]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.protagonist.features[0].id").isEqualTo(predefined.id)
            .jsonPath("$.selectedTags.protagonist.features[0].name").isEqualTo("현대 판타지")

        check(tagRepository.count() == 1L)
    }

    @Test
    fun `같은 태그를 제공 태그와 직접 추가로 함께 보내도 한 번만 연결한다`() {
        val predefined = seedTag(SimpleStoryTagCategory.PROTAGONIST, "현대 판타지", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {
                    "featureTagIds": [${predefined.id}],
                    "customTags": ["현대판타지"]
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.features.length()").isEqualTo(1)
            .jsonPath("$.selectedTags.protagonist.features[0].id").isEqualTo(predefined.id)

        check(tagRepository.count() == 1L)
        check(sessionTagRepository.count() == 1L)
        check(storyAiClient.lastRequest?.protagonist?.features == listOf("현대 판타지"))
    }

    @Test
    fun `정규화 키는 SQL lower처럼 문자마다 1대1로 소문자화한다`() {
        // 'İ'(U+0130)는 전체 케이스 매핑(String.lowercase)이면 'i' + 결합 점 2코드포인트로 늘어난다.
        // 마이그레이션 백필의 SQL lower()는 1코드포인트 'i'를 내므로, 런타임도 문자 단위 매핑으로 맞춘다.
        val name = "İ".repeat(30)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"customTags": ["$name"]}
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.protagonist.features[0].name").isEqualTo(name)

        check(tagRepository.findAll().single().normalizedName == "i".repeat(30))
    }

    @Test
    fun `같은 출처와 분류와 이름의 태그는 중복 저장할 수 없다`() {
        tagRepository.saveAndFlush(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "마법 학교",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        assertThrows<DataIntegrityViolationException> {
            tagRepository.saveAndFlush(
                StoryCreationTag(
                    category = SimpleStoryTagCategory.GENRE,
                    name = "마법 학교",
                    tagSource = StoryCreationTagSource.CUSTOM,
                ),
            )
        }
    }

    @Test
    fun `간편 제작 진행 정보가 수정되면 updatedAt이 갱신된다`() {
        val session = sessionRepository.saveAndFlush(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val beforeUpdatedAt = session.updatedAt

        Thread.sleep(5)
        session.status = StoryCreationSessionStatus.STORY_CREATED
        val updatedSession = sessionRepository.saveAndFlush(session)

        check(updatedSession.updatedAt.isAfter(beforeUpdatedAt))
    }

    @Test
    fun `주인공이 없으면 스토리라인 생성 요청을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","genreTagIds":[]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.message").isEqualTo("요청 값이 올바르지 않습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
            .jsonPath("$.details.length()").isNumber
    }

    @Test
    fun `존재하지 않는 선택 태그가 있으면 스토리라인 생성 요청을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","genreTagIds":[999999],"protagonist":{}}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.message").isEqualTo("사용할 수 없는 태그 ID가 포함되어 있습니다: 999999")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
    }

    @Test
    fun `AI 서버 오류는 Bad Gateway로 응답한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        storyAiClient.fail = true

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","genreTagIds":[${genre.id}],"protagonist":{}}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo(502)
            .jsonPath("$.code").isEqualTo("BAD_GATEWAY")
            .jsonPath("$.message").isEqualTo("AI 스토리라인 생성 요청에 실패했습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
    }

    @Test
    fun `스토리라인 생성 API는 Bad Gateway 응답을 문서화한다`() {
        restTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paths['/api/v1/stories/simple/storylines'].post.responses['502'].description")
            .isEqualTo("AI 서버 요청 실패")
    }

    @Test
    fun `선택한 스토리라인으로 최종 스토리를 생성하고 저장한다`() {
        val seeded = seedGeneratedSession()

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "simpleCreationId": ${seeded.sessionId},
                  "storylineId": ${seeded.storylineIds[1]},
                  "additionalInfos": ["주인공은 신중하다", "결말은 여운 있게"]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            // 생성된 스토리 id는 순차 PK가 아니라 공개 식별자(public_id UUID 문자열)다.
            .jsonPath("$.id").isNotEmpty
            .jsonPath("$.storyId").doesNotExist()
            .jsonPath("$.title").isEqualTo("잿빛 왕관")
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.genres[0]").isEqualTo("다크 판타지")
            .jsonPath("$.genres[1]").isEqualTo("정치극")
            .jsonPath("$.startSettings.length()").isEqualTo(1)
            .jsonPath("$.startSettings[0].name").isEqualTo("선왕의 장례식 날")
            .jsonPath("$.settings").doesNotExist()
            // 추천 입력은 최상위가 아니라 시작 설정에 중첩된다(KNK-515 복수화).
            .jsonPath("$.suggestedInputs").doesNotExist()

        val compileRequest = storyAiClient.lastCompileRequest
        requireNotNull(compileRequest)
        check(storyAiClient.compileTransactionActive == false)
        check(compileRequest.genreTags == listOf("다크 판타지", "정치극"))
        check(compileRequest.protagonist.features == listOf("신중한"))
        check(compileRequest.supportingCharacters.isEmpty())
        check(compileRequest.selectedStoryline == "스토리라인 2")
        check(compileRequest.additionalInfo == "주인공은 신중하다\n결말은 여운 있게")

        check(storyRepository.count() == 1L)
        check(storySettingRepository.count() == 1L)
        check(storyStartSettingRepository.count() == 1L)
        check(storySuggestedInputRepository.count() == 3L)

        val session = sessionRepository.findById(seeded.sessionId).orElseThrow()
        check(session.status == StoryCreationSessionStatus.STORY_CREATED)
        check(session.storyId != null)
        val selected = storylineRepository.findById(seeded.storylineIds[1]).orElseThrow()
        check(selected.isSelected)
    }

    @Test
    fun `같은 특징을 고른 여러 인물의 컴파일 AI 태그를 역할별 정규화 키로 중복 제거한다`() {
        val protagonistFeature = seedTag(SimpleStoryTagCategory.PROTAGONIST, "헌신적인", 10)
        val supportingFeature = seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "헌신적인", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "requestId": "${java.util.UUID.randomUUID()}",
                  "protagonist": {"gender": "FEMALE", "featureTagIds": [${protagonistFeature.id}]},
                  "supportingCharacters": [
                    {"name": "조력자 1", "featureTagIds": [${supportingFeature.id}]},
                    {"name": "조력자 2", "featureTagIds": [${supportingFeature.id}]}
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated

        // 인물별 귀속 저장은 유지된다. 같은 주변 인물 태그가 각 character_id에 한 행씩 연결된다.
        val session = sessionRepository.findAll().single()
        val savedTags = sessionTagRepository.findAllWithTagByCreationSessionId(session.id)
        check(savedTags.count { it.tag.category == SimpleStoryTagCategory.PROTAGONIST } == 1)
        check(savedTags.count { it.tag.category == SimpleStoryTagCategory.SUPPORTING_CHARACTER } == 2)

        // 인물 단위 계약에선 같은 표시명이어도 인물별로 각자 자기 특징을 싣는다(역할 간 합산·중복 제거 없음).
        val storylineReq = requireNotNull(storyAiClient.lastRequest)
        check(storylineReq.protagonist.features == listOf("헌신적인"))
        check(storylineReq.supportingCharacters.map { it.name } == listOf("조력자 1", "조력자 2"))
        check(storylineReq.supportingCharacters.map { it.features } == listOf(listOf("헌신적인"), listOf("헌신적인")))

        val storyline = storylineRepository.findAll().first()
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${session.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated

        val compileRequest = storyAiClient.lastCompileRequest
        requireNotNull(compileRequest)
        // 같은 표시명이어도 인물별로 각자 자기 특징을 싣는다.
        check(compileRequest.protagonist.features == listOf("헌신적인"))
        check(compileRequest.supportingCharacters.map { it.features } == listOf(listOf("헌신적인"), listOf("헌신적인")))
        // 컴파일 AI 요청은 저장된 인물의 이름·성별을 그대로 보존한다(미입력은 null, 입력값은 원값). 주변 인물 이름은 저장 순서대로 실린다.
        check(compileRequest.protagonist.name == null)
        check(compileRequest.protagonist.gender == "FEMALE")
        check(compileRequest.supportingCharacters.map { it.name } == listOf("조력자 1", "조력자 2"))
        check(compileRequest.supportingCharacters.all { it.gender == null })
    }

    @Test
    fun `인물 행이 없는 구 세션은 컴파일 AI 요청에 세션 태그 특징을 카테고리로 복원한다`() {
        // KNK-845 배포 전 생성된 세션은 story_creation_characters 행 없이(character_id 전부 null) 세션 스코프 태그만 있다.
        val session = sessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val tags = listOf(
            seedTag(SimpleStoryTagCategory.GENRE, "다크 판타지", 10),
            seedTag(SimpleStoryTagCategory.PROTAGONIST, "신중한", 10),
            seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "충직한", 10),
        )
        sessionTagRepository.saveAll(
            tags.map { tag -> StoryCreationSessionTag(creationSession = session, tag = tag) },
        )
        val storyline = storylineRepository.save(
            StoryCreationStoryline(creationSession = session, storylineText = "스토리라인 1", storylineOrder = 1),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${session.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated

        // 인물 행이 없어도 사용자 선택 특징이 유실되지 않고 카테고리로 복원돼 실린다(유료 컴파일 특징 유실 방지).
        val compileRequest = requireNotNull(storyAiClient.lastCompileRequest)
        check(compileRequest.genreTags == listOf("다크 판타지"))
        check(compileRequest.protagonist.name == null)
        check(compileRequest.protagonist.gender == null)
        check(compileRequest.protagonist.features == listOf("신중한"))
        check(compileRequest.supportingCharacters.map { it.features } == listOf(listOf("충직한")))
    }

    @Test
    fun `인물 행이 없고 주변 인물 태그도 없는 구 세션은 주변 인물을 빈 배열로 보낸다`() {
        val session = sessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val tags = listOf(
            seedTag(SimpleStoryTagCategory.GENRE, "다크 판타지", 10),
            seedTag(SimpleStoryTagCategory.PROTAGONIST, "신중한", 10),
        )
        sessionTagRepository.saveAll(
            tags.map { tag -> StoryCreationSessionTag(creationSession = session, tag = tag) },
        )
        val storyline = storylineRepository.save(
            StoryCreationStoryline(creationSession = session, storylineText = "스토리라인 1", storylineOrder = 1),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${session.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated

        val compileRequest = requireNotNull(storyAiClient.lastCompileRequest)
        check(compileRequest.protagonist.features == listOf("신중한"))
        check(compileRequest.supportingCharacters.isEmpty())
    }

    @Test
    fun `주변 인물 행만 있고 주인공 행이 없으면 세션 스코프 주인공 특징을 카테고리로 복원한다`() {
        // 스키마상 주인공 없이 주변 인물만 있는 세션이 가능하다. 이 경우에도 character_id NULL인 PROTAGONIST 카테고리
        // 세션 태그(이동 태그 포함)가 유실되지 않고 category 폴백으로 주인공 특징에 복원돼야 한다.
        val session = sessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "다크 판타지", 10)
        val protagonistFeature = seedTag(SimpleStoryTagCategory.PROTAGONIST, "회귀", 10)
        val supportingFeature = seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "충직한", 10)
        val supporting = characterRepository.save(
            StoryCreationCharacter(
                creationSession = session,
                role = StoryCreationCharacterRole.SUPPORTING_CHARACTER,
                name = "레온",
                sortOrder = 1,
            ),
        )
        sessionTagRepository.saveAll(
            listOf(
                // 장르·주인공 특징은 세션 스코프(character_id NULL). 주인공 인물 행은 없다.
                StoryCreationSessionTag(creationSession = session, tag = genre),
                StoryCreationSessionTag(creationSession = session, tag = protagonistFeature),
                // 주변 인물 특징은 주변 인물 행에 귀속.
                StoryCreationSessionTag(creationSession = session, tag = supportingFeature, character = supporting),
            ),
        )
        val storyline = storylineRepository.save(
            StoryCreationStoryline(creationSession = session, storylineText = "스토리라인 1", storylineOrder = 1),
        )

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${session.id},"storylineId":${storyline.id},"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated

        val compileRequest = requireNotNull(storyAiClient.lastCompileRequest)
        // 주인공 행이 없어도 NULL 주인공 특징이 유실되지 않고 category 폴백으로 복원된다.
        check(compileRequest.protagonist.name == null)
        check(compileRequest.protagonist.gender == null)
        check(compileRequest.protagonist.features == listOf("회귀"))
        // 주변 인물 조립은 그대로.
        check(compileRequest.supportingCharacters.single().name == "레온")
        check(compileRequest.supportingCharacters.single().features == listOf("충직한"))
    }

    @Test
    fun `추가 정보가 13개면 스토리를 생성한다`() {
        val seeded = seedGeneratedSession()
        val additionalInfos = (1..13).joinToString(",") { "\"추가 정보 $it\"" }

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${seeded.sessionId},"storylineId":${seeded.storylineIds[0]},"additionalInfos":[$additionalInfos]}""",
            )
            .exchange()
            .expectStatus().isCreated

        check(storyRepository.count() == 1L)
    }

    @Test
    fun `추가 정보가 14개면 스토리 생성을 거절한다`() {
        val seeded = seedGeneratedSession()
        val additionalInfos = (1..14).joinToString(",") { "\"추가 정보 $it\"" }

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${seeded.sessionId},"storylineId":${seeded.storylineIds[0]},"additionalInfos":[$additionalInfos]}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple")

        check(storyRepository.count() == 0L)
    }

    @Test
    fun `존재하지 않는 진행 정보면 스토리 생성을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":999999,"storylineId":1}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("간편 제작 진행 정보를 찾을 수 없습니다.")
    }

    @Test
    fun `세션에 속하지 않은 스토리라인이면 스토리 생성을 거절한다`() {
        val seeded = seedGeneratedSession()

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${seeded.sessionId},"storylineId":999999}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.message").isEqualTo("선택한 스토리라인을 찾을 수 없습니다.")
    }

    @Test
    fun `이미 스토리가 생성된 진행이면 충돌로 응답한다`() {
        val seeded = seedGeneratedSession()
        val session = sessionRepository.findById(seeded.sessionId).orElseThrow()
        session.status = StoryCreationSessionStatus.STORY_CREATED
        sessionRepository.saveAndFlush(session)

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${seeded.sessionId},"storylineId":${seeded.storylineIds[0]}}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.status").isEqualTo(409)
            .jsonPath("$.message").isEqualTo("이미 스토리가 생성된 간편 제작 진행입니다.")
    }

    @Test
    fun `AI 응답 제목과 한 줄 소개가 컬럼 길이를 초과하면 잘라서 저장한다`() {
        val seeded = seedGeneratedSession()
        storyAiClient.compileTitle = "가".repeat(150)
        storyAiClient.compileOneLineIntro = "나".repeat(300)

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${seeded.sessionId},"storylineId":${seeded.storylineIds[0]}}""")
            .exchange()
            .expectStatus().isCreated

        val story = storyRepository.findAll().single()
        check(story.title.length == 100)
        check(story.oneLineIntro?.length == 255)
    }

    @Test
    fun `최종 스토리 생성 AI 오류는 Bad Gateway로 응답한다`() {
        val seeded = seedGeneratedSession()
        storyAiClient.compileFail = true

        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"${java.util.UUID.randomUUID()}","simpleCreationId":${seeded.sessionId},"storylineId":${seeded.storylineIds[0]}}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo(502)
            .jsonPath("$.code").isEqualTo("BAD_GATEWAY")
            .jsonPath("$.message").isEqualTo("AI 스토리 생성 요청에 실패했습니다.")

        check(storyRepository.count() == 0L)
        val session = sessionRepository.findById(seeded.sessionId).orElseThrow()
        check(session.status == StoryCreationSessionStatus.STORYLINES_GENERATED)
    }

    @Test
    fun `스토리 생성 API는 Bad Gateway 응답을 문서화한다`() {
        restTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paths['/api/v1/stories/simple'].post.responses['502'].description")
            .isEqualTo("AI 서버 요청 실패")
    }

    private fun seedGeneratedSession(): SeededSession {
        val session = sessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        // KNK-846 컴파일 경로는 인물(character_id) 단위로 특징을 되싣는다. 장르는 세션 스코프, 주인공 특징은 인물 스코프로 시드한다.
        val genreTags = listOf(
            seedTag(SimpleStoryTagCategory.GENRE, "다크 판타지", 10),
            seedTag(SimpleStoryTagCategory.GENRE, "정치극", 11),
        )
        val protagonistFeature = seedTag(SimpleStoryTagCategory.PROTAGONIST, "신중한", 10)
        val protagonist = characterRepository.save(
            StoryCreationCharacter(
                creationSession = session,
                role = StoryCreationCharacterRole.PROTAGONIST,
                sortOrder = 1,
            ),
        )
        sessionTagRepository.saveAll(
            genreTags.map { tag -> StoryCreationSessionTag(creationSession = session, tag = tag) } +
                StoryCreationSessionTag(creationSession = session, tag = protagonistFeature, character = protagonist),
        )
        val storylines = storylineRepository.saveAll(
            (1..3).map { order ->
                StoryCreationStoryline(
                    creationSession = session,
                    storylineText = "스토리라인 $order",
                    storylineOrder = order.toShort(),
                )
            },
        )
        return SeededSession(session.id, storylines.map { it.id })
    }

    private data class SeededSession(
        val sessionId: Long,
        val storylineIds: List<Long>,
    )

    private fun seedTag(
        category: SimpleStoryTagCategory,
        name: String,
        sortOrder: Int,
    ): StoryCreationTag =
        tagRepository.save(
            StoryCreationTag(
                category = category,
                name = name,
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = sortOrder,
            ),
        )

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun storyAiClient(): CapturingStoryAiClient = CapturingStoryAiClient()
    }

    class CapturingStoryAiClient : StoryAiClient {
        var lastRequest: AiStorylinesRequest? = null
            private set
        var lastCompileRequest: AiStoryCompileRequest? = null
            private set
        var fail: Boolean = false
        var emptyRecommendedInfos: Boolean = false
        var compileFail: Boolean = false
        var compileTitle: String = "잿빛 왕관"
        var compileOneLineIntro: String = "무너진 왕국에서 진실을 좇는다."
        var transactionActiveDuringCall: Boolean? = null
            private set
        var compileTransactionActive: Boolean? = null
            private set

        override fun createStorylines(request: AiStorylinesRequest, traceLink: AiTraceLink): AiStorylinesResponse {
            lastRequest = request
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive()
            if (fail) {
                throw IllegalStateException("AI failure")
            }

            return AiStorylinesResponse(
                stories = (1..3).map { index ->
                    AiStoryItem(
                        id = index,
                        storyline = "생성 스토리 $index",
                        recommendedInfos = if (emptyRecommendedInfos) {
                            emptyList()
                        } else {
                            (1..3).map { infoIndex -> "추가 정보 $index-$infoIndex" }
                        },
                    )
                },
            )
        }

        override fun compileStory(request: AiStoryCompileRequest, traceLink: AiTraceLink): AiStoryCompileResponse {
            lastCompileRequest = request
            compileTransactionActive = TransactionSynchronizationManager.isActualTransactionActive()
            if (compileFail) {
                throw IllegalStateException("AI failure")
            }

            return AiStoryCompileResponse(
                stories = AiStoryMeta(
                    title = compileTitle,
                    oneLineIntro = compileOneLineIntro,
                    description = "역병과 반란으로 무너진 왕국 이야기.",
                ),
                storySettings = AiStorySettings(
                    worldSetting = "# 세계관\n아르덴 왕국...",
                    characterSetting = "# 등장인물\n레이...",
                    userRoleSetting = "# 주인공\n견습 기사...",
                    ruleSetting = "# 전개 규칙\n정치 음모...",
                ),
                storyStartSettings = AiStoryStartSettings(
                    name = "선왕의 장례식 날",
                    startSituation = "장례식이 끝난 늦은 밤...",
                    prologue = "잿빛 비가 사흘째...",
                ),
                storySuggestedInputs = listOf(
                    "레이에게 문을 열어준다",
                    "경계하며 누구냐고 묻는다",
                    "침묵한다",
                ),
            )
        }

        fun reset() {
            lastRequest = null
            lastCompileRequest = null
            fail = false
            emptyRecommendedInfos = false
            compileFail = false
            compileTitle = "잿빛 왕관"
            compileOneLineIntro = "무너진 왕국에서 진실을 좇는다."
            transactionActiveDuringCall = null
            compileTransactionActive = null
        }
    }
}
