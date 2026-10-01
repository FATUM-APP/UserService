package fatum.dto;

import fatum.model.constant.VerificationOutcome;

/**
 * Profile picture of a user, as seen by its owner.
 *
 * <p>A verified account can hold two pictures at once: the one everybody sees and the one that is
 * being checked. The client sends a new picture, receives {@code pendingVerification = true} and then
 * polls this same resource until {@code pendingVerification} turns false and
 * {@code lastChangeOutcome} says what happened.</p>
 *
 * @param image                the picture in use, null when the account has none yet
 * @param pendingImage         the picture being checked, null when there is none
 * @param pendingVerification  true while a face comparison is running
 * @param lastChangeOutcome    result of the newest check: {@code PENDING}, {@code VERIFIED} or
 *                             {@code REJECTED}
 */
public record ProfileImageResponse(
        StoredFileResponse image,
        StoredFileResponse pendingImage,
        boolean pendingVerification,
        VerificationOutcome lastChangeOutcome
) {
}