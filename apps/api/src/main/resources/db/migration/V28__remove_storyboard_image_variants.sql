-- Keep adopted images and images used as video or feedback sources as ordinary candidates.
CREATE TEMPORARY TABLE discarded_variant_tasks ON COMMIT DROP AS
SELECT t.id, t.user_id, t.project_id
FROM task t
WHERE t.storyboard_variant_revision_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM shot_image i
    WHERE i.task_id = t.id AND (
        i.visible_in_storyboard OR i.selected
        OR EXISTS (SELECT 1 FROM video v WHERE v.source_image_id = i.id)
        OR EXISTS (SELECT 1 FROM task v WHERE v.type = 'VIDEO' AND v.target_id = i.shot_id
                   AND v.input_snapshot->>'sourceImageId' = i.id::text)
    )
  )
  AND NOT EXISTS (SELECT 1 FROM image_creation c WHERE c.source_task_id = t.id);

-- The existing cleanup worker handles media from discarded tasks, including late writes.
INSERT INTO storage_cleanup(prefix)
SELECT 'u/' || user_id || '/p/' || project_id || '/images/' || id FROM discarded_variant_tasks
ON CONFLICT DO NOTHING;
DELETE FROM shot_image i USING discarded_variant_tasks t WHERE i.task_id = t.id;
DELETE FROM task t USING discarded_variant_tasks d WHERE t.id = d.id;

-- Keep the text of earlier Agent replies and remove obsolete adoption actions.
UPDATE agent_chat_message SET result = result - 'variantChoice' WHERE result ? 'variantChoice';
UPDATE agent_command_receipt SET result = result - 'variantChoice' WHERE result ? 'variantChoice';
UPDATE agent_run SET result = result - 'variantChoice' WHERE result ? 'variantChoice';

ALTER TABLE shot_image DROP COLUMN visible_in_storyboard;
ALTER TABLE task DROP COLUMN storyboard_variant_revision_id;
DROP TABLE storyboard_variant_adoption, storyboard_variant_member, storyboard_variant_revision, storyboard_variant;
