-- 일반 모드 초안 시작설정 저작(KNK-460, V32)에서 추가했던 오프닝 장면·첫 AI 메시지 컬럼을 제거한다(KNK-464).
-- 초안 저작 플로우를 단발 등록(POST /stories/general)으로 되돌리는 정합 작업이다. 스펙 시작설정은
-- name·prologue·start_situation 3필드뿐이라 스펙에 없는 컬럼을 정리한다. 저작한 오프닝·첫 메시지를 채팅
-- 런타임에 반영하는 후속(KNK-461)을 재개할 때는 스펙에 개념을 먼저 정의한 뒤 다시 추가한다.
ALTER TABLE story_start_settings DROP COLUMN opening_scene;
ALTER TABLE story_start_settings DROP COLUMN first_ai_message;
