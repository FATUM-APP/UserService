package fatum.storage;

public record StoredObject(
        String key,
        String originalFilename,
        String contentType,
        long size
) {
}
