ALTER TABLE task ADD COLUMN admission_wait_reason VARCHAR(64);
ALTER TABLE task ADD COLUMN admission_wait_since TIMESTAMPTZ;

-- Recovery is coordinated in short DB transactions; no Redis I/O while holding these locks.
CREATE TABLE generation_bucket (
    bucket_id CHAR(64) PRIMARY KEY,
    config_signature CHAR(64) NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','RECOVERING')),
    redis_run_id VARCHAR(64) NOT NULL DEFAULT '',
    recovery_token UUID NOT NULL DEFAULT gen_random_uuid(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE generation_admission (
    id UUID PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    bucket_id CHAR(64) NOT NULL,
    epoch BIGINT,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ALLOWED','DENIED','ERROR')),
    reason VARCHAR(64) NOT NULL,
    redis_at_ms BIGINT,
    retry_after_ms BIGINT NOT NULL DEFAULT 0,
    attempt_id BIGINT REFERENCES task_attempt(id) ON DELETE CASCADE,
    submitted_at TIMESTAMPTZ,
    waited_ms DOUBLE PRECISION,
    expired BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_admission_task ON generation_admission(task_id,created_at);
