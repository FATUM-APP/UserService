package fatum.verification.analyzer;

import fatum.model.User;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFieldMatcherTest {

    private final DocumentFieldMatcher matcher = new DocumentFieldMatcher();

    private User user;

    @BeforeEach
    void setUp() {
        user = new User(
                "aws-1",
                "jane@fatum.com",
                "Jane Doe",
                "+573001112233",
                LocalDate.of(1998, 5, 10),
                "janedoe",
                "1020304050",
                DocumentType.ID,
                Gender.FEMALE);
    }

    @Test
    void aDocumentThatAgreesWithTheRegisteredDataScoresOneHundred() {
        DocumentFieldMatcher.MatchResult result = matcher.match(document(
                "1020304050", "IDENTITY CARD", "JANE DOE", "1998-05-10"), user);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.score()).isCloseTo(100d, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.matched()).containsExactlyInAnyOrder("documentNumber", "documentType", "fullName", "birthDate");
        assertThat(result.mismatched()).isEmpty();
    }

    @Test
    void aDifferentIdentityNumberWeighsFortyFivePercent() {
        DocumentFieldMatcher.MatchResult result = matcher.match(document(
                "9999999999", "IDENTITY CARD", "JANE DOE", "1998-05-10"), user);

        assertThat(result.score()).isCloseTo(55d, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.mismatched()).containsExactly("documentNumber");
    }

    @Test
    void comparesIdentityNumbersIgnoringSeparatorsAddedByTheOcr() {
        assertThat(matcher.sameDocumentNumber("1.020.304.050", "1020304050")).isTrue();
        assertThat(matcher.sameDocumentNumber("1020304050", "1020304051")).isFalse();
    }

    @Test
    void comparesNamesIgnoringAccentsAndWordOrder() {
        assertThat(matcher.sameName("JANE DOE", "Jane Doe")).isTrue();
        assertThat(matcher.sameName("DOE JANE", "Jane Doe")).isTrue();
        assertThat(matcher.sameName("JANÉ DOE", "Jane Doe")).isTrue();
        assertThat(matcher.sameName("JOHN SMITH", "Jane Doe")).isFalse();
    }

    @Test
    void comparesTheDocumentTypeWithTheDeclaredOne() {
        assertThat(matcher.sameDocumentType("PASSPORT", DocumentType.PASSPORT)).isTrue();
        assertThat(matcher.sameDocumentType("PASAPORTE", DocumentType.PASSPORT)).isTrue();
        assertThat(matcher.sameDocumentType("DRIVING LICENSE", DocumentType.DRIVING_LICENSE)).isTrue();
        assertThat(matcher.sameDocumentType("PASSPORT", DocumentType.ID)).isFalse();
    }

    @Test
    void parsesTheDateFormatsIdentityDocumentsUse() {
        assertThat(matcher.parseDate("1998-05-10")).isEqualTo(LocalDate.of(1998, 5, 10));
        assertThat(matcher.parseDate("10/05/1998")).isEqualTo(LocalDate.of(1998, 5, 10));
        assertThat(matcher.parseDate("10-05-1998")).isEqualTo(LocalDate.of(1998, 5, 10));
        assertThat(matcher.parseDate("19980510")).isEqualTo(LocalDate.of(1998, 5, 10));
        assertThat(matcher.parseDate("10 MAY 1998")).isEqualTo(LocalDate.of(1998, 5, 10));
        assertThat(matcher.parseDate("DATE OF BIRTH 10/05/1998")).isEqualTo(LocalDate.of(1998, 5, 10));
        assertThat(matcher.parseDate("not a date")).isNull();
        assertThat(matcher.parseDate(null)).isNull();
    }

    @Test
    void aDocumentThatCouldNotBeReadIsNotEvaluated() {
        DocumentFieldMatcher.MatchResult result = matcher.match(ExtractedDocument.notAnalyzed("textract-disabled"), user);

        assertThat(result.evaluated()).isFalse();
        assertThat(result.score()).isZero();
        assertThat(result.mismatched()).containsExactly("document-not-analyzed");
    }

    @Test
    void aDocumentWithoutComparableFieldsIsNotEvaluated() {
        ExtractedDocument extracted = new ExtractedDocument(
                true, null, null, null, null, List.of(), List.of("some text"), "DetectDocumentText");

        assertThat(matcher.match(extracted, user).evaluated()).isFalse();
    }

    @Test
    void onlyTheFieldsThatWereReadTakePartInTheScore() {
        ExtractedDocument extracted = new ExtractedDocument(
                true, "1020304050", null, null, null, List.of(), List.of(), "AnalyzeID");

        DocumentFieldMatcher.MatchResult result = matcher.match(extracted, user);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.score()).isCloseTo(100d, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.matched()).containsExactly("documentNumber");
    }

    @Test
    void aPartiallyWrongDocumentKeepsAPercentageOfCoincidence() {
        ExtractedDocument extracted = document("1020304050", "IDENTITY CARD", "JOHN SMITH", "1998-05-10");

        DocumentFieldMatcher.MatchResult result = matcher.match(extracted, user);

        // number 0.45 + type 0.15 + birth date 0.20 out of 1.00
        assertThat(result.score()).isCloseTo(80d, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.mismatched()).containsExactly("fullName");
    }

    private ExtractedDocument document(String number, String type, String name, String birthDate) {
        return new ExtractedDocument(true, number, type, name, birthDate, List.of("AnalyzeID"), List.of(), "AnalyzeID");
    }
}
