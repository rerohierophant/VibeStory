CREATE TABLE agent_run (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    request_key UUID NOT NULL,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_id BIGINT REFERENCES project(id) ON DELETE CASCADE,
    episode_id BIGINT REFERENCES episode(id) ON DELETE CASCADE,
    creative_turn_id UUID REFERENCES creative_turn(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('MAIN','SCRIPT','STORYBOARD')),
    instruction TEXT NOT NULL,
    provider TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    model TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('QUEUED','RUNNING','WAITING','DONE','FAILED','UNKNOWN')),
    messages JSONB NOT NULL,
    skill_versions JSONB NOT NULL,
    result JSONB,
    error TEXT,
    model_rounds INTEGER NOT NULL DEFAULT 0,
    tool_count INTEGER NOT NULL DEFAULT 0,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(session_id,request_key),
    CHECK ((role='MAIN') = (creative_turn_id IS NULL))
);
CREATE UNIQUE INDEX uq_agent_main_active ON agent_run(session_id)
    WHERE role='MAIN' AND state IN ('QUEUED','RUNNING','WAITING');
CREATE INDEX ix_agent_run_pending ON agent_run(state,updated_at);

CREATE TABLE agent_model_call (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    round INTEGER NOT NULL,
    request JSONB NOT NULL,
    response JSONB,
    usage JSONB,
    state TEXT NOT NULL CHECK (state IN ('RUNNING','DONE','UNKNOWN')),
    UNIQUE(run_id,round)
);

CREATE TABLE agent_tool_call (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL,
    name TEXT NOT NULL,
    arguments JSONB NOT NULL,
    result JSONB NOT NULL,
    UNIQUE(run_id,call_id)
);

ALTER TABLE agent_chat_message ADD COLUMN agent_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL;
