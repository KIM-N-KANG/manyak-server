package com.knk.manyak.notice

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.notice.entity.*
import com.knk.manyak.notice.repository.NoticeRepository
import com.knk.manyak.notice.service.NoticeService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SpringBootTest
class NoticeNotificationConnectionIntegrationTests {
    @Autowired private lateinit var notices: NoticeRepository
    @Autowired private lateinit var service: NoticeService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var manager: PlatformTransactionManager
    private lateinit var notice: Notice
    private val tx get() = TransactionTemplate(manager)

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        val owner = users.save(User(nickname = "공지 연결"))
        notice = notices.save(Notice(title = "공지", body = "본문", tag = NoticeTag.UPDATE,
            status = NoticeStatus.PUBLISHED, createdByUserId = owner.id))
    }

    @Test fun `연결은 트랜잭션 밖에서 거절하고 첫 시각은 롤백과 재연결 규칙을 따른다`() {
        assertThatThrownBy { service.connectNotification(notice.publicId) }.isInstanceOf(IllegalTransactionStateException::class.java)
        tx.executeWithoutResult { status ->
            val link = service.connectNotification(notice.publicId)!!
            assertThat(link.id).isEqualTo(notice.id)
            assertThat(link.tag).isEqualTo(NoticeTag.UPDATE)
            status.setRollbackOnly()
        }
        assertThat(notices.findById(notice.id).orElseThrow().notifiedAt).isNull()
        tx.executeWithoutResult { service.connectNotification(notice.publicId) }
        val first = notices.findById(notice.id).orElseThrow()
        tx.executeWithoutResult { service.connectNotification(notice.publicId) }
        val again = notices.findById(notice.id).orElseThrow()
        assertThat(again.notifiedAt).isEqualTo(first.notifiedAt).isNotNull()
        assertThat(again.updatedAt).isEqualTo(first.updatedAt)
        tx.executeWithoutResult { assertThat(service.connectNotification(UUID.randomUUID())).isNull() }
    }

    @Test fun `연결과 게시 해제는 먼저 잡은 공지 잠금의 커밋을 기다린다`() {
        for (connectionFirst in listOf(true, false)) {
            notice.status = NoticeStatus.PUBLISHED; notice.notifiedAt = null
            notices.saveAndFlush(notice)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val waiting = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit {
                    tx.executeWithoutResult {
                        if (connectionFirst) assertThat(service.connectNotification(notice.publicId)).isNotNull()
                        else notices.lockByPublicId(notice.publicId)!!.status = NoticeStatus.DRAFT
                        notices.flush(); locked.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                check(locked.await(5, TimeUnit.SECONDS))
                val second = pool.submit<Boolean> {
                    waiting.countDown()
                    tx.execute {
                        if (connectionFirst) {
                            notices.lockByPublicId(notice.publicId)!!.status = NoticeStatus.DRAFT
                            true
                        } else service.connectNotification(notice.publicId) != null
                    }!!
                }
                check(waiting.await(5, TimeUnit.SECONDS))
                assertThatThrownBy { second.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
                release.countDown(); first.get(5, TimeUnit.SECONDS)
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(connectionFirst)
                val row = notices.findById(notice.id).orElseThrow()
                assertThat(row.status).isEqualTo(NoticeStatus.DRAFT)
                assertThat(row.notifiedAt != null).isEqualTo(connectionFirst)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }
}
