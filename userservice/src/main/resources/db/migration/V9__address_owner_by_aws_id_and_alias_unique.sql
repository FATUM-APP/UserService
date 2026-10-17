-- The address belongs to the account, and the account is named by its aws identifier everywhere: that
-- is what the token carries and what never changes, while the username is the mutable, human facing
-- name. The owner column moves with it, and so does the unique key.
--
-- What must not repeat is the pair (account, alias), because the alias is the name the client uses to
-- pick an address: two equal aliases for one account would make the lookup ambiguous and the query
-- would return two rows instead of one.
--
-- The migration keeps the existing data: the owner is filled from the username relation before the
-- old column disappears.
ALTER TABLE addresses
    ADD COLUMN user_aws_id VARCHAR(255);

UPDATE addresses address
SET user_aws_id = (SELECT users.aws_id FROM users WHERE users.username = address.user_username);

ALTER TABLE addresses
    ALTER COLUMN user_aws_id SET NOT NULL;

ALTER TABLE addresses
    DROP CONSTRAINT uk_user_residence;

ALTER TABLE addresses
    DROP CONSTRAINT fk_address_user;

ALTER TABLE addresses
    DROP COLUMN user_username;

ALTER TABLE addresses
    ADD CONSTRAINT fk_address_user FOREIGN KEY (user_aws_id) REFERENCES users (aws_id);

ALTER TABLE addresses
    ADD CONSTRAINT uk_user_alias UNIQUE (user_aws_id, alias);