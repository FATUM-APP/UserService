package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.User;
import fatum.model.constant.DocumentType;
import fatum.repository.DocumentFileRepository;
import fatum.repository.UserRepository;
import fatum.storage.PdfMerger;
import fatum.storage.S3FileStorage;
import fatum.storage.StoredObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public class DocumentService {

    private static final String OBJECT_PREFIX = "documents";

    private final UserRepository userRepository;
    private final DocumentFileRepository documentFileRepository;
    private final S3FileStorage storage;
    private final PdfMerger pdfMerger;
    private final String bucketName;

    public DocumentService(
            UserRepository userRepository,
            DocumentFileRepository documentFileRepository,
            S3FileStorage storage,
            PdfMerger pdfMerger,
            @Value("${aws.s3.documents-bucket}") String bucketName) {
        this.userRepository = userRepository;
        this.documentFileRepository = documentFileRepository;
        this.storage = storage;
        this.pdfMerger = pdfMerger;
        this.bucketName = bucketName;
    }

    /**
     * Uploads an identity document. For double-sided document types (ID, DRIVING_LICENSE),
     * the front and back files are merged into a single PDF before upload.
     * For single-sided types (PASSPORT), only the front file is used.
     *
     * @param awsId        the user's Cognito sub
     * @param documentType the type of document being uploaded
     * @param front        the front side file (required)
     * @param back         the back side file (required for ID and DRIVING_LICENSE, ignored for PASSPORT)
     */
    @Transactional
    public StoredFileResponse upload(
            String awsId,
            DocumentType documentType,
            MultipartFile front,
            MultipartFile back) throws FatumUserException, IOException {

        User user = getActiveUser(awsId);

        if (documentFileRepository.findByUserAwsId(awsId).isPresent()) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }

        validateFile(front, "front");

        byte[] pdfBytes;
        String mergedFilename;

        if (documentType == DocumentType.PASSPORT) {
            // Single-sided: wrap to PDF if it's an image, or use as-is if already PDF
            validateSupportedContentType(front.getContentType());
            pdfBytes = pdfMerger.wrapToPdf(front);
            mergedFilename = documentType.name().toLowerCase() + ".pdf";
        } else {
            // Double-sided (ID, DRIVING_LICENSE): both sides required, merge into one PDF
            if (back == null || back.isEmpty()) {
                throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);
            }
            validateFile(back, "back");
            validateSupportedContentType(front.getContentType());
            validateSupportedContentType(back.getContentType());
            pdfBytes = pdfMerger.mergeToPdf(front, back);
            mergedFilename = documentType.name().toLowerCase() + ".pdf";
        }

        StoredObject uploaded = storage.upload(
                pdfBytes,
                bucketName,
                OBJECT_PREFIX,
                mergedFilename,
                "application/pdf");

        try {
            DocumentFile document = new DocumentFile(
                    uploaded.key(),
                    uploaded.originalFilename(),
                    uploaded.contentType(),
                    uploaded.size(),
                    user);

            DocumentFile saved = documentFileRepository.save(document);
            return toResponse(saved);
        } catch (RuntimeException exception) {
            storage.delete(bucketName, uploaded.key());
            throw exception;
        }
    }

    /**
     * Uploads a single pre-scanned PDF document directly.
     * The file must be a valid PDF.
     *
     * @param awsId        the user's Cognito sub
     * @param documentType the type of document being uploaded
     * @param file         the PDF file
     */
    @Transactional
    public StoredFileResponse uploadSingle(
            String awsId,
            DocumentType documentType,
            MultipartFile file) throws FatumUserException, IOException {

        User user = getActiveUser(awsId);

        if (documentFileRepository.findByUserAwsId(awsId).isPresent()) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }

        validateFile(file, "file");

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT_TYPE);
        }

        String safeFilename = documentType.name().toLowerCase() + ".pdf";

        StoredObject uploaded = storage.upload(
                file.getBytes(),
                bucketName,
                OBJECT_PREFIX,
                safeFilename,
                "application/pdf");

        try {
            DocumentFile document = new DocumentFile(
                    uploaded.key(),
                    uploaded.originalFilename(),
                    uploaded.contentType(),
                    uploaded.size(),
                    user);

            DocumentFile saved = documentFileRepository.save(document);
            return toResponse(saved);
        } catch (RuntimeException exception) {
            storage.delete(bucketName, uploaded.key());
            throw exception;
        }
    }


    public StoredFileResponse get(String awsId) throws FatumUserException {
        getActiveUser(awsId);
        DocumentFile document = documentFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(document);
    }

    private StoredFileResponse toResponse(DocumentFile document) {
        return new StoredFileResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getFileSize(),
                storage.presignedDownloadUrl(bucketName, document.getDocumentKey()),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    private User getActiveUser(String awsId) throws FatumUserException {
        if (awsId == null || awsId.isBlank()) throw new FatumUserException(FatumUserException.NULL_VALUE);
        User user = userRepository.findByAwsId(awsId.trim());
        if (user == null) throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        return user;
    }

    private void validateFile(MultipartFile file, String label) throws FatumUserException {
        if (file == null || file.isEmpty()) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);
        }
    }

    private void validateSupportedContentType(String contentType) throws FatumUserException {
        if (contentType == null) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT_TYPE);
        }
        if (!contentType.equalsIgnoreCase("application/pdf") && !contentType.startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT_TYPE);
        }
    }
}