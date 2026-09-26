package com.knk.manyak.story.controller

import com.knk.manyak.story.submission.*
import com.knk.manyak.auth.entity.User
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.http.MediaType

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorySubmissionIntegrationTests {
    @Autowired private lateinit var users: com.knk.manyak.auth.repository.UserRepository
    @Autowired private lateinit var stories: com.knk.manyak.story.repository.StoryRepository
    @Autowired private lateinit var submissions: com.knk.manyak.story.submission.StorySubmissionRepository
    @Autowired private lateinit var service: com.knk.manyak.story.submission.StorySubmissionService
    @Autowired private lateinit var worker: com.knk.manyak.story.submission.SubmissionTransactions
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var cleaner: com.knk.manyak.support.DatabaseCleaner
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "storyModerationExecutor")
    private lateinit var executor: java.util.concurrent.Executor
    @org.junit.jupiter.api.BeforeEach fun clean() = cleaner.cleanAll()

    private fun request() = mapper.readValue("""{
        "title":"제목", "oneLineIntro":"소개", "genres":["판타지"],
        "storySettings":{"worldSetting":"세계", "characterSetting":"인물", "userRoleSetting":"역할", "ruleSetting":"규칙"},
        "startSettings":[{"name":"시작", "prologue":"도입", "startSituation":"상황", "suggestedInputs":["하나","둘","셋"]}]
    }""", com.knk.manyak.story.dto.CreateGeneralStoryRequest::class.java)

    @Test fun `접수는 라이브를 만들지 않고 승인 회차를 한번만 적용한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        val accepted = service.create(request(), user.id)
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
        val row = submissions.findByPublicId(java.util.UUID.fromString(accepted.submissionId))!!
        val approved = com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null)
        worker.finish(row.id, 1, approved)
        worker.finish(row.id, 1, approved)
        org.junit.jupiter.api.Assertions.assertEquals(1, stories.count())
        org.junit.jupiter.api.Assertions.assertEquals(com.knk.manyak.story.submission.SubmissionStatus.APPROVED, submissions.findById(row.id).orElseThrow().status)
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException::class.java) { service.delete(accepted.submissionId, user.id) }
    }

    @Test fun `반려 후 재제출은 ID 유지와 회차 증가 및 늦은 결과 차단`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        val accepted = service.create(request(), user.id)
        val row = submissions.findByPublicId(java.util.UUID.fromString(accepted.submissionId))!!
        worker.finish(row.id, 1, com.knk.manyak.story.submission.ModerationResult("REJECTED", listOf(com.knk.manyak.story.submission.ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
        val retry = service.resubmit(accepted.submissionId, request().copy(title = "수정"), user.id)
        org.junit.jupiter.api.Assertions.assertEquals(accepted.submissionId, retry.submissionId)
        worker.finish(row.id, 1, com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null))
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
        val current = submissions.findById(row.id).orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(2, current.attempt)
        org.junit.jupiter.api.Assertions.assertTrue(current.issues.isEmpty())
        service.delete(accepted.submissionId, user.id)
        worker.finish(row.id, 2, com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null))
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
    }

    @Test fun `수정 제출은 라이브 불변이고 공개 범위 변경도 검수중에는 충돌한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        val story = stories.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "원본"))
        val accepted = service.update(story.publicId.toString(), com.knk.manyak.story.dto.UpdateStoryRequest(title = "제출"), user.id)
        org.junit.jupiter.api.Assertions.assertTrue(accepted is com.knk.manyak.story.submission.SubmissionAccepted)
        org.junit.jupiter.api.Assertions.assertEquals("원본", stories.findById(story.id).orElseThrow().title)
        org.junit.jupiter.api.Assertions.assertEquals("제출", service.editForm(story.publicId.toString(), user.id).path("title").asText())
        val exception = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            service.update(story.publicId.toString(), com.knk.manyak.story.dto.UpdateStoryRequest(visibility = com.knk.manyak.story.entity.StoryVisibility.PUBLIC), user.id)
        }
        org.junit.jupiter.api.Assertions.assertEquals(409, exception.statusCode.value())
    }

    @Autowired private lateinit var client: RestTestClient


    @Autowired private lateinit var images: com.knk.manyak.story.service.StoryImageService
    @Autowired private lateinit var storyService: com.knk.manyak.story.service.StoryService
    @Autowired private lateinit var runner: com.knk.manyak.story.submission.SubmissionExecutor
    @Autowired private lateinit var assembler: com.knk.manyak.story.submission.SubmissionFormAssembler
    @Autowired private lateinit var tokens: com.knk.manyak.auth.jwt.JwtTokenProvider
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private lateinit var ai: com.knk.manyak.story.submission.StoryModerationClient

    @Test fun `회원 HTTP 접수는 202와 UUID만 반환하고 타인 제출본은 404`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        val token = tokens.issueAccessToken(user.publicId)
        client.post().uri("/api/v1/stories/general").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request())).exchange()
            .expectStatus().isAccepted.expectBody().jsonPath("$.submissionId").isNotEmpty
            .jsonPath("$.status").isEqualTo("PENDING").jsonPath("$.id").doesNotExist()
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
        val row = submissions.findAll().single()
        val other = users.save(com.knk.manyak.auth.entity.User(nickname = "타인"))
        client.get().uri("/api/v1/stories/submissions/${row.publicId}")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(other.publicId)}").exchange().expectStatus().isNotFound
        client.get().uri("/api/v1/stories/submissions?limit=0").header("Authorization", "Bearer $token")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(1)
        client.get().uri("/api/v1/stories/submissions?limit=abc").header("Authorization", "Bearer $token")
            .exchange().expectStatus().isBadRequest
    }

    @Test fun `검수 중 이미지 삭제는 충돌하고 스토리 삭제 후 늦은 승인은 무시한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        val story = stories.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), com.knk.manyak.story.dto.UpdateStoryRequest(title = "제출"), user.id)
        val row = submissions.findAll().single()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException::class.java) { images.deleteThumbnail(story.publicId.toString(), user.id) }
        org.junit.jupiter.api.Assertions.assertEquals(409, ex.statusCode.value())
        storyService.deleteStory(story.publicId.toString(), user.id)
        org.junit.jupiter.api.Assertions.assertEquals(0, submissions.count())
        worker.finish(row.id, row.attempt, com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null))
        org.junit.jupiter.api.Assertions.assertNotNull(stories.findById(story.id).orElseThrow().deletedAt)
    }

    @Test fun `회수는 dispatched_at 기준이고 FAILED를 자동 재시도하지 않는다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        service.create(request(), user.id)
        var row = submissions.findAll().single()
        worker.reclaim(row.id, row.dispatchedAt.minusSeconds(1))
        org.junit.jupiter.api.Assertions.assertEquals(1, submissions.findById(row.id).orElseThrow().attempt)
        worker.reclaim(row.id, row.dispatchedAt.plusSeconds(1))
        row = submissions.findById(row.id).orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(2, row.attempt)
        worker.fail(row.id, 2, "MODERATION_UNAVAILABLE")
        worker.reclaim(row.id, java.time.Instant.now().plusSeconds(600))
        org.junit.jupiter.api.Assertions.assertEquals(2, submissions.findById(row.id).orElseThrow().attempt)
    }

    @Test fun `AI 호출 실패는 FAILED와 MODERATION_UNAVAILABLE이며 원문은 보존한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        service.create(request(), user.id)
        val row = submissions.findAll().single()
        org.mockito.Mockito.doThrow(IllegalStateException("external failure")).`when`(ai).moderate(assembler.aiInput(mapper.readTree(row.inputForm)))
        runner.run(com.knk.manyak.story.submission.SubmissionRequested(row.id, row.attempt))
        val failed = submissions.findById(row.id).orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals("MODERATION_UNAVAILABLE", failed.errorCode)
        org.junit.jupiter.api.Assertions.assertEquals(row.payload, failed.payload)
        org.junit.jupiter.api.Assertions.assertTrue(failed.issues.isEmpty())
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
    }

    @Test fun `AI 실행 오류 코드는 반려 이슈와 분리한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        service.create(request(), user.id)
        val row = submissions.findAll().single()
        worker.finish(row.id, row.attempt, com.knk.manyak.story.submission.ModerationResult("REJECTED", emptyList(), "IMAGE_READ_FAILED"))
        val failed = submissions.findById(row.id).orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(com.knk.manyak.story.submission.SubmissionStatus.FAILED, failed.status)
        org.junit.jupiter.api.Assertions.assertEquals("IMAGE_READ_FAILED", failed.errorCode)
    }

    @Test fun `AI 입력은 식별자 공개 범위 최소 턴 수를 제외한다`() {
        val input = assembler.aiInput(mapper.readTree("""{"title":"제목","visibility":"PUBLIC","characters":[{"id":"id","name":"이름","images":[{"id":null,"objectKey":"key","imageUrl":"url","imageName":"이름_기본","moderationStatus":"APPROVED"}]}],"startSettings":[{"id":"start","endings":[{"requirement":{"minTurns":1,"achievementCondition":"조건"}}]}],"submission":null}"""))
        val encoded = mapper.writeValueAsString(input)
        listOf("visibility", "minTurns", "objectKey", "moderationStatus", "submission", "\"id\"").forEach { org.junit.jupiter.api.Assertions.assertFalse(encoded.contains(it)) }
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("achievementCondition"))
    }

    @Test fun `이미지 이슈는 identity로 재매핑하고 사라진 대상은 제외한다`() {
        val original = mapper.readTree("""{"characters":[{"id":"c","images":[{"id":"a"},{"objectKey":"b"}]}]}""")
        val current = mapper.readTree("""{"characters":[{"id":"c","images":[{"objectKey":"b","imageUrl":"url"}]}]}""")
        val issues = listOf(com.knk.manyak.story.submission.ModerationIssue("characters[0].images[0].imageUrl", "IMAGE", "DRUGS", "사유"), com.knk.manyak.story.submission.ModerationIssue("characters[0].images[1].imageUrl", "IMAGE", "DRUGS", "사유"))
        org.junit.jupiter.api.Assertions.assertEquals(listOf("characters[0].images[0].imageUrl"), assembler.remap(issues, original, current).map { it.path })
    }


    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "pushExecutor")
    private lateinit var pushExecutor: java.util.concurrent.Executor
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private lateinit var pushSender: com.knk.manyak.push.service.FcmPushSender
    @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

    @Test fun `local 알림은 커밋 뒤에만 제출되며 servicePush 설정을 따른다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        service.create(request(), user.id)
        val row = submissions.findAll().single()
        org.mockito.Mockito.doAnswer { call -> (call.arguments[0] as Runnable).run(); null }.`when`(pushExecutor)
            .execute(org.mockito.Mockito.any(Runnable::class.java))
        org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
            worker.finish(row.id, row.attempt, com.knk.manyak.story.submission.ModerationResult("REJECTED", listOf(com.knk.manyak.story.submission.ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
            org.mockito.Mockito.verifyNoInteractions(pushSender)
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, org.mockito.Mockito.mockingDetails(pushSender).invocations.size)
        val data = org.mockito.Mockito.mockingDetails(pushSender).invocations.single().arguments[1] as Map<*, *>
        org.junit.jupiter.api.Assertions.assertEquals("STORY_MODERATION_COMPLETED", data["type"])
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED", data["status"])
        user.servicePushEnabled = false
        users.save(user)
        service.resubmit(row.publicId.toString(), request(), user.id)
        worker.fail(row.id, 2, "MODERATION_UNAVAILABLE")
        org.junit.jupiter.api.Assertions.assertEquals(1, org.mockito.Mockito.mockingDetails(pushSender).invocations.size)
    }

    @Test fun `동시 CREATE 승인은 스토리를 한 번만 만든다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        service.create(request(), user.id)
        val row = submissions.findAll().single()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit { worker.finish(row.id, row.attempt, com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null)) } }
            futures.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        org.junit.jupiter.api.Assertions.assertEquals(1, stories.count())
    }

    @Test fun `탈퇴는 제출본을 삭제하고 늦은 결과를 무시한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        service.create(request(), user.id)
        val row = submissions.findAll().single()
        withdrawal.withdraw(user.id)
        worker.finish(row.id, row.attempt, com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null))
        org.junit.jupiter.api.Assertions.assertEquals(0, submissions.count())
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
    }
    @Autowired private lateinit var withdrawal: com.knk.manyak.user.service.UserWithdrawalService


    @Test fun `승인 적용 검증 실패는 롤백 후 APPLY_FAILED로 기록한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "제작자"))
        val story = stories.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), com.knk.manyak.story.dto.UpdateStoryRequest(title = "변경"), user.id)
        val row = submissions.findAll().single()
        // 접수 이후 적용 불변식이 달라진 상황을 재현한다. 제목 변경 뒤 주요 사건 검증에서 실패한다.
        row.payload = """{"title":"변경","mainEvents":[{"name":"중복","description":"설명","keySentence":"문장"},{"name":"중복","description":"설명","keySentence":"문장"}]}"""
        submissions.save(row)
        org.mockito.Mockito.`when`(ai.moderate(assembler.aiInput(mapper.readTree(row.inputForm))))
            .thenReturn(com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null))
        runner.run(com.knk.manyak.story.submission.SubmissionRequested(row.id, row.attempt))
        org.junit.jupiter.api.Assertions.assertEquals("원본", stories.findById(story.id).orElseThrow().title)
        org.junit.jupiter.api.Assertions.assertEquals("APPLY_FAILED", submissions.findById(row.id).orElseThrow().errorCode)
    }

    @Test
    fun `미인증 일반 제작은 입력 검증 전에 401`() {
        client.post().uri("/api/v1/stories/general").contentType(MediaType.APPLICATION_JSON)
            .body("{}").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `미인증 수정과 제출본 조회는 401`() {
        client.patch().uri("/api/v1/stories/00000000-0000-0000-0000-000000000000")
            .contentType(MediaType.APPLICATION_JSON).body("{}").exchange().expectStatus().isUnauthorized
        client.get().uri("/api/v1/stories/submissions").exchange().expectStatus().isUnauthorized
    }
    @Test fun `PENDING PATCH는 잘못된 genres보다 409를 우선한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "작가"))
        val story = stories.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), com.knk.manyak.story.dto.UpdateStoryRequest(title = "변경"), user.id)
        client.patch().uri("/api/v1/stories/${story.publicId}")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body("""{"genres":[]}""")
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test fun `타인과 미존재 제출본 PUT은 무효 입력보다 404를 우선한다`() {
        val owner = users.save(com.knk.manyak.auth.entity.User(nickname = "작가"))
        val other = users.save(com.knk.manyak.auth.entity.User(nickname = "타인"))
        val accepted = service.create(request(), owner.id)
        listOf(accepted.submissionId, java.util.UUID.randomUUID().toString()).forEach { id ->
            client.put().uri("/api/v1/stories/submissions/$id")
                .header("Authorization", "Bearer ${tokens.issueAccessToken(other.publicId)}")
                .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request().copy(genres = emptyList())))
                .exchange().expectStatus().isNotFound
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["UNKNOWN", "missingPath"])
    fun `AI 계약 밖 rule과 path는 정상 반려가 아니라 실행 실패다`(invalid: String) {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "작가"))
        service.create(request(), user.id)
        val row = submissions.findAll().single()
        val issue = com.knk.manyak.story.submission.ModerationIssue(
            if (invalid == "missingPath") "startSettings[9].prologue" else "title",
            "TEXT", if (invalid == "UNKNOWN") invalid else "DRUGS", "사유")
        org.mockito.Mockito.`when`(ai.moderate(assembler.aiInput(mapper.readTree(row.inputForm))))
            .thenReturn(com.knk.manyak.story.submission.ModerationResult("REJECTED", listOf(issue), null))
        runner.run(com.knk.manyak.story.submission.SubmissionRequested(row.id, row.attempt))
        val result = submissions.findById(row.id).orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(com.knk.manyak.story.submission.SubmissionStatus.FAILED, result.status)
        org.junit.jupiter.api.Assertions.assertEquals("MODERATION_UNAVAILABLE", result.errorCode)
        org.junit.jupiter.api.Assertions.assertTrue(result.issues.isEmpty())
        org.junit.jupiter.api.Assertions.assertEquals(0, stories.count())
    }

    @Test fun `제출본 payload는 submission 메타를 포함하지 않고 수정 폼만 포함한다`() {
        val user = users.save(com.knk.manyak.auth.entity.User(nickname = "작가"))
        val accepted = service.create(request(), user.id)
        val row = submissions.findAll().single()
        fun check() {
            val payload = mapper.valueToTree<tools.jackson.databind.JsonNode>(service.get(accepted.submissionId, user.id))["payload"]
            org.junit.jupiter.api.Assertions.assertFalse(payload.has("submission"))
        }
        check()
        worker.finish(row.id, 1, com.knk.manyak.story.submission.ModerationResult("APPROVED", emptyList(), null))
        check()
        val story = stories.findAll().single()
        service.update(story.publicId.toString(), com.knk.manyak.story.dto.UpdateStoryRequest(title = "수정"), user.id)
        val update = submissions.findAll().first { it.kind == com.knk.manyak.story.submission.SubmissionKind.UPDATE }
        val payload = mapper.valueToTree<tools.jackson.databind.JsonNode>(service.get(update.publicId.toString(), user.id))["payload"]
        org.junit.jupiter.api.Assertions.assertFalse(payload.has("submission"))
        org.junit.jupiter.api.Assertions.assertTrue(service.editForm(story.publicId.toString(), user.id).has("submission"))
    }

    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    private fun seedSubmission(status: SubmissionStatus, userId: Long, update: Boolean = false): StorySubmission {
        if (update) {
            val story = stories.save(Story(userId = userId, title = "라이브"))
            service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), userId)
        } else service.create(request(), userId)
        val row = submissions.findAll().maxBy { it.id }
        when (status) {
            SubmissionStatus.APPROVED -> worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
            SubmissionStatus.REJECTED -> worker.finish(row.id, 1, ModerationResult("REJECTED", listOf(ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
            SubmissionStatus.FAILED -> worker.fail(row.id, 1, "MODERATION_UNAVAILABLE")
            SubmissionStatus.PENDING -> Unit
        }
        return submissions.findById(row.id).orElseThrow()
    }

    @ParameterizedTest @EnumSource(SubmissionStatus::class)
    fun `상세 HTTP는 상태별 전체 계약 필드를 반환한다`(status: SubmissionStatus) {
        val user = users.save(User(nickname = "작가"))
        val row = seedSubmission(status, user.id)
        val bytes = client.get().uri("/api/v1/stories/submissions/${row.publicId}")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
            .exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
        val body = mapper.readTree(bytes)
        assertEquals(setOf("submissionId", "storyId", "kind", "payload", "status", "issues", "errorCode", "createdAt", "updatedAt", "decidedAt"), body.properties().map { it.key }.toSet())
        assertEquals(row.publicId.toString(), body["submissionId"].asText())
        assertEquals("CREATE", body["kind"].asText())
        assertEquals(status.name, body["status"].asText())
        assertEquals("제목", body["payload"]["title"].asText())
        assertFalse(body["payload"].has("submission"))
        java.time.Instant.parse(body["createdAt"].asText())
        java.time.Instant.parse(body["updatedAt"].asText())
        if (status == SubmissionStatus.APPROVED) assertEquals(stories.findById(row.storyId!!).orElseThrow().publicId.toString(), body["storyId"].asText())
        else assertTrue(body["storyId"].isNull)
        if (status == SubmissionStatus.PENDING) assertTrue(body["decidedAt"].isNull)
        else java.time.Instant.parse(body["decidedAt"].asText())
        if (status == SubmissionStatus.REJECTED) {
            assertEquals(mapper.valueToTree<tools.jackson.databind.JsonNode>(row.issues), body["issues"])
        } else assertEquals(0, body["issues"].size())
        if (status == SubmissionStatus.FAILED) assertEquals("MODERATION_UNAVAILABLE", body["errorCode"].asText())
        else assertTrue(body["errorCode"].isNull)
    }

    @Test fun `목록 HTTP는 생성 시각과 ID 내림차순이며 타인과 APPROVED를 제외한다`() {
        val user = users.save(User(nickname = "작가"))
        val first = seedSubmission(SubmissionStatus.FAILED, user.id)
        val second = seedSubmission(SubmissionStatus.REJECTED, user.id)
        val newest = seedSubmission(SubmissionStatus.PENDING, user.id)
        // 저장 순서와 생성 시각 순서를 달리하고 동률도 만든다.
        val fixed = java.sql.Timestamp.from(java.time.Instant.parse("2026-01-02T00:00:00Z"))
        listOf(first, second).forEach { jdbc.update("update story_submissions set created_at = ? where id = ?", fixed, it.id) }
        jdbc.update("update story_submissions set created_at = ? where id = ?", java.sql.Timestamp.from(fixed.toInstant().minusSeconds(1)), newest.id)
        seedSubmission(SubmissionStatus.APPROVED, user.id)
        seedSubmission(SubmissionStatus.PENDING, users.save(User(nickname = "타인")).id)
        val body = client.get().uri("/api/v1/stories/submissions")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
            .exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
        assertEquals(listOf(second, first, newest).map { it.publicId.toString() }, mapper.readTree(body).toList().map { it["submissionId"].asText() })
    }

    @ParameterizedTest @EnumSource(SubmissionStatus::class)
    fun `CREATE PUT은 상태를 먼저 확인하고 반려와 실패만 재제출한다`(status: SubmissionStatus) {
        val user = users.save(User(nickname = "작가"))
        val row = seedSubmission(status, user.id)
        val auth = "Bearer ${tokens.issueAccessToken(user.publicId)}"
        val retryable = status in setOf(SubmissionStatus.REJECTED, SubmissionStatus.FAILED)
        client.put().uri("/api/v1/stories/submissions/${row.publicId}").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request().copy(genres = emptyList())))
            .exchange().expectStatus().isEqualTo(if (retryable) 400 else 409)
        client.put().uri("/api/v1/stories/submissions/${row.publicId}").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request().copy(title = "재제출")))
            .exchange().expectStatus().isEqualTo(if (retryable) 202 else 409)
        val current = submissions.findById(row.id).orElseThrow()
        assertEquals(if (retryable) 2 else 1, current.attempt)
        assertEquals(if (retryable) SubmissionStatus.PENDING else status, current.status)
        if (retryable) {
            assertTrue(current.issues.isEmpty())
            assertNull(current.errorCode)
            assertNull(current.decidedAt)
            assertEquals("재제출", mapper.readTree(current.payload)["title"].asText())
        }
    }

    @ParameterizedTest @EnumSource(SubmissionStatus::class)
    fun `UPDATE 제출본 PUT은 모든 상태에서 거절한다`(status: SubmissionStatus) {
        val user = users.save(User(nickname = "작가"))
        val row = seedSubmission(status, user.id, update = true)
        client.put().uri("/api/v1/stories/submissions/${row.publicId}")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request()))
            .exchange().expectStatus().isEqualTo(409)
        assertEquals(status, submissions.findById(row.id).orElseThrow().status)
    }

    @ParameterizedTest @EnumSource(SubmissionStatus::class)
    fun `DELETE는 타인 404 승인 409이고 나머지는 물리 삭제한다`(status: SubmissionStatus) {
        val user = users.save(User(nickname = "작가"))
        val other = users.save(User(nickname = "타인"))
        val row = seedSubmission(status, user.id)
        client.delete().uri("/api/v1/stories/submissions/${row.publicId}")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(other.publicId)}")
            .exchange().expectStatus().isNotFound
        assertTrue(submissions.existsById(row.id))
        client.delete().uri("/api/v1/stories/submissions/${row.publicId}")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
            .exchange().expectStatus().isEqualTo(if (status == SubmissionStatus.APPROVED) 409 else 204)
        assertEquals(status == SubmissionStatus.APPROVED, submissions.existsById(row.id))
    }

    @Autowired private lateinit var scheduler: SubmissionReclaimScheduler

    @Test fun `실행기 거부에도 HTTP 202를 유지하고 스케줄러가 PENDING을 회수한다`() {
        val user = users.save(User(nickname = "작가"))
        org.mockito.Mockito.doThrow(java.util.concurrent.RejectedExecutionException("full"))
            .`when`(executor).execute(org.mockito.Mockito.any(Runnable::class.java))
        client.post().uri("/api/v1/stories/general")
            .header("Authorization", "Bearer ${tokens.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(request()))
            .exchange().expectStatus().isAccepted
        val row = submissions.findAll().single()
        assertEquals(SubmissionStatus.PENDING, row.status)
        assertEquals(1, row.attempt)
        org.mockito.Mockito.verifyNoInteractions(ai)
        row.dispatchedAt = java.time.Instant.now().minusSeconds(301)
        submissions.save(row)
        org.mockito.Mockito.`when`(ai.moderate(assembler.aiInput(mapper.readTree(row.inputForm))))
            .thenReturn(ModerationResult("APPROVED", emptyList(), null))
        val queued = mutableListOf<Runnable>()
        org.mockito.Mockito.doAnswer { call -> queued.add(call.getArgument(0)); null }
            .`when`(executor).execute(org.mockito.Mockito.any(Runnable::class.java))
        scheduler.reclaim()
        // 실제 실행기처럼 회수 트랜잭션의 afterCommit 콜백이 끝난 뒤 실행한다.
        queued.single().run()
        val recovered = submissions.findById(row.id).orElseThrow()
        assertEquals(2, recovered.attempt)
        assertEquals(SubmissionStatus.APPROVED, recovered.status)
        assertNotNull(recovered.storyId)
        assertEquals(1, stories.count())
    }

}
