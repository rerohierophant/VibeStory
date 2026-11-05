ALTER TABLE agent_run ADD COLUMN skill_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
