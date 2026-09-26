package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.S3UploadedImageStorage
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Executor

@ActiveProfiles("test")
@SpringBootTest
class SubmissionImageFreezeIntegrationTests {
    class Objects : S3Client {
        val bytes = mutableMapOf<String, String>()
        val copies = mutableListOf<Pair<String, String>>()
        override fun serviceName() = "s3"
        override fun close() = Unit
        override fun headObject(request: HeadObjectRequest): HeadObjectResponse {
            if (request.key() !in bytes) throw S3Exception.builder().statusCode(404).build()
            return HeadObjectResponse.builder().contentType("image/webp").contentLength(10).build()
        }
        override fun copyObject(request: CopyObjectRequest): CopyObjectResponse {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            val source = java.net.URLDecoder.decode(request.copySource(), Charsets.UTF_8).substringAfter('/')
            val value = bytes[source] ?: throw S3Exception.builder().statusCode(404).build()
            assertFalse(bytes.containsKey(request.key()), "복사본 키는 다시 쓰지 않는다")
            bytes[request.key()] = value
            copies.add(source to request.key())
            return CopyObjectResponse.builder().build()
        }
    }
    private val objects = Objects()
    private val s3 = object : S3UploadedImageStorage("assets", "", "", "https://cdn.test") {
        override val client: S3Client = objects
    }
    @MockitoBean lateinit var storage: UploadedImageStorage
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var images: StoryCharacterImageRepository
    @Autowired lateinit var rows: StorySubmissionRepository
    @Autowired lateinit var service: StorySubmissionService
    @Autowired lateinit var runner: SubmissionExecutor
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var cleaner: DatabaseCleaner
    @MockitoBean(name = "storyModerationExecutor") lateinit var executor: Executor
    @MockitoBean lateinit var ai: StoryModerationClient
    @BeforeEach fun clean() {
        cleaner.cleanAll(); objects.bytes.clear(); objects.copies.clear()
        Mockito.`when`(storage.isEnabled()).thenReturn(true)
        Mockito.doAnswer { call -> s3.head(call.getArgument(0)) }.`when`(storage).head(Mockito.anyString())
        Mockito.doAnswer { call -> s3.serveUrlOf(call.getArgument(0)) }.`when`(storage).serveUrlOf(Mockito.anyString())
        Mockito.doAnswer { call -> s3.copy(call.getArgument(0), call.getArgument(1)); null }.`when`(storage).copy(Mockito.anyString(), Mockito.anyString())
    }

    private fun submit(): StorySubmission {
        val user = users.save(User(nickname = "작가"))
        val cover = "thumbnails/uploaded/drafts/${user.publicId}/cover.webp"
        val character = "characters/uploaded/drafts/${user.publicId}/face.webp"
        objects.bytes[cover] = "검수 표지"
        objects.bytes[character] = "검수 인물"
        service.create(mapper.readValue("""{"title":"제목","oneLineIntro":"소개","genres":["판타지"],
          "storySettings":{"worldSetting":"세계","characterSetting":"인물","userRoleSetting":"역할","ruleSetting":"규칙"},
          "startSettings":[{"name":"시작","prologue":"도입","startSituation":"상황","suggestedInputs":["하나","둘","셋"]}],
          "thumbnailObjectKey":"$cover","characters":[{"name":"세린","images":[{"objectKey":"$character","imageName":"세린_기본"}]}]}""", CreateGeneralStoryRequest::class.java), user.id)
        return rows.findAll().single()
    }
    @Test fun `검수와 라이브는 복사본 바이트를 쓰고 원본 덮어쓰기와 분리된다`() {
        val row = submit()
        var input: tools.jackson.databind.JsonNode? = null
        Mockito.doAnswer { call -> input = call.getArgument(0); ModerationResult("APPROVED", emptyList(), null) }.`when`(ai)
            .moderate(Mockito.any(tools.jackson.databind.JsonNode::class.java) ?: mapper.createObjectNode())
        runner.run(SubmissionRequested(row.id, row.attempt))
        assertEquals(2, objects.copies.size)
        objects.copies.forEach { (source, _) -> objects.bytes[source] = "미검수 덮어쓰기" }
        val story = stories.findAll().single()
        val liveUrls = listOf(story.thumbnailImageUrl!!, images.findAll().single().imageUrl)
        assertEquals(listOf(input!!["thumbnailUrl"].asText(), input!!["characters"][0]["images"][0]["imageUrl"].asText()), liveUrls)
        liveUrls.forEach { url ->
            val key = url.removePrefix("https://cdn.test/")
            assertTrue(key.contains("/uploaded/moderated/"))
            assertNotEquals("미검수 덮어쓰기", objects.bytes[key])
        }
        runner.run(SubmissionRequested(row.id, row.attempt))
        assertEquals(2, objects.copies.size)
    }
    @Test fun `복사 시 원본이 없으면 FAILED이고 AI를 부르지 않는다`() {
        val row = submit()
        objects.bytes.clear()
        runner.run(SubmissionRequested(row.id, row.attempt))
        val failed = rows.findById(row.id).orElseThrow()
        assertEquals(SubmissionStatus.FAILED, failed.status)
        assertEquals("MODERATION_UNAVAILABLE", failed.errorCode)
        Mockito.verifyNoInteractions(ai)
        assertEquals(0, stories.count())
    }
    @Autowired lateinit var transactions: SubmissionTransactions
    @Autowired lateinit var freezer: SubmissionImages

    @Test fun `기록된 복사본은 미완료 실행에서 재사용하고 재제출에서는 새로 복사한다`() {
        val row = submit()
        val work = transactions.start(row.id, row.attempt)!!
        freezer.prepare(work) { source, destination -> transactions.recordCopy(row.id, row.attempt, source, destination) }
        val firstCopies = rows.findById(row.id).orElseThrow().imageCopies
        objects.copies.forEach { (source, _) -> objects.bytes[source] = "새 입력" }
        Mockito.doReturn(ModerationResult("REJECTED", listOf(ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
            .`when`(ai).moderate(Mockito.any(tools.jackson.databind.JsonNode::class.java) ?: mapper.createObjectNode())
        runner.run(SubmissionRequested(row.id, row.attempt))
        assertEquals(2, objects.copies.size)
        assertEquals(firstCopies, rows.findById(row.id).orElseThrow().imageCopies)
        service.resubmit(row.publicId.toString(), mapper.readValue(row.payload, CreateGeneralStoryRequest::class.java).copy(title = "다시 제출"), row.userId)
        assertTrue(rows.findById(row.id).orElseThrow().imageCopies.isEmpty())
        Mockito.doReturn(ModerationResult("APPROVED", emptyList(), null)).`when`(ai)
            .moderate(Mockito.any(tools.jackson.databind.JsonNode::class.java) ?: mapper.createObjectNode())
        runner.run(SubmissionRequested(row.id, rows.findById(row.id).orElseThrow().attempt))
        assertEquals(4, objects.copies.size)
        assertNotEquals(firstCopies, rows.findById(row.id).orElseThrow().imageCopies)
    }
    @Test fun `UPDATE의 기존 라이브 이미지 ID는 다시 복사하지 않는다`() {
        val row = submit()
        Mockito.doReturn(ModerationResult("APPROVED", emptyList(), null)).`when`(ai)
            .moderate(Mockito.any(tools.jackson.databind.JsonNode::class.java) ?: mapper.createObjectNode())
        runner.run(SubmissionRequested(row.id, row.attempt))
        val story = stories.findAll().single()
        val image = images.findAll().single()
        val form = service.editForm(story.publicId.toString(), row.userId)
        val characterId = form["characters"][0]["id"].asText()
        service.update(story.publicId.toString(), mapper.readValue("""{"characters":[{"id":"$characterId","name":"세린","images":[{"id":"${image.publicId}"}]}]}""",
            com.knk.manyak.story.dto.UpdateStoryRequest::class.java), row.userId)
        val update = rows.findAll().first { it.kind == SubmissionKind.UPDATE }
        runner.run(SubmissionRequested(update.id, update.attempt))
        assertEquals(SubmissionStatus.APPROVED, rows.findById(update.id).orElseThrow().status)
        assertEquals(2, objects.copies.size)
        assertEquals(image.imageUrl, images.findAll().single().imageUrl)
    }

}
