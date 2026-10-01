-- The keys handed out by the shared storage service are longer than the original 255 characters.
ALTER TABLE profile_images
    ALTER COLUMN image_key TYPE VARCHAR(512);
