ALTER TABLE story_creation_tags
    ADD CONSTRAINT uq_story_creation_tags_source_type_name
        UNIQUE (tag_source, tag_type, name);
