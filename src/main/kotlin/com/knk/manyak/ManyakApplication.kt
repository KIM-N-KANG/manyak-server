package com.knk.manyak

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

// 피드백 Slack 알림을 요청 스레드와 분리해 비동기로 보내기 위해 @Async 를 활성화한다.
@EnableAsync
@SpringBootApplication
class ManyakApplication

fun main(args: Array<String>) {
    runApplication<ManyakApplication>(*args)
}
