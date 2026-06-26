#!/usr/bin/env bash
# 스키마 문서(dbdoc/)를 실 DB에서 재생성한다.
#
# 전제(이 스크립트는 라이브 DB가 필요하다):
#   - Docker 실행 중 (로컬 PostgreSQL 컨테이너 기동용)
#   - tbls 설치:  brew install tbls
#   - .env 에 MANYAK_DB_* 값 설정 (.env.example 참고)
#
# 흐름: postgres 기동 → Flyway 마이그레이션 적용 → tbls doc 생성.
# DB 스키마(마이그레이션)를 바꾼 뒤 실행하고, 생성된 dbdoc/ 를 함께 커밋한다.
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "docker 가 필요합니다."; exit 1; }
command -v tbls   >/dev/null || { echo "tbls 가 필요합니다: brew install tbls"; exit 1; }

# .env 로드(있으면)
if [ -f .env ]; then set -a; . ./.env; set +a; fi

DB_NAME="${MANYAK_DB_NAME:?MANYAK_DB_NAME 필요(.env)}"
DB_USER="${MANYAK_DB_USERNAME:?MANYAK_DB_USERNAME 필요(.env)}"
DB_PASS="${MANYAK_DB_PASSWORD:?MANYAK_DB_PASSWORD 필요(.env)}"
DB_PORT="${MANYAK_DB_PORT:?MANYAK_DB_PORT 필요(.env)}"

echo "▶ PostgreSQL 기동"
docker compose up -d postgres

echo "▶ postgres healthy 대기"
for _ in $(seq 1 30); do
  if docker compose exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "▶ Flyway 마이그레이션 적용"
# 앱과 동일한 마이그레이션을 Flyway CLI(도커)로 적용한다.
# Docker Desktop(mac/win)에서는 host.docker.internal 이 기본 제공되지만, 네이티브 Linux Docker Engine에는
# 없으므로 --add-host 로 host-gateway 매핑을 명시해 호스트에 게시된 포트에 접속한다.
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:11 \
  -url="jdbc:postgresql://host.docker.internal:${DB_PORT}/${DB_NAME}" \
  -user="$DB_USER" -password="$DB_PASS" \
  -locations=filesystem:/flyway/sql \
  -connectRetries=10 \
  migrate

echo "▶ tbls doc 생성"
export TBLS_DSN="postgres://${DB_USER}:${DB_PASS}@localhost:${DB_PORT}/${DB_NAME}?sslmode=disable"
# -c 를 명시한다: TBLS_DSN env가 설정되면 tbls가 .tbls.yml 자동탐색을 건너뛰어 exclude 등이 무시되기 때문.
tbls doc -c .tbls.yml --rm-dist

echo "✅ dbdoc/ 재생성 완료. 변경분을 커밋하세요."
