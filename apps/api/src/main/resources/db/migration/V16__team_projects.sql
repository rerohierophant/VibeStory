ALTER TABLE project ADD COLUMN team_id BIGINT REFERENCES team(id);
CREATE INDEX ix_project_team ON project(team_id, updated_at DESC, id DESC) WHERE team_id IS NOT NULL;
ALTER TABLE task ADD COLUMN requested_by BIGINT REFERENCES app_user(id);
ALTER TABLE generation_batch ADD COLUMN requested_by BIGINT REFERENCES app_user(id);
