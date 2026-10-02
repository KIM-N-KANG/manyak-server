package com.knk.manyak

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

// 피드백·검색 색인·스토리 신고 알림을 요청 스레드와 분리하기 위해 @Async를 활성화한다.
@EnableAsync
@SpringBootApplication
class ManyakApplication

fun main(args: Array<String>) {
    runApplication<ManyakApplication>(*args)
}
