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
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DocumentFileRepository documentFileRepository = mock(DocumentFileRepository.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);

    private final User user = Fixtures.user();

    private DocumentService service;

    @BeforeEach
    void setUp() {
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(documentFileRepository.save(any(DocumentFile.class))).thenAnswer(call -> call.getArgument(0));
        when(fileStorageClient.upload(any(), any())).thenAnswer(call -> storedFile("documents/new/front.png"));
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3/documents/front.png");
        service = new DocumentService(
                userRepository,
                documentFileRepository,
                fileStorageClient,
                new FileStorageProperties());
    }

    @Test
    void aPassportOnlyNeedsTheFrontSide() throws Exception {
        DocumentResponse response = service.upload(
                Fixtures.USER_ID,
                DocumentType.PASSPORT,
                Fixtures.image("front"),
                null);

        ArgumentCaptor<DocumentFile> saved = ArgumentCaptor.forClass(DocumentFile.class);
        verify(documentFileRepository).save(saved.capture());
        assertThat(saved.getValue().getFrontKey()).isEqualTo("documents/new/front.png");
        assertThat(saved.getValue().getBackKey()).isNull();
        assertThat(saved.getValue().hasBackSide()).isFalse();
        assertThat(response.frontFilename()).isEqualTo("front.png");
        assertThat(response.backUrl()).isNull();
        assertThat(response.frontUrl()).isEqualTo("https://s3/documents/front.png");
    }

    @Test
    void anIdentityCardNeedsBothSides() throws Exception {
        when(fileStorageClient.upload(any(), any()))
                .thenReturn(storedFile("documents/front.png"), storedFile("documents/back.png"));

        DocumentResponse response = service.upload(
                Fixtures.USER_ID,
                DocumentType.ID,
                Fixtures.image("front"),
                Fixtures.image("back"));

        ArgumentCaptor<DocumentFile> saved = ArgumentCaptor.forClass(DocumentFile.class);
        verify(documentFileRepository).save(saved.capture());
        assertThat(saved.getValue().getFrontKey()).isEqualTo("documents/front.png");
        assertThat(saved.getValue().getBackKey()).isEqualTo("documents/back.png");
        assertThat(response.backSize()).isNotNull();
    }

    @Test
    void aTwoSidedDocumentWithoutTheBackSideIsRejected() {
        assertThatThrownBy(() -> service.upload(
                Fixtures.USER_ID,
                DocumentType.ID,
                Fixtures.image("front"),
                null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.DOCUMENT_BACK_REQUIRED);
        verify(fileStorageClient, never()).upload(any(), any());
    }

    @Test
    void aPdfIsNoLongerAccepted() {
        MockMultipartFile pdf = new MockMultipartFile("front", "id.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.upload(Fixtures.USER_ID, DocumentType.PASSPORT, pdf, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_DOCUMENT_TYPE);
    }

    @Test
    void anEmptyFileIsRejected() {
        MockMultipartFile empty = new MockMultipartFile("front", "front.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(Fixtures.USER_ID, DocumentType.PASSPORT, empty, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_DOCUMENT);
    }

    @Test
    void uploadingAgainReplacesTheDocumentAndDeletesThePreviousObjects() throws Exception {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user)));

        service.upload(Fixtures.USER_ID, DocumentType.ID, Fixtures.image("front"), Fixtures.image("back"));

        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/front.png");
        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/back.png");
    }

    @Test
    void theUploadedObjectsAreRemovedWhenTheDatabaseFails() {
        when(documentFileRepository.save(any(DocumentFile.class)))
                .thenThrow(new IllegalStateException("constraint violation"));

        assertThatThrownBy(() -> service.upload(
                Fixtures.USER_ID,
                DocumentType.PASSPORT,
                Fixtures.image("front"),
                null))
                .isInstanceOf(IllegalStateException.class);
        verify(fileStorageClient).delete("user-service:document", "documents/new/front.png");
    }

    @Test
    void anUnknownUserCannotUpload() {
        when(userRepository.findByAwsId("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.upload("ghost", DocumentType.PASSPORT, Fixtures.image("front"), null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void aBlankUserIdentifierIsRejected() {
        assertThatThrownBy(() -> service.upload("  ", DocumentType.PASSPORT, Fixtures.image("front"), null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }

    @Test
    void aMissingDocumentTypeIsRejected() {
        assertThatThrownBy(() -> service.upload(Fixtures.USER_ID, null, Fixtures.image("front"), null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }

    @Test
    void returnsTheStoredDocument() throws Exception {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user)));

        DocumentResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.frontFilename()).isEqualTo("front.png");
        assertThat(response.backFilename()).isEqualTo("back.png");
        assertThat(response.documentType()).isEqualTo(DocumentType.ID);
    }

    @Test
    void reportsWhenTheUserHasNoDocument() {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FILE_NOT_FOUND);
    }

    @Test
    void aFailingPresignedUrlDoesNotBreakTheResponse() throws Exception {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user)));
        when(fileStorageClient.presignedUrl(any(), any())).thenThrow(StorageException.unavailable("down", null));

        DocumentResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.frontUrl()).isNull();
    }

    @Test
    void deletesTheDocumentAndItsObjects() {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user)));

        service.delete(Fixtures.USER_ID);

        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/front.png");
        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/back.png");
        verify(documentFileRepository).delete(any(DocumentFile.class));
    }

    @Test
    void deletingWithoutDocumentDoesNothing() {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        service.delete(Fixtures.USER_ID);

        verify(fileStorageClient, never()).delete(any(), any());
        verify(documentFileRepository, never()).delete(any());
    }

    @Test
    void aFailureOfTheStorageServiceIsPropagated() {
        when(fileStorageClient.upload(any(), any())).thenThrow(StorageException.unavailable("down", null));

        assertThatThrownBy(() -> service.upload(
                Fixtures.USER_ID,
                DocumentType.PASSPORT,
                Fixtures.image("front"),
                null))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void aDeletionFailureIsSwallowedWhenReplacing() throws Exception {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user)));
        org.mockito.Mockito.doThrow(StorageException.unavailable("down", null))
                .when(fileStorageClient).delete(eq("user-service:document"), any());

        DocumentResponse response = service.upload(
                Fixtures.USER_ID,
                DocumentType.PASSPORT,
                Fixtures.image("front"),
                null);

        assertThat(response).isNotNull();
    }

    private StoredFile storedFile(String key) {
        return new StoredFile("id-1", key, "documents-bucket", "front.png", "image/png", 4L);
    }
}
