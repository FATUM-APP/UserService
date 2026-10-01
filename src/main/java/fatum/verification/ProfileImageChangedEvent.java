package fatum.verification;

/**
 * Raised when a verified account has queued a new profile picture.
 *
 * <p>The comparison against the live reference is slow, so it does not happen inside the upload: the
 * event is handled after the upload transaction has committed, in another thread. That is also the
 * point where an external worker, a Lambda for instance, can take the job over.</p>
 *
 * @param userAwsId      owner of the picture
 * @param profileImageId row of the pending picture
 */
public record ProfileImageChangedEvent(String userAwsId, String profileImageId) {
}