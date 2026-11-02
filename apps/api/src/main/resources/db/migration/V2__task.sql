CREATE TABLE task (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL CHECK (type = 'VIDEO'),
    target_type VARCHAR(32) NOT NULL CHECK (target_type = 'SHOT'),
    target_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL CHECK (provider = 'mock'),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'UNKNOWN')),
    input_signature CHAR(64) NOT NULL,
    input_snapshot JSONB NOT NULL,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message TEXT,
    retryable BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (episode_id, project_id, user_id) REFERENCES episode(id, project_id, user_id),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE UNIQUE INDEX uk_task_active ON task(user_id, type, target_type, target_id, input_signature)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_task_claim ON task(status, lease_until, id) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE task_attempt (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES task(id),
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    lease_owner VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'ABANDONED')),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    UNIQUE (task_id, attempt_no)
);
CREATE UNIQUE INDEX uk_attempt_running ON task_attempt(task_id) WHERE status = 'RUNNING';

CREATE TABLE video (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE REFERENCES task(id),
    shot_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    source_image_id BIGINT NOT NULL,
    source_image_version BIGINT NOT NULL,
    storage_key TEXT NOT NULL,
    duration_ms INTEGER NOT NULL CHECK (duration_ms = 3000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (shot_id, episode_id) REFERENCES shot(id, episode_id),
    FOREIGN KEY (source_image_id, shot_id, episode_id, source_image_version)
        REFERENCES shot_image(id, shot_id, episode_id, version)
);
