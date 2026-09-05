package com.knk.manyak.user.service

import com.knk.manyak.auth.dto.MeResponse
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.service.MeResponseAssembler
import com.knk.manyak.auth.social.ProfileImagePresetService
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.security.requireActiveStatus
import com.knk.manyak.user.dto.ProfilePresetResponse
import com.knk.manyak.user.dto.UpdateProfileRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * 프로필 수정(KNK-1147, 정책 KNK-1146). 닉네임과 프로필 이미지 프리셋을 바꾼다.
 *
 * 사용자 행을 **잠그고** 상태를 재검사한다(푸시 토큰·알림 설정 API와 같은 관례) — 정지는 403, 탈퇴·부재는 401.
 * 잠금은 같은 계정의 동시 변경을 직렬화하는 역할도 한다.
 *
 * 프로필 이미지는 **프리셋 선택만** 지원한다(업로드 없음). 닉네임 변경과는 독립이라, 닉네임을 바꿔도 이미지가
 * 따라 바뀌지 않는다(가입 시의 명사 1:1 배정은 최초 1회다 — KNK-388).
 */
@Service
class UserProfileService(
    private val userRepository: UserRepository,
    private val profileImagePresetService: ProfileImagePresetService,
    private val meResponseAssembler: MeResponseAssembler,
) {

    @Transactional
    fun updateProfile(userId: Long, request: UpdateProfileRequest): MeResponse {
        if (request.nickname == null && request.profileImagePreset == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "바꿀 항목이 없습니다.")
        }
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        requireActiveStatus(user.status)

        request.nickname?.let { raw ->
            val nickname = requireValidNickname(raw)
            // 자기 자신은 제외한다 — 대소문자·공백만 바꾸는 변경(같은 정규화 키)을 막지 않기 위해서다.
            if (userRepository.existsByNicknameKeyExcludingSelf(nicknameKeyOf(nickname), userId)) {
                throw nicknameTaken()
            }
            user.nickname = nickname
        }
        request.profileImagePreset?.let { key ->
            if (!profileImagePresetService.hasPreset(key)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 프로필 이미지입니다.")
            }
            user.profileImageUrl = profileImagePresetService.imageUrlFor(key)
            user.profileThumbnailBase64 = profileImagePresetService.thumbnailBase64For(key)
        }

        // 사전 조회와 커밋 사이에 다른 요청이 같은 키를 차지할 수 있다. 유니크 인덱스(V75)가 최종 방어선이고,
        // 그 위반은 사용자에게 500이 아니라 "이미 쓰는 닉네임"으로 보여야 한다.
        try {
            userRepository.flush()
        } catch (exception: DataIntegrityViolationException) {
            throw nicknameTaken(exception)
        }
        return meResponseAssembler.assemble(user)
    }

    @Transactional(readOnly = true)
    fun listPresets(): List<ProfilePresetResponse> =
        profileImagePresetService.presetKeys().mapNotNull { key ->
            profileImagePresetService.imageUrlFor(key)?.let { url ->
                ProfilePresetResponse(
                    key = key,
                    imageUrl = url,
                    thumbnailBase64 = profileImagePresetService.thumbnailBase64For(key),
                )
            }
        }

    private fun nicknameTaken(cause: Throwable? = null) = CodedResponseStatusException(
        HttpStatus.CONFLICT,
        ApiErrorCodes.NICKNAME_TAKEN,
        "이미 사용 중인 닉네임입니다.",
        cause,
    )
}
