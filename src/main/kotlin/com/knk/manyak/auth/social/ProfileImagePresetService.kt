package com.knk.manyak.auth.social

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 닉네임 명사에 1:1 매핑된 프로필 프리셋 이미지를 배정한다(스펙 §4-5 B7, KNK-388).
 *
 * 자산은 이 레포의 static 리소스(`static/profile-presets/{명사}.png`, 256×256)이며 `api.manyak.app`에서 직접 서빙한다
 * (아바타 경로에 제3자 호스트를 두지 않음 — B7 취지). 시작 시 명사 풀([RandomNicknameGenerator.NOUNS])을 훑어 명사별
 * 저해상도 썸네일(base64)을 미리 만든다(40장, 가벼움). 매핑에 없는 명사는 URL·썸네일 모두 null → 클라이언트 기본 아바타.
 *
 * `profile_image_url`에 전체 URL을 저장하므로, 후속 S3/CDN 전환은 [manyak.asset.profile-preset-base-url] 치환만으로 된다.
 */
@Service
class ProfileImagePresetService(
    @Value("\${manyak.asset.profile-preset-base-url:https://api.manyak.app}")
    baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // 후행 슬래시를 제거해 URL 조립을 일관되게 한다.
    private val baseUrl: String = baseUrl.trimEnd('/')

    // 명사 → 저해상도 썸네일 base64. 키 집합이 곧 "프리셋 이미지가 있는 명사"다(누락 명사는 매핑에 없음).
    private val thumbnailsByNoun: Map<String, String> = loadThumbnails()

    /** 명사의 프리셋 이미지 URL. 매핑이 없으면 null. 한글 명사는 경로 세그먼트로 퍼센트 인코딩한다. */
    fun imageUrlFor(noun: String): String? =
        if (thumbnailsByNoun.containsKey(noun)) {
            "$baseUrl/$PRESET_PATH/${UriUtils.encodePathSegment(noun, StandardCharsets.UTF_8)}.png"
        } else {
            null
        }

    /** 명사의 저해상도 썸네일 base64(목록·미리보기용). 매핑이 없으면 null. */
    fun thumbnailBase64For(noun: String): String? = thumbnailsByNoun[noun]

    /** 명사 풀을 훑어 프리셋 리소스가 있는 명사만 썸네일을 만든다. 누락은 경고 후 건너뛴다(해당 명사는 null 폴백). */
    private fun loadThumbnails(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (noun in RandomNicknameGenerator.NOUNS) {
            val resource = ClassPathResource("static/$PRESET_PATH/$noun.png")
            if (!resource.exists()) {
                logger.warn("프로필 프리셋 이미지 누락 — 기본 아바타로 폴백: noun={}", noun)
                continue
            }
            val thumbnail = runCatching { resource.inputStream.use(::encodeThumbnail) }
                .getOrElse {
                    logger.warn("프로필 프리셋 썸네일 생성 실패 — 기본 아바타로 폴백: noun={}", noun, it)
                    null
                } ?: continue
            map[noun] = thumbnail
        }
        logger.info("프로필 프리셋 썸네일 {}/{}종 프리로드", map.size, RandomNicknameGenerator.NOUNS.size)
        return map
    }

    /** 원본을 [THUMBNAIL_SIZE]px 정사각으로 다운스케일해 PNG base64로 인코딩한다(목록·미리보기용 저해상도). */
    private fun encodeThumbnail(input: InputStream): String {
        val original = ImageIO.read(input) ?: error("PNG 디코딩 실패")
        val scaled = BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(original, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, null)
        } finally {
            graphics.dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        const val PRESET_PATH = "profile-presets"

        // 목록·미리보기용 저해상도(원본 256×256 → 48×48).
        const val THUMBNAIL_SIZE = 48
    }
}
