-- Development/test fixture only. The API intentionally has no manual-shot creation endpoint in phase one.
-- psql -v user_id=... -v episode_id=... -f scripts/fixtures/phase1-shot.sql
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO shot(user_id, project_id, episode_id, seq, status, description)
SELECT e.user_id, e.project_id, e.id, 1, 'CONFIRMED', '雨夜街头的占位镜头'
FROM episode e JOIN project p ON p.id = e.project_id AND p.user_id = e.user_id
WHERE e.id = :'episode_id'::bigint AND p.user_id = :'user_id'::bigint
RETURNING id AS fixture_shot_id \gset

-- Logical Mock input only: this is not a generated asset or a production upload.
INSERT INTO shot_image(shot_id, episode_id, selected, storage_key)
VALUES (:fixture_shot_id, :'episode_id'::bigint, true, 'mock/phase1-first-frame')
RETURNING id AS fixture_image_id \gset
COMMIT;
SELECT json_build_object('shotId', :fixture_shot_id::text, 'imageId', :fixture_image_id::text);
