CREATE TABLE image_creation (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('SHOT','CHARACTER','SCENE')),
    target_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    request_signature TEXT NOT NULL,
    source_task_id BIGINT REFERENCES task(id) ON DELETE SET NULL,
    result_task_id BIGINT REFERENCES task(id) ON DELETE SET NULL,
    instruction TEXT NOT NULL,
    decision VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','ACCEPTED','REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    UNIQUE(user_id, request_key),
    FOREIGN KEY (project_id, user_id) REFERENCES project(id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_image_creation_target ON image_creation(user_id, target_type, target_id, created_at);

CREATE TABLE image_preference (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_id BIGINT REFERENCES project(id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('SHOT','CHARACTER','SCENE')),
    target_id BIGINT,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('TARGET','PROJECT','USER')),
    origin_id UUID REFERENCES image_creation(id) ON DELETE SET NULL,
    rule TEXT NOT NULL CHECK (length(btrim(rule)) > 0),
    enabled BOOLEAN NOT NULL DEFAULT true,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((scope='USER' AND project_id IS NULL AND target_id IS NULL)
        OR (scope='PROJECT' AND project_id IS NOT NULL AND target_id IS NULL)
        OR (scope='TARGET' AND project_id IS NOT NULL AND target_id IS NOT NULL)),
    UNIQUE(user_id, origin_id, scope)
);
CREATE INDEX ix_image_preference_lookup ON image_preference(user_id, target_type, project_id) WHERE enabled;

CREATE TABLE image_recipe (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('SHOT','CHARACTER','SCENE')),
    name VARCHAR(120) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, target_type, name)
);
CREATE TABLE image_recipe_version (
    id UUID PRIMARY KEY,
    recipe_id UUID NOT NULL REFERENCES image_recipe(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    number INTEGER NOT NULL,
    template TEXT NOT NULL,
    origin_id UUID REFERENCES image_creation(id) ON DELETE SET NULL,
    request_key UUID NOT NULL,
    request_signature TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(recipe_id, number),
    UNIQUE(user_id, request_key)
);
