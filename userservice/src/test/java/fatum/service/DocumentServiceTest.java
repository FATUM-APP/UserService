package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.User;
import fatum.model.constant.DocumentType;
import fatum.repository.DocumentFileRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.PdfMerger;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final String USER_ID = Fixtures.USER_ID;
    private static final String ROUTE = "user-service:document";
    private static final String KEY = "documents/2026/10/03/id.pdf";
    private static final byte[] PDF = {1, 2, 3, 4};
    private static final StoredFile UPLOADED =
            new StoredFile("obj-1", KEY, "fatum-documents", "id.pdf", "application/pdf", 2048L);

    @Mock
    private UserRepository userRepository;

    @Mock
    private DocumentFileRepository documentFileRepository;

    @Mock
    private FileStorageClient storage;

    @Mock
    private PdfMerger pdfMerger;

    private DocumentService service;
    private User user;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setDocumentRoute(ROUTE);
        service = new DocumentService(
                userRepository, documentFileRepository, storage, properties, pdfMerger);
        user = Fixtures.user();
    }

    // ------------------------------------------------------------------- upload

    @Test
    void mergesBothSidesOfAnIdentityCardIntoOnePdf() throws Exception {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());
        when(pdfMerger.mergeToPdf(any(), any())).thenReturn(PDF);
        when(storage.upload(any(byte[].class), eq("id.pdf"), eq("application/pdf"), eq(ROUTE)))
                .thenReturn(UPLOADED);
        when(documentFileRepository.save(any(DocumentFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignedUrl(ROUTE, KEY)).thenReturn("https://s3/" + KEY);

        StoredFileResponse response = service.upload(
                USER_ID,
                DocumentType.ID,
                Fixtures.image("front", "front.png"),
                Fixtures.image("back", "back.png"));

        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.downloadUrl()).isEqualTo("https://s3/" + KEY);
        verify(pdfMerger).mergeToPdf(any(), any());
    }

    @Test
    void aSingleSidedDocumentOnlyNeedsTheFront() throws Exception {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());
        when(pdfMerger.wrapToPdf(any())).thenReturn(PDF);
        when(storage.upload(any(byte[].class), eq("passport.pdf"), eq("application/pdf"), eq(ROUTE)))
                .thenReturn(new StoredFile("obj-2", KEY, "fatum-documents", "passport.pdf",
                        "application/pdf", 1024L));
        when(documentFileRepository.save(any(DocumentFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignedUrl(ROUTE, KEY)).thenReturn("https://s3/" + KEY);

        service.upload(USER_ID, DocumentType.PASSPORT, Fixtures.image("front", "front.png"), null);

        verify(pdfMerger).wrapToPdf(any());
    }

    @Test
    void aDoubleSidedDocumentWithoutTheBackIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                USER_ID, DocumentType.ID, Fixtures.image("front", "front.png"), null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_DOCUMENT);
    }

    @Test
    void aDocumentWithAnUnsupportedTypeIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                USER_ID,
                DocumentType.PASSPORT,
                Fixtures.file("front", "notes.txt", "text/plain"),
                null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_DOCUMENT_TYPE);
    }

    @Test
    void aSecondDocumentIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user)));

        assertThatThrownBy(() -> service.upload(
                USER_ID,
                DocumentType.ID,
                Fixtures.image("front", "front.png"),
                Fixtures.image("back", "back.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.DOCUMENT_EXISTS);

        verifyNoInteractions(storage);
    }

    @Test
    void anUnknownAccountCannotUpload() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.upload(
                USER_ID,
                DocumentType.PASSPORT,
                Fixtures.image("front", "front.png"),
                null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    // -------------------------------------------------------------- uploadSingle

    @Test
    void storesAPreScannedPdf() throws Exception {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());
        when(storage.upload(any(byte[].class), eq("id.pdf"), eq("application/pdf"), eq(ROUTE)))
                .thenReturn(UPLOADED);
        when(documentFileRepository.save(any(DocumentFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignedUrl(ROUTE, KEY)).thenReturn("https://s3/" + KEY);

        StoredFileResponse response = service.uploadSingle(
                USER_ID,
                DocumentType.ID,
                Fixtures.file("file", "scanned.pdf", "application/pdf"));

        assertThat(response.downloadUrl()).isEqualTo("https://s3/" + KEY);
    }

    @Test
    void aSingleUploadMustBeAPdf() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadSingle(
                USER_ID,
                DocumentType.ID,
                Fixtures.image("file", "photo.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_DOCUMENT_TYPE);
    }

    @Test
    void aFailedSaveRemovesTheObjectThatWasJustUploaded() throws Exception {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());
        when(storage.upload(any(byte[].class), eq("id.pdf"), eq("application/pdf"), eq(ROUTE)))
                .thenReturn(UPLOADED);
        when(documentFileRepository.save(any(DocumentFile.class)))
                .thenThrow(new IllegalStateException("database is down"));

        MockMultipartFile file = Fixtures.file("file", "scanned.pdf", "application/pdf");
        assertThatThrownBy(() -> service.uploadSingle(USER_ID, DocumentType.ID, file))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete(ROUTE, KEY);
    }

    // ----------------------------------------------------------------------- get

    @Test
    void findsTheDocumentOfAnAccount() throws FatumUserException {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user, KEY)));
        when(storage.presignedUrl(ROUTE, KEY)).thenReturn("https://s3/" + KEY);

        assertThat(service.get(USER_ID).downloadUrl()).isEqualTo("https://s3/" + KEY);
    }

    @Test
    void anAccountWithoutDocumentIsReported() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FILE_NOT_FOUND);
    }

    @Test
    void aBlankAccountIdIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.get("   "))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }
}