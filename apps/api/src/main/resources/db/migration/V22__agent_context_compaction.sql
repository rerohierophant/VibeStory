ALTER TABLE agent_run ADD COLUMN context_policy JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE agent_run ADD COLUMN request_message_index INTEGER NOT NULL DEFAULT 1;
ALTER TABLE agent_run ADD COLUMN history_refs UUID[] NOT NULL DEFAULT '{}';
ALTER TABLE agent_model_call ADD COLUMN context_before_bytes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_model_call ADD COLUMN context_after_bytes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_tool_call ADD COLUMN result_text TEXT;

CREATE TABLE agent_context_event (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    layer TEXT NOT NULL CHECK(layer IN ('OFFLOAD','MICRO','COMPACT')),
    source_key TEXT NOT NULL,
    before_bytes INTEGER NOT NULL,
    after_bytes INTEGER NOT NULL,
    details JSONB NOT NULL,
    UNIQUE(run_id,layer,source_key)
);

CREATE TABLE agent_context_summary (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    source_hash TEXT NOT NULL,
    source_messages INTEGER NOT NULL,
    through_message INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('RUNNING','READY','FAILED','UNKNOWN')),
    cursor INTEGER NOT NULL DEFAULT 0,
    part_count INTEGER NOT NULL,
    summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    task_state JSONB NOT NULL,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id,source_hash)
);

CREATE TABLE agent_compaction_call (
    id BIGSERIAL PRIMARY KEY,
    summary_id UUID NOT NULL REFERENCES agent_context_summary(id) ON DELETE CASCADE,
    part INTEGER NOT NULL,
    request JSONB NOT NULL,
    response JSONB,
    usage JSONB,
    state TEXT NOT NULL CHECK(state IN ('RUNNING','DONE','UNKNOWN')),
    UNIQUE(summary_id,part)
);
