CREATE TABLE team (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_member (
    team_id BIGINT NOT NULL REFERENCES team(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    role VARCHAR(8) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);
CREATE UNIQUE INDEX uk_team_owner ON team_member(team_id) WHERE role = 'OWNER';
CREATE INDEX ix_team_member_user ON team_member(user_id, team_id);

CREATE TABLE team_invitation (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES team(id) ON DELETE CASCADE,
    email VARCHAR(254) NOT NULL,
    role VARCHAR(8) NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    inviter_id BIGINT NOT NULL REFERENCES app_user(id),
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '7 days')
);
CREATE UNIQUE INDEX uk_team_pending_invitation ON team_invitation(team_id, email) WHERE status = 'PENDING';
CREATE INDEX ix_team_invitation_inbox ON team_invitation(email, expires_at) WHERE status = 'PENDING';
CREATE INDEX ix_team_invitation_team ON team_invitation(team_id, id DESC);

CREATE TABLE team_audit (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES team(id) ON DELETE CASCADE,
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    action VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    previous_role VARCHAR(8),
    next_role VARCHAR(8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_team_audit_team ON team_audit(team_id, id DESC);
