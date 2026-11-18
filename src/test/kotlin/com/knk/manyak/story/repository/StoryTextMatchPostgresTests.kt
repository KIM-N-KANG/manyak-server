package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Story
import com.knk.manyak.support.SharedPostgres
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.time.Instant
import java.util.function.Supplier

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = [StoryRepository::class])
class StoryTextMatchPostgresConfig

/** 검색 대체 검색의 LIKE 비교가 H2가 아닌 실제 PostgreSQL에서 `%`, `_`, `\`를 문자 그대로 보는지 확인한다(STORY 5-5-8). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoryTextMatchPostgresTests {
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var stories: StoryRepository
    private val base = Instant.parse("2026-11-01T00:00:00Z")

    @BeforeAll fun start() {
        val ds = SharedPostgres.database(migrated = true)
        context = AnnotationConfigApplicationContext()
        context.register(StoryTextMatchPostgresConfig::class.java)
        context.registerBean("dataSource", javax.sql.DataSource::class.java, Supplier { ds })
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean::class.java, Supplier {
            LocalContainerEntityManagerFactoryBean().apply {
                dataSource = ds
                setPackagesToScan("com.knk.manyak")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none", "hibernate.jdbc.time_zone" to "UTC"))
            }
        })
        context.registerBean("transactionManager", PlatformTransactionManager::class.java, Supplier {
            JpaTransactionManager(context.getBean(EntityManagerFactory::class.java)).apply { dataSource = ds }
        })
        context.refresh()
        stories = context.getBean(StoryRepository::class.java)
    }

    @AfterAll fun stop() = context.close()

    // StoryService.searchListedByText와 같은 이스케이프.
    private fun pattern(query: String) = "%" + query.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun titles(query: String) = stories.findTextMatches(pattern(query), PageRequest.of(0, 100)).map { it.title }

    @Test
    fun `와일드카드와 이스케이프 문자를 문자 그대로 비교하고 대소문자를 무시하며 최신순으로 읽는다`() {
        stories.saveAll(listOf(
            Story(title = "100% 진짜", createdAt = base),
            Story(title = "1000 진짜", createdAt = base.plusSeconds(1)),
            Story(title = "a_b 왕국", createdAt = base.plusSeconds(2)),
            Story(title = "axb 왕국", createdAt = base.plusSeconds(3)),
            Story(title = "역슬래시 a\\b", createdAt = base.plusSeconds(4)),
            Story(title = "소개만", oneLineIntro = "BL Kingdom 이야기", createdAt = base.plusSeconds(5)),
            Story(title = "장르만", genre = "판타지,왕국물", createdAt = base.plusSeconds(6)),
        ))
        assertThat(titles("0%")).containsExactly("100% 진짜")
        assertThat(titles("a_b")).containsExactly("a_b 왕국")
        assertThat(titles("a\\b")).containsExactly("역슬래시 a\\b")
        assertThat(titles("bl kINGDOM")).containsExactly("소개만")
        assertThat(titles("왕국")).containsExactly("장르만", "axb 왕국", "a_b 왕국")
    }

    @Test
    fun `운영 규모 1500건을 모두 훑는 질의의 실행 시간을 잰다`() {
        stories.saveAll((1..1_500).map { Story(title = "측정 스토리 $it", oneLineIntro = "한 줄 소개 $it", genre = "판타지", createdAt = base.minusSeconds(it.toLong())) })
        stories.findTextMatches(pattern("측정"), PageRequest.of(0, 100)) // 준비 실행
        val started = System.nanoTime()
        var page = 0
        var scanned = 0
        while (true) {
            val rows = stories.findTextMatches(pattern("측정"), PageRequest.of(page++, 100))
            scanned += rows.size
            if (rows.size < 100) break
        }
        val allMillis = (System.nanoTime() - started) / 1_000_000
        val firstStarted = System.nanoTime()
        stories.findTextMatches(pattern("없는검색어"), PageRequest.of(0, 100))
        val noMatchMillis = (System.nanoTime() - firstStarted) / 1_000_000
        println("대체 검색 측정: 일치 ${scanned}건 전체 훑기 ${allMillis}ms, 일치 없음 ${noMatchMillis}ms")
        assertThat(scanned).isGreaterThanOrEqualTo(1_500)
    }
}
