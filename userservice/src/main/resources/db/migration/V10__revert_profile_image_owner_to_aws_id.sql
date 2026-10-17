-- V7 moved this column to the username because the entity mapped by it at the time, but the
-- username is mutable and every rename broke the foreign key (see fk_profile_image_user violations
-- on PUT /users/update). The account is identified by its aws_id everywhere else — addresses already
-- made this same move in V9 — so profile_images follows the same path back.
ALTER TABLE profile_images
    DROP CONSTRAINT fk_profile_image_user;

-- The column has to be widened before the aws_id values are written into it: aws_id is up to 255
-- characters and the column is still VARCHAR(15) at this point, left that way by V7.
ALTER TABLE profile_images
    ALTER COLUMN user_username TYPE VARCHAR(255);

-- Rows written while the column held the username are translated back to the aws_id of the same
-- account. A row that already holds an aws_id matches no username, so it is left untouched and
-- running this twice is harmless.
UPDATE profile_images
SET user_username = (
    SELECT account.aws_id
    FROM users account
    WHERE account.username = profile_images.user_username
)
WHERE EXISTS (
    SELECT 1
    FROM users account
    WHERE account.username = profile_images.user_username
);

ALTER TABLE profile_images
    RENAME COLUMN user_username TO user_aws_id;

ALTER TABLE profile_images
    ADD CONSTRAINT fk_profile_image_user FOREIGN KEY (user_aws_id) REFERENCES users (aws_id);

ALTER TABLE profile_images
    ADD CONSTRAINT uk_profile_image_user_aws_id UNIQUE (user_aws_id);
