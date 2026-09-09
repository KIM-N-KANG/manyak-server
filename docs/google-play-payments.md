# Google Play 이프 구매

`POST /api/v1/users/me/credits/purchases/google`는 회원의 `{productId, purchaseToken}`을 받아 Google 구매를 검증하고 `{orderId, balance}`를 반환한다. 주문 ID는 공개 UUID이고, balance는 현재 사용 가능한 이프 잔액이다. 서버의 200 응답을 받은 뒤 앱이 consume한다. 서버는 acknowledge·consume을 호출하지 않는다.

## 설정

| 설정 | 기본값 | 용도 |
| --- | --- | --- |
| `MANYAK_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | 빈 문자열 | androidpublisher 권한을 가진 서비스 계정 JSON |
| `MANYAK_GOOGLE_PLAY_PACKAGE_NAME` | 빈 문자열 | 검증 대상 앱 패키지 |
| `MANYAK_GOOGLE_PLAY_ALLOW_TEST_PURCHASES` | false, dev 프로파일은 true | 라이선스 테스터 구매 허용 |
| `manyak.payment.google-play.voided-reconcile.enabled` | true | 환불 대사 활성화 |
| `manyak.payment.google-play.voided-reconcile.fixed-delay` | 1h | 이전 실행 종료 후 다음 실행까지 간격, 최초 지연도 동일 |
| `manyak.payment.google-play.voided-reconcile.lookback` | 48h | 환불 재조회 기간, 0 초과 30일 이하 |

JSON 또는 패키지명이 비어 있어도 서버는 기동한다. 이때 구매 API는 503이고 대사는 Google을 호출하지 않는다. 실제 키는 환경변수로 주입하며 커밋하지 않는다. ECS 시크릿 배선과 Play Console 권한 설정은 별도 배포 작업이다.

## 검증·재전송

- Google `purchaseState=0`만 구매 완료로 받는다. 취소·대기는 400이다.
- 응답에 productId가 있으면 요청과 일치해야 한다. [공식 ProductPurchase 문서](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products)에서 생략 가능한 필드로 정의하므로, 없으면 Google이 검증한 요청 경로의 productId를 사용한다.
- `purchaseType=0`은 테스트 구매다. 필드가 없으면 일반 구매이며 테스트 허용 설정이 필요하지 않다.
- 동일 토큰은 SHA-256으로만 DB에 보관한다. 본인의 동일 상품 주문만 멱등 반환하고, 타인 또는 다른 상품 재사용은 400이다. 환불된 주문 재전송은 재적립하지 않는다.
- 구매 주문과 적립은 한 트랜잭션이다. 동시 요청의 UNIQUE 충돌은 롤백 이후 기존 주문을 읽어 처리한다.
- Google 400·404는 400, Google 5xx·네트워크·자격증명 오류는 502로 변환한다. 원문 토큰·자격증명·Google 오류 본문은 로그와 예외에 포함하지 않는다.

## 환불 대사

[Voided Purchases API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.voidedpurchases/list)의 `tokenPagination.nextPageToken`을 다음 요청의 `token`으로 전달해 전체 페이지를 조회한다. 기본 in-app 구매 및 전체 환불 범위를 사용하며 수량 기반 부분 환불 옵션은 켜지 않는다.

각 토큰의 완료 주문을 잠그고 남은 구매 로트를 회수한다. 회수·REFUNDED 전환·refunded_at·reversal_shortfall은 같은 트랜잭션이다. 이미 환불됐거나 매칭되지 않으면 무시한다. 실행 중복 가드와 주문 행 락이 중복 회수를 막고, 개별 주문 실패는 다음 항목과 다음 회차에 영향을 주지 않는다. 중단이 lookback보다 길면 조회 범위를 30일 한도 내에서 늘려 복구해야 한다.

카운터는 `manyak.payment.google.purchase{result=completed|duplicate|rejected|error}`와 `manyak.payment.google.voided{result=reversed|ignored}`다. 환불 조회·개별 회수 실패는 토큰 없는 경고를 남긴다.

목 클라이언트 테스트와 `http/credit/google-play-purchase.http`를 제공한다. 실제 Google 호출·실토큰·voided 실데이터·실기기 consume 검증은 별도로 수행해야 한다.
