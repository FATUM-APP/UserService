-- Liveness evidence: the frame captured during the proof of life. It is the reference picture of the
-- account, compared with the document and with the profile picture.
CREATE TABLE liveness_files (
    id                VARCHAR(36)   NOT NULL PRIMARY KEY,
    liveness_key      VARCHAR(512)  NOT NULL,
    original_filename VARCHAR(255)  NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    file_size         BIGINT        NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    user_aws_id       VARCHAR(255)  NOT NULL UNIQUE,
    CONSTRAINT fk_liveness_file_user FOREIGN KEY (user_aws_id) REFERENCES users (aws_id)
);
