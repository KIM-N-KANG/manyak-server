-- KNK-254: 기본 제공(PREDEFINED) 태그를 장르 20 / 주인공 특징 20 / 주변인물 특징 20 으로 갱신한다.
--
-- 설계 메모
-- * 적용된 V2 시드는 수정하지 않는다(Flyway 체크섬). 이 마이그레이션으로만 차이를 표현한다.
-- * story_creation_session_tags.tag_id 가 기존 PREDEFINED 태그를 참조할 수 있으므로 DELETE 대신
--   비활성화(is_active = FALSE)로 제거를 표현해 FK 위반과 이력 손실을 막는다.
--   (조회 API는 is_active = TRUE 만 노출하므로 비활성 태그는 신규 선택지에서 사라진다.)
-- * 신규 목록은 (tag_source, tag_type, name) 유니크 제약(uq_story_creation_tags_source_type_name)을
--   대상으로 한 UPSERT 로 적재한다. 이미 존재하는 이름(KEEP)은 sort_order 갱신·재활성화하고,
--   없는 이름(ADD)은 새 행으로 추가한다. 멱등·결정적이다.
-- * 같은 이름이라도 카테고리가 다르면 별개 행이다: '다정한'·'능글맞은' 은 PROTAGONIST·SUPPORTING_CHARACTER 양쪽에 존재.

-- 1) 신규 목록에 없는 기존 PREDEFINED 태그를 비활성화한다(제거 표현).
UPDATE story_creation_tags
SET is_active = FALSE,
    updated_at = now()
WHERE tag_source = 'PREDEFINED'
  AND is_active = TRUE
  AND (tag_type, name) NOT IN (
      ('GENRE', '회귀'), ('GENRE', '현대 판타지'), ('GENRE', '계약 결혼'), ('GENRE', '던전'),
      ('GENRE', '로맨스 판타지'), ('GENRE', '생존물'), ('GENRE', '무협'), ('GENRE', '악역물'),
      ('GENRE', '시스템'), ('GENRE', '육아물'), ('GENRE', '학원물'), ('GENRE', '헌터물'),
      ('GENRE', '빙의'), ('GENRE', '복수극'), ('GENRE', '게임 판타지'), ('GENRE', '재벌물'),
      ('GENRE', '환생'), ('GENRE', '시한부'), ('GENRE', '아포칼립스'), ('GENRE', '성장물'),
      ('PROTAGONIST', '천재'), ('PROTAGONIST', '냉정한'), ('PROTAGONIST', '복수형'), ('PROTAGONIST', '헌신적인'),
      ('PROTAGONIST', '숨겨진 강자'), ('PROTAGONIST', '능글맞은'), ('PROTAGONIST', '성장형'), ('PROTAGONIST', '상처 있는'),
      ('PROTAGONIST', '망나니'), ('PROTAGONIST', '정의로운'), ('PROTAGONIST', '먼치킨'), ('PROTAGONIST', '보호자형'),
      ('PROTAGONIST', '책임감 있는'), ('PROTAGONIST', '선한 인물'), ('PROTAGONIST', '집요한'), ('PROTAGONIST', '두뇌파'),
      ('PROTAGONIST', '겉은 약해도 강한'), ('PROTAGONIST', '다정한'), ('PROTAGONIST', '악한 인물'), ('PROTAGONIST', '계략적인'),
      ('SUPPORTING_CHARACTER', '집착하는'), ('SUPPORTING_CHARACTER', '흑막'), ('SUPPORTING_CHARACTER', '사랑스러운'), ('SUPPORTING_CHARACTER', '라이벌'),
      ('SUPPORTING_CHARACTER', '충성스러운'), ('SUPPORTING_CHARACTER', '소꿉친구'), ('SUPPORTING_CHARACTER', '수상한'), ('SUPPORTING_CHARACTER', '동료'),
      ('SUPPORTING_CHARACTER', '초월자'), ('SUPPORTING_CHARACTER', '까칠한'), ('SUPPORTING_CHARACTER', '스승'), ('SUPPORTING_CHARACTER', '비밀스러운'),
      ('SUPPORTING_CHARACTER', '호위무사'), ('SUPPORTING_CHARACTER', '다정한'), ('SUPPORTING_CHARACTER', '귀족'), ('SUPPORTING_CHARACTER', '조력자'),
      ('SUPPORTING_CHARACTER', '장난기 많은'), ('SUPPORTING_CHARACTER', '가족'), ('SUPPORTING_CHARACTER', '후회하는'), ('SUPPORTING_CHARACTER', '능글맞은')
  );

-- 2) 신규 기본 태그를 UPSERT 한다. sort_order 는 화면 노출 순서(10 단위), is_active 는 TRUE 로 보장한다.
INSERT INTO story_creation_tags (tag_type, name, tag_source, sort_order, is_active)
VALUES
    ('GENRE', '회귀', 'PREDEFINED', 10, TRUE),
    ('GENRE', '현대 판타지', 'PREDEFINED', 20, TRUE),
    ('GENRE', '계약 결혼', 'PREDEFINED', 30, TRUE),
    ('GENRE', '던전', 'PREDEFINED', 40, TRUE),
    ('GENRE', '로맨스 판타지', 'PREDEFINED', 50, TRUE),
    ('GENRE', '생존물', 'PREDEFINED', 60, TRUE),
    ('GENRE', '무협', 'PREDEFINED', 70, TRUE),
    ('GENRE', '악역물', 'PREDEFINED', 80, TRUE),
    ('GENRE', '시스템', 'PREDEFINED', 90, TRUE),
    ('GENRE', '육아물', 'PREDEFINED', 100, TRUE),
    ('GENRE', '학원물', 'PREDEFINED', 110, TRUE),
    ('GENRE', '헌터물', 'PREDEFINED', 120, TRUE),
    ('GENRE', '빙의', 'PREDEFINED', 130, TRUE),
    ('GENRE', '복수극', 'PREDEFINED', 140, TRUE),
    ('GENRE', '게임 판타지', 'PREDEFINED', 150, TRUE),
    ('GENRE', '재벌물', 'PREDEFINED', 160, TRUE),
    ('GENRE', '환생', 'PREDEFINED', 170, TRUE),
    ('GENRE', '시한부', 'PREDEFINED', 180, TRUE),
    ('GENRE', '아포칼립스', 'PREDEFINED', 190, TRUE),
    ('GENRE', '성장물', 'PREDEFINED', 200, TRUE),
    ('PROTAGONIST', '천재', 'PREDEFINED', 10, TRUE),
    ('PROTAGONIST', '냉정한', 'PREDEFINED', 20, TRUE),
    ('PROTAGONIST', '복수형', 'PREDEFINED', 30, TRUE),
    ('PROTAGONIST', '헌신적인', 'PREDEFINED', 40, TRUE),
    ('PROTAGONIST', '숨겨진 강자', 'PREDEFINED', 50, TRUE),
    ('PROTAGONIST', '능글맞은', 'PREDEFINED', 60, TRUE),
    ('PROTAGONIST', '성장형', 'PREDEFINED', 70, TRUE),
    ('PROTAGONIST', '상처 있는', 'PREDEFINED', 80, TRUE),
    ('PROTAGONIST', '망나니', 'PREDEFINED', 90, TRUE),
    ('PROTAGONIST', '정의로운', 'PREDEFINED', 100, TRUE),
    ('PROTAGONIST', '먼치킨', 'PREDEFINED', 110, TRUE),
    ('PROTAGONIST', '보호자형', 'PREDEFINED', 120, TRUE),
    ('PROTAGONIST', '책임감 있는', 'PREDEFINED', 130, TRUE),
    ('PROTAGONIST', '선한 인물', 'PREDEFINED', 140, TRUE),
    ('PROTAGONIST', '집요한', 'PREDEFINED', 150, TRUE),
    ('PROTAGONIST', '두뇌파', 'PREDEFINED', 160, TRUE),
    ('PROTAGONIST', '겉은 약해도 강한', 'PREDEFINED', 170, TRUE),
    ('PROTAGONIST', '다정한', 'PREDEFINED', 180, TRUE),
    ('PROTAGONIST', '악한 인물', 'PREDEFINED', 190, TRUE),
    ('PROTAGONIST', '계략적인', 'PREDEFINED', 200, TRUE),
    ('SUPPORTING_CHARACTER', '집착하는', 'PREDEFINED', 10, TRUE),
    ('SUPPORTING_CHARACTER', '흑막', 'PREDEFINED', 20, TRUE),
    ('SUPPORTING_CHARACTER', '사랑스러운', 'PREDEFINED', 30, TRUE),
    ('SUPPORTING_CHARACTER', '라이벌', 'PREDEFINED', 40, TRUE),
    ('SUPPORTING_CHARACTER', '충성스러운', 'PREDEFINED', 50, TRUE),
    ('SUPPORTING_CHARACTER', '소꿉친구', 'PREDEFINED', 60, TRUE),
    ('SUPPORTING_CHARACTER', '수상한', 'PREDEFINED', 70, TRUE),
    ('SUPPORTING_CHARACTER', '동료', 'PREDEFINED', 80, TRUE),
    ('SUPPORTING_CHARACTER', '초월자', 'PREDEFINED', 90, TRUE),
    ('SUPPORTING_CHARACTER', '까칠한', 'PREDEFINED', 100, TRUE),
    ('SUPPORTING_CHARACTER', '스승', 'PREDEFINED', 110, TRUE),
    ('SUPPORTING_CHARACTER', '비밀스러운', 'PREDEFINED', 120, TRUE),
    ('SUPPORTING_CHARACTER', '호위무사', 'PREDEFINED', 130, TRUE),
    ('SUPPORTING_CHARACTER', '다정한', 'PREDEFINED', 140, TRUE),
    ('SUPPORTING_CHARACTER', '귀족', 'PREDEFINED', 150, TRUE),
    ('SUPPORTING_CHARACTER', '조력자', 'PREDEFINED', 160, TRUE),
    ('SUPPORTING_CHARACTER', '장난기 많은', 'PREDEFINED', 170, TRUE),
    ('SUPPORTING_CHARACTER', '가족', 'PREDEFINED', 180, TRUE),
    ('SUPPORTING_CHARACTER', '후회하는', 'PREDEFINED', 190, TRUE),
    ('SUPPORTING_CHARACTER', '능글맞은', 'PREDEFINED', 200, TRUE)
ON CONFLICT ON CONSTRAINT uq_story_creation_tags_source_type_name
DO UPDATE SET
    sort_order = EXCLUDED.sort_order,
    is_active = TRUE,
    updated_at = now();
