package com.knk.manyak.migration.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResult
import com.knk.manyak.migration.dto.MigrationStatus
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * GuestDataMigrationService의 클레임 계약을 고정한다(저장소는 mock, 소유권 판정만 검증).
 *
 * - 스냅샷 소유권: 요청자 소유 → ALREADY_OWNED, 타인 소유 → CONFLICT, 부재/삭제 → NOT_FOUND.
 * - 게스트(NULL) 행은 원자적 조건부 클레임([StoryRepository.claimByPublicId])으로 처리: 1이면 MIGRATED, 0(동시 선점)이면 CONFLICT.
 * - 같은 요청 내 중복 ID는 두 번째부터 ALREADY_OWNED. 스토리·채팅은 독립 처리하며, UUID 형식 오류는 400.
 */
class GuestDataMigrationServiceTest {

    private val storyRepository: StoryRepository = mock(StoryRepository::class.java)
    private val storyChatRepository: StoryChatRepository = mock(StoryChatRepository::class.java)
    private val storyCreationSessionRepository: StoryCreationSessionRepository =
        mock(StoryCreationSessionRepository::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val userStoryEndingReachRepository: com.knk.manyak.story.repository.UserStoryEndingReachRepository =
        mock(com.knk.manyak.story.repository.UserStoryEndingReachRepository::class.java)
    private val serverAnalytics: com.knk.manyak.global.observability.analytics.ServerAnalytics =
        mock(com.knk.manyak.global.observability.analytics.ServerAnalytics::class.java)
    private val service =
        GuestDataMigrationService(
            storyRepository, storyChatRepository, storyCreationSessionRepository, userRepository,
            userStoryEndingReachRepository, serverAnalytics,
        )

    private val userId = 10L
    private val otherUserId = 99L

    /** 기본은 아직 이관하지 않은(잠금 해제) 계정. 잠금 게이트 테스트는 이 stub을 잠긴 계정으로 덮어쓴다. */
    @BeforeEach
    fun stubUnlockedUser() {
        // 게이트는 동시성 직렬화를 위해 비관적 락 조회(findByIdForUpdate)를 쓴다.
        `when`(userRepository.findByIdForUpdate(userId)).thenReturn(user(migratedAt = null))
    }

    private fun user(migratedAt: Instant?) = User(id = userId, nickname = "me", migratedAt = migratedAt)

    private fun story(publicId: UUID, ownerId: Long?) =
        Story(publicId = publicId, userId = ownerId, title = "제목")

    private fun chat(publicId: UUID, ownerId: Long?) =
        StoryChat(publicId = publicId, userId = ownerId, storyId = 1L)

    @Test
    fun `게스트 스토리는 원자적 클레임에 성공하면 MIGRATED다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, null)))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(1)

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.MIGRATED))
        assertThat(result.chats).isEmpty()
    }

    @Test
    fun `게스트 스토리를 타인이 동시 선점하면(클레임 0건, 타인 소유) CONFLICT다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, null)))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(0)
        `when`(storyRepository.findUserIdByPublicId(pid)).thenReturn(otherUserId)

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.CONFLICT))
    }

    @Test
    fun `같은 유저의 동시 요청으로 선점됐으면(클레임 0건, 요청자 소유) ALREADY_OWNED다`() {
        // 같은 유저가 동시에 두 번 마이그레이션 → 한쪽이 먼저 소유. 나머지는 CONFLICT가 아니라 멱등하게 ALREADY_OWNED여야 한다.
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, null)))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(0)
        `when`(storyRepository.findUserIdByPublicId(pid)).thenReturn(userId)

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.ALREADY_OWNED))
    }

    @Test
    fun `이미 요청자 소유인 스토리는 ALREADY_OWNED다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, userId)))

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.ALREADY_OWNED))
    }

    @Test
    fun `타인 소유 스토리는 CONFLICT다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, otherUserId)))

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.CONFLICT))
    }

    @Test
    fun `존재하지 않거나 삭제된 스토리는 NOT_FOUND다`() {
        // 저장소가 (삭제 제외 조회에서) 아무것도 돌려주지 않으면 부재로 본다.
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(emptyList())

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.NOT_FOUND))
    }

    @Test
    fun `같은 요청에 중복된 게스트 ID는 한 번만 클레임하고 두 번째는 ALREADY_OWNED다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, null)))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(1)

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString(), pid.toString())))

        assertThat(result.stories).containsExactly(
            MigrationResult(pid.toString(), MigrationStatus.MIGRATED),
            MigrationResult(pid.toString(), MigrationStatus.ALREADY_OWNED),
        )
        verify(storyRepository, times(1)).claimByPublicId(pid, userId)
    }

    @Test
    fun `채팅도 스토리와 동일 규칙으로 독립 이관된다`() {
        val guestPid = UUID.randomUUID()
        val othersPid = UUID.randomUUID()
        `when`(storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection()))
            .thenReturn(listOf(chat(guestPid, null), chat(othersPid, otherUserId)))
        `when`(storyChatRepository.claimByPublicId(guestPid, userId)).thenReturn(1)

        val result = service.migrate(
            userId,
            MigrationRequest(chatIds = listOf(guestPid.toString(), othersPid.toString())),
        )

        assertThat(result.chats).containsExactly(
            MigrationResult(guestPid.toString(), MigrationStatus.MIGRATED),
            MigrationResult(othersPid.toString(), MigrationStatus.CONFLICT),
        )
        assertThat(result.stories).isEmpty()
    }

    @Test
    fun `스토리와 채팅을 한 요청에서 함께 이관한다`() {
        val storyPid = UUID.randomUUID()
        val chatPid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(storyPid, null)))
        `when`(storyRepository.claimByPublicId(storyPid, userId)).thenReturn(1)
        `when`(storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(chat(chatPid, null)))
        `when`(storyChatRepository.claimByPublicId(chatPid, userId)).thenReturn(1)

        val result = service.migrate(
            userId,
            MigrationRequest(storyIds = listOf(storyPid.toString()), chatIds = listOf(chatPid.toString())),
        )

        assertThat(result.stories).containsExactly(MigrationResult(storyPid.toString(), MigrationStatus.MIGRATED))
        assertThat(result.chats).containsExactly(MigrationResult(chatPid.toString(), MigrationStatus.MIGRATED))
    }

    @Test
    fun `스토리를 MIGRATED하면 연결된 생성 세션도 클레임한다`() {
        val pid = UUID.randomUUID()
        val migratedStory = Story(id = 77L, publicId = pid, userId = null, title = "제목")
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(migratedStory))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(1)

        service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        verify(storyCreationSessionRepository).claimByStoryId(77L, userId)
    }

    @Test
    fun `이미 소유했거나 충돌한 스토리는 세션을 클레임하지 않는다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, userId)))

        service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        verify(storyCreationSessionRepository, never()).claimByStoryId(anyLong(), anyLong())
    }

    @Test
    fun `UUID 형식이 잘못되면 400이다`() {
        assertThatThrownBy {
            service.migrate(userId, MigrationRequest(storyIds = listOf("not-a-uuid")))
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("400 BAD_REQUEST")
    }

    @Test
    fun `빈 요청은 빈 결과를 반환한다`() {
        val result = service.migrate(userId, MigrationRequest())

        assertThat(result.stories).isEmpty()
        assertThat(result.chats).isEmpty()
        assertThat(result.migrationClosed).isFalse()
    }

    // ---- 이관 1회 잠금 게이트(스펙 §4-3-5, KNK-480) ----

    @Test
    fun `이미 이관한 계정은 평가 없이 migrationClosed와 빈 결과를 반환한다`() {
        // migrated_at이 있으면(계정 잠김) 어떤 저장소도 건드리지 않고 닫힌 응답을 준다.
        `when`(userRepository.findByIdForUpdate(userId)).thenReturn(user(migratedAt = Instant.now()))
        val pid = UUID.randomUUID()

        val result = service.migrate(
            userId,
            MigrationRequest(storyIds = listOf(pid.toString()), chatIds = listOf(pid.toString())),
        )

        assertThat(result.migrationClosed).isTrue()
        assertThat(result.stories).isEmpty()
        assertThat(result.chats).isEmpty()
        verifyNoInteractions(storyRepository, storyChatRepository, storyCreationSessionRepository)
    }

    @Test
    fun `한 건이라도 MIGRATED면 계정을 잠근다(migratedAt 기록)`() {
        val loaded = user(migratedAt = null)
        `when`(userRepository.findByIdForUpdate(userId)).thenReturn(loaded)
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, null)))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(1)

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.migrationClosed).isFalse()
        assertThat(loaded.migratedAt).isNotNull()
    }

    @Test
    fun `한 건도 MIGRATED가 아니면 계정을 잠그지 않는다(migratedAt 유지)`() {
        val loaded = user(migratedAt = null)
        `when`(userRepository.findByIdForUpdate(userId)).thenReturn(loaded)
        val pid = UUID.randomUUID()
        // 이미 요청자 소유(ALREADY_OWNED)라 이번 요청으로 새로 얻은 소유권이 없다.
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, userId)))

        service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(loaded.migratedAt).isNull()
    }
}
