package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.User;
import fatum.repository.DocumentFileRepository;
import fatum.repository.UserRepository;
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
    private final String bucketName;

    public DocumentService(
            UserRepository userRepository,
            DocumentFileRepository documentFileRepository,
            S3FileStorage storage,
            @Value("${aws.s3.documents-bucket}") String bucketName) {
        this.userRepository = userRepository;
        this.documentFileRepository = documentFileRepository;
        this.storage = storage;
        this.bucketName = bucketName;
    }

    @Transactional
    public StoredFileResponse upload(String awsId, MultipartFile file)
            throws FatumUserException, IOException {
        User user = getActiveUser(awsId);
        ValidatedFile validatedFile = validate(file);

        if (documentFileRepository.findByUserAwsId(awsId).isPresent()) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }

        StoredObject uploaded = storage.upload(
                file,
                bucketName,
                OBJECT_PREFIX,
                validatedFile.safeFilename(),
                validatedFile.contentType());

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


    private ValidatedFile validate(MultipartFile file) throws FatumUserException {
        if (file == null || file.isEmpty()) throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);

        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (originalFilename == null || originalFilename.isBlank()) throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);

        if (!isSupportedContentType(contentType))
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT_TYPE);
        try {
            return new ValidatedFile(storage.sanitizeFilename(originalFilename), contentType);
        } catch (IllegalArgumentException exception) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);
        }
    }

    private boolean isSupportedContentType(String contentType) {
        return contentType != null
                && (contentType.equalsIgnoreCase("application/pdf")
                || contentType.startsWith("image/"));
    }

    private record ValidatedFile(String safeFilename, String contentType) {
    }
}
