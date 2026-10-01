package fatum.verification.analyzer;

import fatum.verification.VerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException;

/**
 * Amazon Rekognition based face comparison.
 *
 * <p>The threshold is sent as zero so the service always answers with the best similarity it found;
 * whether that similarity is "the same person" is a business decision that belongs to the policy, not
 * to the AWS call.</p>
 */
@Component
public class RekognitionFaceComparator implements FaceComparator {

    private static final Logger log = LoggerFactory.getLogger(RekognitionFaceComparator.class);

    private final RekognitionClient rekognitionClient;
    private final VerificationProperties properties;

    public RekognitionFaceComparator(RekognitionClient rekognitionClient, VerificationProperties properties) {
        this.rekognitionClient = rekognitionClient;
        this.properties = properties;
    }

    @Override
    public FaceMatch compare(byte[] source, byte[] target) {
        if (!properties.getAnalyzers().isRekognitionEnabled()) {
            return FaceMatch.notEvaluated("rekognition-disabled");
        }
        if (source == null || source.length == 0 || target == null || target.length == 0) {
            return FaceMatch.notEvaluated("empty-image");
        }
        try {
            CompareFacesResponse response = rekognitionClient.compareFaces(CompareFacesRequest.builder()
                    .sourceImage(Image.builder().bytes(SdkBytes.fromByteArray(source)).build())
                    .targetImage(Image.builder().bytes(SdkBytes.fromByteArray(target)).build())
                    .similarityThreshold(0f)
                    .build());
            double best = response.faceMatches().stream()
                    .mapToDouble(match -> match.similarity() == null ? 0d : match.similarity())
                    .max()
                    .orElse(0d);
            return new FaceMatch(true, best, response.faceMatches().isEmpty() ? "no-face-match" : null);
        } catch (InvalidParameterException exception) {
            // Rekognition raises this when one of the images has no detectable face: it is a result,
            // not a failure, and the similarity is genuinely zero.
            log.warn("Rekognition found no usable face: {}", exception.getMessage());
            return new FaceMatch(true, 0d, "no-face-detected");
        } catch (SdkException exception) {
            log.error("Rekognition could not compare the faces", exception);
            return FaceMatch.notEvaluated("rekognition-error");
        }
    }
}
