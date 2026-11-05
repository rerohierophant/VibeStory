CREATE TABLE agent_command_receipt (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    instruction TEXT NOT NULL,
    project_id BIGINT REFERENCES project(id) ON DELETE CASCADE,
    episode_id BIGINT REFERENCES episode(id) ON DELETE CASCADE,
    result JSONB,
    PRIMARY KEY (user_id, request_key)
);
