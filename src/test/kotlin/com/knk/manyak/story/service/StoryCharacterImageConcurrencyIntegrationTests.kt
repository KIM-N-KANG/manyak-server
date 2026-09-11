package com.knk.manyak.story.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import com.knk.manyak.story.dto.AddCharacterImageRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 인물당 이미지 상한(10장)의 동시성(KNK-1126, 검수 지적).
 *
 * 상한 판정이 "현재 개수를 읽고 → 넣는다"라 잠금이 없으면 9장에서 서로 다른 이름의 동시 요청 둘이 모두
 * 통과해 11장이 된다(`sort_order`도 둘 다 9로 겹친다). 인물 행을 비관적 쓰기 락으로 잡아 직렬화한다 —
 * 상한이 인물 단위라 스토리 행까지 잠글 필요가 없다.
 */
@ActiveProfiles("test")
@SpringBootTest
class StoryCharacterImageConcurrencyIntegrationTests {

    @MockitoBean private lateinit var uploadedImageStorage: UploadedImageStorage

    @Autowired private lateinit var storyImageService: StoryImageService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyCharacterRepository: StoryCharacterRepository
    @Autowired private lateinit var storyCharacterImageRepository: StoryCharacterImageRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        `when`(uploadedImageStorage.isEnabled()).thenReturn(true)
        `when`(uploadedImageStorage.head(anyString())).thenReturn(UploadedObject("image/webp", 1024))
        `when`(uploadedImageStorage.serveUrlOf(anyString())).thenAnswer { "https://cdn.test/${it.arguments[0]}" }
    }

    @Test
    fun `상한 직전의 동시 추가는 직렬화되어 10장을 넘지 않는다`() {
        val owner = userRepository.save(User(nickname = "작가"))
        val story = storyRepository.save(Story(userId = owner.id, title = "상한 경합 스토리"))
        val character = storyCharacterRepository.save(StoryCharacter(story = story, name = "세린"))
        // 상한(10) 직전까지 채운다. 남은 자리는 하나뿐이라 동시 요청 둘 중 하나만 성공해야 한다.
        (1..StoryCharacterImage.MAX_IMAGES_PER_CHARACTER - 1).forEach { index ->
            storyCharacterImageRepository.save(
                StoryCharacterImage(
                    character = character,
                    imageName = "세린_표정$index",
                    imageUrl = "https://cdn.test/seed/$index.webp",
                    sortOrder = index - 1,
                ),
            )
        }

        val threads = 2
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val outcomes = try {
            val futures = (1..threads).map { index ->
                pool.submit<Boolean> {
                    ready.countDown()
                    go.await()
                    runCatching {
                        storyImageService.addCharacterImage(
                            story.publicId.toString(),
                            character.publicId.toString(),
                            owner.id,
                            AddCharacterImageRequest(
                                objectKey = "characters/uploaded/${story.publicId}/race-$index.webp",
                                imageName = "세린_경합$index",
                            ),
                        )
                    }.isSuccess
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown() // 두 스레드 동시 출발
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 한쪽만 성공하고 상한이 지켜진다.
        assertThat(outcomes.count { it }).isEqualTo(1)
        assertThat(storyCharacterImageRepository.countByCharacterId(character.id))
            .isEqualTo(StoryCharacterImage.MAX_IMAGES_PER_CHARACTER.toLong())
        // sort_order가 겹치지 않는다(둘 다 통과했다면 9가 둘이 된다).
        assertThat(storyCharacterImageRepository.findByCharacterIdOrderBySortOrderAscIdAsc(character.id).map { it.sortOrder })
            .doesNotHaveDuplicates()
    }
}
