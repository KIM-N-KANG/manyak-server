package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.dto.CreditTransactionPageResponse
import com.knk.manyak.credit.entity.CreditLot
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditLotRepository
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * GET /api/v1/users/me/credits/transactions 통합 검증(KNK-1044).
 *
 * 원장(credit_transactions)을 직접 시드해 분류(type)·제목 역참조·만료일·커서 페이징을 고정한다.
 * 원장 행을 서비스가 아니라 리포지토리로 넣는 이유는 createdAt·로트 만료일을 테스트가 정확히 통제해야
 * 하기 때문이다(소멸 행의 createdAt ≠ 실제 만료일 같은 케이스는 실제 흐름으로는 재현하기 어렵다).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditTransactionHistoryIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var lotRepository: CreditLotRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var sessionRepository: StoryCreationSessionRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private val base: Instant = Instant.parse("2026-08-30T10:00:00Z")

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(): User =
        userRepository.save(User(nickname = "내역유저", status = UserStatus.ACTIVE))

    private fun token(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun tx(
        userId: Long,
        amount: Long,
        reason: CreditReason,
        refType: String? = null,
        refId: Long? = null,
        createdAt: Instant = base,
    ): CreditTransaction = transactionRepository.save(
        CreditTransaction(
            userId = userId,
            amount = amount,
            reason = reason,
            refType = refType,
            refId = refId,
            idempotencyKey = if (reason == CreditReason.CHAT_TURN || reason == CreditReason.STORY_CREATION) {
                null
            } else {
                "seed:${UUID.randomUUID()}"
            },
            createdAt = createdAt,
        ),
    )

    private fun lot(userId: Long, transactionId: Long?, expiresAt: Instant?, amount: Long = 100): CreditLot =
        lotRepository.save(
            CreditLot(
                userId = userId,
                transactionId = transactionId,
                originalAmount = amount,
                remaining = amount,
                expiresAt = expiresAt,
                createdAt = base,
            ),
        )

    private fun story(userId: Long, title: String, deleted: Boolean = false): Story =
        storyRepository.save(
            Story(userId = userId, title = title, deletedAt = if (deleted) base else null),
        )

    private fun chat(userId: Long, storyId: Long): StoryChat =
        storyChatRepository.save(StoryChat(userId = userId, storyId = storyId))

    private fun session(userId: Long, storyId: Long?): StoryCreationSession =
        sessionRepository.save(
            StoryCreationSession(
                userId = userId,
                storyId = storyId,
                status = StoryCreationSessionStatus.STORY_CREATED,
            ),
        )

    private fun get(user: User, query: String = ""): CreditTransactionPageResponse =
        restTestClient.get()
            .uri("/api/v1/users/me/credits/transactions$query")
            .header("Authorization", token(user))
            .exchange()
            .expectStatus().isOk
            .expectBody(CreditTransactionPageResponse::class.java)
            .returnResult().responseBody!!

    @Test
    fun `토큰 없이 이용내역을 조회하면 401이다`() {
        restTestClient.get().uri("/api/v1/users/me/credits/transactions")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        restTestClient.get().uri("/api/v1/users/me/credits/transactions")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(UUID.randomUUID())}")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `원장이 비면 빈 목록과 null 커서를 반환한다`() {
        val page = get(saveUser())

        assertThat(page.items).isEmpty()
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `적립과 소모가 최신순으로 나오고 type이 각각 EARN SPEND다`() {
        val user = saveUser()
        tx(user.id, 250, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, -10, CreditReason.CHAT_TURN, createdAt = base.plusSeconds(60))

        val items = get(user).items

        assertThat(items.map { it.type.name }).containsExactly("SPEND", "EARN")
        assertThat(items.map { it.reason }).containsExactly(CreditReason.CHAT_TURN, CreditReason.ATTENDANCE_REWARD)
        assertThat(items.map { it.amount }).containsExactly(-10L, 250L)
    }

    @Test
    fun `type 필터는 해당 분류 행만 반환한다`() {
        val user = saveUser()
        tx(user.id, 250, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, -20, CreditReason.STORY_CREATION, createdAt = base.plusSeconds(10))
        val expireLot = lot(user.id, transactionId = null, expiresAt = base.minusSeconds(3600))
        tx(user.id, -700, CreditReason.EXPIRE, "CREDIT_LOT", expireLot.id, base.plusSeconds(20))

        assertThat(get(user, "?type=SPEND").items.map { it.reason })
            .containsExactly(CreditReason.STORY_CREATION)
        assertThat(get(user, "?type=EARN").items.map { it.reason })
            .containsExactly(CreditReason.ATTENDANCE_REWARD)
        assertThat(get(user, "?type=EXPIRE").items.map { it.reason })
            .containsExactly(CreditReason.EXPIRE)
        assertThat(get(user, "?type=ALL").items).hasSize(3)
    }

    @Test
    fun `REFUND는 EARN으로 분류된다`() {
        val user = saveUser()
        tx(user.id, 10, CreditReason.REFUND, "CHAT", 1L)

        val items = get(user, "?type=EARN").items

        assertThat(items).hasSize(1)
        assertThat(items[0].type.name).isEqualTo("EARN")
        assertThat(items[0].reason).isEqualTo(CreditReason.REFUND)
    }

    @Test
    fun `PURCHASE는 ALL에도 나오지 않는다`() {
        val user = saveUser()
        tx(user.id, 1000, CreditReason.PURCHASE)
        tx(user.id, 250, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))

        assertThat(get(user, "?type=ALL").items.map { it.reason })
            .containsExactly(CreditReason.ATTENDANCE_REWARD)
        assertThat(get(user, "?type=EARN").items.map { it.reason })
            .containsExactly(CreditReason.ATTENDANCE_REWARD)
    }

    @Test
    fun `채팅 턴은 채팅이 속한 스토리 제목을 붙인다`() {
        val user = saveUser()
        val story = story(user.id, "루멘시아 아카데미")
        val chat = chat(user.id, story.id)
        tx(user.id, -10, CreditReason.CHAT_TURN, "CHAT", chat.id)

        assertThat(get(user).items.single().title).isEqualTo("루멘시아 아카데미")
    }

    @Test
    fun `제작 소모는 제작 세션을 거쳐 스토리 제목을 붙인다`() {
        val user = saveUser()
        val story = story(user.id, "심해의 등대")
        val session = session(user.id, story.id)
        // ref_id에 담긴 값이 스토리 PK가 아니라 세션 PK임을 고정한다(세션 id와 스토리 id가 겹치면 검증이 무의미).
        val decoy = story(user.id, "엉뚱한 스토리")
        assertThat(decoy.id).isNotEqualTo(story.id)
        tx(user.id, -20, CreditReason.STORY_CREATION, "STORY", session.id)

        assertThat(get(user).items.single().title).isEqualTo("심해의 등대")
    }

    @Test
    fun `삭제된 스토리와 연결 끊긴 행은 title이 null이다`() {
        val user = saveUser()
        val deleted = story(user.id, "지워진 스토리", deleted = true)
        val chat = chat(user.id, deleted.id)
        tx(user.id, -10, CreditReason.CHAT_TURN, "CHAT", chat.id, base)
        // 스토리를 만들지 못하고 끝난 세션(story_id NULL)
        tx(user.id, -20, CreditReason.STORY_CREATION, "STORY", session(user.id, null).id, base.plusSeconds(10))
        // 존재하지 않는 채팅을 가리키는 행
        tx(user.id, -10, CreditReason.CHAT_TURN, "CHAT", 999_999L, base.plusSeconds(20))

        assertThat(get(user).items.map { it.title }).containsExactly(null, null, null)
    }

    @Test
    fun `획득 행은 그 적립분의 로트 만료일을 내려준다`() {
        val user = saveUser()
        val earn = tx(user.id, 250, CreditReason.ATTENDANCE_REWARD)
        val expiresAt = base.plusSeconds(30 * 24 * 3600L)
        lot(user.id, transactionId = earn.id, expiresAt = expiresAt)

        val item = get(user).items.single()

        assertThat(item.type.name).isEqualTo("EARN")
        assertThat(item.expiresAt).isEqualTo(expiresAt)
    }

    @Test
    fun `소멸 행은 createdAt이 아니라 로트의 실제 만료일을 내려준다`() {
        val user = saveUser()
        val expiredAt = Instant.parse("2026-08-06T00:00:00Z")
        val recordedAt = Instant.parse("2026-08-12T03:11:00Z")
        val expiredLot = lot(user.id, transactionId = null, expiresAt = expiredAt)
        tx(user.id, -700, CreditReason.EXPIRE, "CREDIT_LOT", expiredLot.id, recordedAt)

        val item = get(user).items.single()

        assertThat(item.type.name).isEqualTo("EXPIRE")
        assertThat(item.createdAt).isEqualTo(recordedAt)
        assertThat(item.expiresAt).isEqualTo(expiredAt)
    }

    @Test
    fun `소모 행의 expiresAt은 null이다`() {
        val user = saveUser()
        tx(user.id, -10, CreditReason.CHAT_TURN, "CHAT", 1L)

        assertThat(get(user).items.single().expiresAt).isNull()
    }

    @Test
    fun `커서 페이징은 겹치지도 빠뜨리지도 않는다`() {
        val user = saveUser()
        // 같은 createdAt 경계를 포함시켜 단일 시각 커서로는 갈라지지 않는 구간을 만든다.
        tx(user.id, 1, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, 2, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))
        tx(user.id, 3, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))
        tx(user.id, 4, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(20))

        val first = get(user, "?limit=2")
        assertThat(first.items).hasSize(2)
        assertThat(first.nextCursor).isNotNull()

        val second = get(user, "?limit=2&cursor=${first.nextCursor}")
        assertThat(second.items).hasSize(2)

        val amounts = (first.items + second.items).map { it.amount }
        assertThat(amounts).containsExactly(4L, 3L, 2L, 1L)

        // 총 건수가 limit의 배수라도 빈 페이지를 한 번 더 요청하게 두지 않는다(limit+1 판정).
        assertThat(second.nextCursor).isNull()
    }

    @Test
    fun `밀리초 안에서 갈라지는 페이지 경계에서도 행을 빠뜨리지 않는다`() {
        val user = saveUser()
        // 같은 밀리초(.123) 안에서 마이크로초만 다른 네 행. 페이지 경계를 .123002 에 두면, 커서를 밀리초로
        // 절삭했을 때 .123000 < created_at < .123002 인 행(amount=2)이 `< C`에도 `= C`에도 걸리지 않고 사라진다.
        val ms = Instant.parse("2026-08-30T10:00:00Z").plusNanos(123_000_000L)
        tx(user.id, 1, CreditReason.ATTENDANCE_REWARD, createdAt = ms)
        tx(user.id, 2, CreditReason.ATTENDANCE_REWARD, createdAt = ms.plusNanos(1_000L))
        tx(user.id, 3, CreditReason.ATTENDANCE_REWARD, createdAt = ms.plusNanos(2_000L))
        tx(user.id, 4, CreditReason.ATTENDANCE_REWARD, createdAt = ms.plusNanos(3_000L))

        // 시드가 실제로 같은 밀리초 안에서 갈라져 있어야 이 검증이 의미가 있다(H2는 마이크로초까지 보존한다).
        val stored = transactionRepository.findAll().filter { it.userId == user.id }.map { it.createdAt }
        assertThat(stored.distinct()).hasSize(4)
        assertThat(stored.map { it.toEpochMilli() }.distinct()).hasSize(1)

        val first = get(user, "?limit=2")
        assertThat(first.items.map { it.amount }).containsExactly(4L, 3L)
        assertThat(first.nextCursor).isNotNull()

        val second = get(user, "?limit=2&cursor=${first.nextCursor}")
        assertThat(second.items.map { it.amount }).containsExactly(2L, 1L)
        assertThat(second.nextCursor).isNull()
    }

    @Test
    fun `조회 결과가 limit에 못 미치면 nextCursor는 null이다`() {
        val user = saveUser()
        tx(user.id, 1, CreditReason.ATTENDANCE_REWARD)

        assertThat(get(user, "?limit=50").nextCursor).isNull()
    }

    @Test
    fun `다음 페이지가 있을 때만 nextCursor를 채운다`() {
        val user = saveUser()
        tx(user.id, 1, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, 2, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))

        // 마지막 페이지를 정확히 채웠어도 뒤에 아무것도 없으면 커서를 주지 않는다.
        assertThat(get(user, "?limit=2").nextCursor).isNull()
        assertThat(get(user, "?limit=1").nextCursor).isNotNull()
    }

    @Test
    fun `잘못된 커서는 400이다`() {
        val user = saveUser()

        restTestClient.get().uri("/api/v1/users/me/credits/transactions?cursor=not-a-cursor")
            .header("Authorization", token(user))
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `커서를 디코딩해도 원장 id가 드러나지 않는다`() {
        val user = saveUser()
        val first = tx(user.id, 1, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, 2, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))

        val cursor = get(user, "?limit=1").nextCursor
        assertThat(cursor).isNotNull()

        // 커서가 가리키는 행은 2번째 행이지만, 원장 id가 평문으로 남지 않는지 두 id 모두로 확인한다.
        val plain = String(Base64.getUrlDecoder().decode(cursor), Charsets.ISO_8859_1)
        assertThat(plain).doesNotContain("${first.id}")
        assertThat(plain).doesNotContain("${first.id + 1}")
        // 초 단위 시각도 그대로 드러나면 안 된다(봉인 대상은 (createdAt, id) 쌍 전체다).
        assertThat(plain).doesNotContain("${base.epochSecond}")
    }

    @Test
    fun `변조된 커서는 400이다`() {
        val user = saveUser()
        tx(user.id, 1, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, 2, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))

        val cursor = get(user, "?limit=1").nextCursor!!
        // 마지막 글자를 바꿔 인증 태그를 깨뜨린다. GCM이 위조를 잡아 복호화 자체가 실패해야 한다.
        val tampered = cursor.dropLast(1) + if (cursor.last() == 'A') 'B' else 'A'

        restTestClient.get().uri("/api/v1/users/me/credits/transactions?cursor=$tampered")
            .header("Authorization", token(user))
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `같은 위치의 커서라도 매번 다른 문자열이지만 같은 페이지를 준다`() {
        val user = saveUser()
        tx(user.id, 1, CreditReason.ATTENDANCE_REWARD, createdAt = base)
        tx(user.id, 2, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(10))
        tx(user.id, 3, CreditReason.ATTENDANCE_REWARD, createdAt = base.plusSeconds(20))

        val one = get(user, "?limit=1").nextCursor!!
        val two = get(user, "?limit=1").nextCursor!!

        // IV가 매번 새로 뽑히므로 같은 (createdAt, id)를 가리켜도 문자열은 달라진다.
        assertThat(one).isNotEqualTo(two)
        // 그래도 둘 다 같은 위치를 복원해 같은 다음 페이지를 준다.
        assertThat(get(user, "?limit=2&cursor=$one").items.map { it.amount })
            .isEqualTo(get(user, "?limit=2&cursor=$two").items.map { it.amount })
            .containsExactly(2L, 1L)
    }

    @Test
    fun `다른 회원의 원장은 보이지 않는다`() {
        val me = saveUser()
        val other = saveUser()
        tx(other.id, 250, CreditReason.ATTENDANCE_REWARD)

        assertThat(get(me).items).isEmpty()
    }
}
