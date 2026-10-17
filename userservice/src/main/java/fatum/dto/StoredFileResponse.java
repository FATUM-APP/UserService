package fatum.dto;

import java.time.Instant;

public record StoredFileResponse(
        String id,
        String originalFilename,
        String contentType,
        long size,
        String downloadUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
