package fatum.dto;

import java.util.Locale;

/**
 * Normalises the text that crosses the HTTP boundary.
 *
 * <p>The request DTOs are the single place where text is cleaned: their compact constructors run
 * before Bean Validation, before the mapper and before the service, so every layer downstream
 * receives a value that is already trimmed and, when the field is case insensitive, folded to the
 * case the database stores.</p>
 *
 * <p>Keeping the rules here has two consequences worth stating: a new entry point cannot forget
 * them, and no other layer repeats them. Every method is null safe, so a missing value stays
 * missing and the validation annotations of the DTO are the ones that report the error.</p>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Trims the value; {@code null} stays {@code null}. */
    public static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** Trims and folds to lower case: e-mail, username and addresses are stored that way. */
    public static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Trims and folds to upper case: names and documents are stored that way. */
    public static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Trims, and turns a blank value into {@code null}.
     *
     * <p>It is what a lookup key needs: a key that is only spaces cannot match anything, so it is
     * reported as missing instead of as "not found".</p>
     */
    public static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
