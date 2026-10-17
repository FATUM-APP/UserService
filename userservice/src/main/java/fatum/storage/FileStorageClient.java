package fatum.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * Contract the user service uses to store files.
 *
 * <p>Implementations talk to the shared {@code fatum-file-service}; nothing in this service knows
 * about buckets. The route decides where the file ends up, so the same call can send profile images
 * and identity documents to different buckets without changing the code.</p>
 */
public interface FileStorageClient {

    /**
     * Uploads a file to the bucket configured for the route.
     *
     * @param file  content to store
     * @param route route name such as {@code user-service:document}
     * @return the stored file metadata, including the key to persist
     */
    StoredFile upload(MultipartFile file, String route);

    /**
     * Uploads content that was built in memory, such as the PDF that merges both sides of an
     * identity document.
     *
     * @param content     bytes to store
     * @param filename    name to register for the object
     * @param contentType MIME type of the content, or null to let the storage service decide
     * @param route       route name such as {@code user-service:document}
     * @return the stored file metadata, including the key to persist
     */
    StoredFile upload(byte[] content, String filename, String contentType, String route);

    /** Removes an object. Idempotent: deleting a key that no longer exists succeeds. */
    void delete(String route, String objectKey);

    /** Temporary download URL for an object of the route. */
    String presignedUrl(String route, String objectKey);
}