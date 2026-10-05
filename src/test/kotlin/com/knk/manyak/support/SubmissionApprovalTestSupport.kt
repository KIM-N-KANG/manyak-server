package com.knk.manyak.support

import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.submission.*
import org.springframework.boot.test.context.TestComponent
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** 기존 라이브 저장 회귀 테스트는 202를 확인하고 명시적으로 승인한 뒤 실제 GET·DB 결과를 검증한다. */
@TestComponent
class SubmissionApprovalTestSupport(
    private val client: RestTestClient,
    private val mapper: ObjectMapper,
    private val submissions: StorySubmissionRepository,
    private val transactions: SubmissionTransactions,
    private val stories: StoryRepository,
    private val users: UserRepository,
    private val tokens: JwtTokenProvider,
) {
    fun complete(response: RestTestClient.ResponseSpec, edit: Boolean = false): RestTestClient.ResponseSpec {
        val received = response.expectBody().returnResult()
        if (received.status.value() != 202) return response
        response.expectStatus().isAccepted
        val id = mapper.readTree(received.responseBody!!).path("submissionId").asText()
        val row = submissions.findByPublicId(UUID.fromString(id))!!
        // 이 헬퍼는 기존 라이브 저장 검증용이다. 불변 복사는 별도 S3 통합 테스트에서 검증한다.
        row.imageCopies = SubmissionImages.newKeys(mapper.readTree(row.inputForm)).associateWith { it }
        submissions.save(row)
        transactions.finish(row.id, row.attempt, ModerationResult("APPROVED", emptyList(), null))
        val approved = submissions.findById(row.id).orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(SubmissionStatus.APPROVED, approved.status)
        val story = stories.findById(approved.storyId!!).orElseThrow()
        return client.get().uri("/api/v1/stories/${story.publicId}" + if (edit) "/edit" else "")
            .header("Authorization", bearer(row.userId)).exchange()
    }
    fun bearer(userId: Long): String = "Bearer ${tokens.issueAccessToken(users.findById(userId).orElseThrow().publicId)}"
}
