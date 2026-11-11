package com.knk.manyak.search.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.search.config.StorySearchConfig
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryLike
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryLikeRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.MockedBoundaryIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * 검색 문서의 직렬화 결과를 글자 단위로 고정한다(STORY 4-1-27). 묶음 읽기로 바꿔도(KNK-2078) 단건과 같은 문서가
 * 나와야 하므로, 문서를 만드는 분기(이름 토큰 치환, 장르 토막, 인물 순서, 표지 검수, 게스트, 비공개, 삭제, 집계)를
 * 한 픽스처에 모아 운영 OpenSearch 클라이언트와 같은 매퍼로 직렬화한 문자열을 비교한다.
 */
class StorySearchDocumentReaderIntegrationTests : MockedBoundaryIntegrationTest() {
    @Autowired lateinit var reader: StorySearchDocumentReader
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var characters: StoryCharacterRepository
    @Autowired lateinit var chats: StoryChatRepository
    @Autowired lateinit var likes: StoryLikeRepository
    @Autowired lateinit var cleaner: DatabaseCleaner

    private val mapper = StorySearchConfig.searchJsonMapper().objectMapper()
    private val createdAt = Instant.parse("2026-11-01T03:04:05.678Z")
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-00000000000$n")
    private fun json(storyId: Long): String? = reader.read(storyId)?.let(mapper::writeValueAsString)

    @BeforeEach fun clean() = cleaner.cleanAll()

    @Test fun `회원 공개 스토리는 치환한 제목과 소개, 장르, 인물 순서, 검수 통과 표지, 작성자, 집계를 싣는다`() {
        val owner = users.save(User(nickname = "작가 원본"))
        val fans = (0 until 3).map { users.save(User(nickname = "독자$it")) }
        val story = stories.save(Story(publicId = id(1), userId = owner.id, title = "{username}의 약속", protagonistName = "서린",
            createdAt = createdAt).apply {
            oneLineIntro = "{username}이 돌아온다"
            genre = "판타지, 미스터리,,  "
            thumbnailImageUrl = "https://cdn.example/a.png"
            thumbnailImageKey = "covers/a.png"
            thumbnailModerationStatus = ImageModerationStatus.APPROVED
        })
        // 인물은 이름 순서와 저장(id) 순서가 반대가 되게 둔다. 문서는 id 순서다.
        characters.save(StoryCharacter(story = story, name = "하람"))
        characters.save(StoryCharacter(story = story, name = "가온"))
        // 턴 합계는 current + hidden + regenerated - forkedFrom이고 삭제된 채팅도 포함한다.
        // 포크 채팅으로 차감 항을, 삭제된 채팅으로 포함 규칙을 고정한다: (5 + 2 + 1) + (6 - 2) + 3 = 15.
        chats.save(StoryChat(userId = fans[0].id, storyId = story.id).apply { currentTurn = 5; hiddenTurnCount = 2; regeneratedCount = 1 })
        chats.save(StoryChat(userId = fans[1].id, storyId = story.id, forkedFromTurn = 2).apply { currentTurn = 6 })
        chats.save(StoryChat(userId = fans[2].id, storyId = story.id).apply { currentTurn = 3; deletedAt = Instant.parse("2026-11-02T00:00:00Z") })
        fans.forEach { likes.save(StoryLike(userId = it.id, storyId = story.id)) }

        assertThat(json(story.id)).isEqualTo(
            """{"publicId":"${id(1)}","title":"서린의 약속","oneLineIntro":"서린이 돌아온다","genres":["판타지","미스터리"],""" +
                """"characterNames":["하람","가온"],"thumbnailUrlSm":"https://cdn.example/a.png",""" +
                """"author":{"id":null,"nickname":"작가 원본"},"turnCount":15,"likeCount":3,"createdAt":1793502245678,"visible":true}""",
        )
    }

    @Test fun `검수 대기 표지는 프리셋 키 URL로 바꾸고 없는 값은 빈 문자열과 빈 목록과 0이다`() {
        val owner = users.save(User(nickname = "작가"))
        val story = stories.save(Story(publicId = id(2), userId = owner.id, title = "숨은 표지", createdAt = createdAt).apply {
            thumbnailImageUrl = "https://cdn.example/b.png"
            thumbnailImageKey = "covers/b.png"
            thumbnailModerationStatus = ImageModerationStatus.PENDING
        })

        assertThat(json(story.id)).isEqualTo(
            """{"publicId":"${id(2)}","title":"숨은 표지","oneLineIntro":"","genres":[],"characterNames":[],""" +
                """"thumbnailUrlSm":"https://cdn.test/thumbnails/covers/b.png_sm.png","author":{"id":null,"nickname":"작가"},""" +
                """"turnCount":0,"likeCount":0,"createdAt":1793502245678,"visible":true}""",
        )
    }

    @Test fun `게스트 스토리는 작성자가 null이다`() {
        val story = stories.save(Story(publicId = id(3), userId = null, title = "게스트", createdAt = createdAt))

        assertThat(json(story.id)).isEqualTo(
            """{"publicId":"${id(3)}","title":"게스트","oneLineIntro":"","genres":[],"characterNames":[],"thumbnailUrlSm":null,""" +
                """"author":null,"turnCount":0,"likeCount":0,"createdAt":1793502245678,"visible":true}""",
        )
    }

    @Test fun `비공개와 소프트 삭제 스토리도 문서를 만들고 visible이 false이며 없는 스토리는 문서가 없다`() {
        val owner = users.save(User(nickname = "작가"))
        val privateStory = stories.save(Story(publicId = id(4), userId = owner.id, title = "비공개",
            visibility = StoryVisibility.PRIVATE, createdAt = createdAt))
        val deleted = stories.save(Story(publicId = id(5), userId = owner.id, title = "삭제", createdAt = createdAt).apply {
            deletedAt = Instant.parse("2026-11-02T00:00:00Z")
        })

        assertThat(json(privateStory.id)).isEqualTo(
            """{"publicId":"${id(4)}","title":"비공개","oneLineIntro":"","genres":[],"characterNames":[],"thumbnailUrlSm":null,""" +
                """"author":{"id":null,"nickname":"작가"},"turnCount":0,"likeCount":0,"createdAt":1793502245678,"visible":false}""",
        )
        assertThat(json(deleted.id)).isEqualTo(
            """{"publicId":"${id(5)}","title":"삭제","oneLineIntro":"","genres":[],"characterNames":[],"thumbnailUrlSm":null,""" +
                """"author":{"id":null,"nickname":"작가"},"turnCount":0,"likeCount":0,"createdAt":1793502245678,"visible":false}""",
        )
        assertThat(json(deleted.id + 1_000)).isNull()
    }

    @Test fun `묶음 읽기와 페이지 읽기는 스토리마다 단건 읽기와 같은 문서를 만들고 스토리 사이에 값이 섞이지 않는다`() {
        val owner = users.save(User(nickname = "작가"))
        val other = users.save(User(nickname = "다른 작가"))
        val fans = (0 until 3).map { users.save(User(nickname = "독자$it")) }
        // 인물, 채팅(포크 포함), 좋아요가 있는 스토리 둘을 같은 작성자로 두고 값이 서로 다르게 한다.
        val rich = stories.save(Story(userId = owner.id, title = "{username}의 약속", protagonistName = "서린").apply {
            genre = "판타지, 미스터리"
        })
        characters.save(StoryCharacter(story = rich, name = "하람"))
        characters.save(StoryCharacter(story = rich, name = "가온"))
        chats.save(StoryChat(userId = fans[0].id, storyId = rich.id).apply { currentTurn = 5; hiddenTurnCount = 2; regeneratedCount = 1 })
        chats.save(StoryChat(userId = fans[1].id, storyId = rich.id, forkedFromTurn = 2).apply { currentTurn = 6 })
        fans.forEach { likes.save(StoryLike(userId = it.id, storyId = rich.id)) }
        val sibling = stories.save(Story(userId = owner.id, title = "같은 작가의 다른 스토리"))
        characters.save(StoryCharacter(story = sibling, name = "도윤"))
        chats.save(StoryChat(userId = fans[2].id, storyId = sibling.id).apply { currentTurn = 4 })
        likes.save(StoryLike(userId = fans[0].id, storyId = sibling.id))
        val plain = stories.save(Story(userId = other.id, title = "아무것도 없음"))
        val guest = stories.save(Story(userId = null, title = "게스트"))
        val privateStory = stories.save(Story(userId = other.id, title = "비공개", visibility = StoryVisibility.PRIVATE))
        val deleted = stories.save(Story(userId = owner.id, title = "삭제").apply { deletedAt = Instant.parse("2026-11-02T00:00:00Z") })
        val ids = listOf(rich, sibling, plain, guest, privateStory, deleted).map { it.id }
        val single = ids.map { json(it)!! }

        val batch = reader.readAll(ids.reversed() + (ids.max() + 1_000)).map(mapper::writeValueAsString)
        val page = reader.readPage(0).map(mapper::writeValueAsString)

        assertThat(batch).containsExactlyElementsOf(single)
        assertThat(page).containsExactlyElementsOf(single)
        assertThat(single[0]).contains(""""characterNames":["하람","가온"]""", ""","turnCount":12,"likeCount":3,""")
        assertThat(single[1]).contains(""""characterNames":["도윤"]""", ""","turnCount":4,"likeCount":1,""")
        assertThat(single[2]).contains(""""characterNames":[]""", ""","turnCount":0,"likeCount":0,""")
        assertThat(reader.readAll(emptyList())).isEmpty()
    }
}
