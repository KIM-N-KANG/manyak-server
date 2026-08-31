package com.knk.manyak.story.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.support.DatabaseCleaner
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 스토리 목록 경로가 마지막 공개 버전 스냅샷을 **읽지 않는다**는 회귀 가드(PR #224 Codex P2).
 *
 * 스냅샷을 `stories`의 jsonb 컬럼으로 두면 JPA basic 매핑이 기본 eager라 스토리 엔티티를 뜰 때마다 JSON
 * 전체를 읽고 역직렬화한다. `/stories/batch`는 최대 100건을 엔티티로 긁고 서재·오리지널 목록도 같은 경로인데
 * 이 값을 쓰지 않으며, 설정 본문에 입력 상한이 없어 한 건이 수십 KB까지 커질 수 있다.
 * `@Basic(fetch = LAZY)`는 바이트코드 인핸스먼트가 켜져 있어야 지연되는데 이 레포는 켜져 있지 않아
 * (빌드에 `org.hibernate.orm` 플러그인 없음) 조용히 무시된다. 그래서 별도 테이블로 뺐다.
 *
 * 이 테스트는 그 분리가 실제로 효과가 있는지를 Hibernate 통계의 **엔티티 로드 횟수**로 고정한다.
 * 누군가 스냅샷을 다시 `Story`에 붙이거나 목록 경로에서 조회하면 여기서 드러난다.
 *
 * 전용 H2로 분리한다 — 통계를 켜는 프로퍼티 때문에 별도 컨텍스트가 만들어지므로 기본 컨텍스트와 DB를 공유하면 안 된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:manyak-snapshot-isolation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class StoryPublicSnapshotIsolationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var startSettingRepository: StoryStartSettingRepository
    @Autowired private lateinit var snapshotService: StoryPublicSnapshotService
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    private val snapshotEntityName = "com.knk.manyak.story.entity.StoryPublicSnapshotRow"

    private fun statistics(): Statistics =
        entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private fun snapshotLoadCount(): Long =
        statistics().getEntityStatistics(snapshotEntityName).loadCount

    /**
     * 측정 직전에 통계를 0으로 되돌린다. 측정 전후 값을 빼는 방식은 시드가 남긴 로드·컨텍스트 재생성 같은
     * 잡음에 흔들려 간헐 실패를 만든다 — 측정 구간만 남기면 그 흔들림이 사라진다.
     */
    private fun startMeasuring() = statistics().clear()

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        statistics().isStatisticsEnabled = true
    }

    /** 스냅샷을 가진 공개 스토리 [count]건. 목록이 이 값을 읽으면 통계에 잡힌다. */
    private fun seedPublicStoriesWithSnapshots(count: Int, ownerId: Long? = null): List<Story> =
        (1..count).map { index ->
            val story = storyRepository.save(Story(userId = ownerId, title = "스토리 $index", genre = "판타지"))
            startSettingRepository.save(
                StoryStartSetting(story = story, name = "시작", prologue = "긴 프롤로그 본문 $index"),
            )
            snapshotService.refresh(storyRepository.findById(story.id).orElseThrow())
            story
        }

    @Test
    fun `스토리 배치 조회는 마지막 공개 버전 스냅샷을 읽지 않는다`() {
        val stories = seedPublicStoriesWithSnapshots(3)
        startMeasuring()

        restTestClient.post()
            .uri("/api/v1/stories/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyIds":${stories.map { "\"${it.publicId}\"" }}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)

        assertThat(snapshotLoadCount())
            .withFailMessage(
                "스토리 배치 조회가 스냅샷을 읽었습니다(%d건). 목록 경로는 이 값을 쓰지 않으므로, " +
                    "다시 stories에 붙었거나 목록에서 조회하고 있습니다.",
                snapshotLoadCount(),
            )
            .isZero()
    }

    @Test
    fun `회원 서재의 스토리 목록도 마지막 공개 버전 스냅샷을 읽지 않는다`() {
        val owner = userRepository.save(User(nickname = "제작자", status = UserStatus.ACTIVE))
        seedPublicStoriesWithSnapshots(3, ownerId = owner.id)
        startMeasuring()

        restTestClient.get()
            .uri("/api/v1/users/me/stories")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(owner.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)

        assertThat(snapshotLoadCount())
            .withFailMessage("서재의 스토리 목록이 스냅샷을 읽었습니다. 이 경로는 이 값을 쓰지 않습니다.")
            .isZero()
    }

    @Test
    fun `읽을 수 없는 스토리를 참조하는 채팅 경로는 스냅샷을 읽는다`() {
        // 위 두 테스트가 "안 읽는다"만 고정하면, 조회를 통째로 지워도 통과한다.
        // 실제로 읽어야 하는 경로가 읽는지도 함께 못박는다.
        val story = seedPublicStoriesWithSnapshots(1).single()
        startMeasuring()

        assertThat(snapshotService.findByStoryId(story.id)).isNotNull
        assertThat(snapshotLoadCount()).isEqualTo(1)
    }
}
