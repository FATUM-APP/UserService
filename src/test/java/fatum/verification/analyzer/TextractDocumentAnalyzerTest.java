package fatum.verification.analyzer;

import fatum.model.constant.DocumentType;
import fatum.verification.VerificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.AnalyzeIDDetections;
import software.amazon.awssdk.services.textract.model.AnalyzeIdRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeIdResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.EntityType;
import software.amazon.awssdk.services.textract.model.IdentityDocument;
import software.amazon.awssdk.services.textract.model.IdentityDocumentField;
import software.amazon.awssdk.services.textract.model.Relationship;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextractDocumentAnalyzerTest {

    private final TextractClient textractClient = mock(TextractClient.class);

    private final VerificationProperties properties = new VerificationProperties();
    private TextractDocumentAnalyzer analyzer;

    private final byte[] image = {1, 2, 3};

    @BeforeEach
    void setUp() {
        analyzer = new TextractDocumentAnalyzer(textractClient, properties);
    }

    @Test
    void readsTheFieldsTextractRecognisesInAnIdentityDocument() {
        when(textractClient.analyzeID(any(AnalyzeIdRequest.class))).thenReturn(AnalyzeIdResponse.builder()
                .identityDocuments(IdentityDocument.builder()
                        .identityDocumentFields(
                                field("DOCUMENT_NUMBER", "1020304050"),
                                field("FIRST_NAME", "JANE"),
                                field("LAST_NAME", "DOE"),
                                field("DATE_OF_BIRTH", "1998-05-10"))
                        .build())
                .build());

        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, image, null);

        assertThat(extracted.analyzed()).isTrue();
        assertThat(extracted.documentNumber()).isEqualTo("1020304050");
        assertThat(extracted.fullName()).isEqualTo("JANE DOE");
        assertThat(extracted.birthDate()).isEqualTo("1998-05-10");
        assertThat(extracted.source()).isEqualTo("AnalyzeID");
    }

    @Test
    void fallsBackToTheFormsWhenTheDocumentIsNotRecognisedAsAnIdentityDocument() {
        when(textractClient.analyzeID(any(AnalyzeIdRequest.class))).thenReturn(AnalyzeIdResponse.builder().build());
        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class))).thenReturn(AnalyzeDocumentResponse.builder()
                .blocks(
                        keyBlock("k1", "Document number", "v1"),
                        valueBlock("v1", "w1", "w2"),
                        wordBlock("w1", "1020"),
                        wordBlock("w2", "304050"))
                .build());

        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, image, null);

        assertThat(extracted.analyzed()).isTrue();
        assertThat(extracted.documentNumber()).isEqualTo("1020 304050");
        assertThat(extracted.source()).isEqualTo("AnalyzeDocument");
    }

    @Test
    void fallsBackToTheRawTextAsALastResort() {
        when(textractClient.analyzeID(any(AnalyzeIdRequest.class))).thenReturn(AnalyzeIdResponse.builder().build());
        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class))).thenReturn(AnalyzeDocumentResponse.builder().build());
        when(textractClient.detectDocumentText(any(DetectDocumentTextRequest.class))).thenReturn(DetectDocumentTextResponse.builder()
                .blocks(lineBlock("l1", "CEDULA 1020304050"), lineBlock("l2", "FECHA DE NACIMIENTO 10/05/1998"))
                .build());

        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, image, null);

        assertThat(extracted.analyzed()).isTrue();
        assertThat(extracted.documentNumber()).isEqualTo("1020304050");
        assertThat(extracted.birthDate()).isEqualTo("1998-05-10");
        assertThat(extracted.source()).isEqualTo("DetectDocumentText");
    }

    @Test
    void readsTheBackSideAsWell() {
        when(textractClient.analyzeID(any(AnalyzeIdRequest.class))).thenReturn(AnalyzeIdResponse.builder().build());
        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class))).thenReturn(AnalyzeDocumentResponse.builder().build());
        when(textractClient.detectDocumentText(any(DetectDocumentTextRequest.class))).thenReturn(
                DetectDocumentTextResponse.builder().blocks(lineBlock("l1", "SIN INFORMACION")).build());

        analyzer.extract(DocumentType.ID, image, new byte[]{4, 5});

        verify(textractClient, org.mockito.Mockito.times(2)).detectDocumentText(any(DetectDocumentTextRequest.class));
    }

    @Test
    void nothingIsReadWhenEverythingComesBackEmpty() {
        when(textractClient.analyzeID(any(AnalyzeIdRequest.class))).thenReturn(AnalyzeIdResponse.builder().build());
        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class))).thenReturn(AnalyzeDocumentResponse.builder().build());
        when(textractClient.detectDocumentText(any(DetectDocumentTextRequest.class))).thenReturn(DetectDocumentTextResponse.builder().build());

        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, image, null);

        assertThat(extracted.analyzed()).isFalse();
        assertThat(extracted.source()).isEqualTo("detect-text-empty");
    }

    @Test
    void anEmptyImageIsNotSentToAws() {
        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, new byte[0], null);

        assertThat(extracted.analyzed()).isFalse();
        assertThat(extracted.source()).isEqualTo("empty-image");
        verify(textractClient, never()).analyzeID(any(AnalyzeIdRequest.class));
    }

    @Test
    void theAnalyzerCanBeSwitchedOff() {
        properties.getAnalyzers().setTextractEnabled(false);

        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, image, null);

        assertThat(extracted.analyzed()).isFalse();
        assertThat(extracted.source()).isEqualTo("textract-disabled");
    }

    @Test
    void anAwsFailureIsReportedAsNotAnalyzed() {
        when(textractClient.analyzeID(any(AnalyzeIdRequest.class)))
                .thenThrow(SdkClientException.create("textract down"));

        ExtractedDocument extracted = analyzer.extract(DocumentType.ID, image, null);

        assertThat(extracted.analyzed()).isFalse();
        assertThat(extracted.source()).isEqualTo("textract-error");
    }

    @Test
    void mapsTheKeyValuePairsOfAnyDocument() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Document Number", "1020304050");
        fields.put("Tipo de documento", "CEDULA DE CIUDADANIA");
        fields.put("Apellidos y Nombres", "JANE DOE");
        fields.put("Fecha de nacimiento", "10/05/1998");

        ExtractedDocument extracted = analyzer.fromKeyValues(fields, List.of(), "AnalyzeDocument");

        assertThat(extracted.documentNumber()).isEqualTo("1020304050");
        assertThat(extracted.documentType()).isEqualTo("CEDULA DE CIUDADANIA");
        assertThat(extracted.fullName()).isEqualTo("JANE DOE");
        assertThat(extracted.birthDate()).isEqualTo("1998-05-10");
    }

    @Test
    void aDocumentWithoutComparableFieldsIsReportedAsNotAnalyzed() {
        ExtractedDocument extracted = analyzer.fromKeyValues(Map.of(), List.of("texto irrelevante"), "AnalyzeDocument");

        assertThat(extracted.analyzed()).isFalse();
        assertThat(extracted.rawLines()).containsExactly("texto irrelevante");
    }

    private IdentityDocumentField field(String key, String value) {
        return IdentityDocumentField.builder()
                .type(AnalyzeIDDetections.builder().text(key).build())
                .valueDetection(AnalyzeIDDetections.builder().text(value).build())
                .build();
    }

    private Block keyBlock(String id, String text, String valueId) {
        return Block.builder()
                .id(id)
                .blockType(BlockType.KEY_VALUE_SET)
                .entityTypes(EntityType.KEY)
                .text(text)
                .relationships(Relationship.builder().type(RelationshipType.VALUE).ids(valueId).build())
                .build();
    }

    private Block valueBlock(String id, String... children) {
        return Block.builder()
                .id(id)
                .blockType(BlockType.KEY_VALUE_SET)
                .entityTypes(EntityType.VALUE)
                .relationships(Relationship.builder().type(RelationshipType.CHILD).ids(children).build())
                .build();
    }

    private Block wordBlock(String id, String text) {
        return Block.builder().id(id).blockType(BlockType.WORD).text(text).build();
    }

    private Block lineBlock(String id, String text) {
        return Block.builder().id(id).blockType(BlockType.LINE).text(text).build();
    }
}
