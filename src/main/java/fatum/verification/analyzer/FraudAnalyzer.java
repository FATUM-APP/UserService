package fatum.verification.analyzer;

import fatum.model.User;

/**
 * Looks for signs of a forged document. Implemented with Amazon Bedrock.
 *
 * <p>The model receives the fields Textract read together with the data the user registered and has to
 * answer how likely the document is to be manipulated, which inconsistencies it found and why.</p>
 */
public interface FraudAnalyzer {

    /**
     * @param extracted     fields read from the document
     * @param user          registered user, used for the comparison
     * @param documentMatch percentage of fields that already agree with the registered data
     */
    FraudAssessment assess(ExtractedDocument extracted, User user, double documentMatch);
}
