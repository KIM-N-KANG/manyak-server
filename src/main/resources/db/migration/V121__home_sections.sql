CREATE TABLE home_sections (
    id bigserial PRIMARY KEY,
    public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name varchar(30) NOT NULL,
    source varchar(10) NOT NULL CHECK (source IN ('MANUAL', 'FILTER')),
    sort_by varchar(10) CHECK (sort_by IN ('POPULAR', 'LATEST', 'LIKES', 'CHATS')),
    original_only boolean NOT NULL DEFAULT false,
    item_limit integer NOT NULL DEFAULT 20 CHECK (item_limit BETWEEN 1 AND 20),
    is_active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL CHECK (sort_order >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_home_sections_source_sort CHECK (
        (source = 'FILTER' AND sort_by IS NOT NULL) OR (source = 'MANUAL' AND sort_by IS NULL)
    )
);
CREATE INDEX idx_home_sections_active_order ON home_sections (is_active, sort_order, id);

CREATE TABLE home_section_genres (
    section_id bigint NOT NULL REFERENCES home_sections(id) ON DELETE CASCADE,
    genre varchar(30) NOT NULL,
    PRIMARY KEY (section_id, genre)
);

CREATE TABLE home_section_stories (
    section_id bigint NOT NULL REFERENCES home_sections(id) ON DELETE CASCADE,
    story_id bigint NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    sort_order integer NOT NULL CHECK (sort_order >= 1),
    PRIMARY KEY (section_id, story_id),
    UNIQUE (section_id, sort_order)
);
