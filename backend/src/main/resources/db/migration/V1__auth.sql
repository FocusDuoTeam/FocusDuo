CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL CONSTRAINT uq_app_users_username UNIQUE,
    display_name VARCHAR(40) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    CONSTRAINT ck_app_users_username CHECK (username ~ '^[a-z0-9_]{3,32}$'),
    CONSTRAINT ck_app_users_display_name CHECK (char_length(display_name) BETWEEN 2 AND 40)
);

CREATE TABLE auth_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_auth_sessions_hash CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_sessions_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX ix_auth_sessions_live_expiry ON auth_sessions(expires_at) WHERE revoked_at IS NULL;
