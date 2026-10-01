-- The trusted picture of an account can now only come from two places, and the difference matters:
-- only a picture produced by Rekognition can be read straight from S3 by the face comparator and is
-- worth keeping as evidence when a case goes to an administrator.
--
-- Existing rows were uploaded by an administrator, which is the only way the reference used to be set
-- by hand.
ALTER TABLE liveness_files ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE liveness_files ADD COLUMN storage_bucket VARCHAR(255);