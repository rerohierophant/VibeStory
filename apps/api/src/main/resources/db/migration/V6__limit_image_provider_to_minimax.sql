-- Keep V5 immutable; the prototype supports DeepSeek text and MiniMax images, plus local Mock.
ALTER TABLE task DROP CONSTRAINT task_target_type_check;
ALTER TABLE task ADD CONSTRAINT task_target_type_check CHECK (
    (type = 'VIDEO' AND target_type = 'SHOT' AND provider = 'mock' AND episode_id IS NOT NULL) OR
    (type = 'BREAKDOWN' AND target_type = 'EPISODE' AND provider IN ('mock','openai-compatible') AND episode_id IS NOT NULL) OR
    (type = 'IMAGE' AND target_type = 'SHOT' AND provider IN ('mock','minimax') AND episode_id IS NOT NULL) OR
    (type = 'ASSET_IMAGE' AND target_type IN ('CHARACTER','SCENE') AND provider IN ('mock','minimax') AND episode_id IS NULL));
