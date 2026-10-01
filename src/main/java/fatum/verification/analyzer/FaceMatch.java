package fatum.verification.analyzer;

/** Result of comparing two faces with Amazon Rekognition. */
public record FaceMatch(boolean evaluated, double similarity, String detail) {

    public static FaceMatch notEvaluated(String detail) {
        return new FaceMatch(false, 0d, detail);
    }

    public static FaceMatch of(double similarity) {
        return new FaceMatch(true, similarity, null);
    }
}
