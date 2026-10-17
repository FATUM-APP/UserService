package fatum.storage;

/**
 * Metadata of a file that lives in the shared storage service.
 *
 * <p>The user service keeps {@code key} (and {@code id} when it needs a stable reference) so its own
 * tables still describe which file belongs to which user; the bucket and the object itself stay on
 * the storage side.</p>
 */
public record StoredFile(
        String id,
        String key,
        String bucket,
        String originalFilename,
        String contentType,
        long size
) {
}
