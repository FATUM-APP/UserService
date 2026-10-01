package fatum.service;

import fatum.dto.DocumentResponse;
import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.User;
import fatum.model.constant.DocumentType;
import fatum.repository.DocumentFileRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Identity documents, stored as photographs of both sides.
 *
 * <p>The previous implementation merged the sides into a PDF with PDFBox. The verification pipeline
 * needs the picture itself (Textract and Rekognition read images), so the document is now kept as one
 * image per side and the object itself lives in the shared storage service.</p>
 *
 * <p>A passport has a single side; the rest of the types require both, which is validated before
 * anything is uploaded.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final UserRepository userRepository;
    private final DocumentFileRepository documentFileRepository;
    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;

    public DocumentService(
            UserRepository userRepository,
            DocumentFileRepository documentFileRepository,
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties) {
        this.userRepository = userRepository;
        this.documentFileRepository = documentFileRepository;
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
    }

    /**
     * Stores the document of a user, replacing the previous one when it exists.
     *
     * @param awsId        Cognito subject of the user
     * @param documentType type declared by the user; it drives how many sides are required
     * @param front        picture of the front side, always required
     * @param back         picture of the back side, required unless the document is a passport
     */
    @Transactional
    public DocumentResponse upload(
            String awsId,
            DocumentType documentType,
            MultipartFile front,
            MultipartFile back) throws FatumUserException {

        User user = getActiveUser(awsId);
        if (documentType == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        validateImage(front, FatumUserException.INVALID_DOCUMENT);
        boolean twoSided = documentType != DocumentType.PASSPORT;
        if (twoSided) {
            if (back == null || back.isEmpty()) {
                throw new FatumUserException(FatumUserException.DOCUMENT_BACK_REQUIRED);
            }
            validateImage(back, FatumUserException.INVALID_DOCUMENT);
        }

        String route = storageProperties.getDocumentRoute();
        StoredFile uploadedFront = fileStorageClient.upload(front, route);
        StoredFile uploadedBack = null;
        try {
            if (twoSided) {
                uploadedBack = fileStorageClient.upload(back, route);
            }
            DocumentFile existing = documentFileRepository.findByUserAwsId(awsId).orElse(null);
            String previousFront = existing == null ? null : existing.getFrontKey();
            String previousBack = existing == null ? null : existing.getBackKey();

            DocumentFile document = existing;
            if (document == null) {
                document = new DocumentFile(
                        uploadedFront.key(),
                        uploadedBack == null ? null : uploadedBack.key(),
                        uploadedFront.originalFilename(),
                        uploadedBack == null ? null : uploadedBack.originalFilename(),
                        uploadedFront.contentType(),
                        uploadedFront.size(),
                        uploadedBack == null ? null : uploadedBack.size(),
                        user);
            } else {
                document.replace(
                        uploadedFront.key(),
                        uploadedBack == null ? null : uploadedBack.key(),
                        uploadedFront.originalFilename(),
                        uploadedBack == null ? null : uploadedBack.originalFilename(),
                        uploadedFront.contentType(),
                        uploadedFront.size(),
                        uploadedBack == null ? null : uploadedBack.size());
            }
            DocumentFile saved = documentFileRepository.save(document);
            deleteQuietly(previousFront);
            deleteQuietly(previousBack);
            return toResponse(saved, user.getDocumentType());
        } catch (RuntimeException exception) {
            deleteQuietly(uploadedFront.key());
            if (uploadedBack != null) {
                deleteQuietly(uploadedBack.key());
            }
            throw exception;
        }
    }

    public DocumentResponse get(String awsId) throws FatumUserException {
        User user = getActiveUser(awsId);
        DocumentFile document = documentFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(document, user.getDocumentType());
    }

    /** Removes the document of a user, objects included. Used when the user starts over. */
    @Transactional
    public void delete(String awsId) {
        documentFileRepository.findByUserAwsId(awsId).ifPresent(document -> {
            deleteQuietly(document.getFrontKey());
            deleteQuietly(document.getBackKey());
            documentFileRepository.delete(document);
        });
    }

    private DocumentResponse toResponse(DocumentFile document, DocumentType documentType) {
        String route = storageProperties.getDocumentRoute();
        return new DocumentResponse(
                document.getId(),
                documentType,
                document.getFrontFilename(),
                document.getBackFilename(),
                document.getContentType(),
                document.getFrontSize(),
                document.getBackSize(),
                presignedQuietly(route, document.getFrontKey()),
                presignedQuietly(route, document.getBackKey()),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    private User getActiveUser(String awsId) throws FatumUserException {
        if (awsId == null || awsId.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByAwsId(awsId.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private void validateImage(MultipartFile file, String message) throws FatumUserException {
        if (file == null || file.isEmpty()) {
            throw new FatumUserException(message);
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new FatumUserException(message);
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT_TYPE);
        }
    }

    private String presignedQuietly(String route, String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            return fileStorageClient.presignedUrl(route, objectKey);
        } catch (StorageException exception) {
            log.warn("The download URL of {} could not be created", objectKey, exception);
            return null;
        }
    }

    private void deleteQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            fileStorageClient.delete(storageProperties.getDocumentRoute(), objectKey);
        } catch (StorageException exception) {
            log.warn("The object {} could not be deleted from the document route", objectKey, exception);
        }
    }
}
