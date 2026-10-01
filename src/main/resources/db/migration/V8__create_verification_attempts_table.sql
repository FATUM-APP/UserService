-- One row per verification attempt. It keeps the scoring band, the outcome, who decided, every partial
-- score and the evidence that was used, which is what lets the policy reason about the history and an
-- administrator review a case.
CREATE TABLE verification_attempts (
    id                      VARCHAR(36)      NOT NULL PRIMARY KEY,
    user_aws_id             VARCHAR(255)     NOT NULL,
    attempt_number          INTEGER          NOT NULL,
    band                    VARCHAR(20)      NOT NULL,
    outcome                 VARCHAR(20)      NOT NULL,
    decision                VARCHAR(20)      NOT NULL,
    score                   DOUBLE PRECISION NOT NULL,
    document_match          DOUBLE PRECISION,
    document_liveness_match DOUBLE PRECISION,
    profile_liveness_match  DOUBLE PRECISION,
    fraud_risk              DOUBLE PRECISION,
    summary                 VARCHAR(1000),
    flags                   VARCHAR(1000),
    document_front_key      VARCHAR(512),
    document_back_key       VARCHAR(512),
    liveness_key            VARCHAR(512),
    profile_image_key       VARCHAR(512),
    decided_by              VARCHAR(255),
    notes                   VARCHAR(1000),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_verification_attempt_user FOREIGN KEY (user_aws_id) REFERENCES users (aws_id)
);

CREATE INDEX idx_verification_attempt_user ON verification_attempts (user_aws_id, attempt_number);

-- The review queue looks for the attempts that are waiting for an administrator.
CREATE INDEX idx_verification_attempt_outcome ON verification_attempts (outcome);
