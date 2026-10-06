package com.knk.manyak.chat.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.dto.*
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.*
import com.knk.manyak.story.service.StoryPublicSnapshotService
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.user.dto.*
import com.knk.manyak.user.service.UserPersonaService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.server.ResponseStatusException
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.*

@ActiveProfiles("test")
@SpringBootTest
class PersonaChatIntegrationTests {
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var personas: UserPersonaService
    @Autowired private lateinit var service: ChatService
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var starts: StoryStartSettingRepository
    @Autowired private lateinit var inputs: StorySuggestedInputRepository
    @Autowired private lateinit var snapshots: StoryPublicSnapshotService
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: ObjectMapper
    @BeforeEach fun setup() = cleaner.cleanAll()
    @Test fun `페르소나 snapshot 표시 공유 권한과 복구 프롤로그`() {
        val author = users.save(User(nickname = "작가"))
        val reader = users.save(User(nickname = "독자"))
        val story = stories.save(Story(userId = author.id, title = "{username}의 스토리", protagonistName = "민우"))
        val start = starts.save(StoryStartSetting(story = story, name = "시작", prologue = "{username}은(는) 출발"))
        inputs.save(StorySuggestedInput(startSetting = start, inputText = "{username}으로(로)", inputOrder = 1))
        snapshots.refresh(story)
        val persona = personas.create(reader.id, CreateUserPersonaRequest("하늘", "선택한 설명"))
        val created = service.createChat(CreateChatRequest(story.publicId.toString(), personaId = persona.id), reader.id)
        assertEquals("하늘", created.persona!!.name)
        assertEquals("하늘은 출발", created.prologue)
        assertEquals(listOf("하늘로"), created.suggestedInputs)
        val chat = chats.findByPublicIdAndDeletedAtIsNull(UUID.fromString(created.id))!!
        assertEquals("하늘", chat.personaNameSnapshot)
        assertEquals("선택한 설명", chat.personaDescriptionSnapshot)
        assertEquals("하늘은 출발", chat.storyPrologueSnapshot)
        personas.update(reader.id, persona.id, UpdateUserPersonaRequest("변경", "다른 설명"))
        personas.delete(reader.id, persona.id)
        assertEquals("하늘의 스토리", service.getChatDetail(created.id, reader.id).storyTitle)
        assertEquals("하늘", service.getMyChats(reader.id, 10).single().persona!!.name)
        story.visibility = StoryVisibility.PRIVATE
        story.title = "비공개 {username}"
        story.protagonistName = "비밀"
        stories.saveAndFlush(story)
        val share = service.createChatShare(created.id, reader.id)
        val shared = service.getChatShare(share.shareId, null)
        assertEquals("하늘의 스토리", shared.storyTitle)
        assertEquals("하늘은 출발", shared.prologue)
        assertFalse(mapper.valueToTree<tools.jackson.databind.JsonNode>(shared).has("persona"))
        jdbc.update("update story_chats set start_setting_id = null where id = ?", chat.id)
        assertEquals("하늘은 출발", service.getChatDetail(created.id, reader.id).prologue)
    }
    @Test fun `게스트 페르소나 요청은 스토리 조회 전에 거부하고 기본 선택은 null이다`() {
        val error = assertFailsWith<ResponseStatusException> { service.createChat(CreateChatRequest("bad-story", personaId = "bad-persona"), null) }
        assertEquals(401, error.statusCode.value())
        val owner = users.save(User(nickname = "소유자"))
        val story = stories.save(Story(userId = owner.id, title = "{username}", protagonistName = "민우"))
        starts.save(StoryStartSetting(story = story, name = "시작", prologue = "{username}이(가)"))
        val created = service.createChat(CreateChatRequest(story.publicId.toString()), owner.id)
        assertNull(created.persona)
        assertEquals("민우가", created.prologue)
        assertEquals(404, assertFailsWith<ResponseStatusException> { service.createChat(CreateChatRequest(story.publicId.toString(), personaId = "bad"), owner.id) }.statusCode.value())
    }
    @Test fun `이름에 들어 있는 토큰과 과거 snapshot은 다시 해석하지 않는다`() {
        val owner = users.save(User(nickname = "작가"))
        val reader = users.save(User(nickname = "독자"))
        val story = stories.save(Story(userId = owner.id, title = "스토리", protagonistName = "기본"))
        starts.save(StoryStartSetting(story = story, name = "시작", prologue = "{username}은(는)"))
        snapshots.refresh(story)
        val persona = personas.create(reader.id, CreateUserPersonaRequest("{username}", "역할"))
        val chat = service.createChat(CreateChatRequest(story.publicId.toString(), personaId = persona.id), reader.id)
        assertEquals("{username}는", chat.prologue)
        story.visibility = StoryVisibility.PRIVATE
        stories.saveAndFlush(story)
        jdbc.update("update story_chats set start_setting_id = null where public_id = ?", UUID.fromString(chat.id))
        assertEquals("{username}는", service.getChatDetail(chat.id, reader.id).prologue)
        val old = mapper.readValue("""{"title":"{username} 원문"}""", StoryPublicSnapshot::class.java)
        assertNull(old.protagonistName)
        assertEquals("{username} 원문", com.knk.manyak.story.service.UsernameTokenRenderer.render(old.title, old.protagonistName))
    }
}
