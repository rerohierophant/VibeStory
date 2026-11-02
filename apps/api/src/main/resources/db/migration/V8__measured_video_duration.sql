-- Keep requested duration in task input. The media row records the MP4's measured duration.
ALTER TABLE video DROP CONSTRAINT video_duration_ms_check;
ALTER TABLE video ADD CONSTRAINT video_duration_ms_check CHECK (duration_ms BETWEEN 1 AND 20000);
