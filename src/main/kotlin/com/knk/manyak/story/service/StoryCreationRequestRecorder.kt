package com.knk.manyak.story.service

import com.knk.manyak.story.entity.StoryCreationRequest
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.entity.StoryCreationStage
import com.knk.manyak.story.entity.isOwnedBy
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 백그라운드 생성 복구·멱등 래퍼(KNK-631, 스펙 §4-3-8).
 *
 * 생성 요청을 클라이언트 생성 [UUID]로 추적해 세 가지를 보장한다:
 * 1. **복구** — 요청 수신 시 PENDING 행을 **별도 트랜잭션**으로 커밋해, 본 저장 트랜잭션이 끝나기 전에도
 *    복구 조회가 진행 상태를 볼 수 있게 한다(응답을 못 받은 클라이언트가 결과를 되찾는 경로).
 * 2. **멱등** — 같은 requestId 재요청은 COMPLETED면 저장된 결과를 [block] 미실행으로 반환하고(중복 생성·과금 방지),
 *    PENDING이면 409(진행 중)를 던진다.
 * 3. **재실행** — FAILED 재요청은 PENDING으로 되돌려 [block]을 다시 실행한다(일시 실패 재시도 허용).
 *
 * 소유가 다른 requestId 재사용은 409로 거부한다.
 */
@Component
class StoryCreationRequestRecorder(
    private val repository: StoryCreationRequestRepository,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    // PENDING 기록·상태 갱신을 본 저장 트랜잭션과 독립 커밋한다(복구 GET이 PENDING을 보게).
    private val txTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun <T : Any> execute(
        requestId: UUID,
        stage: StoryCreationStage,
        ownerUserId: Long?,
        ownerDeviceId: String?,
        responseType: Class<T>,
        block: () -> T,
    ): T =
        when (val claim = claimOrReplay(requestId, stage, ownerUserId, ownerDeviceId)) {
            is Claim.Replay -> objectMapper.readValue(claim.resultJson, responseType)
            is Claim.Run -> {
                val result = try {
                    block()
                } catch (throwable: Throwable) {
                    updateStatus(claim.id, StoryCreationRequestStatus.FAILED, resultJson = null)
                    throw throwable
                }
                updateStatus(claim.id, StoryCreationRequestStatus.COMPLETED, objectMapper.writeValueAsString(result))
                result
            }
        }

    /** 신규 요청은 PENDING 행을 기록해 실행하도록, 재요청은 상태에 따라 replay·409·재실행으로 분기한다. */
    private fun claimOrReplay(
        requestId: UUID,
        stage: StoryCreationStage,
        ownerUserId: Long?,
        ownerDeviceId: String?,
    ): Claim = txTemplate.execute {
        val existing = repository.findByRequestId(requestId)
        if (existing != null) {
            return@execute resolveExisting(existing, ownerUserId, ownerDeviceId)
        }
        try {
            val saved = repository.saveAndFlush(
                StoryCreationRequest(
                    requestId = requestId,
                    userId = ownerUserId,
                    deviceId = ownerDeviceId,
                    stage = stage,
                    status = StoryCreationRequestStatus.PENDING,
                ),
            )
            Claim.Run(saved.id)
        } catch (exception: DataIntegrityViolationException) {
            // 동시 삽입 경합: 유니크 제약 위반이면 승자 행을 다시 읽어 재요청으로 처리한다.
            val now = repository.findByRequestId(requestId) ?: throw exception
            resolveExisting(now, ownerUserId, ownerDeviceId)
        }
    } ?: error("Story creation request claim transaction result is empty")

    private fun resolveExisting(row: StoryCreationRequest, ownerUserId: Long?, ownerDeviceId: String?): Claim {
        if (!row.isOwnedBy(ownerUserId, ownerDeviceId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 사용된 요청 ID입니다.")
        }
        return when (row.status) {
            StoryCreationRequestStatus.COMPLETED ->
                Claim.Replay(row.resultJson ?: error("COMPLETED story creation request has no stored result"))
            StoryCreationRequestStatus.PENDING ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "이미 진행 중인 생성 요청입니다.")
            StoryCreationRequestStatus.FAILED -> {
                // 일시 실패 재시도: 같은 requestId로 다시 실행하도록 PENDING으로 되돌린다.
                row.status = StoryCreationRequestStatus.PENDING
                repository.saveAndFlush(row)
                Claim.Run(row.id)
            }
        }
    }

    private fun updateStatus(id: Long, status: StoryCreationRequestStatus, resultJson: String?) {
        txTemplate.execute {
            val row = repository.findById(id)
                .orElseThrow { IllegalStateException("Story creation request row $id disappeared") }
            row.status = status
            if (resultJson != null) {
                row.resultJson = resultJson
            }
            repository.save(row)
        }
    }

    private sealed interface Claim {
        /** 새로 기록한(또는 재실행할) 요청 행 — [block]을 실행한다. */
        data class Run(val id: Long) : Claim

        /** 이미 COMPLETED인 요청 — 저장된 결과를 [block] 없이 반환한다. */
        data class Replay(val resultJson: String) : Claim
    }
}
