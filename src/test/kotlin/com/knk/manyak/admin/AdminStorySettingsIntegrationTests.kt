package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.*
import com.knk.manyak.story.submission.StorySubmission
import com.knk.manyak.story.submission.StorySubmissionRepository
import com.knk.manyak.story.submission.SubmissionKind
import com.knk.manyak.story.submission.SubmissionStatus
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** ADMIN 4-2-7 스토리 설정, 5-5-6 스토리 설정 행. */
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.official-user-public-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"])
class AdminStorySettingsIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var settings: StorySettingRepository
    @Autowired private lateinit var startSettings: StoryStartSettingRepository
    @Autowired private lateinit var inputs: StorySuggestedInputRepository
    @Autowired private lateinit var endings: StoryEndingRepository
    @Autowired private lateinit var mainEvents: StoryMainEventRepository
    @Autowired private lateinit var characters: StoryCharacterRepository
    @Autowired private lateinit var images: StoryCharacterImageRepository
    @Autowired private lateinit var submissions: StorySubmissionRepository
    private lateinit var token: String
    private lateinit var owner: User
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = tokens.issueAdminToken(admin.publicId)
        owner = users.save(User(nickname = "제작자"))
    }

    private fun settingsOf(id: Any, auth: String? = token) = client.get().uri("/api/v1/admin/stories/$id/settings")
        .also { spec -> auth?.let { spec.header("Authorization", "Bearer $it") } }.exchange()
    private fun json(response: RestTestClient.ResponseSpec): JsonNode =
        mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun ok(story: Story) = json(settingsOf(story.publicId).expectStatus().isOk)

    private fun bare(userId: Long? = owner.id, title: String = "빈 스토리") = stories.save(Story(userId = userId, title = title, genre = "판타지"))

    /** 다른 회원 소유 DRAFT·PRIVATE 스토리. 저장 순서와 표시 순서를 일부러 엇갈리게 넣는다. */
    private fun full(): Story {
        val story = stories.save(Story(userId = owner.id, title = "{username}의 계약서", oneLineIntro = "{username}이가 과거를 추적한다",
            description = "{username}의 기억", protagonistName = "서린", genre = "판타지, 미스터리", status = StoryStatus.DRAFT,
            visibility = StoryVisibility.PRIVATE, thumbnailImageUrl = "https://cdn.example.test/thumbnails/pending.webp",
            thumbnailModerationStatus = ImageModerationStatus.PENDING))
        settings.save(StorySetting(story = story, worldSetting = "{username}의 왕국", characterSetting = "도윤",
            userRoleSetting = "{username}은 마법사", ruleSetting = "단서 공개"))
        val first = startSettings.save(StoryStartSetting(story = story, name = "첫 장면", prologue = "{username}의 프롤로그", startSituation = "밤"))
        val second = startSettings.save(StoryStartSetting(story = story, name = "둘째 장면"))
        listOf("셋째 입력" to 3, "첫째 입력" to 1, "둘째 입력" to 2).forEach { (text, order) ->
            inputs.save(StorySuggestedInput(startSetting = first, inputText = text, inputOrder = order.toShort()))
        }
        endings.save(StoryEnding(startSetting = first, name = "왕좌", minTurns = 10, achievementCondition = "왕좌를 되찾는다",
            epilogue = "{username}의 대관식", sortOrder = 1))
        mainEvents.save(StoryMainEvent(story = story, name = "둘째 사건", description = "설명2", keySentence = "문장2", sortOrder = 1))
        mainEvents.save(StoryMainEvent(story = story, name = "첫째 사건", description = "설명1", keySentence = "{username} 문장1", sortOrder = 0))
        val doyun = characters.save(StoryCharacter(story = story, name = "도윤", description = "{username}의 사서"))
        characters.save(StoryCharacter(story = story, name = "서아"))
        listOf(Triple("도윤_거절", 2, ImageModerationStatus.REJECTED), Triple("도윤_기본", 0, ImageModerationStatus.APPROVED),
            Triple("도윤_대기", 1, ImageModerationStatus.PENDING)).forEach { (name, order, status) ->
            images.save(StoryCharacterImage(character = doyun, imageName = name, imageUrl = "https://cdn.example.test/characters/$name.webp",
                sortOrder = order, moderationStatus = status))
        }
        assertThat(second.id).isGreaterThan(first.id)
        return story
    }

    @Test fun `다른 회원의 비공개 초안 설정을 치환하지 않은 원문과 저장 순서로 돌려준다`() {
        val body = ok(full())
        assertThat(body["title"].asText()).isEqualTo("{username}의 계약서")
        assertThat(body["oneLineIntro"].asText()).isEqualTo("{username}이가 과거를 추적한다")
        assertThat(body["description"].asText()).isEqualTo("{username}의 기억")
        assertThat(body["protagonistName"].asText()).isEqualTo("서린")
        assertThat(body["genres"].toList().map { it.asText() }).containsExactly("판타지", "미스터리")
        assertThat(body["storySettings"]["worldSetting"].asText()).isEqualTo("{username}의 왕국")
        assertThat(body["storySettings"]["userRoleSetting"].asText()).isEqualTo("{username}은 마법사")
        assertThat(body["visibility"].asText()).isEqualTo("PRIVATE")
        val starts = body["startSettings"]
        assertThat(starts.toList().map { it["name"].asText() }).containsExactly("첫 장면", "둘째 장면")
        assertThat(starts[0]["id"].isString).isTrue()
        assertThat(starts[0]["prologue"].asText()).isEqualTo("{username}의 프롤로그")
        assertThat(starts[0]["suggestedInputs"].toList().map { it.asText() }).containsExactly("첫째 입력", "둘째 입력", "셋째 입력")
        assertThat(starts[0]["endings"].size()).isEqualTo(1)
        assertThat(starts[0]["endings"][0]["epilogue"].asText()).isEqualTo("{username}의 대관식")
        assertThat(starts[0]["endings"][0]["requirement"]["minTurns"].asInt()).isEqualTo(10)
        assertThat(starts[1]["prologue"].isNull).isTrue()
        assertThat(starts[1]["suggestedInputs"].size()).isZero()
        assertThat(starts[1]["endings"].size()).isZero()
        assertThat(body["mainEvents"].toList().map { it["name"].asText() }).containsExactly("첫째 사건", "둘째 사건")
        assertThat(body["mainEvents"][0]["keySentence"].asText()).isEqualTo("{username} 문장1")
        assertThat(body["mainEvents"][0]["sortOrder"].asInt()).isZero()
        val chars = body["characters"]
        assertThat(chars.toList().map { it["name"].asText() }).containsExactly("도윤", "서아")
        assertThat(chars[0]["description"].asText()).isEqualTo("{username}의 사서")
        assertThat(chars[1]["description"].isNull).isTrue()
        assertThat(chars.toList().map { it["id"] }).allSatisfy { assertThat(it.isString).isTrue() }
        assertThat(chars[0]["images"].toList().map { it["id"] }).allSatisfy { assertThat(it.isString).isTrue() }
    }

    @Test fun `표지와 인물 이미지는 검수 상태와 무관하게 전부 표시 순서로 싣는다`() {
        val body = ok(full())
        assertThat(body["thumbnailUrl"].asText()).isEqualTo("https://cdn.example.test/thumbnails/pending.webp")
        assertThat(body["thumbnailModerationStatus"].asText()).isEqualTo("PENDING")
        val imgs = body["characters"][0]["images"]
        assertThat(imgs.toList().map { it["imageName"].asText() }).containsExactly("도윤_기본", "도윤_대기", "도윤_거절")
        assertThat(imgs.toList().map { it["moderationStatus"].asText() }).containsExactly("APPROVED", "PENDING", "REJECTED")
        assertThat(imgs[1]["imageUrl"].asText()).isEqualTo("https://cdn.example.test/characters/도윤_대기.webp")
        assertThat(body["characters"][1]["images"].size()).isZero()
    }

    @Test fun `설정 행과 자식이 없으면 통글은 모두 null이고 목록은 빈 배열이다`() {
        val body = ok(bare())
        assertThat(listOf("worldSetting", "characterSetting", "userRoleSetting", "ruleSetting")).allSatisfy {
            assertThat(body["storySettings"].has(it)).isTrue(); assertThat(body["storySettings"][it].isNull).isTrue()
        }
        for (field in listOf("startSettings", "mainEvents", "characters")) assertThat(body[field].isArray && body[field].isEmpty).isTrue()
        assertThat(body["thumbnailUrl"].isNull).isTrue()
        assertThat(body["oneLineIntro"].isNull).isTrue()
        assertThat(body["protagonistName"].isNull).isTrue()
        assertThat(body["thumbnailModerationStatus"].asText()).isEqualTo("APPROVED")
    }

    @Test fun `공식 계정 스토리는 오리지널 상세의 수정 폼 필드와 같은 값이다`() {
        val official = users.save(User(publicId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), nickname = "공식"))
        val story = full().also { jdbc.update("UPDATE stories SET user_id = ? WHERE id = ?", official.id, it.id) }
        val body = ok(story)
        val original = json(client.get().uri("/api/v1/admin/originals/${story.publicId}").header("Authorization", "Bearer $token")
            .exchange().expectStatus().isOk) as ObjectNode
        original["characters"].forEach { character -> character["images"].forEach { (it as ObjectNode).remove("objectKey") } }
        body.properties().forEach { (key, value) -> assertThat(original[key]).describedAs(key).isEqualTo(value) }
    }

    @Test fun `소유자 없는 게스트 스토리도 조회한다`() {
        assertThat(ok(bare(userId = null, title = "게스트"))["title"].asText()).isEqualTo("게스트")
    }

    @Test fun `대기 중인 수정 제출본이 아니라 현재 저장값을 돌려준다`() {
        val story = bare(title = "저장된 제목")
        submissions.save(StorySubmission(userId = owner.id, kind = SubmissionKind.UPDATE, storyId = story.id,
            payload = """{"title":"제출본 제목"}""", inputForm = """{"title":"제출본 제목"}"""))
        val ownerForm = json(client.get().uri("/api/v1/stories/${story.publicId}/edit")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(owner.publicId)}").exchange().expectStatus().isOk)
        assertThat(ownerForm["title"].asText()).isEqualTo("제출본 제목")
        assertThat(ok(story)["title"].asText()).isEqualTo("저장된 제목")
    }

    @Test fun `반려된 제출본이 있어도 submission과 이미지 objectKey 필드가 없다`() {
        val story = full()
        submissions.save(StorySubmission(userId = owner.id, kind = SubmissionKind.UPDATE, storyId = story.id,
            payload = "{}", inputForm = "{}", status = SubmissionStatus.REJECTED, decidedAt = Instant.now()))
        val body = ok(story)
        assertThat(body.has("submission")).isFalse()
        assertThat(body["characters"][0]["images"].toList().map { it.has("objectKey") }).containsOnly(false)
        assertThat(body.properties().map { it.key }).containsExactlyInAnyOrder("title", "oneLineIntro", "description", "protagonistName",
            "genres", "storySettings", "startSettings", "mainEvents", "visibility", "thumbnailUrl", "thumbnailModerationStatus", "characters")
        assertThat(body["characters"][0].properties().map { it.key }).containsExactlyInAnyOrder("id", "name", "description", "images")
        assertThat(body["characters"][0]["images"][0].properties().map { it.key }).containsExactlyInAnyOrder("id", "imageName", "imageUrl", "moderationStatus")
    }

    @Test fun `삭제됐거나 없는 스토리는 404이고 UUID가 아니면 400이다`() {
        val deleted = stories.save(Story(userId = owner.id, title = "삭제", deletedAt = Instant.now()))
        for (id in listOf(deleted.publicId, UUID.randomUUID())) assertThat(json(settingsOf(id).expectStatus().isNotFound)["code"].asText()).isEqualTo("NOT_FOUND")
        assertThat(json(settingsOf("not-a-uuid").expectStatus().isBadRequest)["code"].asText()).isEqualTo("BAD_REQUEST")
    }

    @Test fun `앱 접근 토큰은 401이고 역할 없는 회원의 관리자 토큰은 403이다`() {
        val story = bare()
        settingsOf(story.publicId, auth = null).expectStatus().isUnauthorized
        settingsOf(story.publicId, auth = tokens.issueAccessToken(owner.publicId)).expectStatus().isUnauthorized
        settingsOf(story.publicId, auth = tokens.issueAdminToken(owner.publicId)).expectStatus().isForbidden
    }

    @Test fun `스토리 설정 조회는 접속 기록을 남기지 않는다`() {
        ok(full())
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `OpenAPI 스키마는 모든 필드를 필수로 두고 값이 없을 수 있는 필드만 null을 허용한다`() {
        val docs = json(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk)
        val schemas = docs["components"]["schemas"]
        val op = docs["paths"]["/api/v1/admin/stories/{storyId}/settings"]["get"]
        val ref = op["responses"]["200"]["content"].first()["schema"]["\$ref"].asText().substringAfterLast('/')
        fun check(name: String, nullable: Set<String>) {
            val schema = schemas[name]
            assertThat(schema).describedAs(name).isNotNull()
            val properties = schema["properties"].properties().map { it.key }
            assertThat(schema["required"].toList().map { it.asText() }).describedAs("$name required").containsExactlyInAnyOrderElementsOf(properties)
            for (property in properties) {
                val types = schema["properties"][property]["type"]?.toList().orEmpty().map { it.asText() }
                assertThat(types.contains("null")).describedAs("$name.$property nullable").isEqualTo(property in nullable)
            }
        }
        check(ref, setOf("oneLineIntro", "description", "protagonistName", "thumbnailUrl"))
        val props = schemas[ref]["properties"]
        fun item(property: String, node: JsonNode = props) = node[property]["items"]["\$ref"].asText().substringAfterLast('/')
        val character = item("characters")
        check(character, setOf("description"))
        check(item("images", schemas[character]["properties"]), emptySet())
        check(props["storySettings"]["\$ref"].asText().substringAfterLast('/'), setOf("worldSetting", "characterSetting", "userRoleSetting", "ruleSetting"))
        val start = item("startSettings")
        check(start, setOf("prologue", "startSituation"))
        check(item("mainEvents"), emptySet())
        val ending = item("endings", schemas[start]["properties"])
        check(ending, emptySet())
        check(schemas[ending]["properties"]["requirement"]["\$ref"].asText().substringAfterLast('/'), emptySet())
    }
}
