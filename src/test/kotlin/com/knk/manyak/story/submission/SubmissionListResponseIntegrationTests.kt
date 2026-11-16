package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Executor

/**
 * 내 제출본 목록의 응답을 고정한다(KNK-2077 M3). 목록의 각 항목은 같은 제출본의 상세 응답과 같아야 한다(MODERATION 4-2-3).
 * 대기 수정본은 현재 스토리에 요청을 적용한 폼, 반려 수정본은 라이브에서 지워진 이미지의 위반 항목을 뺀 폼이다.
 */
class SubmissionListResponseIntegrationTests : MockedBoundaryIntegrationTest() {
    @Autowired private lateinit var providedGenreTags: com.knk.manyak.story.repository.StoryCreationTagRepository
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var tokens: JwtTokenProvider
    @Autowired private lateinit var storage: UploadedImageStorage
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var characters: StoryCharacterRepository
    @Autowired private lateinit var images: StoryCharacterImageRepository
    @Autowired private lateinit var rows: StorySubmissionRepository
    @Autowired private lateinit var service: StorySubmissionService
    @Autowired private lateinit var transactions: SubmissionTransactions
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired @Qualifier("storyModerationExecutor") private lateinit var executor: Executor
    @Autowired private lateinit var registry: io.micrometer.observation.ObservationRegistry

    @BeforeEach fun setup() {
        com.knk.manyak.support.stopExecuting(executor)
        cleaner.cleanAll()
        com.knk.manyak.support.seedProvidedGenres(providedGenreTags, "판타지", "미스터리")
        `when`(storage.isEnabled()).thenReturn(true)
        doAnswer { UploadedObject("image/webp", 10) }.`when`(storage).head(anyString())
        doAnswer { call -> "https://cdn.test/" + call.getArgument<String>(0) }.`when`(storage).serveUrlOf(anyString())
    }

    private fun createRequest(user: User, title: String, chars: List<Pair<String, Int>>, starts: Int = 1) = mapper.readValue("""{"title":"$title","oneLineIntro":"소개","genres":["판타지","미스터리"],
        "storySettings":{"worldSetting":"세계","characterSetting":"인물","userRoleSetting":"역할","ruleSetting":"규칙"},
        "startSettings":[${(1..starts).joinToString(",") { s -> """{"name":"시작$s","prologue":"도입$s","startSituation":"상황$s","suggestedInputs":["하나$s","둘$s","셋$s"],
          "endings":[{"name":"끝$s-1","requirement":{"minTurns":$s,"achievementCondition":"조건$s"},"epilogue":"에필$s"},{"name":"끝$s-2","requirement":{"minTurns":0,"achievementCondition":"조건"},"epilogue":"에필"}]}""" }}],
        "mainEvents":[{"name":"사건1","description":"설명1","keySentence":"문장1"},{"name":"사건2","description":"설명2","keySentence":"문장2"}],
        "thumbnailObjectKey":"thumbnails/uploaded/drafts/${user.publicId}/${UUID.randomUUID()}.webp",
        "characters":[${chars.joinToString(",") { (name, count) -> """{"name":"$name","description":"$name 소개","images":[${(1..count).joinToString(",") { i ->
            """{"objectKey":"characters/uploaded/drafts/${user.publicId}/${UUID.randomUUID()}.webp","imageName":"${name}_$i"}""" }}]}""" }}]}""",
        CreateGeneralStoryRequest::class.java)

    /** 등록 제출본을 승인해 라이브 스토리를 만든다(SubmissionApprovalTestSupport와 같은 방식). */
    private fun approvedStory(user: User, request: CreateGeneralStoryRequest): Story {
        val id = service.create(request, user.id).submissionId
        val row = rows.findByPublicId(UUID.fromString(id))!!
        row.imageCopies = SubmissionImages.newKeys(mapper.readTree(row.inputForm)).associateWith { it }
        rows.save(row)
        transactions.finish(row.id, row.attempt, ModerationResult("APPROVED", emptyList(), null))
        return stories.findById(rows.findById(row.id).orElseThrow().storyId!!).orElseThrow()
    }

    private fun reject(id: String, issues: List<ModerationIssue>, imageErrors: List<SubmissionImageError> = emptyList()) {
        val row = rows.findByPublicId(UUID.fromString(id))!!
        row.status = SubmissionStatus.REJECTED
        row.issues = issues
        row.imageErrors = imageErrors
        rows.save(row)
    }

    private fun get(user: User, path: String): JsonNode = mapper.readTree(client.get().uri("/api/v1/stories/submissions$path")
        .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
        .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    private fun JsonNode.texts(field: String? = null): List<String> =
        (0 until size()).map { (if (field == null) this[it] else this[it][field]).asText() }

    @Test fun `목록 항목은 상세와 같고 대기 수정본과 반려 수정본의 현재 폼을 복원한다`() {
        val author = users.save(User(nickname = "작가"))
        val other = users.save(User(nickname = "다른 작가"))
        val rich = approvedStory(author, createRequest(author, "풍성한", listOf("세린" to 2, "루아" to 2), starts = 2))
        val plain = approvedStory(author, createRequest(author, "단출한", listOf("미루" to 2)))
        service.create(createRequest(other, "남의 것", listOf("남" to 1)), other.id)

        // 반려된 등록본
        val created = service.create(createRequest(author, "반려 등록", listOf("하나" to 1)), author.id).submissionId
        reject(created, listOf(ModerationIssue("characters[0].images[0].imageUrl", "IMAGE", "R1", "사유"), ModerationIssue("title", "TEXT", "R2", "사유")),
            listOf(SubmissionImageError("thumbnailUrl", "AI_IMAGE_FAILED")))

        // 대기 수정본: 제목 변경, 인물 순서 바꿈, 이미지 하나 유지와 새 이미지
        val richChars = characters.findByStoryIdOrderByIdAsc(rich.id)
        val luaImages = images.findByCharacterIdOrderBySortOrderAscIdAsc(richChars[1].id)
        val pending = service.update(rich.publicId.toString(), mapper.readValue("""{"title":"새 제목","characters":[
            {"id":"${richChars[1].publicId}","name":"루아","description":"바뀐 소개","images":[{"id":"${luaImages[1].publicId}"},
              {"objectKey":"characters/uploaded/drafts/${author.publicId}/${UUID.randomUUID()}.webp","imageName":"루아_새"}]},
            {"id":"${richChars[0].publicId}","name":"세린"}]}""", UpdateStoryRequest::class.java), author.id) as SubmissionAccepted

        // 반려 수정본: 위반 이미지 하나가 그 뒤 라이브에서 지워짐
        val plainImages = images.findByCharacterIdOrderBySortOrderAscIdAsc(characters.findByStoryIdOrderByIdAsc(plain.id).single().id)
        val rejected = service.update(plain.publicId.toString(), UpdateStoryRequest(title = "반려 수정"), author.id) as SubmissionAccepted
        reject(rejected.submissionId, listOf(ModerationIssue("characters[0].images[0].imageUrl", "IMAGE", "R1", "사유"),
            ModerationIssue("characters[0].images[1].imageUrl", "IMAGE", "R1", "사유")))
        images.delete(plainImages[0])

        val list = get(author, "")

        assertThat(list.texts("submissionId")).containsExactly(rejected.submissionId, pending.submissionId, created)
        (0 until list.size()).map { list[it] }.forEach { item -> assertThat(item).isEqualTo(get(author, "/${item["submissionId"].asText()}")) }

        val updated = list[1]["payload"]
        assertThat(list[1]["kind"].asText()).isEqualTo("UPDATE")
        assertThat(list[1]["storyId"].asText()).isEqualTo(rich.publicId.toString())
        assertThat(updated["title"].asText()).isEqualTo("새 제목")
        assertThat(updated["genres"].texts()).containsExactly("판타지", "미스터리")
        assertThat(updated["storySettings"]["ruleSetting"].asText()).isEqualTo("규칙")
        assertThat(updated["startSettings"].texts("name")).containsExactly("시작1", "시작2")
        assertThat(updated["startSettings"][1]["suggestedInputs"].texts()).containsExactly("하나2", "둘2", "셋2")
        assertThat(updated["startSettings"][1]["endings"].texts("name")).containsExactly("끝2-1", "끝2-2")
        assertThat(updated["mainEvents"].texts("name")).containsExactly("사건1", "사건2")
        assertThat(updated["characters"].texts("name")).containsExactly("루아", "세린")
        assertThat(updated["characters"][0]["description"].asText()).isEqualTo("바뀐 소개")
        assertThat(updated["characters"][0]["images"].texts("imageName")).containsExactly("루아_2", "루아_새")
        assertThat(updated["characters"][1]["images"].texts("imageName")).containsExactly("세린_1", "세린_2")
        assertThat(updated["thumbnailUrl"].asText()).startsWith("https://cdn.test/")
        assertThat(updated.has("submission")).isFalse()

        val restored = list[0]
        assertThat(restored["status"].asText()).isEqualTo("REJECTED")
        assertThat(restored["payload"]["title"].asText()).isEqualTo("반려 수정")
        assertThat(restored["payload"]["characters"][0]["images"].texts("imageName")).containsExactly("미루_2")
        assertThat(restored["issues"].texts("path")).containsExactly("characters[0].images[0].imageUrl")

        val create = list[2]
        assertThat(create["kind"].asText()).isEqualTo("CREATE")
        assertThat(create["storyId"].isNull).isTrue()
        assertThat(create["issues"].texts("path")).containsExactly("characters[0].images[0].imageUrl", "title")
        assertThat(create["imageErrors"].texts("path")).containsExactly("thumbnailUrl")
    }

    @Test fun `목록의 조회 수는 수정본 수와 무관하다`() {
        com.knk.manyak.auth.service.LockSql.install(registry)
        val author = users.save(User(nickname = "작가"))
        service.create(createRequest(author, "등록", listOf("하나" to 1)), author.id)
        fun addUpdates(count: Int) = repeat(count) {
            val story = approvedStory(author, createRequest(author, "라이브$it", listOf("세린" to 2, "루아" to 1), starts = 2))
            service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정$it"), author.id)
        }
        addUpdates(2)
        val two = com.knk.manyak.auth.service.LockSql.statements { service.list(author.id, 100) }
        addUpdates(3)
        val five = com.knk.manyak.auth.service.LockSql.statements { service.list(author.id, 100) }
        assertThat(service.list(author.id, 100)).hasSize(6)
        assertThat(five).isEqualTo(two)
        assertThat(two).hasSizeLessThanOrEqualTo(10)
    }
}
