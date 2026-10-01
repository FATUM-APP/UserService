-- The pipeline now runs in two phases, so an attempt no longer stores "the liveness frame against the
-- document" but two different comparisons:
--
--   document_profile_match   the picture of the document against the profile picture, the cheap check
--                            that decides whether the paid proof of life is worth requesting
--   reference_document_match the picture Rekognition produced during the proof of life against the
--                            picture of the document, the check that confirms who owns the document
--
-- The old columns held the same kind of measurement, so their names are reused instead of leaving dead
-- columns behind.
ALTER TABLE verification_attempts RENAME COLUMN document_liveness_match TO reference_document_match;
ALTER TABLE verification_attempts RENAME COLUMN profile_liveness_match TO document_profile_match;

-- Identity verifications and profile picture checks no longer share a budget, so every row says which
-- one it is. Existing rows can only be identity verifications.
ALTER TABLE verification_attempts ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'FULL';
ALTER TABLE verification_attempts ALTER COLUMN type DROP DEFAULT;

-- Confidence of the proof of life, only meaningful for a full attempt once Rekognition has answered.
ALTER TABLE verification_attempts ADD COLUMN liveness_confidence DOUBLE PRECISION;

-- When the decision was taken, which for an attempt that reached the second phase is later than the
-- creation of the row.
ALTER TABLE verification_attempts ADD COLUMN decided_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_verification_attempt_type ON verification_attempts (user_aws_id, type, attempt_number);