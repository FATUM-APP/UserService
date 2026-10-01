-- Audit trail of the retention policy: what was deleted or kept, and why. It answers the question
-- "why is this document no longer in the bucket?".
--
-- There is deliberately no foreign key to users: the trail has to survive the deletion of the user it
-- refers to.
CREATE TABLE storage_events (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_aws_id VARCHAR(255) NOT NULL,
    file_type   VARCHAR(30)  NOT NULL,
    object_key  VARCHAR(512),
    action      VARCHAR(20)  NOT NULL,
    reason      VARCHAR(40)  NOT NULL,
    detail      VARCHAR(500),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_storage_event_user ON storage_events (user_aws_id, created_at DESC);
