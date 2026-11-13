ALTER TABLE asset_version
    ADD COLUMN visible_in_library BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT ck_asset_version_selected_visible CHECK (visible_in_library OR NOT is_selected);
