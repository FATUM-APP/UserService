package fatum.dto;

import fatum.model.constant.DocumentType;

import java.time.Instant;

/**
 * Identity document of a user, as photographs.
 *
 * <p>Both sides are reported separately because a single sided document (passport) leaves the back
 * fields empty, and because the client shows the two pictures side by side.</p>
 */
public record DocumentResponse(
        String id,
        DocumentType documentType,
        String frontFilename,
        String backFilename,
        String contentType,
        long frontSize,
        Long backSize,
        String frontUrl,
        String backUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
