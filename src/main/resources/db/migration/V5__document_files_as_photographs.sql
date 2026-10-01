-- Identity documents are photographs now: one image per side, instead of a PDF that merged both.
-- Existing rows are preserved: their key keeps pointing at the old object, which the next upload
-- replaces.
ALTER TABLE document_files
    RENAME COLUMN document_key TO front_key;

ALTER TABLE document_files
    RENAME COLUMN original_filename TO front_filename;

ALTER TABLE document_files
    RENAME COLUMN file_size TO front_size;

ALTER TABLE document_files
    ADD COLUMN back_key VARCHAR(512),
    ADD COLUMN back_filename VARCHAR(255),
    ADD COLUMN back_size BIGINT;

-- Object keys are longer than the original 255 characters: they include prefix, date and identifier.
ALTER TABLE document_files
    ALTER COLUMN front_key TYPE VARCHAR(512);
