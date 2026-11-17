package com.knk.manyak.image.service

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 업로드 객체 여러 개의 HEAD를 동시에 보낸다(KNK-2077). 제출 접수는 잠금을 쥔 채 이미지마다 HEAD하므로 순차로 보내면
 * 잠금 보유 시간이 장수에 비례한다. 결과는 키별로 돌려주고, 판정과 오류 순서는 호출자가 입력 순서대로 정한다.
 *
 * 실행기 스레드는 HEAD만 한다(트랜잭션과 DB 없음). 작업마다 가상 스레드를 쓰고 동시 수는 호출 하나당 [concurrency]로 막는다.
 * 요청 MDC와 관측 스코프는 [MdcTaskDecorator]로 넘긴다. 호출당 제한은 S3 클라이언트의 5초다.
 * 동시 수 상한은 호출당이라 인스턴스 전체 수는 동시 호출 수에 비례한다. 그것이 S3 클라이언트 기본 커넥션 50 안에 드는 근거는
 * 접수가 트랜잭션 안이라 동시 접수가 Hikari 풀(운영 태스크당 5)에 묶인다는 점이므로, 풀을 키우면 다시 계산해야 한다.
 */
@Component
class UploadedObjectHeads(
    private val storage: UploadedImageStorage,
    @Value("\${manyak.image.head-concurrency:8}") private val concurrency: Int = 8,
) : DisposableBean {
    init { require(concurrency > 0) { "manyak.image.head-concurrency must be positive" } }

    private val executor = SimpleAsyncTaskExecutor("image-head-").apply {
        setVirtualThreads(true)
        setTaskDecorator(MdcTaskDecorator())
    }

    /**
     * 키마다 [UploadedImageStorage.head]의 결과(없으면 null) 또는 던진 예외를 모두 기다려 돌려준다. 실패를 흡수하는 크기 조회용이다.
     * 키가 둘 미만이면 동시 실행할 이유가 없어 빈 맵이다.
     */
    fun headAll(keys: Collection<String>): Map<String, Result<UploadedObject?>> = heads(keys, stopAtMissing = false)

    /**
     * 검증용. 결과를 입력 순서로 기다리다 처음으로 예외이거나 없음(null)인 키에서 남은 작업을 취소하고 멈춘다. 그 키까지만 결과에 담고
     * 뒤 키는 빼서, 호출자가 그 키들을 만나면 순차로 HEAD하게 한다. 순차 검증은 첫 실패에서 멈추므로 응답이 같고,
     * S3가 응답하지 않을 때 실패까지 걸리는 시간도 첫 호출의 제한(5초)으로 순차와 같다.
     */
    fun headUntilMissing(keys: Collection<String>): Map<String, Result<UploadedObject?>> = heads(keys, stopAtMissing = true)

    private fun heads(keys: Collection<String>, stopAtMissing: Boolean): Map<String, Result<UploadedObject?>> {
        val distinct = keys.distinct()
        if (distinct.size < 2) return emptyMap()
        // 허가는 제출하는 쪽이 입력 순서로 받는다. 그래서 앞 키가 뒤 키에 밀려 늦게 시작하지 않는다.
        val gate = Semaphore(concurrency)
        // 제출한 키 중 하나라도 없거나 실패하면 더 제출하지 않는다. 뒤 키는 그보다 앞의 첫 실패에서 멈추는 순차 검증이 쓰지 않는다.
        val missingSeen = AtomicBoolean(false)
        val futures = mutableListOf<Pair<String, FutureTask<UploadedObject?>>>()
        val results = LinkedHashMap<String, Result<UploadedObject?>>()
        try {
            for (key in distinct) {
                gate.acquire()
                if (missingSeen.get()) { gate.release(); break }
                // FutureTask의 cancel(true)는 실행 중인 가상 스레드를 인터럽트한다.
                val task = FutureTask<UploadedObject?>(Callable {
                    try {
                        runCatching { storage.head(key) }
                            .also { if (stopAtMissing && it.getOrNull() == null) missingSeen.set(true) }
                            .getOrThrow()
                    } finally { gate.release() }
                })
                executor.execute(task)
                futures += key to task
            }
            for ((key, future) in futures) {
                val result: Result<UploadedObject?> = try { Result.success(future.get()) } catch (e: ExecutionException) { Result.failure(e.cause ?: e) }
                results[key] = result
                if (stopAtMissing && result.getOrNull() == null) break
            }
        } catch (e: InterruptedException) {
            // 요청 스레드가 끊기면 남은 HEAD를 멈추고 인터럽트 상태를 되돌려 전파한다.
            futures.forEach { it.second.cancel(true) }
            Thread.currentThread().interrupt()
            throw e
        }
        // 멈춘 뒤의 작업은 기다리지 않는다. 끝난 작업에는 효과가 없다.
        futures.forEach { it.second.cancel(true) }
        return results
    }

    override fun destroy() = executor.close()
}
