CREATE TABLE custom_style (
    id VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    label VARCHAR(120) NOT NULL CHECK (length(btrim(label)) > 0),
    storage_key TEXT NOT NULL UNIQUE,
    media_type VARCHAR(32) NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_custom_style_user ON custom_style(user_id, created_at);

-- Durable tombstones: replay cleanup after a crash, including files published by an old Worker.
-- Prefixes are constructed by the server from scoped IDs, never supplied by an API caller.
CREATE TABLE storage_cleanup (
    prefix TEXT PRIMARY KEY CHECK (prefix ~ '^u/[0-9]+/(p/[0-9]+(/e/[0-9]+)?|styles/custom_[a-f0-9]+)$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE episode DROP CONSTRAINT episode_project_id_user_id_fkey;
ALTER TABLE episode ADD CONSTRAINT episode_project_id_user_id_fkey
    FOREIGN KEY (project_id, user_id) REFERENCES project(id, user_id) ON DELETE CASCADE;
ALTER TABLE shot DROP CONSTRAINT shot_episode_id_project_id_user_id_fkey;
ALTER TABLE shot ADD CONSTRAINT shot_episode_id_project_id_user_id_fkey
    FOREIGN KEY (episode_id, project_id, user_id) REFERENCES episode(id, project_id, user_id) ON DELETE CASCADE;
ALTER TABLE shot_image DROP CONSTRAINT shot_image_shot_id_episode_id_fkey;
ALTER TABLE shot_image ADD CONSTRAINT shot_image_shot_id_episode_id_fkey
    FOREIGN KEY (shot_id, episode_id) REFERENCES shot(id, episode_id) ON DELETE CASCADE;
ALTER TABLE task DROP CONSTRAINT task_episode_id_project_id_user_id_fkey;
ALTER TABLE task ADD CONSTRAINT task_episode_id_project_id_user_id_fkey
    FOREIGN KEY (episode_id, project_id, user_id) REFERENCES episode(id, project_id, user_id) ON DELETE CASCADE;
ALTER TABLE task_attempt DROP CONSTRAINT task_attempt_task_id_fkey;
ALTER TABLE task_attempt ADD CONSTRAINT task_attempt_task_id_fkey
    FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE;
ALTER TABLE video DROP CONSTRAINT video_task_id_fkey;
ALTER TABLE video ADD CONSTRAINT video_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE;
ALTER TABLE video DROP CONSTRAINT video_shot_id_episode_id_fkey;
ALTER TABLE video ADD CONSTRAINT video_shot_id_episode_id_fkey
    FOREIGN KEY (shot_id, episode_id) REFERENCES shot(id, episode_id) ON DELETE CASCADE;
ALTER TABLE video DROP CONSTRAINT video_source_image_id_shot_id_episode_id_source_image_vers_fkey;
ALTER TABLE video ADD CONSTRAINT video_source_image_version_fkey
    FOREIGN KEY (source_image_id, shot_id, episode_id, source_image_version)
    REFERENCES shot_image(id, shot_id, episode_id, version) ON DELETE CASCADE;

CREATE INDEX ix_project_updated ON project(user_id, updated_at DESC, id DESC);
CREATE INDEX ix_task_project ON task(project_id);
CREATE INDEX ix_task_episode ON task(episode_id);
CREATE INDEX ix_video_shot ON video(shot_id);
