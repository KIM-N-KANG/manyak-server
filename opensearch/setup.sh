#!/usr/bin/env bash
# 로컬 관측 스택 초기 설정(KNK-853).
#
# docker-compose.observability.yml 로 컨테이너를 띄운 뒤 한 번 실행한다.
# 두 가지를 등록하는데, 사는 곳이 서로 다르다.
#   ① 인덱스 템플릿  → OpenSearch(9200). 로그를 "어떤 타입으로 저장할지"
#   ② 인덱스 패턴    → Dashboards(5601). Discover에서 "어떤 인덱스를 어떤 시간축으로 볼지"
# 둘 다 컨테이너 안에만 사는 상태라, 이 스크립트가 없으면 `down -v` 후 손으로 다시 만들어야 한다.
#
#   사용: ./opensearch/setup.sh
set -euo pipefail

OS_URL="${OS_URL:-http://localhost:9200}"
OSD_URL="${OSD_URL:-http://localhost:5601}"
PATTERN="manyak-logs-*"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "▶ OpenSearch 기동 확인 ($OS_URL)"
for i in $(seq 1 30); do
  if curl -sf "$OS_URL/_cluster/health" > /dev/null 2>&1; then break; fi
  [ "$i" -eq 30 ] && { echo "  ✗ OpenSearch에 연결할 수 없습니다. 컨테이너가 떠 있는지 확인하세요."; exit 1; }
  sleep 2
done
echo "  ✓ 응답함"

echo "▶ ① 인덱스 템플릿 등록 (OpenSearch)"
curl -sf -X PUT "$OS_URL/_index_template/manyak-logs" \
  -H 'Content-Type: application/json' \
  -d @"$SCRIPT_DIR/index-template.json" > /dev/null
echo "  ✓ manyak-logs (패턴 $PATTERN)"

echo "▶ 오늘자 인덱스 생성 (비어 있어도 만든다)"
# 왜 미리 만드나: 아래 ②가 부르는 `_fields_for_wildcard`는 **실제 인덱스에서** 필드 목록을 읽는다.
# 인덱스가 하나도 없으면 400이 나서 인덱스 패턴을 만들 수 없다(닭과 달걀).
# 빈 인덱스를 먼저 만들어 두면 ①의 템플릿이 적용되면서 매핑된 필드가 생겨 이 문제가 사라진다.
# 덤으로 템플릿이 실제로 먹었는지도 여기서 확인된다.
BOOTSTRAP_INDEX="manyak-logs-local-$(date +%Y.%m.%d)"
if curl -sf -o /dev/null "$OS_URL/$BOOTSTRAP_INDEX"; then
  echo "  ✓ $BOOTSTRAP_INDEX (이미 있음)"
else
  curl -sf -X PUT "$OS_URL/$BOOTSTRAP_INDEX" > /dev/null
  echo "  ✓ $BOOTSTRAP_INDEX"
fi
MAPPED=$(curl -sf "$OS_URL/$BOOTSTRAP_INDEX/_mapping" \
  | python3 -c 'import sys,json; print(len(list(json.load(sys.stdin).values())[0]["mappings"]["properties"]))')
[ "$MAPPED" -ge 18 ] || { echo "  ✗ 템플릿이 적용되지 않았습니다(매핑 필드 $MAPPED개). ①을 확인하세요."; exit 1; }
echo "  ✓ 템플릿 적용 확인 (매핑 필드 ${MAPPED}개)"

echo "▶ Dashboards 기동 확인 ($OSD_URL)"
for i in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$OSD_URL/api/status" 2>/dev/null)" = "200" ]; then break; fi
  [ "$i" -eq 60 ] && { echo "  ✗ Dashboards에 연결할 수 없습니다."; exit 1; }
  sleep 5
done
echo "  ✓ 응답함"

echo "▶ ② 인덱스 패턴 등록 (Dashboards)"
# 필드 목록을 함께 넣어야 한다. title·timeFieldName만 등록하면 Dashboards가 필드를 못 찾아
# Discover에서 `Could not locate that index-pattern-field (id: @timestamp)`로 막힌다.
FIELDS=$(curl -sf "$OSD_URL/api/index_patterns/_fields_for_wildcard?pattern=$PATTERN&meta_fields=_source&meta_fields=_id&meta_fields=_type&meta_fields=_index&meta_fields=_score")
BODY=$(FIELDS="$FIELDS" python3 -c '
import json, os
fields = json.loads(os.environ["FIELDS"])["fields"]
print(json.dumps({"attributes": {
    "title": "manyak-logs-*",
    "timeFieldName": "@timestamp",
    "fields": json.dumps(fields),
}}))')
# POST + overwrite=true 를 쓴다. PUT은 **기존 객체 수정** 전용이라 처음 실행할 때 404가 난다
# (`down -v` 로 볼륨을 지우면 Dashboards 저장 객체도 함께 사라지므로 매번 "처음"이 될 수 있다).
# POST + overwrite 는 없으면 만들고 있으면 덮어써서, 몇 번을 실행해도 같은 결과가 된다.
curl -sf -X POST "$OSD_URL/api/saved_objects/index-pattern/manyak-logs?overwrite=true" \
  -H 'osd-xsrf: true' -H 'Content-Type: application/json' \
  -d "$BODY" > /dev/null
echo "  ✓ $PATTERN (시간축 @timestamp, 필드 $(printf '%s' "$FIELDS" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["fields"]))')개)"

echo
# 운영 도메인처럼 FGAC 를 쓰면 OSD_URL 에 자격증명을 실어 넘기게 되는데(https://user:pw@host),
# 그 URL 을 그대로 안내하면 브라우저가 거부한다
# (`Request cannot be constructed from a URL that includes credentials`, KNK-854 실측).
# 그래서 안내 문구에서는 자격증명을 떼고 보여준다. curl 호출에는 위처럼 그대로 쓴다.
BROWSER_URL=$(printf '%s' "$OSD_URL" | sed -E 's#^(https?://)[^/@]*@#\1#')
echo "완료. Discover 열기 → $BROWSER_URL/app/data-explorer/discover"
echo "로그가 안 보이면 오른쪽 위 시간 범위를 넓히세요(기본값이 Last 15 minutes입니다)."
