package com.knk.manyak.migration.service

import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResult
import com.knk.manyak.migration.dto.MigrationStatus
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
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
    private val service = GuestDataMigrationService(storyRepository, storyChatRepository)

    private val userId = 10L
    private val otherUserId = 99L

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
    fun `게스트 스토리라도 동시 선점되면(클레임 0건) CONFLICT다`() {
        val pid = UUID.randomUUID()
        `when`(storyRepository.findAllByPublicIdInAndDeletedAtIsNull(anyCollection())).thenReturn(listOf(story(pid, null)))
        `when`(storyRepository.claimByPublicId(pid, userId)).thenReturn(0)

        val result = service.migrate(userId, MigrationRequest(storyIds = listOf(pid.toString())))

        assertThat(result.stories).containsExactly(MigrationResult(pid.toString(), MigrationStatus.CONFLICT))
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
    }
}
