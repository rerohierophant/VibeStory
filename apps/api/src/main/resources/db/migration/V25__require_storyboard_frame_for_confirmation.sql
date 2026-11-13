-- Earlier versions allowed confirming a shot before generating its storyboard frame.
UPDATE shot s
SET status = 'DRAFT', version = version + 1, updated_at = clock_timestamp()
WHERE s.status = 'CONFIRMED'
  AND NOT EXISTS (
    SELECT 1 FROM shot_image i
    WHERE i.shot_id = s.id AND i.selected AND i.status = 'READY' AND i.visible_in_storyboard
  );
