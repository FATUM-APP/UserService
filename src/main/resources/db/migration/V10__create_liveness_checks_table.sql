-- One row per proof of life run against Amazon Rekognition Face Liveness.
--
-- The row is written before the client opens the camera, because the session identifier has to exist
-- for the client to start streaming, and it is the only thing that links a session to its owner.
-- The reference picture Rekognition produces stays in S3, so only the bucket and the key are kept.
CREATE TABLE liveness_checks (
    id               VARCHAR(36)      NOT NULL PRIMARY KEY,
    user_aws_id      VARCHAR(255)     NOT NULL,
    attempt_id       VARCHAR(36)      NOT NULL,
    session_id       VARCHAR(128)     NOT NULL UNIQUE,
    status           VARCHAR(20)      NOT NULL,
    confidence       DOUBLE PRECISION,
    reference_bucket VARCHAR(255),
    reference_key    VARCHAR(512),
    failure_reason   VARCHAR(500),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_liveness_check_user FOREIGN KEY (user_aws_id) REFERENCES users (aws_id),
    CONSTRAINT fk_liveness_check_attempt FOREIGN KEY (attempt_id) REFERENCES verification_attempts (id)
);

-- A user is asked for his open sessions on every request of the flow.
CREATE INDEX idx_liveness_check_user ON liveness_checks (user_aws_id, status);