package com.knk.manyak.user.consent

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.requireActiveStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class UserConsentService(
    private val userRepository: UserRepository,
    private val userConsentRepository: UserConsentRepository,
    @Value("\${manyak.legal.terms-version}") private val termsVersion: String,
    @Value("\${manyak.legal.privacy-version}") private val privacyVersion: String,
) {
    // 조회도 사용자 행을 잠근다. PostgreSQL의 FOR UPDATE 때문에 readOnly를 사용하지 않는다.
    @Transactional
    fun getConsents(userId: Long): UserConsentResponse {
        lockActiveUser(userId)
        return statusOf(userId)
    }

    @Transactional
    fun recordConsents(userId: Long, request: UserConsentRequest): UserConsentResponse {
        lockActiveUser(userId)
        val submitted = submitted(request)
        if (submitted.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "동의 항목을 하나 이상 제출해야 합니다.")
        }
        validateVersions(request)
        submitted.forEach { (type, version) -> userConsentRepository.insertIfAbsent(userId, type.name, version) }
        return statusOf(userId)
    }

    /** 인증 전 조회와 서버 게이트는 계정 상태나 로그인 시각을 쓰지 않는다. */
    @Transactional(readOnly = true)
    fun authenticationStatus(userId: Long?): UserConsentResponse {
        fun status(type: ConsentDocType): ConsentStatusResponse {
            val version = requiredVersion(type)
            return ConsentStatusResponse(version, userId == null ||
                !userConsentRepository.existsByUserIdAndDocTypeAndVersion(userId, type, version))
        }
        return UserConsentResponse(status(ConsentDocType.TERMS), status(ConsentDocType.PRIVACY), status(ConsentDocType.AGE14))
    }

    fun validateVersions(request: UserConsentRequest) {
        submitted(request).forEach { (type, version) ->
            if (version != requiredVersion(type)) {
                throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, ApiErrorCodes.CONSENT_VERSION_MISMATCH,
                    "현행 문서 버전을 확인하고 다시 동의해 주세요.")
            }
        }
    }

    fun validateRequired(userId: Long?, request: UserConsentRequest) {
        validateVersions(request)
        val status = authenticationStatus(userId)
        if ((status.terms.needsConsent && request.terms == null) ||
            (status.privacy.needsConsent && request.privacy == null) ||
            (status.age14.needsConsent && request.age14 == null)) {
            throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, ApiErrorCodes.CONSENT_REQUIRED_MISSING,
                "필수 동의 항목을 모두 제출해 주세요.")
        }
    }

    /** 호출자의 계정 생성 트랜잭션에 참여한다. 기존 회원도 users 잠금 후 DELETED를 재검사한다. */
    @Transactional
    fun recordForAuthentication(userId: Long, request: UserConsentRequest) {
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        if (user.status == UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
        // SUSPENDED는 이 인증 완료 경로에서만 허용한다.
        validateRequired(userId, request)
        submitted(request).forEach { (type, version) -> userConsentRepository.insertIfAbsent(userId, type.name, version) }
    }

    private fun submitted(request: UserConsentRequest) = listOfNotNull(
        request.terms?.let { ConsentDocType.TERMS to it },
        request.privacy?.let { ConsentDocType.PRIVACY to it },
        request.age14?.let { ConsentDocType.AGE14 to it },
    )

    private fun lockActiveUser(userId: Long) {
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        requireActiveStatus(user.status)
    }

    private fun requiredVersion(type: ConsentDocType): String = when (type) {
        ConsentDocType.TERMS -> termsVersion
        ConsentDocType.PRIVACY -> privacyVersion
        ConsentDocType.AGE14 -> AGE14_VERSION
    }

    private fun statusOf(userId: Long): UserConsentResponse {
        val agreed = userConsentRepository.findAllByUserId(userId).map { it.docType to it.version }.toSet()
        fun status(type: ConsentDocType): ConsentStatusResponse {
            val version = requiredVersion(type)
            return ConsentStatusResponse(version, (type to version) !in agreed)
        }
        return UserConsentResponse(status(ConsentDocType.TERMS), status(ConsentDocType.PRIVACY), status(ConsentDocType.AGE14))
    }

    private companion object {
        const val AGE14_VERSION = "1"
    }
}
