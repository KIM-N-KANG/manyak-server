package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.submission.*
import com.knk.manyak.support.AdminMetricsIntegrationTest
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class AdminModerationMetricsIntegrationTests : AdminMetricsIntegrationTest() {
    override val clockAt: java.time.Instant = java.time.Instant.parse("2026-10-20T15:30:00Z")
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var submissions: StorySubmissionRepository
    @Autowired private lateinit var stories: StoryRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private lateinit var admin: User
    private lateinit var team: User
    private lateinit var token: String

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.saveAndFlush(User(nickname = UUID.randomUUID().toString()))
        val seed = users.saveAndFlush(User(nickname = "검수 팀"))
        jdbc.update("UPDATE users SET id=987654321 WHERE id=?", seed.id)
        team = users.findById(987654321).orElseThrow()
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun submission(status: SubmissionStatus = SubmissionStatus.PENDING, created: Instant = old,
                           decided: Instant? = if (status == SubmissionStatus.PENDING) null else now,
                           held: Boolean = false, issues: List<ModerationIssue> = emptyList(), owner: User = admin): StorySubmission {
        val storyId = if (status == SubmissionStatus.APPROVED) stories.saveAndFlush(Story(userId = owner.id, title = "검수 fixture")).id else null
        return submissions.saveAndFlush(StorySubmission(userId = owner.id, storyId = storyId, kind = SubmissionKind.CREATE,
            payload = "{}", status = status, createdAt = created, decidedAt = decided, issues = issues,
            heldAt = if (held) now else null, holdReason = if (held) "RETRY_EXHAUSTED" else null,
            errorCode = if (status == SubmissionStatus.FAILED) "AI_FAILED" else null))
    }
    private fun issue(rule: String = "EXPLICIT_SEXUAL_CONTENT", type: String = "TEXT", path: String = "title") = ModerationIssue(path, type, rule, "fixture")
    private fun response(query: String = "", auth: String? = token) = client.get().uri("/api/v1/admin/moderation/metrics$query")
        .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun read(query: String = "") = mapper.readTree(response(query).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `기간 판정은 승인 거절의 합이고 실패와 범위 밖 판정은 분리한다`() {
        repeat(6) { submission(SubmissionStatus.APPROVED) }
        repeat(2) { submission(SubmissionStatus.REJECTED) }
        submission(SubmissionStatus.FAILED)
        submission(SubmissionStatus.REJECTED, decided = old)
        val body = read("?from=2026-10-21&to=2026-10-21")
        assertThat(body["decided"].asLong()).isEqualTo(8)
        assertThat(body["approved"].asLong()).isEqualTo(6)
        assertThat(body["rejected"].asLong()).isEqualTo(2)
        assertThat(body["failed"].asLong()).isEqualTo(1)
        assertThat(body["rejectionRate"].asDouble()).isEqualTo(0.25)
        val d = body["daily"][0]
        assertThat(d["submitted"].asLong()).isZero()
        assertThat(d["approved"].asLong()).isEqualTo(6)
        assertThat(d["rejected"].asLong()).isEqualTo(2)
        assertThat(d["failed"].asLong()).isEqualTo(1)
    }

    @Test fun `현재 보류와 대기는 기간과 무관하고 보류를 대기에 중복 포함하지 않는다`() {
        submission(held = true)
        repeat(3) { submission() }
        submission(SubmissionStatus.APPROVED)
        val b = read("?from=2020-01-01&to=2020-01-01")
        assertThat(b["held"].asLong()).isEqualTo(1)
        assertThat(b["pending"].asLong()).isEqualTo(3)
        val current = read("?days=7")
        assertThat(current["held"]).isEqualTo(b["held"])
        assertThat(current["pending"]).isEqualTo(b["pending"])
    }

    @Test fun `거절 사유는 같은 제출본의 규칙 종류 중복을 제거하고 수 다음 규칙 이름 순이다`() {
        submission(SubmissionStatus.REJECTED, issues = listOf(issue(), issue(path = "description"), issue(type = "IMAGE"), issue("DRUGS")))
        submission(SubmissionStatus.REJECTED, issues = listOf(issue(), issue("HATE_VIOLENCE_INCITEMENT")))
        submission(SubmissionStatus.REJECTED, issues = emptyList())
        submission(SubmissionStatus.APPROVED, issues = listOf(issue("OTHER_APPROVED")))
        submission(SubmissionStatus.FAILED, issues = listOf(issue("OTHER_FAILED")))
        submission(issues = listOf(issue("OTHER_PENDING")))
        submission(SubmissionStatus.REJECTED, decided = old, issues = listOf(issue("OTHER_OLD")))
        val rows = read()["rejectionRules"].toList()
        assertThat(rows.map { it["rule"].asText() }).containsExactly("EXPLICIT_SEXUAL_CONTENT", "DRUGS", "EXPLICIT_SEXUAL_CONTENT", "HATE_VIOLENCE_INCITEMENT")
        assertThat(rows.map { it["type"].asText() }).containsExactly("TEXT", "TEXT", "IMAGE", "TEXT")
        assertThat(rows.map { it["submissions"].asLong() }).containsExactly(2, 1, 1, 1)
    }

    @Test fun `재제출은 이전 판정과 사유를 없애고 처음 제출일만 남긴다`() {
        val created = Instant.parse("2026-10-19T15:00:00Z") // 10-20
        val row = submission(SubmissionStatus.REJECTED, created = created, decided = created, issues = listOf(issue()))
        assertThat(read("?from=2026-10-20&to=2026-10-20")["rejected"].asLong()).isEqualTo(1)
        row.resubmit("{}", now)
        submissions.saveAndFlush(row)
        val historical = read("?from=2026-10-20&to=2026-10-20")
        assertThat(historical["decided"].asLong()).isZero()
        assertThat(historical["rejected"].asLong()).isZero()
        assertThat(historical["rejectionRules"].toList()).isEmpty()
        assertThat(historical["daily"][0]["submitted"].asLong()).isEqualTo(1)
        val today = read("?from=2026-10-21&to=2026-10-21")
        assertThat(today["pending"].asLong()).isEqualTo(1)
        assertThat(today["daily"][0]["submitted"].asLong()).isZero()
    }

    @Test fun `팀 계정 제출본과 팀 소유 스토리 판정도 모든 집계에 포함한다`() {
        submission(SubmissionStatus.APPROVED, created = now, owner = team)
        submission(SubmissionStatus.REJECTED, created = now, issues = listOf(issue()), owner = team)
        submission(SubmissionStatus.FAILED, created = now, owner = team)
        submission(held = true, created = now, owner = team)
        submission(created = now, owner = team)
        val body = read("?from=2026-10-21&to=2026-10-21")
        assertThat(body["approved"].asLong()).isEqualTo(1)
        assertThat(body["rejected"].asLong()).isEqualTo(1)
        assertThat(body["failed"].asLong()).isEqualTo(1)
        assertThat(body["held"].asLong()).isEqualTo(1)
        assertThat(body["pending"].asLong()).isEqualTo(1)
        assertThat(body["daily"][0]["submitted"].asLong()).isEqualTo(5)
        assertThat(body["rejectionRules"][0]["submissions"].asLong()).isEqualTo(1)
    }

    @Test fun `처음 제출과 판정은 각각 KST 날짜와 asOf까지 집계한다`() {
        val before = Instant.parse("2026-10-20T14:59:59.999999Z")
        val midnight = Instant.parse("2026-10-20T15:00:00Z")
        for (at in listOf(before, midnight, now, now.plusNanos(1000)))
            submission(SubmissionStatus.REJECTED, created = at, decided = at, issues = listOf(issue()))
        val yesterday = read("?from=2026-10-20&to=2026-10-20")
        assertThat(yesterday["rejected"].asLong()).isEqualTo(1)
        assertThat(yesterday["daily"][0]["submitted"].asLong()).isEqualTo(1)
        val today = read("?from=2026-10-21&to=2026-10-21")
        assertThat(today["rejected"].asLong()).isEqualTo(2)
        assertThat(today["daily"][0]["submitted"].asLong()).isEqualTo(2)
        assertThat(today["rejectionRules"][0]["submissions"].asLong()).isEqualTo(2)
    }

    @Test fun `모든 날짜를 채우고 판정이 없거나 실패뿐이면 거절률은 null이다`() {
        submission(SubmissionStatus.FAILED)
        val body = read("?from=2026-10-18&to=2026-10-21")
        assertThat(body["daily"].toList().map { it["date"].asText() }).containsExactly("2026-10-18", "2026-10-19", "2026-10-20", "2026-10-21")
        assertThat(body["decided"].asLong()).isZero()
        assertThat(body["rejectionRate"].isNull).isTrue()
        assertThat(body["failed"].asLong()).isEqualTo(1)
        assertThat(body["rejectionRules"].toList()).isEmpty()
        body["daily"].toList().take(3).forEach { d ->
            for (key in listOf("submitted", "approved", "rejected", "failed")) assertThat(d[key].asLong()).isZero()
        }
    }

    @Test fun `기본 기간 기간 오류와 관리자 인증을 차트와 같이 처리한다`() {
        val body = read()
        assertThat(body["from"].asText()).isEqualTo("2026-09-22")
        assertThat(body["to"].asText()).isEqualTo("2026-10-21")
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        assertThat(body["daily"].toList()).hasSize(30)
        for (days in listOf(7, 30, 90)) assertThat(read("?days=$days")["daily"].toList()).hasSize(days)
        assertThat(read("?from=2025-10-21&to=2026-10-21")["daily"].toList()).hasSize(366)
        for (q in listOf("days=10", "days=", "from=bad&to=bad", "from=2026-10-21", "to=2026-10-21",
            "from=2026-10-22&to=2026-10-22", "from=2025-10-20&to=2026-10-21", "from=2026-10-21&to=2026-10-20",
            "days=7&from=2026-10-20&to=2026-10-21")) response("?$q").expectStatus().isBadRequest
        response(auth = null).expectStatus().isUnauthorized
        response(auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
    }
}
