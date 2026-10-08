CREATE TABLE rooms (
    id uuid PRIMARY KEY,
    code varchar(9) NOT NULL UNIQUE,
    owner_id uuid NOT NULL REFERENCES app_users(id),
    status varchar(16) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    revision bigint NOT NULL CHECK (revision >= 1),
    focus_duration_seconds integer NOT NULL CHECK (focus_duration_seconds BETWEEN 300 AND 10800 AND focus_duration_seconds % 60 = 0),
    break_duration_seconds integer NOT NULL CHECK (break_duration_seconds BETWEEN 60 AND 3600 AND break_duration_seconds % 60 = 0),
    current_round_id uuid,
    created_at timestamptz NOT NULL
);

CREATE TABLE room_memberships (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL REFERENCES rooms(id),
    user_id uuid NOT NULL REFERENCES app_users(id),
    slot integer NOT NULL CHECK (slot IN (1, 2)),
    active boolean NOT NULL,
    display_name varchar(40) NOT NULL,
    goal varchar(240) NOT NULL DEFAULT '',
    joined_at timestamptz NOT NULL,
    UNIQUE (room_id, slot),
    UNIQUE (room_id, user_id)
);
-- Both occupancy and a user's single active room are enforced independently of application checks.
CREATE UNIQUE INDEX one_active_membership_per_user ON room_memberships(user_id) WHERE active;

CREATE TABLE room_tasks (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    title varchar(160) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 160),
    completed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (room_id, user_id) REFERENCES room_memberships(room_id, user_id)
);
CREATE INDEX room_tasks_member ON room_tasks(room_id, user_id, created_at, id);

CREATE TABLE rounds (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL REFERENCES rooms(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('FOCUS', 'BREAK')),
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    duration_seconds integer NOT NULL CHECK (duration_seconds > 0),
    started_at timestamptz NOT NULL,
    ends_at timestamptz,
    segment_started_at timestamptz,
    active_elapsed_ms bigint NOT NULL CHECK (active_elapsed_ms >= 0 AND active_elapsed_ms <= duration_seconds::bigint * 1000),
    ended_at timestamptz,
    completion_reason varchar(24) CHECK (completion_reason IN ('ELAPSED', 'MANUAL', 'ROOM_CLOSED')),
    CHECK (
      (status = 'RUNNING' AND ends_at IS NOT NULL AND segment_started_at IS NOT NULL AND ended_at IS NULL AND completion_reason IS NULL) OR
      (status = 'PAUSED' AND ends_at IS NULL AND segment_started_at IS NULL AND ended_at IS NULL AND completion_reason IS NULL) OR
      (status IN ('COMPLETED', 'CANCELLED') AND ends_at IS NULL AND segment_started_at IS NULL AND ended_at IS NOT NULL AND completion_reason IS NOT NULL)
    ),
    UNIQUE (room_id, id)
);
CREATE UNIQUE INDEX one_active_round_per_room ON rounds(room_id) WHERE status IN ('RUNNING', 'PAUSED');
CREATE INDEX due_rounds ON rounds(ends_at) WHERE status = 'RUNNING';
ALTER TABLE rooms ADD CONSTRAINT current_round_belongs_to_room
    FOREIGN KEY (id, current_round_id) REFERENCES rounds(room_id, id);

CREATE TABLE round_history (
    round_id uuid PRIMARY KEY REFERENCES rounds(id),
    room_id uuid NOT NULL REFERENCES rooms(id),
    ended_at timestamptz NOT NULL,
    snapshot jsonb NOT NULL
);
CREATE INDEX history_recent ON round_history(ended_at DESC, round_id DESC);
CREATE TABLE history_participants (
    id uuid PRIMARY KEY,
    round_id uuid NOT NULL REFERENCES round_history(round_id),
    user_id uuid NOT NULL REFERENCES app_users(id),
    UNIQUE (round_id, user_id)
);
CREATE INDEX history_for_user ON history_participants(user_id, round_id);

CREATE TABLE idempotency_results (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    request_key uuid NOT NULL,
    method varchar(8) NOT NULL,
    path varchar(250) NOT NULL,
    original_revision bigint,
    request_body jsonb,
    response_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (user_id, request_key)
);

-- A historical result is an append-only record, even if a future application bug attempts an update.
CREATE FUNCTION reject_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Round history is immutable';
END;
$$;
CREATE TRIGGER immutable_round_history BEFORE UPDATE OR DELETE ON round_history
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER immutable_history_participants BEFORE UPDATE OR DELETE ON history_participants
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
