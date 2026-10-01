package fatum.verification.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fatum.model.User;
import fatum.verification.VerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon Bedrock based forgery detection.
 *
 * <p>The model does not look at the picture: it receives the fields Textract read together with the data
 * the user registered and has to judge whether the combination looks manipulated (a number that does not
 * belong to the document type, a name that contradicts the record, a date that cannot be right). Keeping
 * the judgement text based makes the result explainable, which is what an administrator needs to review a
 * case.</p>
 *
 * <p>The call uses {@code InvokeModel} with the Anthropic Messages payload, which is available for every
 * Claude model on Bedrock and does not depend on the newer Converse API.</p>
 */
@Component
public class BedrockFraudAnalyzer implements FraudAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BedrockFraudAnalyzer.class);

    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";

    private static final String SYSTEM_PROMPT = """
            You are a document fraud analyst for an identity verification platform.
            You receive the fields extracted from an identity document together with the data the user
            registered. Decide how likely the document is to be forged or manipulated.
            Answer with a single JSON object and nothing else, using this shape:
            {"risk": <0-100 integer>, "flags": ["short-reason", ...], "reasoning": "<one paragraph>"}
            Use a high risk only when there is real evidence of manipulation or of a document that does
            not belong to the registered person.""";

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final VerificationProperties properties;
    private final ObjectMapper objectMapper;

    public BedrockFraudAnalyzer(
            BedrockRuntimeClient bedrockRuntimeClient,
            VerificationProperties properties,
            ObjectMapper objectMapper) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public FraudAssessment assess(ExtractedDocument extracted, User user, double documentMatch) {
        if (!properties.getAnalyzers().isBedrockEnabled()) {
            return FraudAssessment.notEvaluated("bedrock-disabled");
        }
        if (extracted == null || !extracted.analyzed()) {
            return FraudAssessment.notEvaluated("document-not-analyzed");
        }
        try {
            InvokeModelResponse response = bedrockRuntimeClient.invokeModel(InvokeModelRequest.builder()
                    .modelId(properties.getAnalyzers().getBedrockModelId())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(buildPayload(extracted, user, documentMatch)))
                    .build());
            return parse(response.body().asUtf8String());
        } catch (RuntimeException exception) {
            log.error("Bedrock could not assess the authenticity of the document", exception);
            return FraudAssessment.notEvaluated("bedrock-error");
        }
    }

    /** Builds the Anthropic Messages request body. */
    String buildPayload(ExtractedDocument extracted, User user, double documentMatch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("anthropic_version", ANTHROPIC_VERSION);
        payload.put("max_tokens", properties.getAnalyzers().getBedrockMaxTokens());
        payload.put("temperature", properties.getAnalyzers().getBedrockTemperature());
        payload.put("system", SYSTEM_PROMPT);
        payload.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", buildPrompt(extracted, user, documentMatch))))));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("The Bedrock payload could not be serialized", exception);
        }
    }

    /** Extracts the JSON verdict from the model answer; tolerates markdown fences and extra prose. */
    FraudAssessment parse(String responseBody) {
        String answer = textOf(responseBody);
        if (answer == null || answer.isBlank()) {
            return FraudAssessment.notEvaluated("bedrock-empty-answer");
        }
        String json = extractJson(answer);
        if (json == null) {
            return FraudAssessment.notEvaluated("bedrock-unparsable-answer");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            double risk = node.path("risk").asDouble(0d);
            List<String> flags = new ArrayList<>();
            node.path("flags").forEach(flag -> flags.add(flag.asText()));
            String reasoning = node.path("reasoning").asText(null);
            return FraudAssessment.of(risk, flags, reasoning);
        } catch (Exception exception) {
            log.warn("The Bedrock answer is not valid JSON: {}", answer, exception);
            return FraudAssessment.notEvaluated("bedrock-unparsable-answer");
        }
    }

    /** Reads the first text block of the Anthropic answer. */
    private String textOf(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode content = node.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    String text = block.path("text").asText(null);
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
            return null;
        } catch (Exception exception) {
            log.warn("The Bedrock response is not valid JSON", exception);
            return null;
        }
    }

    private String extractJson(String answer) {
        String trimmed = answer.replace("```json", " ").replace("```", " ").trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private String buildPrompt(ExtractedDocument extracted, User user, double documentMatch) {
        return """
                Document type declared by the user: %s
                Fields read from the document:
                  - identity number: %s
                  - document type: %s
                  - full name: %s
                  - birth date: %s
                  - reader used: %s
                Registered user data:
                  - name: %s
                  - identity number: %s
                  - birth date: %s
                  - declared document type: %s
                Percentage of fields that already match: %.2f
                Judge the authenticity of the document.""".formatted(
                user.getDocumentType(),
                safe(extracted.documentNumber()),
                safe(extracted.documentType()),
                safe(extracted.fullName()),
                safe(extracted.birthDate()),
                safe(extracted.source()),
                safe(user.getName()),
                safe(user.getDocument()),
                user.getBirthDate(),
                user.getDocumentType(),
                documentMatch);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "(not read)" : value;
    }
}
