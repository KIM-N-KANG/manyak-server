---
name: create-http-verification
description: manyak-server에서 Spring Boot API 엔드포인트를 추가하거나 요청/응답 계약(필드·상태 코드·기본값)을 바꿨을 때, 사람이 IntelliJ HTTP Client로 직접 눌러보는 `http/*.http` 수동 검증 파일을 만들거나 갱신할 때 반드시 사용하세요. "http 파일 만들어줘", "수동 검증 파일", "직접 찔러볼 파일", "엔드포인트 테스트해볼 파일" 같은 명시적 요청은 물론, 컨트롤러·서비스에 새 엔드포인트를 구현했거나 응답을 바꾼 작업이면 사용자가 말하지 않아도 검증 파일을 함께 만드세요. 이 파일에는 IntelliJ에서만 통하는 비자명한 규칙(요청 간 값 전달은 응답 핸들러의 client.global.set + {{변수}}, VS Code식 응답 체이닝 문법 금지)과 표준 구조(헬스체크·준비 흐름·에러 케이스·자유 조회 요청)가 있어, 그냥 작성하면 "unsubstituted variable" 같은 함정에 빠집니다. 그러니 직접 쓰지 말고 이 스킬을 따르세요. 통합 테스트(H2)와 별개로 실제 서버·실 DB를 사람이 눈으로 확인하는 용도입니다.
---

# HTTP 검증 파일 작성

## 목적

통합 테스트는 H2 인메모리 DB에서 격리·재현성 있게 동작을 고정한다. 하지만 개발자가 **실제 서버(실 Postgres·AI)에 띄워 직접 눌러보는** 손맛 검증은 별도로 필요하다. 이 `.http` 파일이 그 역할이며, IntelliJ HTTP Client / VS Code REST Client에서 클릭 한 번으로 요청을 보내고 응답을 눈으로 확인한다.

핵심 가치는 "파일을 빨리 만드는 것"이 아니라, **IntelliJ에서 실제로 동작하고(문법 함정 회피)**, 해피 패스와 에러 케이스를 모두 담아 **리뷰어·동료가 그대로 따라 실행**할 수 있게 만드는 것이다.

## 언제 만드나

- 새 엔드포인트를 구현했거나, 기존 엔드포인트의 요청/응답 계약·상태 코드·기본값을 바꿨을 때 (테스트와 함께 항상 작성/갱신)
- 사용자가 "http 파일", "수동 검증", "직접 눌러볼 파일"을 요청할 때

## 위치와 네이밍

- 항상 프로젝트 루트의 `http/` 디렉터리에 둔다.
- 기능/리소스 단위로 한 파일: `http/<기능>.http` (예: `story-detail.http`, `story-list.http`, `simple-story-creation.http`).
- 이미 관련 파일이 있으면 새로 만들지 말고 **기존 파일에 섹션을 추가**한다.

## IntelliJ 호환 — 가장 중요한 함정

요청 사이에 값을 넘길 때 **반드시 응답 핸들러의 `client.global.set` + `{{변수}}`** 를 쓴다.

VS Code REST Client의 응답 체이닝 문법(`{{요청명.response.body.$.field}}`)은 **IntelliJ가 지원하지 않아** "Invalid request because of unsubstituted variable" 에러가 난다. 우리 팀은 IntelliJ를 쓰므로 이 문법은 절대 쓰지 않는다.

올바른 패턴:

```
### 1. 선행 요청
POST {{baseUrl}}/api/v1/stories/simple/storylines
Content-Type: application/json

{ ...요청 본문... }

> {%
    client.global.set("simpleCreationId", response.body.simpleCreationId);
    client.global.set("storylineId", response.body.storylines[0].id);
%}

### 2. 후행 요청 — 위에서 저장한 변수를 사용
POST {{baseUrl}}/api/v1/stories/simple
Content-Type: application/json

{ "simpleCreationId": {{simpleCreationId}}, "storylineId": {{storylineId}} }
```

이 방식은 **선행 요청이 먼저 실행돼야** 변수가 채워진다. 그래서 파일은 항상 위에서부터 순서대로 실행하는 흐름으로 구성하고, 순서 의존성을 주석으로 명시한다.

## 표준 구조

위에서부터 순서대로 실행하면 완결되도록 다음 순서로 구성한다.

1. **헤더 주석**: 파일 용도, 사전 준비(`./gradlew bootRun`, `docker compose up -d`로 Postgres·AI 기동), 실행 순서 안내.
2. **`@baseUrl` 변수**: `@baseUrl = http://localhost:8080` (포트가 다르면 조정).
3. **헬스 체크**: `GET {{baseUrl}}/actuator/health` — 서버가 떴는지 먼저 확인.
4. **준비 흐름(필요 시)**: 대상 엔드포인트가 데이터를 전제하면, 그 데이터를 만드는 선행 요청을 두고 응답 핸들러로 id를 전역 저장한다. (예: 상세/목록 조회는 스토리를 먼저 생성)
5. **대상 엔드포인트 — 핵심 검증**: 응답 핸들러 `> {% ... %}` 안에 `client.test(...)`로 주요 필드·매핑·기본값을 어서션한다.
6. **에러 케이스**: 해당 엔드포인트가 내는 상태 코드를 각각 한 요청씩 (`400` 검증 실패, `404` 미존재, `409` 충돌, `502` 외부 연동 실패 등). 각 요청 핸들러에서 상태 코드와 메시지를 어서션한다.
7. **자유 조회 요청(권장)**: 준비 흐름·어서션 없이, 실제 DB에 있는 id 등을 직접 넣어 눈으로 보는 요청을 하나 둔다. 핵심 검증 섹션은 "정확히 N건" 같은 고정 시나리오라 임의 데이터를 넣으면 어서션이 깨지기 때문이다.

## 어서션 작성

응답 핸들러에서 `client.test("설명", () => { client.assert(조건, "실패 메시지"); })` 형태로 쓴다. 실패 메시지에는 기대값과 실제값을 함께 넣어 디버깅이 쉽게 한다.

```
> {%
    client.test("상세 조회 응답이 정상이다", () => {
        client.assert(response.status === 200, `200이 아닙니다. actual=${response.status}`);
    });
    const b = response.body;
    client.test("genres가 한글 문자열 배열이다", () => {
        client.assert(Array.isArray(b.genres) && b.genres.indexOf("다크 판타지") >= 0,
            `genres가 기대와 다릅니다. actual=${JSON.stringify(b.genres)}`);
    });
%}
```

JSON 응답에서 null 필드는 그대로 `null`로 직렬화되므로, "값이 비어 있음"은 `=== null`로 확인한다.

## 마무리

- 파일을 만든 뒤, 가능하면 실제 서버에 한 번 실행해 동작을 확인하거나(서버가 떠 있으면), 최소한 통합 테스트로 같은 동작이 검증됨을 보고한다.
- 커밋 시 이 `.http` 파일은 작업 산출물의 일부로 함께 커밋한다(팀 컨벤션상 `Test` 태그가 자연스럽다).
- 좋은 본보기: `http/story-detail.http`, `http/story-list.http`, `http/simple-story-creation.http`.
