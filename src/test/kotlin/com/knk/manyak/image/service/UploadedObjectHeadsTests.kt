package com.knk.manyak.image.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 동시 HEAD의 조기 종료, 전부 기다림, 인터럽트 전파(KNK-2077). 시간 대신 래치로 판정한다. */
class UploadedObjectHeadsTests {
    private val release = CountDownLatch(1)
    private val started = AtomicInteger()
    private val finished = AtomicInteger()
    private val interrupted = AtomicInteger()

    /** 키가 `hold`로 시작하면 [release]까지 기다린다. `missing`은 없음, `boom`은 예외, 나머지는 객체. */
    private val storage = object : UploadedImageStorage {
        override fun head(objectKey: String): UploadedObject? {
            started.incrementAndGet()
            if (objectKey.startsWith("hold")) {
                try { release.await(10, TimeUnit.SECONDS) } catch (e: InterruptedException) { interrupted.incrementAndGet(); throw e }
            }
            finished.incrementAndGet()
            return when {
                objectKey.startsWith("missing") -> null
                objectKey.startsWith("boom") -> throw IllegalStateException("s3 down")
                else -> UploadedObject("image/webp", 10)
            }
        }
        override fun presignPut(objectKey: String, contentType: String, contentLength: Long, expiresIn: Duration): String? = null
        override fun isEnabled() = true
        override fun serveUrlOf(objectKey: String): String? = null
    }
    private val heads = UploadedObjectHeads(storage, concurrency = 4)

    @AfterEach fun tearDown() { release.countDown(); heads.destroy() }

    @Test fun `검증용은 처음 없는 키까지만 담고 남은 HEAD를 기다리지 않는다`() {
        val result = heads.headUntilMissing(listOf("missing-a") + (1..10).map { "hold-$it" })
        assertThat(result.keys).containsExactly("missing-a")
        assertThat(result.getValue("missing-a").getOrThrow()).isNull()
        assertThat(finished.get()).isEqualTo(1)
        assertThat(started.get()).isLessThanOrEqualTo(4)
    }

    @Test fun `검증용은 처음 예외인 키까지만 담고 그 앞 키의 결과는 담는다`() {
        val result = heads.headUntilMissing(listOf("ok-a", "boom-b") + (1..10).map { "hold-$it" })
        assertThat(result.keys).containsExactly("ok-a", "boom-b")
        assertThat(result.getValue("ok-a").getOrThrow()).isNotNull()
        assertThat(result.getValue("boom-b").exceptionOrNull()).isInstanceOf(IllegalStateException::class.java).hasMessage("s3 down")
        assertThat(finished.get()).isEqualTo(2)
    }

    @Test fun `크기 조회용은 없음과 예외가 있어도 전부 기다린다`() {
        val result = heads.headAll(listOf("missing-a", "boom-b", "ok-c", "ok-d", "ok-e"))
        assertThat(result.keys).containsExactly("missing-a", "boom-b", "ok-c", "ok-d", "ok-e")
        assertThat(finished.get()).isEqualTo(5)
    }

    @Test fun `요청 스레드가 인터럽트되면 남은 HEAD를 취소하고 인터럽트 상태를 남긴 채 전파한다`() {
        val outcome = AtomicReference<Throwable?>()
        val flagAfter = AtomicReference<Boolean>()
        val caller = Thread {
            try { heads.headAll((1..6).map { "hold-$it" }) } catch (e: Throwable) { outcome.set(e) }
            flagAfter.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        while (started.get() < 4) Thread.onSpinWait()
        caller.interrupt()
        caller.join(5_000)

        assertThat(outcome.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(flagAfter.get()).isTrue()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (interrupted.get() < 4 && System.nanoTime() < deadline) Thread.onSpinWait()
        assertThat(interrupted.get()).isEqualTo(4)
        assertThat(finished.get()).isZero()
    }
}
