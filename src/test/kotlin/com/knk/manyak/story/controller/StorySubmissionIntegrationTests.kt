package com.knk.manyak.story.controller

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
        worker.finish(row.id, 1, com.knk.manyak.story.submission.ModerationResult("REJECTED", listOf(com.knk.manyak.story.submission.ModerationIssue("title", "TEXT", "RULE", "사유")), null))
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
        val issues = listOf(com.knk.manyak.story.submission.ModerationIssue("characters[0].images[0].imageUrl", "IMAGE", "RULE", "사유"), com.knk.manyak.story.submission.ModerationIssue("characters[0].images[1].imageUrl", "IMAGE", "RULE", "사유"))
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
            worker.finish(row.id, row.attempt, com.knk.manyak.story.submission.ModerationResult("REJECTED", listOf(com.knk.manyak.story.submission.ModerationIssue("title", "TEXT", "RULE", "사유")), null))
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
}
