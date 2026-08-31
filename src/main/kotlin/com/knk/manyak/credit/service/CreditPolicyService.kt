package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 런타임 조정 가능한 크레딧 수치의 키(KNK-1056). [storageKey]가 `credit_policies.policy_key`와 짝이다.
 *
 * enum으로 두는 이유: 조회를 문자열로 하면 오타가 예외가 아니라 "조용히 기본값"으로 나타나 아무도 눈치채지 못한다.
 *
 * [minimumAmount]는 **이 값 미만이면 크레딧 경로가 깨지는 하한**이다. 보상·소모 5종은 1 이상이어야 한다 —
 * [CreditWalletService.reward]·[CreditWalletService.deduct]가 `require(amount > 0)`이라, 0을 넣으면
 * "보상을 잠깐 끄기"가 아니라 그 경로 전체가 500이 된다. `invite_monthly_cap`만 0을 허용한다:
 * 상한 0은 "초대자 적립 중단"이라는 뜻이 통하는 설정이고, 기존 코드가 이미 감당한다(집계 0 >= 상한 0 →
 * 초대자 몫만 조용히 스킵, 제출자 몫은 정상 적립).
 *
 * 0을 "기능 끄기"로 해석하지 않는다 — no-op 시맨틱은 응답 계약(`rewarded`·`amount`)과 관계 저장까지
 * 정해야 하는 별도 기능이다. 지금은 "잘못된 값이므로 무시하고 기본값"이 맞다.
 *
 * 상·하한 방어의 분담: DB CHECK(`amount BETWEEN 0 AND 10000`)는 자릿수 오타를 막는 **거친 방어선**이고,
 * 키마다 다른 세밀한 판정은 여기(애플리케이션) 몫이다. DB는 어떤 키가 0을 허용하는지 모른다.
 */
enum class CreditPolicyKey(val storageKey: String, val minimumAmount: Long) {
    SIGNUP_REWARD("signup_reward", 1),
    INVITE_REWARD("invite_reward", 1),
    INVITE_MONTHLY_CAP("invite_monthly_cap", 0),
    ATTENDANCE_REWARD("attendance_reward", 1),
    STORY_CREATION_COST("story_creation_cost", 1),
    CHAT_TURN_COST("chat_turn_cost", 1),
}

/**
 * 크레딧 수치를 해석한다(KNK-1056, 스펙 §4-3-7): 유효한 오버라이드 행이 있으면 그 값, 없으면 yml 기본값.
 *
 * yml 기본값을 각 서비스가 아니라 **여기 한 곳에** 모아 [CreditPolicyKey]와 exhaustive `when`으로 묶는다.
 * 키를 늘렸는데 기본값을 빠뜨리면 컴파일이 깨지고, 부팅 로그가 "지금 유효한 전체 수치"를 한 줄로 찍을 수 있다.
 * `@Value`에 `:기본값` 폴백을 두지 않는 것도 같은 이유다 — 폴백이 있으면 yml 키를 오타 내거나 개명해도
 * 하드코딩된 숫자가 조용히 먹어서 정본이 둘이 된다. 키가 없으면 부팅이 실패하는 게 맞다.
 * (환경변수 폴백 `${MANYAK_CREDIT_*:기본값}`은 application.yml에 있고, 그쪽이 정본이다.)
 *
 * 캐시: 보상·소모 경로마다 쿼리가 늘면 안 되므로 전건을 [cacheTtl] 동안 스냅샷으로 들고 있는다. 정책 6개짜리
 * 테이블이라 부분 조회·무효화 장치가 필요 없다. `effective_until` 판정은 캐시가 아니라 **읽을 때** 하므로,
 * 만료는 TTL을 기다리지 않고 즉시 반영된다.
 *
 * 갱신은 single-flight다(`synchronized` + 이중 검사). `@Volatile`은 참조 가시성만 보장할 뿐 갱신을 단일화하지
 * 않아서, 느린 조회가 늦게 돌아와 **최신 스냅샷을 옛 값으로 덮을 수 있다**(그 상태가 TTL 내내 지속된다).
 * 금액 정책에서 이건 성능이 아니라 정확성 문제라 락으로 막는다(6행 조회라 경합 비용은 무시 가능).
 *
 * 장애 내성: 조회가 실패해도 요청을 막지 않는다(크레딧 경로가 정책 조회 때문에 죽으면 안 된다). warn 로그를
 * 남기고 직전 스냅샷을 유지한 채 TTL만 미룬다 — 스냅샷이 아직 없으면 자연히 yml 기본값이 된다.
 * 실패마다 기본값으로 떨어뜨리지 않는 이유: 진행 중인 이벤트 수치가 DB 순단마다 조용히 되돌아가면 안 된다.
 * **한계**: 그래서 DB가 끊긴 동안에는 `effective_until`이 NULL인 영구 오버라이드를 철회할 수 없다(행을 지워도
 * 스냅샷이 남는다). 유한 `effective_until`은 판정이 읽는 시점이라 장애 중에도 정상 만료된다. 영구 오버라이드
 * 철회가 급한데 DB만 끊긴 상황은 좁아서, 요청을 막지 않는다는 요구를 우선한다(회복 수단은 재기동).
 */
@Service
class CreditPolicyService(
    private val creditPolicyRepository: CreditPolicyRepository,
    // yml 기본값(스펙 §4-3-7, 2026-08-31 재확정). 오버라이드가 없을 때 쓰는 폴백이며, 정본은 application.yml이다.
    @param:Value("\${manyak.credit.signup-reward}") signupReward: Long,
    @param:Value("\${manyak.credit.invite-reward}") inviteReward: Long,
    @param:Value("\${manyak.credit.invite-monthly-cap}") inviteMonthlyCap: Long,
    @param:Value("\${manyak.credit.attendance-reward}") attendanceReward: Long,
    @param:Value("\${manyak.credit.story-creation-cost}") storyCreationCost: Long,
    @param:Value("\${manyak.credit.chat-turn-cost}") chatTurnCost: Long,
    @param:Value("\${manyak.credit.policy-cache-ttl:PT60S}") private val cacheTtl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val defaults: Map<CreditPolicyKey, Long> = CreditPolicyKey.entries.associateWith { key ->
        when (key) {
            CreditPolicyKey.SIGNUP_REWARD -> signupReward
            CreditPolicyKey.INVITE_REWARD -> inviteReward
            CreditPolicyKey.INVITE_MONTHLY_CAP -> inviteMonthlyCap
            CreditPolicyKey.ATTENDANCE_REWARD -> attendanceReward
            CreditPolicyKey.STORY_CREATION_COST -> storyCreationCost
            CreditPolicyKey.CHAT_TURN_COST -> chatTurnCost
        }
    }

    init {
        // 기본값에는 물러설 곳이 없으므로(오버라이드와 달리 폴백이 없다) 부팅을 실패시킨다.
        // 운영자가 env로 MANYAK_CREDIT_SIGNUP_REWARD=0을 넣는 사고는 배포 시점에 드러나는 게 낫다.
        defaults.forEach { (key, amount) ->
            require(amount >= key.minimumAmount) {
                "크레딧 정책 기본값이 최소값 미만입니다: ${key.storageKey}=$amount (최소 ${key.minimumAmount})"
            }
        }
    }

    private val refreshLock = Any()

    @Volatile
    private var snapshot: Snapshot = Snapshot(overrides = emptyList(), expiresAt = Instant.MIN)

    /**
     * [key]의 현재 유효값. 오버라이드가 없거나 만료됐거나 최소값 미만이면 yml 기본값이다.
     *
     * 소모 경로는 한 요청 안에서 이 값을 **한 번만 읽어 재사용**해야 한다(차감액과 환불액이 어긋나면
     * 사용자가 손해를 보거나 이득을 본다). 호출부가 지역 변수로 붙잡아 차감·환불에 같은 값을 넘긴다.
     */
    fun amountOf(key: CreditPolicyKey): Long {
        val now = clock.instant()
        return amountIn(overridesAt(now), key, now)
    }

    /** 부팅 시 지금 유효한 전체 수치를 한 줄로 남긴다. 코드만 봐서는 운영에 뭐가 걸려 있는지 알 수 없기 때문이다. */
    @EventListener(ApplicationReadyEvent::class)
    fun logEffectivePoliciesOnStartup() {
        val now = clock.instant()
        logger.info("credit_policy_effective {}", effectiveOf(overridesAt(now), now))
    }

    private fun overridesAt(now: Instant): List<CreditPolicy> {
        val cached = snapshot
        if (now.isBefore(cached.expiresAt)) return cached.overrides
        // 갱신은 한 번에 하나만. 락 안에서 TTL을 다시 확인해 이미 갱신됐으면 조회하지 않는다.
        synchronized(refreshLock) {
            val current = snapshot
            if (now.isBefore(current.expiresAt)) return current.overrides
            return try {
                val loaded = validOverrides(creditPolicyRepository.findAll())
                snapshot = Snapshot(loaded, now.plus(cacheTtl))
                logChanges(before = current.overrides, after = loaded, now = now)
                loaded
            } catch (exception: Exception) {
                // 정책 조회 실패로 크레딧 지급·차감을 막지 않는다. 직전 스냅샷을 유지하고 TTL만 미뤄 재조회를 늦춘다.
                logger.warn("credit_policy_load_failed: 직전 스냅샷을 유지한다", exception)
                snapshot = Snapshot(current.overrides, now.plus(cacheTtl))
                current.overrides
            }
        }
    }

    /**
     * 최소값을 지키지 못하는 오버라이드를 걸러낸다. 예외를 던지지 않는다 — 운영 SQL 실수로 크레딧 경로가
     * 죽으면 안 되므로 그 키만 기본값으로 되돌린다. 읽을 때가 아니라 적재할 때 거르므로 warn은 갱신마다 한 번이다.
     */
    private fun validOverrides(loaded: List<CreditPolicy>): List<CreditPolicy> {
        val byKey = CreditPolicyKey.entries.associateBy { it.storageKey }
        return loaded.filter { override ->
            val key = byKey[override.policyKey] ?: return@filter false
            val valid = override.amount >= key.minimumAmount
            if (!valid) {
                logger.warn(
                    "credit_policy_override_rejected key={} amount={} minimum={} — 기본값 {}을 쓴다",
                    key.storageKey,
                    override.amount,
                    key.minimumAmount,
                    defaults.getValue(key),
                )
            }
            valid
        }
    }

    /** 오버라이드가 적용·만료돼 유효값이 바뀐 키만 남긴다(재조회 시점 기준이라 만료 로그는 최대 [cacheTtl] 늦다). */
    private fun logChanges(before: List<CreditPolicy>, after: List<CreditPolicy>, now: Instant) {
        val previous = effectiveOf(before, now)
        val current = effectiveOf(after, now)
        CreditPolicyKey.entries
            .filter { previous[it] != current[it] }
            .forEach { logger.info("credit_policy_changed key={} from={} to={}", it.storageKey, previous[it], current[it]) }
    }

    private fun effectiveOf(overrides: List<CreditPolicy>, now: Instant): Map<CreditPolicyKey, Long> =
        CreditPolicyKey.entries.associateWith { amountIn(overrides, it, now) }

    private fun amountIn(overrides: List<CreditPolicy>, key: CreditPolicyKey, now: Instant): Long =
        overrides.firstOrNull { it.policyKey == key.storageKey && it.isEffectiveAt(now) }?.amount
            ?: defaults.getValue(key)

    private class Snapshot(val overrides: List<CreditPolicy>, val expiresAt: Instant)
}
