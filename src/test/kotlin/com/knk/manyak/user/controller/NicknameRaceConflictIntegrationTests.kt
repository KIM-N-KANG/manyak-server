package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 닉네임 유니크 위반의 경합 경로(KNK-1147, Codex 리뷰 P2).
 *
 * 사전 조회(자기 제외 exists)와 커밋 사이에 다른 요청이 같은 정규화 키를 차지할 수 있고, 그때는 유니크
 * 인덱스(V75)가 최종 방어선이다. 그 위반은 사용자에게 **500이 아니라 409**로 보여야 한다.
 *
 * 테스트 프로파일은 H2 + `ddl-auto`라 함수 유니크 인덱스가 없어(마이그레이션을 타지 않는다) 실제 위반을
 * 만들 수 없다. 그래서 저장 시점의 위반만 스파이로 흉내 내, **예외를 어디서 잡는지**를 고정한다 —
 * 트랜잭션 안에서 잡아 커밋으로 넘어가면 rollback-only 오염으로 500이 된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NicknameRaceConflictIntegrationTests {

    @MockitoSpyBean private lateinit var userRepository: UserRepository

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `저장 시점에 닉네임 유니크가 깨지면 500이 아니라 409다`() {
        val user = userRepository.save(User(nickname = "몽환적인 이야기꾼"))
        // 사전 조회는 통과했는데 커밋 직전에 상대가 같은 키를 차지한 상황.
        doThrow(DataIntegrityViolationException("uq_users_nickname_key")).`when`(userRepository).flush()

        restTestClient.patch()
            .uri("/api/v1/users/me")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"nickname":"먼저 차지당한 닉네임"}""")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NICKNAME_TAKEN")
    }
}
