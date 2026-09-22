CREATE TABLE document_files (
    id                VARCHAR(36)   NOT NULL PRIMARY KEY,
    document_key      VARCHAR(255)  NOT NULL,
    original_filename VARCHAR(255)  NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    file_size         BIGINT        NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    user_aws_id       VARCHAR(255)  NOT NULL UNIQUE,
    CONSTRAINT fk_document_file_user FOREIGN KEY (user_aws_id) REFERENCES users (aws_id)
);
