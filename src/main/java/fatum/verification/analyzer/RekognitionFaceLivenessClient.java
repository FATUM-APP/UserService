package fatum.verification.analyzer;

import fatum.verification.VerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.AuditImage;
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequest;
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequestSettings;
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionResponse;
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsRequest;
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse;
import software.amazon.awssdk.services.rekognition.model.LivenessOutputConfig;
import software.amazon.awssdk.services.rekognition.model.LivenessSessionStatus;
import software.amazon.awssdk.services.rekognition.model.S3Object;

import java.time.Instant;

/**
 * Amazon Rekognition implementation of the proof of life.
 *
 * <p>The reference picture is written by Rekognition into the configured bucket, which is why the
 * session is created with an output configuration: the service never receives the video and never
 * stores it either.</p>
 */
@Component
public class RekognitionFaceLivenessClient implements FaceLivenessClient {

    private static final Logger log = LoggerFactory.getLogger(RekognitionFaceLivenessClient.class);

    private final RekognitionClient rekognitionClient;
    private final VerificationProperties properties;

    public RekognitionFaceLivenessClient(RekognitionClient rekognitionClient, VerificationProperties properties) {
        this.rekognitionClient = rekognitionClient;
        this.properties = properties;
    }

    @Override
    public LivenessSession createSession(String userAwsId) {
        VerificationProperties.Liveness liveness = properties.getLiveness();
        if (!liveness.isEnabled() || !properties.getAnalyzers().isRekognitionEnabled()) {
            log.debug("The proof of life is disabled; no session was created for {}", userAwsId);
            return null;
        }
        if (!StringUtils.hasText(liveness.getBucket())) {
            log.error("No bucket is configured for the proof of life: set fatum.verification.liveness.bucket");
            return null;
        }
        try {
            CreateFaceLivenessSessionResponse response = rekognitionClient.createFaceLivenessSession(
                    CreateFaceLivenessSessionRequest.builder()
                            .settings(CreateFaceLivenessSessionRequestSettings.builder()
                                    .outputConfig(LivenessOutputConfig.builder()
                                            .s3Bucket(liveness.getBucket())
                                            .s3KeyPrefix(liveness.getKeyPrefix())
                                            .build())
                                    .auditImagesLimit(liveness.getAuditImagesLimit())
                                    .build())
                            .build());
            log.info("Proof of life session {} created for {}", response.sessionId(), userAwsId);
            return new LivenessSession(
                    response.sessionId(),
                    Instant.now().plus(liveness.getSessionTtl()),
                    liveness.getBucket(),
                    liveness.getKeyPrefix());
        } catch (SdkException exception) {
            log.error("Rekognition could not create a proof of life session for {}", userAwsId, exception);
            return null;
        }
    }

    @Override
    public LivenessResult result(String sessionId) {
        try {
            GetFaceLivenessSessionResultsResponse response = rekognitionClient.getFaceLivenessSessionResults(
                    GetFaceLivenessSessionResultsRequest.builder().sessionId(sessionId).build());
            return toResult(response);
        } catch (SdkException exception) {
            log.error("Rekognition could not answer the proof of life session {}", sessionId, exception);
            return LivenessResult.unavailable("rekognition-error");
        }
    }

    private LivenessResult toResult(GetFaceLivenessSessionResultsResponse response) {
        double confidence = response.confidence() == null ? 0d : response.confidence();
        S3Object reference = referenceOf(response.referenceImage());
        String bucket = reference == null ? null : reference.bucket();
        String key = reference == null ? null : reference.name();
        LivenessSessionStatus status = response.status();
        if (status == null) {
            return LivenessResult.unavailable("missing-status");
        }
        return switch (status) {
            case SUCCEEDED -> new LivenessResult(LivenessStatus.SUCCEEDED, confidence, bucket, key, null);
            case FAILED -> new LivenessResult(LivenessStatus.FAILED, confidence, bucket, key, "liveness-failed");
            case EXPIRED -> new LivenessResult(LivenessStatus.EXPIRED, confidence, null, null, "session-expired");
            case CREATED, IN_PROGRESS -> LivenessResult.pending("session-in-progress");
            default -> LivenessResult.unavailable("unknown-session-status");
        };
    }

    private S3Object referenceOf(AuditImage referenceImage) {
        if (referenceImage == null || referenceImage.s3Object() == null) {
            return null;
        }
        return referenceImage.s3Object();
    }
}