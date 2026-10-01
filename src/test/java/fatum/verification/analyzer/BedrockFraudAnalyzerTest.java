package fatum.verification.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fatum.model.User;
import fatum.support.Fixtures;
import fatum.verification.VerificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockFraudAnalyzerTest {

    private final BedrockRuntimeClient bedrockClient = mock(BedrockRuntimeClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final VerificationProperties properties = new VerificationProperties();
    private final User user = Fixtures.user();

    private BedrockFraudAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new BedrockFraudAnalyzer(bedrockClient, properties, objectMapper);
    }

    @Test
    void readsTheVerdictOfTheModel() {
        givenAnswer("""
                {"risk": 12, "flags": ["blurred-photo"], "reasoning": "The picture is blurry but coherent."}
                """);

        FraudAssessment assessment = analyzer.assess(document(), user, 80d);

        assertThat(assessment.evaluated()).isTrue();
        assertThat(assessment.risk()).isEqualTo(12d);
        assertThat(assessment.flags()).containsExactly("blurred-photo");
        assertThat(assessment.reasoning()).contains("blurry");
    }

    @Test
    void toleratesTheMarkdownFencesModelsLikeToAdd() {
        givenAnswer("""
                Sure, here is my analysis:
                ```json
                {"risk": 75, "flags": ["suspicious-font"], "reasoning": "The font is not the official one."}
                ```
                """);

        FraudAssessment assessment = analyzer.assess(document(), user, 40d);

        assertThat(assessment.risk()).isEqualTo(75d);
        assertThat(assessment.flags()).containsExactly("suspicious-font");
    }

    @Test
    void anUnparsableAnswerIsNotEvaluated() {
        givenAnswer("I cannot answer that.");

        FraudAssessment assessment = analyzer.assess(document(), user, 40d);

        assertThat(assessment.evaluated()).isFalse();
        assertThat(assessment.reasoning()).isEqualTo("bedrock-unparsable-answer");
    }

    @Test
    void anEmptyAnswerIsNotEvaluated() {
        givenAnswer("");

        assertThat(analyzer.assess(document(), user, 40d).evaluated()).isFalse();
    }

    @Test
    void aDocumentThatCouldNotBeReadIsNotSentToTheModel() {
        FraudAssessment assessment = analyzer.assess(ExtractedDocument.notAnalyzed("textract-error"), user, 0d);

        assertThat(assessment.evaluated()).isFalse();
        assertThat(assessment.reasoning()).isEqualTo("document-not-analyzed");
        verify(bedrockClient, never()).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void theAnalyzerCanBeSwitchedOff() {
        properties.getAnalyzers().setBedrockEnabled(false);

        FraudAssessment assessment = analyzer.assess(document(), user, 40d);

        assertThat(assessment.evaluated()).isFalse();
        assertThat(assessment.reasoning()).isEqualTo("bedrock-disabled");
        verify(bedrockClient, never()).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void anAwsFailureIsNotEvaluated() {
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(SdkClientException.create("bedrock down"));

        FraudAssessment assessment = analyzer.assess(document(), user, 40d);

        assertThat(assessment.evaluated()).isFalse();
        assertThat(assessment.reasoning()).isEqualTo("bedrock-error");
    }

    @Test
    void theRequestUsesTheAnthropicMessagesPayload() throws Exception {
        String payload = analyzer.buildPayload(document(), user, 55d);

        JsonNode node = objectMapper.readTree(payload);
        assertThat(node.path("anthropic_version").asText()).isEqualTo("bedrock-2023-05-31");
        assertThat(node.path("max_tokens").asInt()).isEqualTo(800);
        assertThat(node.path("system").asText()).contains("document fraud analyst");
        String prompt = node.path("messages").get(0).path("content").get(0).path("text").asText();
        assertThat(prompt)
                .contains("JANE DOE")
                .contains("1020304050")
                .contains("Percentage of fields that already match: 55");
    }

    @Test
    void theModelIdentifierComesFromTheConfiguration() {
        properties.getAnalyzers().setBedrockModelId("anthropic.claude-3-haiku-20240307-v1:0");
        givenAnswer("{\"risk\": 5, \"flags\": [], \"reasoning\": \"ok\"}");

        analyzer.assess(document(), user, 90d);

        org.mockito.ArgumentCaptor<InvokeModelRequest> request =
                org.mockito.ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(request.capture());
        assertThat(request.getValue().modelId()).isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
        assertThat(request.getValue().contentType()).isEqualTo("application/json");
    }

    private void givenAnswer(String answer) {
        String body = objectMapper.createObjectNode()
                .set("content", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("type", "text").put("text", answer)))
                .toString();
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(body))
                .build());
    }

    private ExtractedDocument document() {
        return new ExtractedDocument(true, "1020304050", "ID", "JANE DOE", "1998-05-10",
                List.of("AnalyzeID"), List.of(), "AnalyzeID");
    }
}
