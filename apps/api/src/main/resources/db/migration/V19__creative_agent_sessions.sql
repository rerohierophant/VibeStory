CREATE TABLE creative_source (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    name TEXT NOT NULL,
    body TEXT NOT NULL,
    UNIQUE (project_id, request_key)
);

CREATE TABLE creative_session (
    id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    episode_id BIGINT REFERENCES episode(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES creative_session(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('PLAN', 'MAIN', 'SCRIPT', 'STORYBOARD')),
    UNIQUE NULLS NOT DISTINCT (project_id, episode_id, role),
    CHECK ((episode_id IS NULL) = (role = 'PLAN')),
    CHECK ((parent_id IS NOT NULL) = (role IN ('SCRIPT', 'STORYBOARD')))
);

CREATE TABLE creative_turn (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES creative_session(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    source_id BIGINT REFERENCES creative_source(id) ON DELETE CASCADE,
    script_id UUID,
    previous_id UUID,
    story_revision BIGINT,
    shot_revision BIGINT,
    body TEXT,
    instruction TEXT NOT NULL,
    provider TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    model TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'READY', 'APPLIED', 'FAILED', 'UNKNOWN')),
    cursor INTEGER NOT NULL DEFAULT 0,
    result JSONB,
    summary TEXT,
    error TEXT,
    applied_ids BIGINT[],
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    seq BIGSERIAL UNIQUE,
    UNIQUE (session_id, request_key)
);
CREATE INDEX ix_creative_turn_session ON creative_turn(session_id, seq DESC);
CREATE UNIQUE INDEX uq_creative_active_turn ON creative_turn(session_id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX ix_creative_claim ON creative_turn(seq) WHERE state IN ('QUEUED','RUNNING');

CREATE TABLE creative_episode_source (
    episode_id BIGINT PRIMARY KEY REFERENCES episode(id) ON DELETE CASCADE,
    source_id BIGINT NOT NULL REFERENCES creative_source(id) ON DELETE CASCADE,
    draft_id UUID NOT NULL REFERENCES creative_turn(id) ON DELETE CASCADE,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    CHECK (start_offset >= 0 AND end_offset > start_offset)
);

ALTER TABLE agent_conversation ADD COLUMN creative_episode_id BIGINT UNIQUE REFERENCES episode(id) ON DELETE CASCADE;
