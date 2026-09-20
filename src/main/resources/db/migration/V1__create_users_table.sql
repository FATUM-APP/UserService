CREATE TABLE users (
    aws_id         VARCHAR(255)  NOT NULL PRIMARY KEY,
    email          VARCHAR(100)  NOT NULL UNIQUE,
    complete_name  VARCHAR(142)  NOT NULL,
    birth_date     DATE          NOT NULL,
    username       VARCHAR(15)   NOT NULL UNIQUE,
    phone_number   VARCHAR(20)   NOT NULL UNIQUE,
    role           VARCHAR(20)   NOT NULL DEFAULT 'CLIENT',
    is_authenticated BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN       NOT NULL DEFAULT TRUE,
    document       VARCHAR(30)   NOT NULL UNIQUE,
    gender         VARCHAR(5)    NOT NULL,
    document_type  VARCHAR(20)   NOT NULL,
    city           VARCHAR(50),
    country        VARCHAR(50)   NOT NULL DEFAULT 'COLOMBIA'
);
