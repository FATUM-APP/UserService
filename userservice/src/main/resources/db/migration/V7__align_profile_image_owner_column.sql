-- A profile image belongs to an account, and the account is identified by its username: that is what
-- the entity maps. The table still carried the column of the identity provider, which is why
-- `ddl-auto: validate` refused to start the application ("missing column [user_username] in table
-- [profile_images]"). This aligns the table with the mapping.
ALTER TABLE profile_images
    DROP CONSTRAINT fk_profile_image_user;

-- Rows written while the column held the identifier of the identity provider are translated to the
-- username of the same account. A row that already holds a username matches no aws_id, so it is left
-- untouched and running this twice is harmless.
UPDATE profile_images
SET user_aws_id = (
    SELECT account.username
    FROM users account
    WHERE account.aws_id = profile_images.user_aws_id
)
WHERE EXISTS (
    SELECT 1
    FROM users account
    WHERE account.aws_id = profile_images.user_aws_id
);

-- The username is the short value the users table stores, not the long identifier of the provider.
ALTER TABLE profile_images
    RENAME COLUMN user_aws_id TO user_username;

ALTER TABLE profile_images
    ALTER COLUMN user_username TYPE VARCHAR(15);

ALTER TABLE profile_images
    ADD CONSTRAINT fk_profile_image_user FOREIGN KEY (user_username) REFERENCES users (username);