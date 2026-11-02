ALTER TABLE task DROP CONSTRAINT task_target_type_check;
ALTER TABLE task ADD CONSTRAINT task_target_type_check CHECK (
    (type = 'VIDEO' AND target_type = 'SHOT' AND provider IN ('mock','minimax') AND episode_id IS NOT NULL) OR
    (type = 'BREAKDOWN' AND target_type = 'EPISODE' AND provider IN ('mock','openai-compatible') AND episode_id IS NOT NULL) OR
    (type = 'IMAGE' AND target_type = 'SHOT' AND provider IN ('mock','minimax') AND episode_id IS NOT NULL) OR
    (type = 'ASSET_IMAGE' AND target_type IN ('CHARACTER','SCENE') AND provider IN ('mock','minimax') AND episode_id IS NULL));
ALTER TABLE task_attempt ADD COLUMN external_task_id VARCHAR(100);
ALTER TABLE video DROP CONSTRAINT video_duration_ms_check;
ALTER TABLE video ADD CONSTRAINT video_duration_ms_check CHECK (duration_ms IN (3000,6000));
