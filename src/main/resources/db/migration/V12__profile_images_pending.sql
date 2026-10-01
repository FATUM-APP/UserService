-- A verified account can only change its picture to a photograph of the same person, and that is
-- checked against the live reference. Until the comparison finishes, the new picture is PENDING: it is
-- stored, nobody sees it, and the previous one stays ACTIVE.
--
-- That is why a user can now hold two rows at the same time, and why the uniqueness moves from "one row
-- per user" to "one row per user and state".
ALTER TABLE profile_images ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE profile_images DROP CONSTRAINT profile_images_user_aws_id_key;
CREATE UNIQUE INDEX ux_profile_image_user_status ON profile_images (user_aws_id, status);