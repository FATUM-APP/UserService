package fatum.verification.analyzer;

import fatum.model.User;
import fatum.model.constant.DocumentType;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares what Textract read with what the user registered.
 *
 * <p>The result is a percentage, as the business asked: the identity number, the document type, the
 * name and the birth date each weigh a share of the score, and a field the analyzer could not read is
 * simply left out of the calculation.</p>
 */
@Component
public class DocumentFieldMatcher {

    private static final double NUMBER_WEIGHT = 0.45d;
    private static final double TYPE_WEIGHT = 0.15d;
    private static final double NAME_WEIGHT = 0.20d;
    private static final double BIRTH_DATE_WEIGHT = 0.20d;

    private static final Pattern DATE_IN_TEXT = Pattern.compile("(\\d{1,4}[-/.\\s]\\d{1,2}[-/.\\s]\\d{1,4})");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            monthNameFormat("d MMM yyyy"),
            monthNameFormat("MMM d yyyy"),
            monthNameFormat("dd MMM yyyy"));

    /**
     * The month name reaches us in any case ("10 MAY 1998", "10 May 1998"): the OCR is not consistent
     * about it, so the parser accepts both.
     */
    private static DateTimeFormatter monthNameFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    public MatchResult match(ExtractedDocument extracted, User user) {
        if (extracted == null || !extracted.analyzed() || !extracted.hasAnyField()) {
            return MatchResult.notEvaluated("document-not-analyzed");
        }
        List<String> matched = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        double weight = 0d;
        double score = 0d;

        if (hasText(extracted.documentNumber())) {
            weight += NUMBER_WEIGHT;
            if (sameDocumentNumber(extracted.documentNumber(), user.getDocument())) {
                score += NUMBER_WEIGHT;
                matched.add("documentNumber");
            } else {
                mismatched.add("documentNumber");
            }
        }
        if (hasText(extracted.documentType())) {
            weight += TYPE_WEIGHT;
            if (sameDocumentType(extracted.documentType(), user.getDocumentType())) {
                score += TYPE_WEIGHT;
                matched.add("documentType");
            } else {
                mismatched.add("documentType");
            }
        }
        if (hasText(extracted.fullName())) {
            weight += NAME_WEIGHT;
            if (sameName(extracted.fullName(), user.getName())) {
                score += NAME_WEIGHT;
                matched.add("fullName");
            } else {
                mismatched.add("fullName");
            }
        }
        LocalDate extractedBirthDate = parseDate(extracted.birthDate());
        if (extractedBirthDate != null) {
            weight += BIRTH_DATE_WEIGHT;
            if (extractedBirthDate.equals(user.getBirthDate())) {
                score += BIRTH_DATE_WEIGHT;
                matched.add("birthDate");
            } else {
                mismatched.add("birthDate");
            }
        }
        if (weight == 0d) {
            return MatchResult.notEvaluated("no-comparable-field");
        }
        return new MatchResult(true, score / weight * 100d, matched, mismatched);
    }

    /** Identity numbers are compared ignoring spaces, dots and dashes added by the OCR. */
    public boolean sameDocumentNumber(String extracted, String registered) {
        String left = normalizeDocumentNumber(extracted);
        String right = normalizeDocumentNumber(registered);
        return !left.isEmpty() && left.equals(right);
    }

    public boolean sameDocumentType(String extracted, DocumentType registered) {
        String value = normalize(extracted);
        return switch (registered) {
            case PASSPORT -> value.contains("PASSPORT") || value.contains("PASAPORTE");
            case ID -> value.contains("IDENTITY") || value.contains("CEDULA") || value.contains("IDCARD")
                    || value.contains("ID") || value.contains("CITIZENSHIP");
            case DRIVING_LICENSE -> value.contains("DRIVING") || value.contains("LICENSE")
                    || value.contains("LICENCIA") || value.contains("CONDUCCION");
        };
    }

    /** Every word of the registered name has to appear in the name read from the document. */
    public boolean sameName(String extracted, String registered) {
        String extractedName = normalize(extracted);
        String registeredName = normalize(registered);
        if (extractedName.isEmpty() || registeredName.isEmpty()) {
            return false;
        }
        if (extractedName.contains(registeredName) || registeredName.contains(extractedName)) {
            return true;
        }
        for (String token : registeredName.split("\\s+")) {
            if (token.length() > 1 && !extractedName.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** Accepts the date formats identity documents use; returns null when nothing can be parsed. */
    public LocalDate parseDate(String value) {
        if (!hasText(value)) {
            return null;
        }
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(candidate, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next known format.
            }
        }
        Matcher matcher = DATE_IN_TEXT.matcher(candidate);
        if (matcher.find()) {
            String found = matcher.group(1).trim();
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                try {
                    return LocalDate.parse(found.replace(' ', '-'), formatter);
                } catch (DateTimeParseException ignored) {
                    // Try the next known format.
                }
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    /**
     * Identity numbers are a run of digits: the dots, spaces and dashes the OCR inserts between them
     * carry no meaning, so they are removed instead of turned into separators.
     */
    private String normalizeDocumentNumber(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Outcome of the comparison between the document and the registered data. */
    public record MatchResult(boolean evaluated, double score, List<String> matched, List<String> mismatched) {

        public MatchResult {
            matched = matched == null ? List.of() : List.copyOf(matched);
            mismatched = mismatched == null ? List.of() : List.copyOf(mismatched);
        }

        public static MatchResult notEvaluated(String reason) {
            return new MatchResult(false, 0d, List.of(), List.of(reason));
        }
    }
}
