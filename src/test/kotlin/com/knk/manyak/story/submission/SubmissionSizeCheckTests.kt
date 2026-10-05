package com.knk.manyak.story.submission

import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class SubmissionSizeCheckTests {
    private val mapper = JsonMapper()
    private val storage = mock(UploadedImageStorage::class.java)

    @Test fun `UTF8 JSON과 base64의 합계가 예산과 같으면 허용하고 1바이트 초과하면 거절한다`() {
        val input = mapper.readTree("""{"title":"한글","thumbnailUrl":"https://cdn.test/cover.webp"}""")
        `when`(storage.serveUrlOf("")).thenReturn("https://cdn.test/")
        `when`(storage.head("cover.webp")).thenReturn(UploadedObject("image/webp", 3))
        val actualJson = input.deepCopy() as ObjectNode
        actualJson.put("submissionId", "00000000-0000-0000-0000-000000000000")
        val total = 4L + mapper.writeValueAsBytes(actualJson).size
        SubmissionSizeCheck(storage, mapper, total).check(input, input, emptyMap(), false)
        assertThrows(CodedResponseStatusException::class.java) {
            SubmissionSizeCheck(storage, mapper, total - 1).check(input, input, emptyMap(), false)
        }.also { assertEquals("IMAGES_TOO_LARGE", it.errorCode) }
    }

    @Test fun `유지 인물 이미지 중복 참조도 전송 장수대로 합산한다`() {
        val input = mapper.readTree("""{"characters":[{"images":[{"imageUrl":"https://cdn.test/face.webp"},{"imageUrl":"https://cdn.test/face.webp"}]}]}""")
        `when`(storage.serveUrlOf("")).thenReturn("https://cdn.test/")
        `when`(storage.head("face.webp")).thenReturn(UploadedObject("image/webp", 900))
        assertThrows(CodedResponseStatusException::class.java) {
            SubmissionSizeCheck(storage, mapper, 2000).check(input, input, emptyMap(), true)
        }
        verify(storage, times(2)).head("face.webp")
    }

    @Test fun `외부 URL과 없는 객체는 5MiB로 계산하고 외부 호스트를 호출하지 않는다`() {
        val input = mapper.readTree("""{"thumbnailUrl":"https://external.test/cover.webp"}""")
        `when`(storage.serveUrlOf("")).thenReturn("https://cdn.test/")
        assertThrows(CodedResponseStatusException::class.java) {
            SubmissionSizeCheck(storage, mapper, 6L * 1024 * 1024).check(input, input, emptyMap(), true)
        }
        verify(storage, never()).head(anyString())
    }
}
