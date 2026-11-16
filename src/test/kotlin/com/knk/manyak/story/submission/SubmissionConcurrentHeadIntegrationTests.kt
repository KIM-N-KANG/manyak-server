package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 제출 접수의 S3 HEAD 동시 실행(KNK-2077 M1 안 B). 잠금과 트랜잭션, 검증 순서는 그대로이고 HEAD만 동시에 보낸다.
 * 여러 이미지가 실패해도 응답은 순차로 돌렸을 때 처음 만나는 오류와 같아야 한다. 키의 토큰이 HEAD 결과와 지연을 정한다.
 */
class SubmissionConcurrentHeadIntegrationTests : MockedBoundaryIntegrationTest() {
    @Autowired private lateinit var providedGenreTags: com.knk.manyak.story.repository.StoryCreationTagRepository
    @Autowired private lateinit var storage: UploadedImageStorage
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var characters: StoryCharacterRepository
    @Autowired private lateinit var images: StoryCharacterImageRepository
    @Autowired private lateinit var service: StorySubmissionService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired @Qualifier("storyModerationExecutor") private lateinit var executor: Executor

    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()
    private val requestIds: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    /** `hold` 토큰 HEAD가 기다리는 래치. 테스트가 접수 반환 뒤에 연다. */
    private var hold = CountDownLatch(1)
    private val heldFinished = AtomicInteger()

    @BeforeEach fun setup() {
        com.knk.manyak.support.stopExecuting(executor)
        cleaner.cleanAll()
        com.knk.manyak.support.seedProvidedGenres(providedGenreTags, "판타지", "미스터리")
        inFlight.set(0); peak.set(0); requestIds.clear(); hold = CountDownLatch(1); heldFinished.set(0)
        `when`(storage.isEnabled()).thenReturn(true)
        doAnswer { call -> "https://cdn.test/" + call.getArgument<String>(0) }.`when`(storage).serveUrlOf(anyString())
        doAnswer { call ->
            val key = call.getArgument<String>(0)
            requestIds += MDC.get("request_id")
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                if ("hold" in key) {
                    // 기다리지 않는 구현이면 접수가 이 HEAD들보다 먼저 반환한다. 취소되면 인터럽트로 빠진다.
                    hold.await(10, TimeUnit.SECONDS)
                    heldFinished.incrementAndGet()
                } else Thread.sleep(Regex("slow(\\d+)").find(key)?.groupValues?.get(1)?.toLong() ?: DELAY)
                when {
                    "missing" in key -> null
                    "big" in key -> UploadedObject("image/webp", 6L * 1024 * 1024)
                    "gif" in key -> UploadedObject("image/gif", 10)
                    "boom" in key -> throw IllegalStateException("s3 down")
                    else -> UploadedObject("image/webp", 10)
                }
            } finally { inFlight.decrementAndGet() }
        }.`when`(storage).head(anyString())
    }

    private fun heads() = mockingDetails(storage).invocations.count { it.method.name == "head" }

    /** [chars]는 인물마다 이미지 토큰 목록이다. 이름이 `!`로 시작하면 형식에 맞지 않는 이미지 이름을 쓴다. */
    private fun request(user: User, thumbnail: String?, chars: List<List<String>>, owner: UUID = user.publicId) = mapper.readValue("""{"title":"제목","oneLineIntro":"소개","genres":["판타지"],
        "storySettings":{"worldSetting":"세계","characterSetting":"인물","userRoleSetting":"역할","ruleSetting":"규칙"},
        "startSettings":[{"name":"시작","prologue":"도입","startSituation":"상황","suggestedInputs":["하나","둘","셋"]}],
        ${thumbnail?.let { """"thumbnailObjectKey":"thumbnails/uploaded/drafts/$owner/$it-${UUID.randomUUID()}.webp",""" } ?: ""}
        "characters":[${chars.mapIndexed { c, tokens -> """{"name":"인물$c","images":[${tokens.mapIndexed { i, token ->
            val name = if (token.startsWith("!")) "잘못" else "인물${c}_$i"
            """{"objectKey":"characters/uploaded/drafts/${if (token.startsWith("foreign")) UUID.randomUUID() else owner}/${token.removePrefix("!")}-${UUID.randomUUID()}.webp","imageName":"$name"}""" }.joinToString(",")}]}""" }.joinToString(",")}]}""",
        CreateGeneralStoryRequest::class.java)

    @Test fun `등록의 HEAD 61개는 동시에 나가고 동시 수는 8을 넘지 않으며 요청 MDC를 잇는다`() {
        val author = users.save(User(nickname = "작가"))
        MDC.put("request_id", "req-61")
        try {
            service.create(request(author, "ok", List(6) { List(10) { "ok" } }), author.id)
        } finally { MDC.remove("request_id") }

        assertThat(heads()).isEqualTo(61)
        assertThat(peak.get()).isBetween(2, 8)
        assertThat(requestIds).hasSize(61).containsOnly("req-61")
    }

    @Test fun `제목만 바꾼 수정의 유지 이미지 크기 조회 61개도 동시에 나간다`() {
        val author = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = author.id, title = "라이브", status = StoryStatus.PUBLISHED, genre = "판타지",
            thumbnailImageUrl = "https://cdn.test/thumbnails/live/cover.webp"))
        repeat(6) { c ->
            val character = characters.save(StoryCharacter(story = story, name = "인물$c"))
            repeat(10) { i -> images.save(StoryCharacterImage(character = character, imageName = "인물${c}_$i",
                imageUrl = "https://cdn.test/characters/live/c$c-$i.webp", sortOrder = i + 1)) }
        }
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "새 제목"), author.id)

        assertThat(heads()).isEqualTo(61)
        assertThat(peak.get()).isBetween(2, 8)
    }

    private fun failure(thumbnail: String?, vararg chars: List<String>): Throwable {
        val author = users.save(User(nickname = "작가"))
        return catchThrowable { service.create(request(author, thumbnail, chars.toList()), author.id) }
    }

    private fun assertBadRequest(error: Throwable, reason: String, code: String? = null) {
        assertThat(error).isInstanceOf(ResponseStatusException::class.java)
        error as ResponseStatusException
        assertThat(error.statusCode.value()).isEqualTo(400)
        assertThat(error.reason).isEqualTo(reason)
        if (code != null) assertThat((error as CodedResponseStatusException).errorCode).isEqualTo(code)
        else assertThat(error).isNotInstanceOf(CodedResponseStatusException::class.java)
    }

    @Test fun `늦게 끝난 앞 이미지의 없음이 먼저 끝난 뒤 이미지의 크기 초과보다 먼저다`() {
        assertBadRequest(failure("ok", listOf("ok-slow200", "missing-slow100"), listOf("big-slow1")),
            "업로드된 이미지를 찾을 수 없습니다. 업로드를 마친 뒤 다시 시도해 주세요.", "UPLOAD_NOT_FOUND")
    }

    @Test fun `인물 이미지의 형식 오류가 그 뒤에 검증하는 표지의 없음보다 먼저다`() {
        assertBadRequest(failure("missing-slow1", listOf("gif-slow150")), "지원하지 않는 이미지 형식입니다(JPEG·PNG·WebP).")
    }

    @Test fun `앞 이미지의 HEAD 예외가 뒤 이미지의 없음보다 먼저 그대로 전파된다`() {
        val error = failure("ok", listOf("boom-slow150", "missing-slow1"))
        assertThat(error).isInstanceOf(IllegalStateException::class.java).hasMessage("s3 down")
    }

    @Test fun `HEAD 오류와 이미지 이름 오류는 입력 순서대로 먼저 만난 것이다`() {
        assertBadRequest(failure("ok", listOf("missing-slow150", "!ok")),
            "업로드된 이미지를 찾을 수 없습니다. 업로드를 마친 뒤 다시 시도해 주세요.", "UPLOAD_NOT_FOUND")
        assertBadRequest(failure("ok", listOf("!ok", "missing-slow1")),
            "이미지 이름은 '인물0_접미' 형식이어야 합니다(접미는 1~20자 한글·영문·숫자).")
    }

    @Test fun `남의 접두어 키는 HEAD하지 않고 그 자리에서 거절한다`() {
        assertBadRequest(failure("ok", listOf("foreign-ok", "missing-slow1")), "내가 올린 업로드 이미지가 아닙니다.")
        assertThat(mockingDetails(storage).invocations.filter { it.method.name == "head" }.map { it.getArgument<String>(0) })
            .noneMatch { it.contains("foreign") }
    }

    private companion object { const val DELAY = 30L }

    @Test fun `첫 이미지의 HEAD 예외는 남은 HEAD를 기다리지 않고 순차와 같은 예외로 끝난다`() {
        val error = try { failure("hold", listOf("boom-slow1") + List(9) { "hold" }, List(10) { "hold" }) } finally { assertThat(heldFinished.get()).isZero(); hold.countDown() }
        assertThat(error).isInstanceOf(IllegalStateException::class.java).hasMessage("s3 down")
    }

    @Test fun `첫 이미지가 없으면 남은 HEAD를 기다리지 않고 순차와 같은 없음으로 끝난다`() {
        val error = try { failure("hold", listOf("missing-slow1") + List(9) { "hold" }, List(10) { "hold" }) } finally { assertThat(heldFinished.get()).isZero(); hold.countDown() }
        assertBadRequest(error, "업로드된 이미지를 찾을 수 없습니다. 업로드를 마친 뒤 다시 시도해 주세요.", "UPLOAD_NOT_FOUND")
    }
}
