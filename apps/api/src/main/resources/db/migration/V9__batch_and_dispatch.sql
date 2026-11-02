ALTER TABLE task ADD COLUMN dispatch_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE task ADD COLUMN not_before TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE task ADD COLUMN dispatch_blocked VARCHAR(64);
ALTER TABLE task ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE generation_batch (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_signature CHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('IMAGE','VIDEO')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (episode_id,project_id,user_id) REFERENCES episode(id,project_id,user_id) ON DELETE CASCADE,
    UNIQUE(user_id,episode_id,request_key)
);
CREATE TABLE generation_batch_member (
    batch_id BIGINT NOT NULL REFERENCES generation_batch(id) ON DELETE CASCADE,
    shot_id BIGINT NOT NULL,
    seq INTEGER NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ACCEPTED','REUSED','BLOCKED')),
    task_id BIGINT REFERENCES task(id) ON DELETE SET NULL,
    reason TEXT,
    PRIMARY KEY(batch_id,shot_id)
);
CREATE TABLE task_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id BIGINT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    dispatch_generation BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','SENT','BLOCKED')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    publish_failures INTEGER NOT NULL DEFAULT 0,
    delivery_failures INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(task_id,dispatch_generation)
);
CREATE INDEX ix_outbox_due ON task_outbox(available_at) WHERE status IN ('PENDING','SENDING');
CREATE INDEX ix_task_dispatch_due ON task(not_before,lease_until) WHERE status IN ('QUEUED','RUNNING');
CREATE TABLE dispatch_dead_letter (
    fingerprint CHAR(64) PRIMARY KEY,
    task_id BIGINT REFERENCES task(id) ON DELETE CASCADE,
    reason VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- All submission paths (including retries) share this transactional invariant.
-- Database mode also records notifications; only Rabbit mode runs the relay.
CREATE FUNCTION task_dispatch_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='QUEUED' AND OLD.status<>'QUEUED' THEN
        NEW.dispatch_generation := OLD.dispatch_generation + 1;
        NEW.not_before := now();
        NEW.dispatch_blocked := NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER task_dispatch_identity BEFORE UPDATE ON task FOR EACH ROW EXECUTE FUNCTION task_dispatch_identity();
CREATE FUNCTION task_dispatch_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='QUEUED' THEN
        INSERT INTO task_outbox(task_id,dispatch_generation,available_at)
        VALUES(NEW.id,NEW.dispatch_generation,NEW.not_before) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER task_dispatch_outbox AFTER INSERT OR UPDATE ON task FOR EACH ROW EXECUTE FUNCTION task_dispatch_outbox();
INSERT INTO task_outbox(task_id,dispatch_generation,available_at)
SELECT id,dispatch_generation,not_before FROM task WHERE status='QUEUED';
