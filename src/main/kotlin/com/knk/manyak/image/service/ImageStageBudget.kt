package com.knk.manyak.image.service

import java.time.Duration

/**
 * 인물 이미지 단계(업로드 · 업로드 실패 즉시 삭제 · 트랜잭션 후 보상 삭제) 전체에 걸리는 시간 예산(KNK-966).
 *
 * S3 호출 하나에는 SDK 상한(`apiCallTimeout`)이 걸려 있지만, 호출 여러 개가 순차로 누적되는 것까지는 막지 못한다.
 * 예산이 소진되면 남은 S3 호출을 건너뛰어, 저장소가 느릴 때 이미지 단계가 스토리 생성 요청을 끌고 가지 않게 한다.
 *
 * 시계는 [System.nanoTime]이다 — 벽시계 보정에 흔들리지 않는 단조 증가 시계라 경과 시간 측정에 맞는다.
 */
class ImageStageBudget(private val deadlineNanos: Long) {

    /**
     * 새 S3 호출을 시작해도 되는지. 남은 시간이 **호출 1회 상한보다 적으면 시작하지 않는다** —
     * 마감 직전에 시작한 호출은 [CALL_TIMEOUT]만큼 더 끌 수 있어, 예산을 넘긴 뒤에야 끝나기 때문이다.
     * 이 여유를 두면 시작한 호출은 마감 안에 끝나는 것이 보장된다(SDK가 호출 하나를 그 상한에서 끊는다).
     */
    fun hasRoomForCall(): Boolean = System.nanoTime() + CALL_TIMEOUT.toNanos() <= deadlineNanos

    companion object {
        /** S3 호출 하나(재시도 포함)의 상한. `S3CharacterImageStorage`가 이 값을 apiCallTimeout으로 그대로 쓴다. */
        val CALL_TIMEOUT: Duration = Duration.ofSeconds(10)

        fun startingNow(budget: Duration): ImageStageBudget =
            ImageStageBudget(System.nanoTime() + budget.toNanos())
    }
}
