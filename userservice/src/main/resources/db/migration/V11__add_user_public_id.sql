-- Every account gets a public identifier that other services can use to reference the user without
-- exposing the identity-provider token. Existing rows receive a generated UUID; new rows are filled
-- by Hibernate's @UuidGenerator before the INSERT.
ALTER TABLE users
    ADD COLUMN id VARCHAR(36);

UPDATE users
SET id = CAST(gen_random_uuid() AS VARCHAR(36));

ALTER TABLE users
    ALTER COLUMN id SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT uk_user_id UNIQUE (id);
