-- KNK-1481: 공개 화풍 카탈로그. code는 AI 계약값이며 개인 리소스 식별자가 아니다.
CREATE TABLE art_styles (
    code VARCHAR(50) PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    description TEXT NOT NULL,
    preview_image_url TEXT,
    sort_order INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_art_styles_sort_order CHECK (sort_order >= 1)
);

INSERT INTO art_styles (code, name, description, sort_order, is_active, preview_image_url) VALUES
    ('ANIME', '애니메이션', '깔끔한 선과 선명한 색의 일본 애니메이션 그림체', 1, TRUE, NULL),
    ('SEMI_REAL', '반실사', '실제 사람에 가까운 얼굴, 차분한 색감의 그림체', 2, TRUE, NULL),
    ('LIGHT_NOVEL', '라노벨', '반짝이는 눈과 섬세한 묘사의 라이트노벨 삽화', 3, TRUE, NULL),
    ('CINEMATIC', '시네마틱', '강한 명암과 극적인 역광의 웹소설 표지 느낌', 4, TRUE, NULL),
    ('WEBTOON', '웹툰', '평면 채색의 한국 로맨스 웹툰 그림체', 5, TRUE, NULL),
    ('SHOJO', '순정', '펜 선화에 옅은 채색을 얹은 순정만화 컬러 원고', 6, TRUE, NULL),
    ('MOE', '모에', '파스텔 톤의 귀엽고 사랑스러운 그림체', 7, TRUE, NULL),
    ('RETRO', '레트로', '1990년대 TV 애니메이션 셀화 느낌', 8, TRUE, NULL),
    ('OIL_PAINTING', '유화', '붓 터치가 살아 있는 고전 유화 초상화', 9, TRUE, NULL),
    ('INK_WASH', '수묵', '흑백 먹 번짐의 동양화', 10, TRUE, NULL),
    ('NONE', '없음', '인물 이미지를 만들지 않아요', 11, TRUE, NULL);
