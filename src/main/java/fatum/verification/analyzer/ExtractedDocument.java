package fatum.verification.analyzer;

import java.util.List;

/**
 * What Textract managed to read from the identity document.
 *
 * <p>{@code analyzed=false} means "no conclusion available" (analyzer disabled or unreadable image)
 * rather than "the document is wrong": the score calculation drops the signal instead of punishing the
 * user for a service that is switched off.</p>
 */
public record ExtractedDocument(
        boolean analyzed,
        String documentNumber,
        String documentType,
        String fullName,
        String birthDate,
        List<String> detectedTypes,
        List<String> rawLines,
        String source
) {

    public ExtractedDocument {
        detectedTypes = detectedTypes == null ? List.of() : List.copyOf(detectedTypes);
        rawLines = rawLines == null ? List.of() : List.copyOf(rawLines);
    }

    public static ExtractedDocument notAnalyzed(String reason) {
        return new ExtractedDocument(false, null, null, null, null, List.of(), List.of(), reason);
    }

    public boolean hasAnyField() {
        return documentNumber != null || documentType != null || fullName != null || birthDate != null;
    }

    /** Compact, human readable view used in logs and in the administrator summary. */
    public String summary() {
        if (!analyzed) {
            return "Document not analyzed (" + source + ")";
        }
        return "number=" + value(documentNumber)
                + ", type=" + value(documentType)
                + ", name=" + value(fullName)
                + ", birthDate=" + value(birthDate)
                + ", source=" + source;
    }

    private static String value(String field) {
        return field == null || field.isBlank() ? "-" : field;
    }
}
