#!/usr/bin/env bash
# 스토리 검색 전용 FGAC 역할·태스크 역할 매핑. 실행은 환경별 1회(동일 입력 재실행 가능).
# OS_URL은 setup.sh처럼 관리자 basic auth를 포함한 URL을 받을 수 있다. URL을 출력하지 않는다.
set -euo pipefail
: "${OS_URL:?관리자 인증이 포함된 OpenSearch URL을 설정하세요}"
: "${ENV:?dev 또는 prod를 설정하세요}"
: "${TASK_ROLE_ARN:?해당 환경 ECS 태스크 역할 ARN을 설정하세요}"
case "$ENV" in dev|prod) ;; *) echo 'ENV는 dev 또는 prod여야 합니다.' >&2; exit 1 ;; esac
command -v jq >/dev/null
ROLE="manyak-search-${ENV}"
# 존재 확인(indices:admin/exists)과 매핑 조회는 crud에 없어 환경별 검색 인덱스에 indices_all을 준다.
ROLE_BODY=$(jq -n --arg pattern "stories-${ENV}*" '{
  cluster_permissions: ["cluster_composite_ops"],
  index_permissions: [{index_patterns: [$pattern], allowed_actions: ["indices_all"]}]
}')
MAPPING_BODY=$(jq -n --arg arn "$TASK_ROLE_ARN" '{backend_roles: [$arn], hosts: [], users: []}')
curl --fail --silent --show-error -X PUT "${OS_URL%/}/_plugins/_security/api/roles/${ROLE}" \
  -H 'Content-Type: application/json' --data-binary "$ROLE_BODY" >/dev/null
curl --fail --silent --show-error -X PUT "${OS_URL%/}/_plugins/_security/api/rolesmapping/${ROLE}" \
  -H 'Content-Type: application/json' --data-binary "$MAPPING_BODY" >/dev/null
printf '%s\n' "검색 역할·매핑 적용 완료: ${ROLE}"
