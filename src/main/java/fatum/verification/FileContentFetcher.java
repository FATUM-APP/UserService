package fatum.verification;

/**
 * Reads the bytes of an object that lives in the shared storage service.
 *
 * <p>Textract and Rekognition need the picture itself, not a key, so the verification pipeline asks the
 * storage service for a temporary URL and downloads the object from it. Keeping the download behind an
 * interface means the pipeline can be tested without any AWS call.</p>
 */
public interface FileContentFetcher {

    /**
     * @param route storage route the object belongs to, for example {@code user-service:document}
     * @param key   object key returned when the file was uploaded
     * @return the raw bytes of the object
     */
    byte[] fetch(String route, String key);
}
