-- Project-scoped asset tasks do not depend on the lifetime of any one episode.
ALTER TABLE task ALTER COLUMN episode_id DROP NOT NULL;
ALTER TABLE task ADD CONSTRAINT fk_task_project FOREIGN KEY (project_id, user_id)
    REFERENCES project(id, user_id) ON DELETE CASCADE;
ALTER TABLE task DROP CONSTRAINT task_type_check;
ALTER TABLE task DROP CONSTRAINT task_target_type_check;
ALTER TABLE task ADD CONSTRAINT task_type_check CHECK (type IN ('VIDEO', 'BREAKDOWN', 'IMAGE', 'ASSET_IMAGE'));
ALTER TABLE task ADD CONSTRAINT task_target_type_check CHECK (
    (type = 'VIDEO' AND target_type = 'SHOT' AND provider = 'mock' AND episode_id IS NOT NULL) OR
    (type = 'BREAKDOWN' AND target_type = 'EPISODE' AND provider IN ('mock','openai-compatible') AND episode_id IS NOT NULL) OR
    (type = 'IMAGE' AND target_type = 'SHOT' AND provider IN ('mock','minimax','openai-compatible') AND episode_id IS NOT NULL) OR
    (type = 'ASSET_IMAGE' AND target_type IN ('CHARACTER','SCENE') AND provider IN ('mock','minimax','openai-compatible') AND episode_id IS NULL));

CREATE TABLE asset_version (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    character_id BIGINT,
    scene_id BIGINT,
    source VARCHAR(16) NOT NULL CHECK (source IN ('uploaded','generated')),
    label VARCHAR(120) NOT NULL CHECK (length(btrim(label)) > 0),
    is_selected BOOLEAN NOT NULL DEFAULT false,
    storage_key TEXT NOT NULL UNIQUE,
    media_type VARCHAR(32) NOT NULL CHECK (media_type IN ('image/jpeg','image/png')),
    task_id BIGINT UNIQUE REFERENCES task(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((character_id IS NOT NULL)::integer + (scene_id IS NOT NULL)::integer = 1),
    FOREIGN KEY (project_id, user_id) REFERENCES project(id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (character_id, project_id) REFERENCES character_asset(id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (scene_id, project_id) REFERENCES scene_asset(id, project_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uk_character_version_selected ON asset_version(character_id) WHERE is_selected;
CREATE UNIQUE INDEX uk_scene_version_selected ON asset_version(scene_id) WHERE is_selected;
CREATE INDEX ix_character_versions ON asset_version(character_id, id);
CREATE INDEX ix_scene_versions ON asset_version(scene_id, id);

ALTER TABLE shot_image ADD COLUMN label VARCHAR(120);
ALTER TABLE shot_image ADD COLUMN task_id BIGINT UNIQUE REFERENCES task(id) ON DELETE SET NULL;
ALTER TABLE shot_image ADD COLUMN media_type VARCHAR(32) CHECK (media_type IN ('image/jpeg','image/png'));
-- A task creates one candidate. Multiple candidates are explicit regenerations, keeping paid calls bounded.

ALTER TABLE storage_cleanup DROP CONSTRAINT storage_cleanup_prefix_check;
ALTER TABLE storage_cleanup ADD CONSTRAINT storage_cleanup_prefix_check CHECK (
    prefix ~ '^u/[0-9]+/(p/[0-9]+(/(e/[0-9]+(/(video/[A-Za-z0-9][A-Za-z0-9_.-]*|shot/[0-9]+))?|images/[0-9]+(/[0-9]+)?|assets/(character|scene)/[0-9]+/[a-f0-9]+))?|styles/custom_[a-f0-9]+)$');
