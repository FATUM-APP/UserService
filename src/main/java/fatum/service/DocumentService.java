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
    public StoredFileResponse replace(String auth0Id, MultipartFile file)
            throws FatumUserException, IOException {
        User user = getActiveUser(auth0Id);
        ValidatedFile validatedFile = validate(file);
        DocumentFile current = documentFileRepository.findByUserAuth0Id(auth0Id).orElse(null);
        String previousKey = current == null ? null : current.getDocumentKey();

        StoredObject uploaded = storage.upload(
                file,
                bucketName,
                OBJECT_PREFIX,
                validatedFile.safeFilename(),
                validatedFile.contentType());

        try {
            if (current == null) {
                current = new DocumentFile(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size(),
                        user);
            } else {
                current.replace(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size());
            }

            DocumentFile saved = documentFileRepository.save(current);
            if (previousKey != null && !previousKey.equals(uploaded.key())) {
                storage.delete(bucketName, previousKey);
            }
            return toResponse(saved);
        } catch (RuntimeException exception) {
            storage.delete(bucketName, uploaded.key());
            throw exception;
        }
    }


    public StoredFileResponse get(String auth0Id) throws FatumUserException {
        getActiveUser(auth0Id);
        DocumentFile document = documentFileRepository.findByUserAuth0Id(auth0Id)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(document);
    }


    public StoredFileResponse find(String auth0Id) {
        return documentFileRepository.findByUserAuth0Id(auth0Id)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public void delete(String auth0Id) throws FatumUserException {
        getActiveUser(auth0Id);
        DocumentFile document = documentFileRepository.findByUserAuth0Id(auth0Id)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        storage.delete(bucketName, document.getDocumentKey());
        documentFileRepository.delete(document);
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

    private User getActiveUser(String auth0Id) throws FatumUserException {
        validateAuth0Id(auth0Id);
        User user = userRepository.findByAuth0Id(auth0Id);
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        if (!user.isActive()) {
            throw new FatumUserException(FatumUserException.INACTIVE);
        }
        return user;
    }

    private void validateAuth0Id(String auth0Id) throws FatumUserException {
        if (auth0Id == null || auth0Id.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
    }

    private ValidatedFile validate(MultipartFile file) throws FatumUserException {
        if (file == null || file.isEmpty()) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);
        }
        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT);
        }
        if (!isSupportedContentType(contentType)) {
            throw new FatumUserException(FatumUserException.INVALID_DOCUMENT_TYPE);
        }
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
