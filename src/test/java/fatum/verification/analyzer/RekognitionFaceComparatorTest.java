package fatum.verification.analyzer;

import fatum.verification.VerificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CompareFacesMatch;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RekognitionFaceComparatorTest {

    private final RekognitionClient rekognitionClient = mock(RekognitionClient.class);

    private final VerificationProperties properties = new VerificationProperties();
    private RekognitionFaceComparator comparator;

    private final byte[] reference = {1, 2, 3};
    private final byte[] candidate = {4, 5, 6};

    @BeforeEach
    void setUp() {
        comparator = new RekognitionFaceComparator(rekognitionClient, properties);
    }

    @Test
    void returnsTheBestSimilarityOfTheMatches() {
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class))).thenReturn(CompareFacesResponse.builder()
                .faceMatches(
                        match(91.5),
                        match(97.2))
                .build());

        FaceMatch result = comparator.compare(reference, candidate);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.similarity()).isCloseTo(97.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.detail()).isNull();
    }

    @Test
    void noMatchIsAValidResultWithZeroSimilarity() {
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class)))
                .thenReturn(CompareFacesResponse.builder().build());

        FaceMatch result = comparator.compare(reference, candidate);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.similarity()).isZero();
        assertThat(result.detail()).isEqualTo("no-face-match");
    }

    @Test
    void anImageWithoutAFaceIsAValidResultToo() {
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class)))
                .thenThrow(InvalidParameterException.builder().message("no face").build());

        FaceMatch result = comparator.compare(reference, candidate);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.similarity()).isZero();
        assertThat(result.detail()).isEqualTo("no-face-detected");
    }

    @Test
    void anAwsFailureIsNotEvaluated() {
        when(rekognitionClient.compareFaces(any(CompareFacesRequest.class)))
                .thenThrow(SdkClientException.create("rekognition down"));

        FaceMatch result = comparator.compare(reference, candidate);

        assertThat(result.evaluated()).isFalse();
        assertThat(result.detail()).isEqualTo("rekognition-error");
    }

    @Test
    void theComparatorCanBeSwitchedOff() {
        properties.getAnalyzers().setRekognitionEnabled(false);

        FaceMatch result = comparator.compare(reference, candidate);

        assertThat(result.evaluated()).isFalse();
        assertThat(result.detail()).isEqualTo("rekognition-disabled");
        verify(rekognitionClient, never()).compareFaces(any(CompareFacesRequest.class));
    }

    @Test
    void anEmptyImageIsNotSentToAws() {
        FaceMatch result = comparator.compare(new byte[0], candidate);

        assertThat(result.evaluated()).isFalse();
        assertThat(result.detail()).isEqualTo("empty-image");
        verify(rekognitionClient, never()).compareFaces(any(CompareFacesRequest.class));
    }

    private CompareFacesMatch match(double similarity) {
        return CompareFacesMatch.builder().similarity((float) similarity).build();
    }
}
