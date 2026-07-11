-- KNK-409: 팀 제작 이미지 카탈로그 시드 (스펙 §4-3-9 자산 카탈로그).
--
-- 이 파일은 scripts/build_image_manifest.py가 생성한다. 직접 편집하지 않는다.
-- 원본은 scripts/image-presets.manifest.json이며, 자산 파일명은 그 매니페스트의 입력일 뿐이다.
--
-- 장르는 스칼라 서브쿼리로 태그 마스터를 조회해 넣는다. 마스터에 없는 이름이면 NULL이 되어
-- NOT NULL 위반으로 이 마이그레이션 전체가 롤백된다 — 규칙 위반 자산이 매칭에서 조용히 빠지는
-- 것이 가장 위험한 실패이므로 시드 단계에서 실패시킨다.

INSERT INTO image_presets (image_key, type, mood, subject, prop) VALUES
    ('thumb_0001', 'THUMBNAIL', '네온', '던전로비', '부서진컨트롤러'),
    ('thumb_0002', 'THUMBNAIL', '발랄한', '던전시장', '아이콘스튜'),
    ('thumb_0003', 'THUMBNAIL', '설렘', '노을옥상', '장미꽃잎'),
    ('thumb_0004', 'THUMBNAIL', '설렘', '사무실탕비실', '눈창'),
    ('thumb_0005', 'THUMBNAIL', '우수', '빗속승강장', '우산'),
    ('thumb_0006', 'THUMBNAIL', '화려', '재즈클럽', '피아노'),
    ('thumb_0007', 'THUMBNAIL', '신비', '달빛정원', '하트퀘스트'),
    ('thumb_0008', 'THUMBNAIL', '애틋', '빈티지방', '린넨셔츠'),
    ('thumb_0009', 'THUMBNAIL', '청량', '해안등대', '등대'),
    ('thumb_0010', 'THUMBNAIL', '치유', '온실', '펜던트'),
    ('thumb_0011', 'THUMBNAIL', '고독', '사막전장', '검은망토'),
    ('thumb_0012', 'THUMBNAIL', '음울', '달밤산촌', '초가집'),
    ('thumb_0013', 'THUMBNAIL', '비장', '월야궁궐', '붉은도포'),
    ('thumb_0014', 'THUMBNAIL', '신비', '비밀약초원', '약솥'),
    ('thumb_0015', 'THUMBNAIL', '신비', '월야연못', '달빛검기'),
    ('thumb_0016', 'THUMBNAIL', '청량', '설산창가', '펼친화첩'),
    ('thumb_0017', 'THUMBNAIL', '살벌', '야시장골목', '붉은등'),
    ('thumb_0018', 'THUMBNAIL', '고요한', '산문파', '약선탕'),
    ('thumb_0019', 'THUMBNAIL', '긴장', '빗속객잔', '삿갓'),
    ('thumb_0020', 'THUMBNAIL', '비장', '먹빛대숲', '검은도포'),
    ('thumb_0021', 'THUMBNAIL', '여운', '산중목교', '검집'),
    ('thumb_0022', 'THUMBNAIL', '은밀', '황혼문간', '검집'),
    ('thumb_0023', 'THUMBNAIL', '장엄', '폭포동굴', '퀘스트비급'),
    ('thumb_0024', 'THUMBNAIL', '초현실', '오페라홀', '샹들리에'),
    ('thumb_0025', 'THUMBNAIL', '영리한', '푸른던전', '레시피창'),
    ('thumb_0026', 'THUMBNAIL', '초월', '서버성당', '홀로그램창'),
    ('thumb_0027', 'THUMBNAIL', '압도', '궤도붕괴도시', '우주고리'),
    ('thumb_0028', 'THUMBNAIL', '장엄', '부유폐허', '중력균열'),
    ('thumb_0029', 'THUMBNAIL', '냉혹', '금속숲', '검은비'),
    ('thumb_0030', 'THUMBNAIL', '암흑', '일식궁전', '붉은일식'),
    ('thumb_0031', 'THUMBNAIL', '냉랭', '빙결도시', '온실돔'),
    ('thumb_0032', 'THUMBNAIL', '음산', '생체지하터널', '발광포자'),
    ('thumb_0033', 'THUMBNAIL', '쓸쓸', '침수도시', '폐허고층'),
    ('thumb_0034', 'THUMBNAIL', '절망', '침수도시', '붉은알림창'),
    ('thumb_0035', 'THUMBNAIL', '황량', '사막폐허', '수레'),
    ('thumb_0036', 'THUMBNAIL', '희망적인', '지하철쉼터', '환생씨앗'),
    ('thumb_0037', 'THUMBNAIL', '불타는', '화산던전', '불꽃국수'),
    ('thumb_0038', 'THUMBNAIL', '따뜻', '마법놀이방', '미니상태창'),
    ('thumb_0039', 'THUMBNAIL', '따뜻한', '던전', '랜턴'),
    ('thumb_0040', 'THUMBNAIL', '냉정', '유리타워', '계약패널'),
    ('thumb_0041', 'THUMBNAIL', '호화로운', '펜트하우스', '수정디저트'),
    ('thumb_0042', 'THUMBNAIL', '몽환', '궁정정원', '왕관'),
    ('thumb_0043', 'THUMBNAIL', '몽환', '궤도온실', '지구창'),
    ('thumb_0044', 'THUMBNAIL', '애틋', '사막유적', '고대문'),
    ('thumb_0045', 'THUMBNAIL', '몽환', '공중도서관', '레벨오브'),
    ('thumb_0046', 'THUMBNAIL', '청춘', '벚꽃정원', '벚꽃'),
    ('thumb_0047', 'THUMBNAIL', '명랑한', '마법학원', '불사조요리모자'),
    ('thumb_0048', 'THUMBNAIL', '청량', '가상교실', '스탯팔찌'),
    ('thumb_0049', 'THUMBNAIL', '긴박', '도시폐허', '랭크카드'),
    ('thumb_0050', 'THUMBNAIL', '신비로운', '궁전', '요람'),
    ('bg_0001', 'BACKGROUND', '긴장', '던전로비', '포탈'),
    ('bg_0002', 'BACKGROUND', '네온감성', '미래오락실', '빛콘솔'),
    ('bg_0003', 'BACKGROUND', '발랄한', '가상정원', '세이브분수'),
    ('bg_0004', 'BACKGROUND', '청량', '공중섬허브', '세이브크리스탈'),
    ('bg_0005', 'BACKGROUND', '겨울감성', '산장외부', '빨간스카프'),
    ('bg_0006', 'BACKGROUND', '몽환', '축제발코니', '뮤직박스'),
    ('bg_0007', 'BACKGROUND', '비밀스러운', '심야카페', '튤립꽃다발'),
    ('bg_0008', 'BACKGROUND', '비오는밤', '호텔입구', '빨간우산'),
    ('bg_0009', 'BACKGROUND', '서약', '해변의식장', '조개반지'),
    ('bg_0010', 'BACKGROUND', '애틋', '비내리는역', '유리우산'),
    ('bg_0011', 'BACKGROUND', '애틋한', '한옥마당', '노리개'),
    ('bg_0012', 'BACKGROUND', '고요', '대나무수련장', '옥패'),
    ('bg_0013', 'BACKGROUND', '고요한', '대나무수련장', '목검거치대'),
    ('bg_0014', 'BACKGROUND', '고요한', '대나무정자', '옥검'),
    ('bg_0015', 'BACKGROUND', '긴장된', '비오는골목', '검은우산'),
    ('bg_0016', 'BACKGROUND', '뜨거운', '용광검방', '담금통'),
    ('bg_0017', 'BACKGROUND', '몽환적', '연꽃호수', '옥피리'),
    ('bg_0018', 'BACKGROUND', '몽환적', '운무산', '석등'),
    ('bg_0019', 'BACKGROUND', '비장한', '절벽', '검흔바위'),
    ('bg_0020', 'BACKGROUND', '서늘한', '설산사찰', '빙결종'),
    ('bg_0021', 'BACKGROUND', '설원', '산중제단', '옥륜'),
    ('bg_0022', 'BACKGROUND', '신비한', '동굴서고', '봉인석함'),
    ('bg_0023', 'BACKGROUND', '쓸쓸한', '가을산길', '낙엽계단'),
    ('bg_0024', 'BACKGROUND', '엄숙한', '장문인서재', '봉인서책'),
    ('bg_0025', 'BACKGROUND', '영험한', '동굴석실', '옥좌'),
    ('bg_0026', 'BACKGROUND', '은밀한', '밤강나루', '낡은등불'),
    ('bg_0027', 'BACKGROUND', '은밀한', '약재시장', '약재상자'),
    ('bg_0028', 'BACKGROUND', '음산한', '폐객잔', '깨진술독'),
    ('bg_0029', 'BACKGROUND', '장엄한', '문파연무장', '청동향로'),
    ('bg_0030', 'BACKGROUND', '청량한', '폭포협곡', '돌다리'),
    ('bg_0031', 'BACKGROUND', '평화로운', '계곡온천', '목욕통'),
    ('bg_0032', 'BACKGROUND', '폭풍전야', '해안절벽', '낡은검집'),
    ('bg_0033', 'BACKGROUND', '활기찬', '장터', '홍등'),
    ('bg_0034', 'BACKGROUND', '황량한', '사막관문', '녹슨표창'),
    ('bg_0035', 'BACKGROUND', '고요', '성역아카이브', '코어'),
    ('bg_0036', 'BACKGROUND', '신비로운', '미래아파트', '반지상자'),
    ('bg_0037', 'BACKGROUND', '암흑', '서버성당', '블랙큐브'),
    ('bg_0038', 'BACKGROUND', '고립', '옥상정원', '빗물탱크'),
    ('bg_0039', 'BACKGROUND', '고요', '폐허도시', '녹슨버스'),
    ('bg_0040', 'BACKGROUND', '기계적', '드론격납고', '꺼진드론'),
    ('bg_0041', 'BACKGROUND', '기괴', '오염숲', '형광버섯'),
    ('bg_0042', 'BACKGROUND', '긴박', '침수지하철', '비상등'),
    ('bg_0043', 'BACKGROUND', '몽환', '해안폐항', '난파선'),
    ('bg_0044', 'BACKGROUND', '방사능', '검문소', '깨진계측기'),
    ('bg_0045', 'BACKGROUND', '불길', '산업지대', '붕괴굴뚝'),
    ('bg_0046', 'BACKGROUND', '불안', '병원복도', '넘어진휠체어'),
    ('bg_0047', 'BACKGROUND', '비현실', '거울호수', '가라앉은타워'),
    ('bg_0048', 'BACKGROUND', '서늘', '놀이공원', '멈춘관람차'),
    ('bg_0049', 'BACKGROUND', '신비', '성당폐허', '깨진스테인드글라스'),
    ('bg_0050', 'BACKGROUND', '쓸쓸한', '폐허지하철', '하얀베일'),
    ('bg_0051', 'BACKGROUND', '암울', '지하벙커', '깜빡이는모니터'),
    ('bg_0052', 'BACKGROUND', '압도', '분화구도심', '거대한균열'),
    ('bg_0053', 'BACKGROUND', '음산', '학교운동장', '녹슨스피커'),
    ('bg_0054', 'BACKGROUND', '적막', '옥상정원', '위성비콘'),
    ('bg_0055', 'BACKGROUND', '차가움', '설원마을', '얼어붙은라디오탑'),
    ('bg_0056', 'BACKGROUND', '침묵', '도서관폐허', '흩어진책'),
    ('bg_0057', 'BACKGROUND', '폭풍', '해일도시', '기울어진신호등'),
    ('bg_0058', 'BACKGROUND', '황량', '사막화고속도로', '뒤집힌트럭'),
    ('bg_0059', 'BACKGROUND', '희망', '온실쉘터', '작은새싹'),
    ('bg_0060', 'BACKGROUND', '희망', '폐허검문소', '보급상자'),
    ('bg_0061', 'BACKGROUND', '따뜻', '아기방', '나무블록'),
    ('bg_0062', 'BACKGROUND', '포근한', '아기방', '달별모빌'),
    ('bg_0063', 'BACKGROUND', '환희', '놀이방', '성장나무'),
    ('bg_0064', 'BACKGROUND', '고급스러운', '펜트하우스정원', '샴페인잔'),
    ('bg_0065', 'BACKGROUND', '냉정', '펜트하우스오피스', '금고상자'),
    ('bg_0066', 'BACKGROUND', '노을빛', '루프탑수영장', '진주비녀'),
    ('bg_0067', 'BACKGROUND', '음모', '지하금고', '황금키'),
    ('bg_0068', 'BACKGROUND', '경이', '세계수유적', '씨앗수정'),
    ('bg_0069', 'BACKGROUND', '몽환', '달빛온실', '수정장미'),
    ('bg_0070', 'BACKGROUND', '물빛몽환', '수족관터널', '벨벳반지함'),
    ('bg_0071', 'BACKGROUND', '비밀스러운', '마법도서관', '수정문진'),
    ('bg_0072', 'BACKGROUND', '왕실풍', '성발코니', '푸른장미'),
    ('bg_0073', 'BACKGROUND', '운명적인', '마법기차역', '회중시계'),
    ('bg_0074', 'BACKGROUND', '장엄', '하늘성채', '수정문'),
    ('bg_0075', 'BACKGROUND', '불안', '심야교실', '시험진'),
    ('bg_0076', 'BACKGROUND', '첫사랑', '방과후복도', '리본꽃다발'),
    ('bg_0077', 'BACKGROUND', '활기', '마법복도', '던전문'),
    ('bg_0078', 'BACKGROUND', '압박', '지하철역', '균열'),
    ('bg_0079', 'BACKGROUND', '위기', '벙커상황실', '게이트코어'),
    ('bg_0080', 'BACKGROUND', '위태로운', '도시옥상', '푸른포털'),
    ('char_0001', 'CHARACTER', '교활한', '논바이너리', '자물쇠따개-단검'),
    ('char_0002', 'CHARACTER', '화려한', '여성', '흑요석-카드'),
    ('char_0003', 'CHARACTER', '격정적인', '여성', '철제-건틀릿'),
    ('char_0004', 'CHARACTER', '결연한', '여성', '테니스-라켓'),
    ('char_0005', 'CHARACTER', '냉담한', '여성', '월장석-목걸이'),
    ('char_0006', 'CHARACTER', '눈부신', '여성', '진주-마이크'),
    ('char_0007', 'CHARACTER', '다정한', '남성', '압화-책갈피'),
    ('char_0008', 'CHARACTER', '단정한', '여성', '에스프레소-잔'),
    ('char_0009', 'CHARACTER', '따뜻한', '남성', '나무숟가락'),
    ('char_0010', 'CHARACTER', '매력적인', '남성', '필름카메라'),
    ('char_0011', 'CHARACTER', '몽상적인', '여성', '난초-브로치'),
    ('char_0012', 'CHARACTER', '몽환적인', '남성', '월장석-하프'),
    ('char_0013', 'CHARACTER', '부드러운', '남성', '흰장미'),
    ('char_0014', 'CHARACTER', '비밀스러운', '여성', '뼈-열쇠'),
    ('char_0015', 'CHARACTER', '사려깊은', '여성', '붓'),
    ('char_0016', 'CHARACTER', '수수께끼같은', '남성', '골동품-열쇠'),
    ('char_0017', 'CHARACTER', '신비로운', '남성', '만년필'),
    ('char_0018', 'CHARACTER', '신비로운', '남성', '황금-모래시계'),
    ('char_0019', 'CHARACTER', '아련한', '여성', '투명우산'),
    ('char_0020', 'CHARACTER', '엄숙한', '남성', '옥패'),
    ('char_0021', 'CHARACTER', '자신감있는', '여성', '비단부채'),
    ('char_0022', 'CHARACTER', '장난스러운', '남성', '헤드폰'),
    ('char_0023', 'CHARACTER', '차분한', '남성', '청진기'),
    ('char_0024', 'CHARACTER', '침착한', '남성', '인장반지'),
    ('char_0025', 'CHARACTER', '평온한', '여성', '은장미'),
    ('char_0026', 'CHARACTER', '평온한', '여성', '진주-비녀'),
    ('char_0027', 'CHARACTER', '품위있는', '여성', '붉은실-팔찌'),
    ('char_0028', 'CHARACTER', '거친', '남성', '단조망치'),
    ('char_0029', 'CHARACTER', '격정적인', '여성', '도'),
    ('char_0030', 'CHARACTER', '그을린', '여성', '줄표창'),
    ('char_0031', 'CHARACTER', '금욕적인', '남성', '염주'),
    ('char_0032', 'CHARACTER', '몽환적인', '여성', '얼음검'),
    ('char_0033', 'CHARACTER', '수도자같은', '남성', '쇠사슬'),
    ('char_0034', 'CHARACTER', '신비로운', '여성', '은침'),
    ('char_0035', 'CHARACTER', '엄격한', '여성', '청동거울'),
    ('char_0036', 'CHARACTER', '우울한', '여성', '눈가림-검'),
    ('char_0037', 'CHARACTER', '유쾌한', '남성', '술항아리'),
    ('char_0038', 'CHARACTER', '음산한', '여성', '고리칼날'),
    ('char_0039', 'CHARACTER', '장난스러운', '남성', '대나무-지팡이'),
    ('char_0040', 'CHARACTER', '장난스러운', '여성', '달빛-비파'),
    ('char_0041', 'CHARACTER', '장엄한', '여성', '지휘-부채'),
    ('char_0042', 'CHARACTER', '차가운', '남성', '창'),
    ('char_0043', 'CHARACTER', '치유적인', '남성', '약호리병'),
    ('char_0044', 'CHARACTER', '침울한', '남성', '단검'),
    ('char_0045', 'CHARACTER', '평온한', '남성', '옥피리'),
    ('char_0046', 'CHARACTER', '화려한', '남성', '쌍검'),
    ('char_0047', 'CHARACTER', '희망찬', '여성', '활'),
    ('char_0048', 'CHARACTER', '결연한', '남성', '층-열쇠'),
    ('char_0049', 'CHARACTER', '반항적인', '남성', '데이터-조각'),
    ('char_0050', 'CHARACTER', '분석적인', '여성', '수정-태블릿'),
    ('char_0051', 'CHARACTER', '강인한', '여성', '수통'),
    ('char_0052', 'CHARACTER', '고귀한', '여성', '방패-파편'),
    ('char_0053', 'CHARACTER', '고귀한', '여성', '월계-홀'),
    ('char_0054', 'CHARACTER', '굳건한', '논바이너리', '대장간-망치'),
    ('char_0055', 'CHARACTER', '그늘진', '여성', '까마귀-가면'),
    ('char_0056', 'CHARACTER', '기품있는', '남성', '흑요석-왕관'),
    ('char_0057', 'CHARACTER', '눈부신', '남성', '해바라기-로켓'),
    ('char_0058', 'CHARACTER', '반항적인', '여성', '태양-성배'),
    ('char_0059', 'CHARACTER', '부드러운', '남성', '유리등'),
    ('char_0060', 'CHARACTER', '불길한', '남성', '해골-반지'),
    ('char_0061', 'CHARACTER', '빛나는', '여성', '별-나침반'),
    ('char_0062', 'CHARACTER', '사나운', '여성', '용알'),
    ('char_0063', 'CHARACTER', '수수께끼같은', '논바이너리', '소환-구슬'),
    ('char_0064', 'CHARACTER', '아련한', '남성', '진주조개'),
    ('char_0065', 'CHARACTER', '야성적인', '남성', '뼈피리'),
    ('char_0066', 'CHARACTER', '영리한', '남성', '수은-플라스크'),
    ('char_0067', 'CHARACTER', '우아한', '남성', '인장반지'),
    ('char_0068', 'CHARACTER', '우아한', '여성', '오팔-거울'),
    ('char_0069', 'CHARACTER', '우울한', '여성', '모래시계'),
    ('char_0070', 'CHARACTER', '음산한', '남성', '마도서'),
    ('char_0071', 'CHARACTER', '차가운', '남성', '수정검'),
    ('char_0072', 'CHARACTER', '창의적인', '남성', '구리-회중시계'),
    ('char_0073', 'CHARACTER', '창의적인', '여성', '태엽-큐브'),
    ('char_0074', 'CHARACTER', '침울한', '남성', '검은난초'),
    ('char_0075', 'CHARACTER', '성실한', '남성', '계급장'),
    ('char_0076', 'CHARACTER', '영리한', '여성', '호박색-약병'),
    ('char_0077', 'CHARACTER', '음울한', '남성', '흑요석-나침반'),
    ('char_0078', 'CHARACTER', '정밀한', '여성', '렌즈-조준경'),
    ('char_0079', 'CHARACTER', '치명적인', '여성', '유리침'),
    ('char_0080', 'CHARACTER', '희망찬', '여성', '유리-약병');

INSERT INTO image_preset_genres (image_preset_id, tag_id) VALUES
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0001'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0002'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0003'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0003'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0004'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0004'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0005'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0005'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0006'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0006'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0007'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0008'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0009'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0010'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0011'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0011'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0012'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0012'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0012'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0013'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0013'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0014'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0014'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0015'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0015'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0016'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0016'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0017'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0017'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0018'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0019'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0020'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0021'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0022'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0023'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0024'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0024'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0024'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0025'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0026'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0027'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0027'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0028'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0028'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0028'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0029'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0029'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0030'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0030'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0031'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0031'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0032'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0032'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0033'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0034'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0035'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0036'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0037'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '요리')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0038'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '육아물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0039'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '육아물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0040'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0041'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0042'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0043'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0044'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0045'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0046'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0046'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0047'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0048'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0049'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'thumb_0050'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '환생')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0001'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0002'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0003'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0004'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0005'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0006'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0007'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0008'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0009'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0010'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0011'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0012'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0013'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0014'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0015'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0016'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0017'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0018'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0019'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0020'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0021'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0022'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0023'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0024'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0025'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0026'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0027'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0028'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0029'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0030'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0031'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0032'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0033'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0034'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0035'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0036'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0037'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0038'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0039'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0040'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0041'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0042'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0043'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0044'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0045'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0046'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0047'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0048'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0049'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0050'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0051'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0052'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0053'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0054'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0055'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0056'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0057'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0058'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0059'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0060'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0061'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '육아물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0062'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '육아물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0063'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '육아물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0064'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0065'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0066'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0067'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '재벌물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0068'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0069'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0070'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0071'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0072'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0073'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0074'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0075'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0076'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0077'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0078'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0079'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'bg_0080'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0001'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0002'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '게임 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0003'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0004'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0005'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0006'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0007'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0008'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0009'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0010'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0011'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0012'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0013'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0014'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0015'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0016'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0017'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0018'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0019'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0020'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0021'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0022'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0023'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0024'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0025'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0026'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0027'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '로맨스 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0028'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0029'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0030'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0031'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0032'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0033'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0034'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0035'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0036'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0037'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0038'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0039'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0040'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0041'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0042'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0043'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0044'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0045'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0046'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0047'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '무협')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0048'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0049'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0050'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '시스템')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0051'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '아포칼립스')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0052'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0053'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0054'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0055'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0056'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0057'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0058'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0059'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0060'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0061'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0062'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0063'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0064'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0065'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0066'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0067'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0068'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0069'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0070'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0071'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0072'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0073'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0074'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '현대 판타지')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0075'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0076'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '학원물')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0077'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0078'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0079'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물')),
    ((SELECT id FROM image_presets WHERE image_key = 'char_0080'),
     (SELECT id FROM story_creation_tags
       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = '헌터물'));
