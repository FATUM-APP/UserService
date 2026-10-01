package fatum.verification.analyzer;

import fatum.model.constant.DocumentType;
import fatum.verification.VerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.AnalyzeIdRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeIdResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.EntityType;
import software.amazon.awssdk.services.textract.model.FeatureType;
import software.amazon.awssdk.services.textract.model.IdentityDocument;
import software.amazon.awssdk.services.textract.model.IdentityDocumentField;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Textract based document reader.
 *
 * <p>Strategy, in order of preference:</p>
 * <ol>
 *   <li>{@code AnalyzeID}: understands passports and identity documents and returns normalised fields,
 *       which is why a passport is read with it.</li>
 *   <li>{@code AnalyzeDocument} with FORMS: recovers key/value pairs from documents Textract does not
 *       recognise as an identity document (for example a driving licence or a national ID card).</li>
 *   <li>{@code DetectDocumentText} plus patterns: last resort, it still finds the identity number and
 *       a date in the raw text.</li>
 * </ol>
 */
@Component
public class TextractDocumentAnalyzer implements DocumentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TextractDocumentAnalyzer.class);

    private static final Pattern DOCUMENT_NUMBER = Pattern.compile("\\b([A-Z]{0,3}[- ]?\\d{6,12})\\b");
    private static final Pattern ANY_DATE = Pattern.compile("\\b(\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4})\\b");

    private static final List<String> NUMBER_KEYS = List.of("document number", "id number", "numero", "cedula", "identification", "passport number");
    private static final List<String> TYPE_KEYS = List.of("document type", "id type", "tipo", "type of document");
    private static final List<String> NAME_KEYS = List.of("name", "nombre", "surname", "apellido", "full name");
    private static final List<String> BIRTH_KEYS = List.of("birth", "nacimiento", "date of birth", "dob");

    private static final DateTimeFormatter NORMALIZED_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TextractClient textractClient;
    private final VerificationProperties properties;

    public TextractDocumentAnalyzer(TextractClient textractClient, VerificationProperties properties) {
        this.textractClient = textractClient;
        this.properties = properties;
    }

    @Override
    public ExtractedDocument extract(DocumentType type, byte[] front, byte[] back) {
        if (!properties.getAnalyzers().isTextractEnabled()) {
            return ExtractedDocument.notAnalyzed("textract-disabled");
        }
        if (front == null || front.length == 0) {
            return ExtractedDocument.notAnalyzed("empty-image");
        }
        try {
            ExtractedDocument fromIdentity = analyzeIdentityDocument(front);
            if (fromIdentity.hasAnyField()) {
                return fromIdentity;
            }
            ExtractedDocument fromForms = analyzeForms(front, back);
            if (fromForms.hasAnyField()) {
                return fromForms;
            }
            return detectText(front, back);
        } catch (SdkException exception) {
            log.error("Textract could not analyze the {} document", type, exception);
            return ExtractedDocument.notAnalyzed("textract-error");
        }
    }

    private ExtractedDocument analyzeIdentityDocument(byte[] front) {
        AnalyzeIdResponse response = textractClient.analyzeID(AnalyzeIdRequest.builder()
                .documentPages(Document.builder().bytes(SdkBytes.fromByteArray(front)).build())
                .build());
        Map<String, String> fields = new LinkedHashMap<>();
        for (IdentityDocument document : response.identityDocuments()) {
            for (IdentityDocumentField field : document.identityDocumentFields()) {
                String key = field.type() == null ? null : field.type().text();
                String value = field.valueDetection() == null ? null : field.valueDetection().text();
                if (key != null && value != null) {
                    fields.putIfAbsent(key.toUpperCase(Locale.ROOT), value);
                }
            }
        }
        if (fields.isEmpty()) {
            return ExtractedDocument.notAnalyzed("analyze-id-empty");
        }
        String fullName = join(fields.get("FIRST_NAME"), fields.get("LAST_NAME"));
        return new ExtractedDocument(
                true,
                firstNonNull(fields.get("DOCUMENT_NUMBER"), fields.get("ID_NUMBER")),
                firstNonNull(fields.get("DOCUMENT_TYPE"), fields.get("ID_TYPE")),
                fullName,
                firstNonNull(fields.get("DATE_OF_BIRTH"), fields.get("DOB")),
                List.of("AnalyzeID"),
                List.copyOf(fields.values()),
                "AnalyzeID");
    }

    private ExtractedDocument analyzeForms(byte[] front, byte[] back) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> rawLines = new ArrayList<>();
        collectForms(front, fields, rawLines);
        if (back != null && back.length > 0) {
            collectForms(back, fields, rawLines);
        }
        if (fields.isEmpty() && rawLines.isEmpty()) {
            return ExtractedDocument.notAnalyzed("analyze-document-empty");
        }
        return fromKeyValues(fields, rawLines, "AnalyzeDocument");
    }

    private void collectForms(byte[] image, Map<String, String> fields, List<String> rawLines) {
        AnalyzeDocumentResponse response = textractClient.analyzeDocument(AnalyzeDocumentRequest.builder()
                .document(Document.builder().bytes(SdkBytes.fromByteArray(image)).build())
                .featureTypes(FeatureType.FORMS)
                .build());
        fields.putAll(keyValues(response));
        rawLines.addAll(lines(response));
    }

    private ExtractedDocument detectText(byte[] front, byte[] back) {
        List<String> rawLines = new ArrayList<>();
        rawLines.addAll(detectTextLines(front));
        if (back != null && back.length > 0) {
            rawLines.addAll(detectTextLines(back));
        }
        if (rawLines.isEmpty()) {
            return ExtractedDocument.notAnalyzed("detect-text-empty");
        }
        return fromKeyValues(Map.of(), rawLines, "DetectDocumentText");
    }

    private List<String> detectTextLines(byte[] image) {
        DetectDocumentTextResponse response = textractClient.detectDocumentText(DetectDocumentTextRequest.builder()
                .document(Document.builder().bytes(SdkBytes.fromByteArray(image)).build())
                .build());
        return response.blocks().stream()
                .filter(block -> BlockType.LINE.equals(block.blockType()))
                .map(Block::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    /** Builds the extracted view from key/value pairs, falling back to patterns over the raw text. */
    ExtractedDocument fromKeyValues(Map<String, String> fields, List<String> rawLines, String source) {
        Map<String, String> normalized = new LinkedHashMap<>();
        fields.forEach((key, value) -> normalized.put(normalize(key), value));

        String number = findByKeywords(normalized, NUMBER_KEYS);
        String type = findByKeywords(normalized, TYPE_KEYS);
        String name = findByKeywords(normalized, NAME_KEYS);
        String birthDate = findByKeywords(normalized, BIRTH_KEYS);

        String text = String.join(" ", rawLines);
        if (number == null) {
            number = firstMatch(DOCUMENT_NUMBER, text);
        }
        if (birthDate == null) {
            birthDate = firstMatch(ANY_DATE, text);
        }
        if (birthDate != null) {
            birthDate = normalizeDate(birthDate);
        }
        boolean analyzed = number != null || type != null || name != null || birthDate != null;
        return new ExtractedDocument(
                analyzed,
                number,
                type,
                name,
                birthDate,
                List.of(source),
                rawLines,
                source);
    }

    private Map<String, String> keyValues(AnalyzeDocumentResponse response) {
        Map<String, Block> blocks = new LinkedHashMap<>();
        response.blocks().forEach(block -> blocks.put(block.id(), block));
        Map<String, String> fields = new LinkedHashMap<>();
        for (Block block : response.blocks()) {
            if (!EntityType.KEY.equals(block.entityTypes().isEmpty() ? null : block.entityTypes().get(0))) {
                continue;
            }
            String key = text(block, blocks);
            String value = block.relationships().stream()
                    .filter(relationship -> RelationshipType.VALUE.equals(relationship.type()))
                    .flatMap(relationship -> relationship.ids().stream())
                    .map(blocks::get)
                    .filter(java.util.Objects::nonNull)
                    .map(valueBlock -> text(valueBlock, blocks))
                    .filter(text -> text != null && !text.isBlank())
                    .findFirst()
                    .orElse(null);
            if (key != null && value != null) {
                fields.putIfAbsent(key, value);
            }
        }
        return fields;
    }

    private List<String> lines(AnalyzeDocumentResponse response) {
        return response.blocks().stream()
                .filter(block -> BlockType.LINE.equals(block.blockType()))
                .map(Block::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    private String text(Block block, Map<String, Block> blocks) {
        if (block == null) {
            return null;
        }
        if (block.text() != null && !block.text().isBlank()) {
            return block.text();
        }
        if (block.geometry() == null && block.relationships().isEmpty()) {
            return null;
        }
        return block.relationships().stream()
                .filter(relationship -> RelationshipType.CHILD.equals(relationship.type()))
                .flatMap(relationship -> relationship.ids().stream())
                .map(blocks::get)
                .filter(java.util.Objects::nonNull)
                .filter(child -> BlockType.WORD.equals(child.blockType()))
                .map(Block::text)
                .reduce((left, right) -> left + " " + right)
                .orElse(null);
    }

    private String findByKeywords(Map<String, String> fields, List<String> keywords) {
        return fields.entrySet().stream()
                .filter(entry -> keywords.stream().anyMatch(keyword -> entry.getKey().contains(keyword)))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String normalize(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String normalizeDate(String raw) {
        Optional<LocalDate> parsed = parse(raw);
        return parsed.map(NORMALIZED_DATE::format).orElse(raw);
    }

    private Optional<LocalDate> parse(String raw) {
        String candidate = raw.trim().replaceAll("\\s+", "-");
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"))) {
            try {
                return Optional.of(LocalDate.parse(raw.trim(), formatter));
            } catch (RuntimeException ignored) {
                try {
                    return Optional.of(LocalDate.parse(candidate, formatter));
                } catch (RuntimeException ignoredAgain) {
                    // Try the next format.
                }
            }
        }
        return Optional.empty();
    }

    private String firstMatch(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        // The number pattern can swallow the separator that precedes the digits.
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String join(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + " " + right;
    }

    private String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
