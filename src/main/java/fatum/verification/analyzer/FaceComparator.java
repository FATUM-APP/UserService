package fatum.verification.analyzer;

/**
 * Compares the faces of two images. Implemented with Amazon Rekognition ({@code CompareFaces}).
 *
 * <p>Two comparisons drive the pipeline: the liveness frame against the picture on the identity
 * document (does the document belong to the person?) and the liveness frame against the profile
 * picture (is the account picture the same person?).</p>
 */
public interface FaceComparator {

    /**
     * @param source first image, typically the liveness frame
     * @param target second image, the document picture or the profile picture
     * @return the best similarity found, or a non-evaluated result when no face could be used
     */
    FaceMatch compare(byte[] source, byte[] target);
}
