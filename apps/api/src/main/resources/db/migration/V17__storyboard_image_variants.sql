CREATE TABLE storyboard_variant (
    id BIGSERIAL PRIMARY KEY,
    episode_id BIGINT NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    archived BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_storyboard_variant_episode ON storyboard_variant(episode_id, id);

CREATE TABLE storyboard_variant_revision (
    id BIGSERIAL PRIMARY KEY,
    variant_id BIGINT NOT NULL REFERENCES storyboard_variant(id) ON DELETE CASCADE,
    episode_id BIGINT NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    number INTEGER NOT NULL CHECK (number > 0),
    request_key UUID NOT NULL,
    request_signature CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    UNIQUE (variant_id, number),
    UNIQUE (episode_id, request_key)
);

ALTER TABLE task ADD COLUMN storyboard_variant_revision_id BIGINT
    REFERENCES storyboard_variant_revision(id) ON DELETE SET NULL;
CREATE INDEX ix_task_storyboard_variant_revision ON task(storyboard_variant_revision_id)
    WHERE storyboard_variant_revision_id IS NOT NULL;
ALTER TABLE shot_image ADD COLUMN visible_in_storyboard BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE storyboard_variant_member (
    revision_id BIGINT NOT NULL REFERENCES storyboard_variant_revision(id) ON DELETE CASCADE,
    shot_id BIGINT NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    task_id BIGINT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    shot_size TEXT NOT NULL,
    camera_move TEXT NOT NULL,
    PRIMARY KEY (revision_id, shot_id)
);

CREATE TABLE storyboard_variant_adoption (
    episode_id BIGINT NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    revision_id BIGINT NOT NULL REFERENCES storyboard_variant_revision(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    request_signature CHAR(64) NOT NULL,
    targets JSONB NOT NULL,
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (episode_id, request_key)
);
