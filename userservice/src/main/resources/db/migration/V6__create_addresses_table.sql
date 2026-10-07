-- An account holds several addresses and one of them is the principal one. The order is not a column:
-- it is the position the entity keeps inside its collection, which is why nothing here sorts.
--
-- The pair (username, residence) is unique, so the same address cannot be stored twice for the same
-- account. The service checks it before inserting and the constraint is the backstop.
CREATE TABLE addresses (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    residence     VARCHAR(255) NOT NULL,
    alias         VARCHAR(255) NOT NULL,
    city          VARCHAR(50)  NOT NULL,
    state         VARCHAR(50)  NOT NULL,
    country       VARCHAR(50)  NOT NULL,
    user_username VARCHAR(15)  NOT NULL,
    CONSTRAINT uk_user_residence UNIQUE (user_username, residence),
    CONSTRAINT fk_address_user FOREIGN KEY (user_username) REFERENCES users (username)
);