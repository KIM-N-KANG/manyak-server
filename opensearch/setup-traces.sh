#!/usr/bin/env bash
# 로컬 트레이스 화면 초기 설정(KNK-1551).
#
# 새 Explore의 트레이스 화면은 워크스페이스 안에서, 트레이스 유형으로 등록한 데이터셋만 읽는다.
# 이 스크립트가 세 가지를 만든다. 모두 Dashboards 저장 객체라 `down -v` 뒤에는 다시 실행한다.
#   1. 관측 워크스페이스 manyak-observability
#   2. otel-v1-apm-span* 데이터셋(signalType=traces, 시간축 endTime)
#   3. 데이터셋 필드 목록. API로 만든 데이터셋은 필드가 비어 있어 화면이 열리지 않는다.
#
#   사용: ./opensearch/setup-traces.sh   (스팬이 한 건 이상 들어온 뒤 실행)
set -euo pipefail

OSD_URL="${OSD_URL:-http://localhost:5601}"
WS_NAME="manyak-observability"
TITLE="otel-v1-apm-span*"
osd() { curl -sf -H 'Content-Type: application/json' -H 'osd-xsrf: true' "$@"; }

ws=$(osd -X POST "$OSD_URL/api/workspaces/_list" -d '{}' \
  | jq -r --arg n "$WS_NAME" '.result.workspaces[] | select(.name == $n) | .id' | head -1)
if [ -z "$ws" ]; then
  ws=$(osd -X POST "$OSD_URL/api/workspaces" \
    -d "{\"attributes\":{\"name\":\"$WS_NAME\",\"features\":[\"use-case-observability\"]}}" | jq -r .result.id)
fi
echo "✓ 워크스페이스 $WS_NAME ($ws)"

pid=$(osd "$OSD_URL/w/$ws/api/saved_objects/_find?type=index-pattern&search_fields=title&search=otel-v1-apm-span" \
  | jq -r --arg t "$TITLE" '.saved_objects[] | select(.attributes.title == $t) | .id' | head -1)
if [ -z "$pid" ]; then
  pid=$(osd -X POST "$OSD_URL/w/$ws/api/saved_objects/index-pattern" \
    -d "{\"attributes\":{\"title\":\"$TITLE\",\"timeFieldName\":\"endTime\",\"signalType\":\"traces\"}}" | jq -r .id)
fi
echo "✓ 데이터셋 $TITLE ($pid)"

fields=$(osd "$OSD_URL/w/$ws/api/index_patterns/_fields_for_wildcard?pattern=$TITLE" | jq -c '.fields')
[ "$fields" = "[]" ] && { echo "✗ 스팬 인덱스가 비어 있습니다. 스팬을 먼저 보내고 다시 실행하세요."; exit 1; }
osd -X PUT "$OSD_URL/w/$ws/api/saved_objects/index-pattern/$pid" \
  -d "$(jq -n --arg f "$fields" '{attributes: {fields: $f}}')" > /dev/null
echo "✓ 필드 $(echo "$fields" | jq length)개 반영"
echo "→ $OSD_URL/w/$ws/app/explore/traces"
