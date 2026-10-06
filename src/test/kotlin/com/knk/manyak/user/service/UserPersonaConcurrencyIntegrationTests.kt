package com.knk.manyak.user.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.user.dto.CreateUserPersonaRequest
import com.knk.manyak.user.dto.UpdateUserPersonaRequest
import com.knk.manyak.user.repository.UserPersonaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest
class UserPersonaConcurrencyIntegrationTests {
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var personas: UserPersonaRepository
    @Autowired private lateinit var service: UserPersonaService
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var manager: PlatformTransactionManager
    @BeforeEach fun setup() = cleaner.cleanAll()
    @Test fun `동시 등록은 회원 잠금으로 열 개를 넘지 않는다`() {
        val user = users.save(User(nickname = "작가"))
        repeat(9) { service.create(user.id, CreateUserPersonaRequest("이름", "설명")) }
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map { pool.submit<Int> {
                ready.countDown(); start.await(10, TimeUnit.SECONDS)
                try { service.create(user.id, CreateUserPersonaRequest("이름", "설명")); 201 }
                catch (e: ResponseStatusException) { e.statusCode.value() }
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            assertEquals(listOf(201, 409), results.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            assertEquals(10L, personas.countByUserIdAndDeletedAtIsNull(user.id))
        } finally { pool.shutdownNow() }
    }
    @Test fun `선택 잠금이 해제될 때까지 수정은 완료되지 않는다`() = assertSelectionBlocksWrite(delete = false)

    @Test fun `선택 잠금이 해제될 때까지 삭제는 완료되지 않는다`() = assertSelectionBlocksWrite(delete = true)

    // H2에서 애플리케이션의 잠금 흐름을 고정한다. PostgreSQL 잠금 보장을 검증하는 테스트는 아니다.
    private fun assertSelectionBlocksWrite(delete: Boolean) {
        val user = users.save(User(nickname = "작가"))
        val persona = service.create(user.id, CreateUserPersonaRequest("이전", "이전 설명"))
        val selected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val snapshot = pool.submit<com.knk.manyak.user.dto.PersonaSnapshot> {
                TransactionTemplate(manager).execute {
                    val result = service.snapshot(user.id, persona.id)
                    selected.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    result
                }!!
            }
            assertTrue(selected.await(10, TimeUnit.SECONDS))
            val changed = pool.submit {
                TransactionTemplate(manager).executeWithoutResult {
                    writerEntered.countDown()
                    if (delete) service.delete(user.id, persona.id)
                    else service.update(user.id, persona.id, UpdateUserPersonaRequest("이후", "이후 설명"))
                }
            }
            assertTrue(writerEntered.await(10, TimeUnit.SECONDS), "후행 쓰기 트랜잭션 진입")
            assertFailsWith<TimeoutException>("선택 잠금이 유지되는 동안 쓰기가 완료되면 안 된다") {
                changed.get(500, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            val captured = snapshot.get(20, TimeUnit.SECONDS)
            assertEquals("이전", captured.name)
            assertEquals("이전 설명", captured.description)
            changed.get(20, TimeUnit.SECONDS)
            val stored = personas.findAll().single()
            if (delete) {
                assertTrue(stored.deletedAt != null)
                assertEquals("이전", stored.name)
            } else {
                assertEquals("이후", stored.name)
                assertEquals("이후 설명", stored.description)
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
