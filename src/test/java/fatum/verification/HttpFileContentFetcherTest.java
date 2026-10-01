package fatum.verification;

import fatum.storage.FileStorageClient;
import fatum.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpFileContentFetcherTest {

    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);

    private MockRestServiceServer server;
    private HttpFileContentFetcher fetcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        fetcher = new HttpFileContentFetcher(fileStorageClient, builder.build());
    }

    @Test
    void downloadsTheObjectThroughThePreSignedUrl() {
        when(fileStorageClient.presignedUrl("user-service:document", "documents/front.png"))
                .thenReturn("https://s3.test/documents/front.png?signature=abc");
        server.expect(requestTo("https://s3.test/documents/front.png?signature=abc"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, org.springframework.http.MediaType.IMAGE_PNG));

        assertThat(fetcher.fetch("user-service:document", "documents/front.png")).containsExactly(1, 2, 3);
    }

    @Test
    void aKeyIsRequired() {
        assertThatThrownBy(() -> fetcher.fetch("user-service:document", "  "))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("key is required");
        verifyNoInteractions(fileStorageClient);
    }

    @Test
    void aMissingUrlIsReportedAsUnavailable() {
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> fetcher.fetch("user-service:document", "documents/front.png"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("download URL")
                .satisfies(exception -> assertThat(((StorageException) exception).isClientError()).isFalse());
    }

    @Test
    void anEmptyObjectIsReportedAsUnavailable() {
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3.test/empty.png");
        server.expect(requestTo("https://s3.test/empty.png"))
                .andRespond(withSuccess(new byte[0], org.springframework.http.MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> fetcher.fetch("user-service:document", "documents/empty.png"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aDownloadFailureIsReportedAsUnavailable() {
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3.test/gone.png");
        server.expect(requestTo("https://s3.test/gone.png")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> fetcher.fetch("user-service:document", "documents/gone.png"))
                .isInstanceOf(StorageException.class)
                .satisfies(exception -> assertThat(((StorageException) exception).isClientError()).isFalse());
    }

    @Test
    void anUnreachableBucketIsReportedAsUnavailable() {
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3.test/offline.png");
        server.expect(requestTo("https://s3.test/offline.png"))
                .andRespond(withException(new IOException("host unreachable")));

        assertThatThrownBy(() -> fetcher.fetch("user-service:document", "documents/offline.png"))
                .isInstanceOf(StorageException.class);
    }
}
