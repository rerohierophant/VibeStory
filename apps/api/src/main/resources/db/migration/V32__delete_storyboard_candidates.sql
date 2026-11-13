ALTER TABLE shot_image
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_shot_image_deleted_unselected CHECK (deleted_at IS NULL OR NOT selected);
