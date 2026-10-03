package fatum.storage;

/**
 * Failure raised while talking to the shared storage service.
 *
 * <p>The distinction matters for the HTTP status the client sees: a rejected request (wrong content
 * type, file too large, unknown route) is the caller's problem and must stay a 4xx, while a failure of
 * the storage backend is a 502 for the user service.</p>
 */
public class StorageException extends RuntimeException {

    private final boolean clientError;

    public StorageException(String message, boolean clientError, Throwable cause) {
        super(message, cause);
        this.clientError = clientError;
    }

    /** The storage service rejected the request; the message can be forwarded to the caller. */
    public static StorageException rejected(String message) {
        return new StorageException(message, true, null);
    }

    /** The storage service or the network failed. */
    public static StorageException unavailable(String message, Throwable cause) {
        return new StorageException(message, false, cause);
    }

    public boolean isClientError() {
        return clientError;
    }
}
