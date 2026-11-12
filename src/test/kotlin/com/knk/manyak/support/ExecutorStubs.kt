package com.knk.manyak.support

import org.mockito.Mockito
import java.util.concurrent.Executor

/**
 * 공용 스파이 executor가 제출받은 작업을 실행하지 않게 한다. executor를 목으로 바꿔 비동기 작업을 멈추던 테스트가
 * 같은 동작을 재현할 때 쓴다(KNK-2053에서 executor 목을 [IntegrationTest]의 스파이로 합쳤다). 스텁은 테스트마다 자동 reset된다.
 */
fun stopExecuting(vararg executors: Executor) =
    executors.forEach { Mockito.doNothing().`when`(it).execute(Mockito.any(Runnable::class.java)) }
