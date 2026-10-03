-- The shared storage service hands out longer object keys than the original 255 characters: the key
-- carries the route and the date partition inside it.
ALTER TABLE profile_images
    ALTER COLUMN image_key TYPE VARCHAR(512);

ALTER TABLE document_files
    ALTER COLUMN document_key TYPE VARCHAR(512);
