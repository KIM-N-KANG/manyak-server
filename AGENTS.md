# Codex 작업 지침

## 리뷰 규칙

- 리뷰 코멘트와 리뷰 요약은 **한국어**로 작성한다. 코드 식별자, 파일 경로, 로그, 오류 메시지는 원문을 유지한다.
- 단순 스타일이나 포매팅 문제는 리뷰하지 않는다.
- 작업·리뷰 전에 `CLAUDE.md`와 스펙 정본 `../knk-harness/docs/product-specs/`(특히 `0-glossary.md`, `4-backend.md`, `5-ai-server.md`)를 확인한다. 코드와 스펙이 다르면 **코드를 스펙에 맞춘다**.
- 이 파일과 `CLAUDE.md`가 충돌하면 `CLAUDE.md`를 따른다. 이 파일은 리뷰에 필요한 부분만 추린 요약이다.

## 이 레포에서 자주 나는 오독

- **테스트는 Flyway를 건너뛴다.** 테스트 프로파일은 H2 + `ddl-auto` + `flyway.enabled=false`이므로 `./gradlew test` 통과는 마이그레이션·DB 체크 제약 검증이 아니다. 마이그레이션 검증 경로는 `scripts/gen-db-docs.sh`(실 PostgreSQL에 Flyway 적용)이고, 재생성된 `dbdoc/`을 함께 커밋한다. 체크 제약 위반을 red로 만드는 테스트는 이 환경에서 성립하지 않으니 그런 테스트의 부재를 지적하지 않는다.
- **스토리 공개 읽기 게이트.** publicId로 스토리를 읽는 모든 공개 소비자(상세·batch·자식 리소스 GET·채팅 생성 등)는 `Story.isReadableBy(userId)`를 적용해야 한다. 빠뜨리면 비공개 초안이 유출된다. 스토리 관련 엔드포인트가 추가·변경되면 이 게이트부터 확인한다.
- **외부 노출 식별자는 `public_id`(UUID)다.** 순차 PK를 API에 노출하지 않는다(IDOR 방지).
- **쓰기 엔드포인트는 PATCH 대신 PUT**을 쓴다. CORS `allowedMethods`가 PATCH를 허용하지 않으므로 PATCH 전환을 권하지 않는다.
- **Jackson 3를 쓴다.** databind는 `tools.jackson.databind.*`(ObjectMapper·JsonNode), 애노테이션만 `com.fasterxml.*`다. `com.fasterxml.jackson.databind` 임포트를 권하면 컨텍스트 로드가 깨진다.
- **용어는 용어집을 따른다.** 스토리(이야기 아님)·스토리라인·턴(`turnCount`/`turn_number`)·추천 입력(`suggestedInputs`)·`additional_info`·로어북. 로어북(장르 공용 용어 사전, 트리거 없음)과 키워드북(트리거 키워드)은 다른 개념이니 혼용하지 않는다.
