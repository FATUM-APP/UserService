package fatum.verification.analyzer;

import fatum.model.constant.DocumentType;

/**
 * Reads the identity document. Implemented with Amazon Textract.
 *
 * <p>Two strategies are available and both are used, best effort first: {@code AnalyzeID} understands
 * identity documents (passport, identity card) and returns normalised fields, while
 * {@code AnalyzeDocument} with the FORMS feature plus raw text extraction still recovers the number and
 * dates when the document layout is not one Textract knows.</p>
 */
public interface DocumentAnalyzer {

    /**
     * @param type  document type declared by the user, used to pick the strategy
     * @param front picture of the front side, never null
     * @param back  picture of the back side, null for single sided documents
     */
    ExtractedDocument extract(DocumentType type, byte[] front, byte[] back);
}
