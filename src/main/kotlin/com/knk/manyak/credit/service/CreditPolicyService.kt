package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
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
 * **읽기는 순수 메모리 연산이다.** [amountOf]는 DB를 만지지 않고, 적재는 부팅 1회
 * ([preloadAndLogOnStartup])와 주기 갱신([com.knk.manyak.credit.scheduler.CreditPolicyRefreshScheduler])만 한다.
 * 크레딧 경로는 `@Transactional` 안에서 이 값을 읽는데(출석 적립·초대 제출·턴 차감), 그 안에서 DB I/O를 하면
 * 두 가지가 따라온다(Codex 리뷰 P2 두 라운드):
 * - 조회 실패가 **바깥 트랜잭션을 rollback-only로 찍어** 기본값 폴백이 있어도 커밋이 대신 실패한다.
 * - `REQUIRES_NEW`로 그걸 피하면 이번엔 바깥 커넥션을 쥔 채 **두 번째 커넥션을 기다려**, 풀이 포화된 순간
 *   돈 경로가 connection timeout까지 멈춘다.
 * 호출자 트랜잭션에서 아예 조회하지 않으면 두 문제가 함께 사라진다. 그래서 락도 핫 경로엔 없다
 * ([refreshLock]은 갱신끼리만 부딪친다).
 *
 * 만료(`effective_until`)는 캐시가 아니라 **읽을 때** 판정하므로 갱신 주기를 기다리지 않고 즉시 반영된다.
 *
 * 장애 내성: 적재가 실패해도 요청을 막지 않는다. warn 로그를 남기고 직전 스냅샷을 유지하며, 스냅샷이 아직
 * 없으면(부팅 첫 적재 실패) 자연히 yml 기본값이 된다. 실패마다 기본값으로 떨어뜨리지 않는 이유는 진행 중인
 * 이벤트 수치가 DB 순단마다 조용히 되돌아가면 안 되기 때문이다.
 * **한계**: DB가 끊긴 동안에는 `effective_until`이 NULL인 영구 오버라이드를 철회할 수 없다(행을 지워도
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

    private val knownKeys = CreditPolicyKey.entries.joinToString(", ") { it.storageKey }

    // 갱신끼리만 직렬화한다(부팅 선적재 · 스케줄러). 읽기 경로는 이 락을 잡지 않는다.
    private val refreshLock = Any()

    @Volatile
    private var overrides: List<CreditPolicy> = emptyList()

    // 직전 갱신에서 **관측한** 유효값. 변경 로그의 비교 기준이며 refreshLock이 지킨다.
    // 스냅샷의 원본 행을 다시 평가해 비교하면 안 된다: effective_until 만료는 갱신 사이에 일어나고,
    // 다음 갱신에서 before·after를 같은 now로 재평가하면 둘 다 기본값이라 차이가 사라져
    // 만료가 영영 로그에 남지 않는다(= 한시 이벤트가 끝났는지 확인할 신호가 없다).
    private var lastEffective: Map<CreditPolicyKey, Long>? = null

    /**
     * [key]의 현재 유효값. 오버라이드가 없거나 만료됐거나 최소값 미만이면 yml 기본값이다.
     * **DB를 조회하지 않는다**(메모리 스냅샷 읽기 + 만료 판정뿐).
     *
     * 소모 경로는 한 요청 안에서 이 값을 **한 번만 읽어 재사용**해야 한다(차감액과 환불액이 어긋나면
     * 사용자가 손해를 보거나 이득을 본다). 호출부가 지역 변수로 붙잡아 차감·환불에 같은 값을 넘긴다.
     */
    fun amountOf(key: CreditPolicyKey): Long {
        val now = clock.instant()
        return amountIn(overrides, key, now)
    }

    /**
     * 전체 키의 현재 유효값을 **한 번의 스냅샷·한 번의 now**로 계산한다. 6종을 [amountOf]로 따로 읽으면 그 사이에
     * [refresh]가 끼어들어 한 응답 안에 옛 값과 새 값이 섞인다(만료 경계도 키마다 다르게 판정된다).
     * 여러 수치를 함께 내보내는 조회(`GET /api/v1/credits/policies`)는 이걸 쓴다. DB를 조회하지 않는다.
     */
    fun effectiveAmounts(): Map<CreditPolicyKey, Long> = effectiveOf(overrides, clock.instant())

    /**
     * 오버라이드 스냅샷을 다시 적재한다. 부팅 선적재와 주기 스케줄러가 호출하며, **예외를 밖으로 내보내지 않는다**
     * (스케줄러가 한 번의 예외로 영구 중단되지 않도록, 그리고 부팅이 DB 사정으로 실패하지 않도록).
     */
    fun refresh() {
        synchronized(refreshLock) {
            val now = clock.instant()
            try {
                val loaded = validOverrides(creditPolicyRepository.findAll())
                overrides = loaded
                logChanges(after = loaded, now = now)
            } catch (exception: Exception) {
                // 정책 적재 실패로 크레딧 지급·차감을 막지 않는다. 직전 스냅샷(없으면 빈 목록 = 기본값)을 유지한다.
                logger.warn("credit_policy_load_failed: 직전 스냅샷을 유지한다", exception)
                // 첫 적재가 실패했으면 **지금 쓰는 폴백 값**을 관측값으로 심는다. 비워 두면 DB 복구 후 첫 갱신이
                // "최초 관측"으로 보여 아무것도 남기지 않고, 시작 장애 뒤 정책이 조용히 활성화된다.
                if (lastEffective == null) {
                    lastEffective = effectiveOf(overrides, now)
                }
            }
        }
    }

    /** 부팅 시 한 번 선적재하고, 지금 유효한 전체 수치를 한 줄로 남긴다(코드만 봐서는 운영에 뭐가 걸렸는지 알 수 없다). */
    @EventListener(ApplicationReadyEvent::class)
    fun preloadAndLogOnStartup() {
        refresh()
        logger.info("credit_policy_effective {}", effectiveOf(overrides, clock.instant()))
    }

    /**
     * 쓸 수 없는 오버라이드(모르는 키·최소값 미만)를 걸러낸다. 예외를 던지지 않는다 — 운영 SQL 실수로
     * 크레딧 경로가 죽으면 안 되므로 그 행만 버리고 기본값으로 되돌린다.
     *
     * 두 실수 모두 **버리되 반드시 warn으로 드러낸다**. 운영 SQL이 유일한 변경 수단이라 키 오타
     * (`attendence_reward`)는 DB CHECK를 통과하는데, 조용히 무시하면 잘못된 정책 변경이 이벤트 기간 내내
     * 미적용인 채 지나간다. 두 사유를 같은 자리·같은 레벨로 남겨 운영자가 같은 방식으로 발견하게 한다.
     *
     * 읽을 때가 아니라 적재할 때 거르므로 warn은 갱신마다 한 번이다(요청마다 찍히면 로그가 폭주한다).
     */
    private fun validOverrides(loaded: List<CreditPolicy>): List<CreditPolicy> {
        val byKey = CreditPolicyKey.entries.associateBy { it.storageKey }
        return loaded.filter { override ->
            val key = byKey[override.policyKey]
            if (key == null) {
                // 유효한 키 목록을 함께 실어 오타를 바로 잡을 수 있게 한다.
                logger.warn(
                    "credit_policy_override_unknown_key key={} amount={} — 무시하고 기본값을 쓴다. 유효한 키: {}",
                    override.policyKey,
                    override.amount,
                    knownKeys,
                )
                return@filter false
            }
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

    /**
     * 직전에 관측한 유효값과 견줘 바뀐 키만 남긴다. 오버라이드 적용·철회뿐 아니라 **`effective_until` 자동 만료**도
     * 여기서 드러난다(만료는 갱신 주기만큼 늦게 로그에 남지만, 실제 값은 읽는 즉시 되돌아간다).
     *
     * 첫 갱신은 비교 대상이 없어 아무것도 남기지 않는다 — 그 시점의 전체 유효값은 부팅 로그
     * [preloadAndLogOnStartup]가 한 줄로 찍는다.
     */
    private fun logChanges(after: List<CreditPolicy>, now: Instant) {
        val current = effectiveOf(after, now)
        val previous = lastEffective
        lastEffective = current
        if (previous == null) return
        CreditPolicyKey.entries
            .filter { previous[it] != current[it] }
            .forEach { logger.info("credit_policy_changed key={} from={} to={}", it.storageKey, previous[it], current[it]) }
    }

    private fun effectiveOf(overrides: List<CreditPolicy>, now: Instant): Map<CreditPolicyKey, Long> =
        CreditPolicyKey.entries.associateWith { amountIn(overrides, it, now) }

    private fun amountIn(overrides: List<CreditPolicy>, key: CreditPolicyKey, now: Instant): Long =
        overrides.firstOrNull { it.policyKey == key.storageKey && it.isEffectiveAt(now) }?.amount
            ?: defaults.getValue(key)
}
