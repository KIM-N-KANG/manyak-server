package com.knk.manyak.story.service

import com.knk.manyak.story.entity.StoryCreationRequest
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.entity.StoryCreationStage
import com.knk.manyak.story.entity.isOwnedBy
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
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
    // 이 시간(초)보다 오래 PENDING인 행은 실행 중 프로세스가 죽은 것으로 보고 재실행을 허용한다(무한 409 방지).
    // 최장 생성 동작(스토리 완성 AI 120초)보다 충분히 커, 진행 중인 정상 요청을 가로채지 않는다.
    @param:Value("\${manyak.story.pending-reclaim-after-seconds:300}")
    private val pendingReclaimAfterSeconds: Long,
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
        ownerDeviceIdHash: String?,
        responseType: Class<T>,
        block: () -> T,
    ): T =
        when (val claim = claimOrReplay(requestId, stage, ownerUserId, ownerDeviceIdHash)) {
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

    /**
     * 신규 요청은 PENDING 행을 기록해 실행하도록, 재요청은 상태에 따라 replay·409·재실행으로 분기한다.
     *
     * 삽입 시도와 기존 행 해석을 각각 독립 트랜잭션으로 나눈다. 동시 삽입 경합의 패자는 유니크 제약 위반으로
     * 삽입 트랜잭션만 롤백되고(그 안에서 추가 질의를 하지 않아 abort된 트랜잭션을 재사용하지 않는다), 별도 트랜잭션에서
     * 승자 행을 비관적 락으로 다시 읽어 해석한다. 이 락이 동시 FAILED 재실행도 직렬화한다(둘 다 통과해 이중 실행되지 않도록).
     */
    private fun claimOrReplay(
        requestId: UUID,
        stage: StoryCreationStage,
        ownerUserId: Long?,
        ownerDeviceIdHash: String?,
    ): Claim {
        val insertedId = tryInsertPending(requestId, stage, ownerUserId, ownerDeviceIdHash)
        if (insertedId != null) {
            return Claim.Run(insertedId)
        }
        return resolveExistingLocked(requestId, stage, ownerUserId, ownerDeviceIdHash)
    }

    /** PENDING 행을 삽입하고 새 id를 돌려준다. 유니크 제약 위반(동시 삽입 경합의 패자)이면 null(행이 이미 존재). */
    private fun tryInsertPending(
        requestId: UUID,
        stage: StoryCreationStage,
        ownerUserId: Long?,
        ownerDeviceIdHash: String?,
    ): Long? =
        try {
            txTemplate.execute {
                repository.saveAndFlush(
                    StoryCreationRequest(
                        requestId = requestId,
                        userId = ownerUserId,
                        deviceIdHash = ownerDeviceIdHash,
                        stage = stage,
                        status = StoryCreationRequestStatus.PENDING,
                    ),
                ).id
            }
        } catch (exception: DataIntegrityViolationException) {
            // 유니크 제약 위반은 삽입 트랜잭션 밖으로 전파돼 그 트랜잭션만 깔끔히 롤백된다(abort된 트랜잭션에서 재조회하지 않는다).
            null
        }

    /** 기존 요청 행을 비관적 락으로 조회해 상태별로 해석한다(동시 FAILED 재실행 직렬화). */
    private fun resolveExistingLocked(requestId: UUID, stage: StoryCreationStage, ownerUserId: Long?, ownerDeviceIdHash: String?): Claim =
        txTemplate.execute {
            val row = repository.findByRequestIdForUpdate(requestId)
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "이미 사용된 요청 ID입니다.")
            resolveExisting(row, stage, ownerUserId, ownerDeviceIdHash)
        } ?: error("Story creation request claim transaction result is empty")

    private fun resolveExisting(row: StoryCreationRequest, stage: StoryCreationStage, ownerUserId: Long?, ownerDeviceIdHash: String?): Claim {
        if (!row.isOwnedBy(ownerUserId, ownerDeviceIdHash)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 사용된 요청 ID입니다.")
        }
        // 다른 생성 단계에서 쓴 requestId 재사용을 막는다. 안 그러면 replay가 저장된 응답을 다른 단계의 타입으로 역직렬화해 500이 난다.
        if (row.stage != stage) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "다른 단계에서 사용된 요청 ID입니다.")
        }
        return when (row.status) {
            StoryCreationRequestStatus.COMPLETED ->
                Claim.Replay(row.resultJson ?: error("COMPLETED story creation request has no stored result"))
            StoryCreationRequestStatus.PENDING -> {
                // 진행 중이면 409. 단 임계값보다 오래된 PENDING은 실행 중 프로세스가 죽은 잔여로 보고 재실행을 허용한다
                // (이 판정은 행 락 안에서 이뤄져 동시 재요청과 직렬화된다 — updatedAt를 갱신해 곧이은 재요청은 다시 409로 막힌다).
                if (row.updatedAt.isAfter(Instant.now().minusSeconds(pendingReclaimAfterSeconds))) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "이미 진행 중인 생성 요청입니다.")
                }
                row.updatedAt = Instant.now()
                repository.saveAndFlush(row)
                Claim.Run(row.id)
            }
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
