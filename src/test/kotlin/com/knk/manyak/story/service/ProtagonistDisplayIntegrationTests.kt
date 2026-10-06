package com.knk.manyak.story.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.*
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

@ActiveProfiles("test")
@SpringBootTest
class ProtagonistDisplayIntegrationTests {
    @Autowired private lateinit var creation: GeneralStoryCreationService
    @Autowired private lateinit var service: StoryService
    @Autowired private lateinit var edit: StoryEditService
    @Autowired private lateinit var snapshots: StoryPublicSnapshotService
    @Autowired private lateinit var reader: com.knk.manyak.search.service.StorySearchDocumentReader
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @BeforeEach fun setup() = cleaner.cleanAll()
    @Test fun `기본 이름으로 응답하고 편집과 공개 snapshot은 원문을 유지한다`() {
        val user = users.save(User(nickname = "제작자"))
        val request = mapper.readValue("""{
          "protagonistName":"민우", "title":"{username}의 귀환", "oneLineIntro":"{username}은(는)", "description":"{username}이랑(랑)", "genres":["판타지"], "visibility":"PUBLIC",
          "storySettings":{"worldSetting":"{username}과(와)","characterSetting":"{username}을(를)","userRoleSetting":"{username}이(가)","ruleSetting":"규칙"},
          "startSettings":[{"name":"{username}","prologue":"{username}으로(로)","startSituation":"{username}아(야)","suggestedInputs":["{username}","둘","셋"],"endings":[{"name":"{username}의 끝","requirement":{"minTurns":0,"achievementCondition":"{username}은(는)"},"epilogue":"{username}을(를)"}]}],
          "mainEvents":[{"name":"{username}의 사건","description":"{username}과(와)","keySentence":"{username}이(가)"}],
          "characters":[{"name":"{username} 원래 인물명","description":"{username}의 친구"}]
        }""", CreateGeneralStoryRequest::class.java)
        val created = creation.createGeneralStory(request, user.id)
        assertEquals("민우의 귀환", created.title)
        assertEquals("민우로", created.startSettings.single().prologue)
        val detail = service.getStoryDetail(created.id, null)
        assertEquals("민우의 귀환", detail.title)
        assertEquals("민우는", detail.oneLineIntro)
        assertEquals("민우랑", detail.description)
        assertEquals("민우의 사건", detail.mainEvents.single().name)
        assertEquals("민우가", detail.mainEvents.single().keySentence)
        assertEquals("민우의 끝", detail.startSettings.single().endings.single().name)
        assertEquals("민우의 친구", detail.characters.single().description)
        assertEquals("{username} 원래 인물명", detail.characters.single().name)
        assertEquals("민우의 귀환", service.getStoriesByIds(BatchStoryRequest(listOf(created.id)), null).single().title)
        assertEquals("{username}의 귀환", edit.getEditForm(created.id, user.id).title)
        val story = stories.findAll().single()
        assertEquals("민우", snapshots.findByStoryId(story.id)!!.protagonistName)
        assertEquals("{username}의 귀환", snapshots.findByStoryId(story.id)!!.title)
        assertEquals("민우의 귀환", reader.read(story.id)!!.title)
    }
}
