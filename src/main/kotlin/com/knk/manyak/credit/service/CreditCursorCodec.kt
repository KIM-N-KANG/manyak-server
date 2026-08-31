package com.knk.manyak.credit.service

import com.knk.manyak.auth.config.AuthProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 이용내역 커서가 가리키는 원장 위치. 정렬 키 `(createdAt, id)`와 같은 쌍이다. */
data class CreditCursor(val createdAt: Instant, val id: Long)

/**
 * 이용내역 커서를 봉인·해제한다(KNK-1044).
 *
 * 커서에는 정렬 타이브레이커로 `credit_transactions.id`(순차 PK)가 들어가야 하는데, 이를 base64로만 감싸면
 * 디코딩 한 번에 드러난다. 원장 행을 id로 가져오는 엔드포인트가 없어 IDOR로는 이어지지 않지만, id가 전역
 * 시퀀스라 **커서 두 개를 시차를 두고 받아 빼면 서비스 전체 크레딧 거래량이 추정된다**. 그래서 인코딩이
 * 아니라 암호화한다(외부 노출 식별자 규칙, `AGENTS.md`).
 *
 * 키는 새 시크릿을 만들지 않고 JWT 대칭키에서 파생한다([deriveKey]) — 배포 시 주입할 값이 늘지 않는다.
 * 서명키를 그대로 쓰지 않고 용도 문자열([KEY_INFO])을 거치므로, 커서 키가 새도 JWT 서명키는 복원되지 않는다.
 */
@Component
class CreditCursorCodec(properties: AuthProperties) {

    private val key = deriveKey(properties.secret)
    private val random = SecureRandom()

    /** `(createdAt, id)`를 AES-GCM으로 봉인해 `base64url(iv ‖ ciphertext+tag)`로 만든다. */
    fun encode(cursor: CreditCursor): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val sealed = cipher.doFinal("${cursor.createdAt.epochSecond}:${cursor.createdAt.nano}:${cursor.id}".toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + sealed)
    }

    /**
     * 봉인을 풀어 커서를 복원한다. 형식 오류·변조·다른 키로 만든 커서는 모두 [IllegalArgumentException]이다
     * (호출부가 400으로 바꾼다). GCM 인증 태그가 위조를 잡으므로 변조된 커서는 복호화 단계에서 실패한다.
     */
    fun decode(cursor: String): CreditCursor = runCatching {
        val raw = Base64.getUrlDecoder().decode(cursor)
        require(raw.size > IV_BYTES) { "커서가 짧습니다." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        }
        val (second, nano, id) = String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)).split(":", limit = 3)
        CreditCursor(Instant.ofEpochSecond(second.toLong(), nano.toLong()), id.toLong())
    }.getOrElse {
        // 실패 원인(형식·태그 불일치·파싱)을 밖으로 흘리지 않는다. 호출부의 400 메시지도 원인을 구분하지 않는다.
        throw IllegalArgumentException("커서가 올바르지 않습니다.")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_INFO = "credit-cursor-v1"

        /** JWT 대칭키를 PRF(HMAC-SHA256)에 통과시켜 커서 전용 256bit 키를 만든다. 용도가 다른 키를 같은 값으로 쓰지 않기 위함이다. */
        fun deriveKey(secret: String): SecretKeySpec {
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            }
            return SecretKeySpec(mac.doFinal(KEY_INFO.toByteArray()), "AES")
        }
    }
}
