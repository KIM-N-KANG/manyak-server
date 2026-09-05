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
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * 프로필 수정(KNK-1147, 정책 KNK-1146). 닉네임과 프로필 이미지 프리셋을 바꾼다.
 *
 * **이 클래스는 트랜잭션을 열지 않는다.** 저장은 [ProfileUpdater]가 자기 트랜잭션 안에서 하고, 유니크 위반을
 * 409로 바꾸는 일만 그 **밖에서** 한다([com.knk.manyak.story.service.StoryService.like]와 같은 자리 잡기).
 * 안에서 잡으면 두 가지가 걸린다(Codex 리뷰 P2):
 * - 위반이 flush가 아니라 **커밋 시점**에 드러나면 트랜잭션 안의 try/catch는 아예 지나가지 못한다 → 500.
 * - 예외를 흡수하고 정상 종료하는 형태로 바뀌면 rollback-only로 오염된 트랜잭션이 커밋에서 터진다.
 * 경계 밖에서 잡으면 두 경우가 같은 409로 모인다.
 */
@Service
class UserProfileService(
    private val profileUpdater: ProfileUpdater,
    private val profileImagePresetService: ProfileImagePresetService,
) {

    fun updateProfile(userId: Long, request: UpdateProfileRequest): MeResponse =
        try {
            profileUpdater.update(userId, request)
        } catch (exception: DataIntegrityViolationException) {
            // 사전 조회와 커밋 사이에 다른 요청이 같은 정규화 키를 차지했다. 유니크 인덱스(V75)가 최종
            // 방어선이고, 그 위반은 사용자에게 500이 아니라 "이미 쓰는 닉네임"으로 보여야 한다.
            throw nicknameTaken(exception)
        }

    /** 프리셋 목록은 메모리에서만 읽는다(DB를 만지지 않아 트랜잭션이 필요 없다). */
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
}

/**
 * 프로필 수정의 트랜잭션 단위. 별도 빈인 이유는 유니크 위반 변환을 트랜잭션 **밖**에 두기 위해서다
 * ([UserProfileService] 참조 — 같은 클래스 안에서는 자기 호출이라 프록시를 타지 않는다).
 *
 * 사용자 행을 **잠그고** 상태를 재검사한다(푸시 토큰·알림 설정 API와 같은 관례) — 정지는 403, 탈퇴·부재는 401.
 * 잠금은 같은 계정의 동시 변경을 직렬화하는 역할도 한다.
 *
 * 프로필 이미지는 **프리셋 선택만** 지원한다(업로드 없음). 닉네임 변경과는 독립이라, 닉네임을 바꿔도 이미지가
 * 따라 바뀌지 않는다(가입 시의 명사 1:1 배정은 최초 1회다 — KNK-388).
 */
@Component
class ProfileUpdater(
    private val userRepository: UserRepository,
    private val profileImagePresetService: ProfileImagePresetService,
    private val meResponseAssembler: MeResponseAssembler,
) {

    @Transactional
    fun update(userId: Long, request: UpdateProfileRequest): MeResponse {
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

        // 위반을 커밋까지 미루지 않고 여기서 드러낸다(어디서 깨졌는지가 분명해진다). 잡지는 않는다 —
        // 변환은 트랜잭션 밖의 [UserProfileService.updateProfile] 몫이다.
        userRepository.flush()
        return meResponseAssembler.assemble(user)
    }
}

/** 정규화 기준으로 이미 쓰는 닉네임(KNK-1147). 사전 조회와 유니크 위반 양쪽이 같은 코드로 모인다. */
private fun nicknameTaken(cause: Throwable? = null) = CodedResponseStatusException(
    HttpStatus.CONFLICT,
    ApiErrorCodes.NICKNAME_TAKEN,
    "이미 사용 중인 닉네임입니다.",
    cause,
)
