-- The boolean is_authenticated could not express "the system could not decide, a human has to look
-- at it". The verification status enum replaces it, keeping the meaning of the existing rows.
ALTER TABLE users
    ADD COLUMN verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED';

UPDATE users
SET verification_status = CASE WHEN is_authenticated THEN 'VERIFIED' ELSE 'UNVERIFIED' END;

ALTER TABLE users
    DROP COLUMN is_authenticated;

-- Fix: FEMALE needs six characters and the original column only allowed five.
ALTER TABLE users
    ALTER COLUMN gender TYPE VARCHAR(6);
