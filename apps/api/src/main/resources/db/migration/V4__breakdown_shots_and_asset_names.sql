ALTER TABLE episode ADD COLUMN story_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE episode ADD COLUMN shot_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE episode ADD COLUMN breakdown_task_id BIGINT;

ALTER TABLE shot ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE shot ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE shot ADD CONSTRAINT uk_shot_project UNIQUE (id, project_id);
ALTER TABLE shot ADD CONSTRAINT shot_duration_range CHECK (duration_ms BETWEEN 1500 AND 12000);

ALTER TABLE task DROP CONSTRAINT task_type_check;
ALTER TABLE task DROP CONSTRAINT task_target_type_check;
ALTER TABLE task DROP CONSTRAINT task_provider_check;
ALTER TABLE task ADD CONSTRAINT task_type_check CHECK (type IN ('VIDEO', 'BREAKDOWN'));
ALTER TABLE task ADD CONSTRAINT task_target_type_check CHECK (
    (type = 'VIDEO' AND target_type = 'SHOT' AND provider = 'mock') OR
    (type = 'BREAKDOWN' AND target_type = 'EPISODE' AND provider IN ('mock', 'openai-compatible')));
ALTER TABLE task ADD COLUMN output_snapshot JSONB;
ALTER TABLE task_attempt ADD COLUMN raw_response TEXT;
ALTER TABLE task_attempt ADD COLUMN actual_model VARCHAR(160);
ALTER TABLE task_attempt DROP CONSTRAINT task_attempt_status_check;
ALTER TABLE task_attempt ADD CONSTRAINT task_attempt_status_check
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'ABANDONED', 'CANCELED'));
CREATE UNIQUE INDEX uk_episode_breakdown_active ON task(user_id, episode_id)
    WHERE type = 'BREAKDOWN' AND status IN ('QUEUED', 'RUNNING');
ALTER TABLE episode ADD CONSTRAINT fk_episode_breakdown_task FOREIGN KEY (breakdown_task_id) REFERENCES task(id) ON DELETE SET NULL;

CREATE TABLE character_asset (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, user_id) REFERENCES project(id, user_id) ON DELETE CASCADE,
    UNIQUE (id, project_id)
);
CREATE UNIQUE INDEX uk_character_name ON character_asset(project_id, lower(name));
CREATE TABLE scene_asset (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, user_id) REFERENCES project(id, user_id) ON DELETE CASCADE,
    UNIQUE (id, project_id)
);
CREATE UNIQUE INDEX uk_scene_name ON scene_asset(project_id, lower(name));
CREATE TABLE asset_ref (
    id BIGSERIAL PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    character_id BIGINT,
    scene_id BIGINT,
    CHECK ((role = 'CHARACTER' AND character_id IS NOT NULL AND scene_id IS NULL) OR
           (role = 'SCENE' AND scene_id IS NOT NULL AND character_id IS NULL)),
    FOREIGN KEY (shot_id, project_id) REFERENCES shot(id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (character_id, project_id) REFERENCES character_asset(id, project_id) ON DELETE CASCADE,
    FOREIGN KEY (scene_id, project_id) REFERENCES scene_asset(id, project_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uk_character_ref ON asset_ref(shot_id, character_id) WHERE role = 'CHARACTER';
CREATE UNIQUE INDEX uk_scene_ref ON asset_ref(shot_id) WHERE role = 'SCENE';

-- Completed media is immutable; re-breakdown removes only the exact old shot directories/keys.
-- Existing project/episode/style tombstones continue to work.
ALTER TABLE storage_cleanup DROP CONSTRAINT storage_cleanup_prefix_check;
ALTER TABLE storage_cleanup ADD CONSTRAINT storage_cleanup_prefix_check
    CHECK (prefix ~ '^u/[0-9]+/(p/[0-9]+(/e/[0-9]+(/(video/[A-Za-z0-9][A-Za-z0-9_.-]*|shot/[0-9]+))?)?|styles/custom_[a-f0-9]+)$');
