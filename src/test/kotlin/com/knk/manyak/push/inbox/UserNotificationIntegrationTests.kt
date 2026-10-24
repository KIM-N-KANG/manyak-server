package com.knk.manyak.push.inbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserNotificationIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private lateinit var user: User
    private lateinit var other: User
    private val path = "/api/v1/users/me/notifications"

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        user = users.save(User(nickname = "알림회원", status = UserStatus.ACTIVE))
        other = users.save(User(nickname = "다른회원", status = UserStatus.ACTIVE))
    }

    @Test fun `공지 내역 응답에는 noticeTag가 있고 다른 종류는 null이다`() {
        val id = seed(category = "NEWS")
        jdbc.update("UPDATE user_notifications SET notice_tag='UPDATE' WHERE public_id=?", id)
        get().expectStatus().isOk.expectBody().jsonPath("$.items[0].noticeTag").isEqualTo("UPDATE")
        seed(category = "ACTIVITY", at = Instant.now().plusSeconds(1))
        get().expectStatus().isOk.expectBody().jsonPath("$.items[0].noticeTag").isEmpty
    }

    @Test fun `빈 알림함과 안 읽은 수는 200이다`() {
        get().expectStatus().isOk.expectBody().jsonPath("$.items").isEmpty.jsonPath("$.nextCursor").isEmpty
        counts(0, 0, 0)
    }

    @Test fun `기본 20건과 UUID 커서로 같은 시각의 25건을 누락 없이 끝까지 읽는다`() {
        val ids = (1..25).map { seed() }.reversed()
        seed(other)
        val first = get().expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(20)
        ids.take(20).forEachIndexed { i, id -> first.jsonPath("$.items[$i].notificationId").isEqualTo(id.toString()) }
        first.jsonPath("$.nextCursor").isEqualTo(ids[19].toString())
        val second = get("?cursor=${ids[19]}").expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(5).jsonPath("$.nextCursor").isEmpty
        ids.drop(20).forEachIndexed { i, id -> second.jsonPath("$.items[$i].notificationId").isEqualTo(id.toString()) }
    }

    @Test fun `분류 필터와 오래된 내역과 한도 보정을 적용한다`() {
        val old = seed(category = "NEWS", at = Instant.now().minusSeconds(400L * 86400))
        repeat(2) { seed(category = "ACTIVITY") }
        get("?category=NEWS").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].notificationId").isEqualTo(old.toString())
        get("?category=ACTIVITY").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(2)
        get("?limit=0").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(1)
        repeat(50) { seed() }
        get("?limit=100").expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(50)
    }

    @Test fun `커서와 쿼리 오류는 400이고 다른 회원 내역은 노출하지 않는다`() {
        val foreign = seed(other)
        listOf("?cursor=bad", "?cursor=$foreign", "?cursor=${UUID.randomUUID()}", "?category=ALL", "?limit=abc").forEach {
            get(it).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST")
        }
        post("/read-all?category=ALL").expectStatus().isBadRequest
        get().expectStatus().isOk.expectBody().jsonPath("$.items").isEmpty
        counts(0, 0, 0)
    }

    @Test fun `개별 읽음과 분류별 모두 읽음은 최초 시각을 보존한다`() {
        val read = seed()
        val news = seed(category = "NEWS")
        repeat(2) { seed() }
        seed(other)
        post("/$read/read").expectStatus().isNoContent
        val first = readAt(read)
        assertThat(first).isNotNull()
        post("/$read/read").expectStatus().isNoContent
        assertThat(readAt(read)).isEqualTo(first)
        counts(3, 1, 2)
        post("/read-all?category=NEWS").expectStatus().isNoContent
        val newsReadAt = readAt(news)
        counts(2, 0, 2)
        post("/read-all").expectStatus().isNoContent
        post("/read-all").expectStatus().isNoContent
        counts(0, 0, 0)
        assertThat(readAt(read)).isEqualTo(first)
        assertThat(readAt(news)).isEqualTo(newsReadAt)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE user_id = ? AND read_at IS NULL", Long::class.java, other.id)).isEqualTo(1)
        seed()
        counts(1, 0, 1)
    }

    @Test fun `읽음의 타인과 미존재는 동일한 404이고 잘못된 UUID는 400이다`() {
        listOf(seed(other), UUID.randomUUID()).forEach {
            post("/$it/read").expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND")
        }
        post("/bad/read").expectStatus().isBadRequest
    }

    @Test fun `네 API는 미인증과 탈퇴를 401 정지를 403으로 거절한다`() {
        val id = seed()
        listOf(UserStatus.SUSPENDED to 403, UserStatus.DELETED to 401).forEach { (status, code) ->
            user.status = status
            users.saveAndFlush(user)
            get().expectStatus().isEqualTo(code)
            get("/unread-count").expectStatus().isEqualTo(code)
            post("/$id/read").expectStatus().isEqualTo(code)
            post("/read-all").expectStatus().isEqualTo(code)
        }
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        client.get().uri("$path/unread-count").exchange().expectStatus().isUnauthorized
        client.post().uri("$path/$id/read").exchange().expectStatus().isUnauthorized
        client.post().uri("$path/read-all").exchange().expectStatus().isUnauthorized
    }

    @Test fun `탈퇴하면 본인 내역만 삭제한다`() {
        seed()
        val foreign = seed(other)
        client.delete().uri("/api/v1/users/me").header("Authorization", bearer()).exchange().expectStatus().isNoContent
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE user_id = ?", Long::class.java, user.id)).isZero()
        assertThat(jdbc.queryForObject("SELECT public_id FROM user_notifications", UUID::class.java)).isEqualTo(foreign)
    }

    private fun seed(owner: User = user, category: String = "ACTIVITY", at: Instant = Instant.parse("2026-10-24T00:00:00Z")): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO user_notifications(public_id, user_id, message_key, type, category, title, body, created_at)
            VALUES (?, ?, ?, ?, ?, '제목', '본문', ?)""", id, owner.id, id.toString(), if (category == "NEWS") "NOTICE" else "STORY_COMPLETED", category, Timestamp.from(at))
        return id
    }
    private fun bearer() = "Bearer ${jwt.issueAccessToken(user.publicId)}"
    private fun get(suffix: String = "") = client.get().uri(path + suffix).header("Authorization", bearer()).exchange()
    private fun post(suffix: String) = client.post().uri(path + suffix).header("Authorization", bearer()).exchange()
    private fun readAt(id: UUID) = jdbc.queryForObject("SELECT read_at FROM user_notifications WHERE public_id = ?", Timestamp::class.java, id)
    private fun counts(total: Int, news: Int, activity: Int) {
        get("/unread-count").expectStatus().isOk.expectBody().jsonPath("$.total").isEqualTo(total)
            .jsonPath("$.news").isEqualTo(news).jsonPath("$.activity").isEqualTo(activity)
    }
}
