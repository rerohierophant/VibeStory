CREATE TABLE agent_workflow (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    request_key UUID NOT NULL,
    episode_id BIGINT NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    breakdown_task_id BIGINT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    confirmed_at TIMESTAMPTZ,
    batch_ids BIGINT[] NOT NULL DEFAULT '{}',
    UNIQUE (conversation_id, request_key)
);
CREATE INDEX ix_agent_workflow_episode ON agent_workflow(episode_id);

ALTER TABLE shot_edit_proposal DROP CONSTRAINT shot_edit_proposal_check;
ALTER TABLE shot_edit_proposal ADD COLUMN old_description TEXT;
ALTER TABLE shot_edit_proposal ADD COLUMN new_description TEXT;
ALTER TABLE shot_edit_proposal ADD COLUMN generate_image BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shot_edit_proposal ADD COLUMN image_task_id BIGINT REFERENCES task(id) ON DELETE SET NULL;
ALTER TABLE shot_edit_proposal ADD CHECK (new_shot_size IS NOT NULL OR new_dialogue IS NOT NULL OR new_description IS NOT NULL);

ALTER TABLE agent_chat_message ADD COLUMN suggested_instruction TEXT;
