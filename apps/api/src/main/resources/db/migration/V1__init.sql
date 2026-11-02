CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(btrim(email))),
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    visual_type VARCHAR(80) NOT NULL,
    style TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, user_id)
);

-- Minimal prerequisites for the phase-one video flow; later phases extend these tables.
CREATE TABLE episode (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    episode_index INTEGER NOT NULL CHECK (episode_index > 0),
    title VARCHAR(120) NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    story_content TEXT NOT NULL DEFAULT '',
    breakdown_state VARCHAR(16) NOT NULL DEFAULT 'IDLE' CHECK (breakdown_state IN ('IDLE', 'RUNNING', 'DONE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id, user_id) REFERENCES project(id, user_id),
    UNIQUE (project_id, episode_index),
    UNIQUE (id, project_id, user_id)
);

CREATE TABLE shot (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    seq INTEGER NOT NULL CHECK (seq > 0),
    scene_name TEXT NOT NULL DEFAULT '',
    shot_size VARCHAR(16) NOT NULL DEFAULT '中景',
    camera_move VARCHAR(16) NOT NULL DEFAULT '固定',
    description TEXT NOT NULL DEFAULT '',
    dialogue TEXT NOT NULL DEFAULT '',
    character_names JSONB NOT NULL DEFAULT '[]',
    duration_ms INTEGER NOT NULL DEFAULT 3000 CHECK (duration_ms > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (episode_id, project_id, user_id) REFERENCES episode(id, project_id, user_id),
    UNIQUE (episode_id, seq),
    UNIQUE (id, episode_id)
);

CREATE TABLE shot_image (
    id BIGSERIAL PRIMARY KEY,
    shot_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('QUEUED', 'RUNNING', 'READY', 'FAILED')),
    selected BOOLEAN NOT NULL DEFAULT false,
    storage_key TEXT NOT NULL,
    input_snapshot JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (shot_id, episode_id) REFERENCES shot(id, episode_id),
    CHECK (NOT selected OR status = 'READY'),
    UNIQUE (id, shot_id, episode_id, version)
);
CREATE UNIQUE INDEX uk_shot_image_selected ON shot_image(shot_id) WHERE selected;
CREATE INDEX ix_project_user ON project(user_id, id);
CREATE INDEX ix_episode_user ON episode(user_id, project_id);
