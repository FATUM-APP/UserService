package fatum.verification.analyzer;

/**
 * Compares the faces of two images. Implemented with Amazon Rekognition ({@code CompareFaces}).
 *
 * <p>Three comparisons drive the pipeline:</p>
 * <ul>
 *   <li>the picture of the identity document against the profile picture, the cheap check that
 *       decides whether the paid proof of life is worth requesting;</li>
 *   <li>the picture Rekognition produced during the proof of life against the picture of the
 *       document, the check that confirms the live person is the owner of the document and not
 *       somebody holding their photographs;</li>
 *   <li>a new profile picture against the live reference, to keep a verified account showing always
 *       the same person.</li>
 * </ul>
 *
 * <p>The live reference is written by Rekognition straight into S3, so it is compared by reference
 * and never downloaded.</p>
 */
public interface FaceComparator {

    /**
     * @param source first image
     * @param target second image
     * @return the best similarity found, or a non-evaluated result when no face could be used
     */
    FaceMatch compare(byte[] source, byte[] target);

    /**
     * Compares a local image with an object that already lives in S3.
     *
     * @param localImage bytes of the image held by this service
     * @param bucket     bucket of the stored object
     * @param objectKey  key of the stored object
     * @return the best similarity found, or a non-evaluated result when no face could be used
     */
    FaceMatch compareWithStoredObject(byte[] localImage, String bucket, String objectKey);
}
