package com.knk.manyak.image.service

import com.knk.manyak.global.observability.MdcTaskDecorator
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore

/**
 * 업로드 객체 여러 개의 HEAD를 동시에 보낸다(KNK-2077). 제출 접수는 잠금을 쥔 채 이미지마다 HEAD하므로 순차로 보내면
 * 잠금 보유 시간이 장수에 비례한다. 결과는 키별로 돌려주고, 판정과 오류 순서는 호출자가 입력 순서대로 정한다.
 *
 * 실행기 스레드는 HEAD만 한다(트랜잭션과 DB 없음). 작업마다 가상 스레드를 쓰고 동시 수는 호출 하나당 [concurrency]로 막는다.
 * 요청 MDC와 관측 스코프는 [MdcTaskDecorator]로 넘긴다. S3 클라이언트(Apache, 기본 커넥션 50)와 호출당 제한 5초 안에 든다.
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

    /** 키마다 [UploadedImageStorage.head]의 결과(없으면 null) 또는 던진 예외. 키가 둘 미만이면 동시 실행할 이유가 없어 빈 맵이다. */
    fun headAll(keys: Collection<String>): Map<String, Result<UploadedObject?>> {
        val distinct = keys.distinct()
        if (distinct.size < 2) return emptyMap()
        val gate = Semaphore(concurrency)
        val futures = distinct.associateWith { key ->
            executor.submitCompletable(java.util.concurrent.Callable<UploadedObject?> {
                gate.acquire()
                try { storage.head(key) } finally { gate.release() }
            })
        }
        // 모든 HEAD를 기다린다. 각 호출은 S3 클라이언트 제한(5초) 안에서 끝난다.
        return futures.mapValues { (_, future) ->
            try { Result.success(future.get()) } catch (e: ExecutionException) { Result.failure(e.cause ?: e) }
        }
    }

    override fun destroy() = executor.close()
}
