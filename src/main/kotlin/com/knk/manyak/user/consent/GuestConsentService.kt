package com.knk.manyak.user.consent

import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.DeviceIdHasher
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class GuestConsentService(
    private val repository: GuestConsentRepository,
    private val deviceIdHasher: DeviceIdHasher,
    @Value("\${manyak.legal.guest-privacy-version}") private val guestPrivacyVersion: String,
) {
    @Transactional(readOnly = true)
    fun getConsents(deviceId: String?): GuestConsentResponse = statusOf(requireDeviceHash(deviceId))

    @Transactional
    fun recordConsents(deviceId: String?, request: GuestConsentRequest): GuestConsentResponse {
        val hash = requireDeviceHash(deviceId)
        val version = request.guestPrivacy
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "동의 항목을 하나 이상 제출해야 합니다.")
        if (version != guestPrivacyVersion) {
            throw CodedResponseStatusException(
                HttpStatus.BAD_REQUEST, ApiErrorCodes.CONSENT_VERSION_MISMATCH,
                "현행 문서 버전을 확인하고 다시 동의해 주세요.",
            )
        }
        repository.insertIfAbsent(hash, GuestConsentDocType.GUEST_PRIVACY.name, version)
        return statusOf(hash)
    }

    private fun requireDeviceHash(deviceId: String?): String {
        val raw = deviceId?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "게스트의 동의 요청은 X-Manyak-Device-Id 헤더가 필요합니다.",
            )
        return deviceIdHasher.hash(raw)
    }

    private fun statusOf(hash: String): GuestConsentResponse {
        val agreed = repository.findAllByDeviceIdHash(hash).any {
            it.docType == GuestConsentDocType.GUEST_PRIVACY && it.version == guestPrivacyVersion
        }
        return GuestConsentResponse(ConsentStatusResponse(guestPrivacyVersion, !agreed))
    }
}
