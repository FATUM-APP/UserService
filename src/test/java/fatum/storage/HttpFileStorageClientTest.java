package fatum.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpFileStorageClientTest {

    private MockRestServiceServer server;
    private HttpFileStorageClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://storage.test:8081");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpFileStorageClient(builder.build(), new ObjectMapper());
    }

    @Test
    void uploadsTheFileNamingTheRoute() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:document"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("""
                        {"id":"obj-1","key":"documents/2026/10/01/front.png","bucket":"fatum-documents",
                         "originalFilename":"front.png","contentType":"image/png","size":4}
                        """, MediaType.APPLICATION_JSON));

        StoredFile stored = client.upload(Fixtures.image("file", "front.png"), "user-service:document");

        assertThat(stored.key()).isEqualTo("documents/2026/10/01/front.png");
        assertThat(stored.bucket()).isEqualTo("fatum-documents");
        assertThat(stored.originalFilename()).isEqualTo("front.png");
        assertThat(stored.size()).isEqualTo(4L);
        server.verify();
    }

    @Test
    void theMultipartBodyCarriesTheFilename() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:liveness"))
                .andRespond(request -> {
                    MockClientHttpRequest httpRequest = (MockClientHttpRequest) request;
                    String body = httpRequest.getBodyAsString();
                    assertThat(body).contains("filename=\"frame.png\"");
                    return withSuccess("""
                            {"id":"obj-2","key":"liveness/frame.png","bucket":"b","originalFilename":"frame.png",
                             "contentType":"image/png","size":4}
                            """, MediaType.APPLICATION_JSON).createResponse(request);
                });

        client.upload(Fixtures.image("file", "frame.png"), "user-service:liveness");

        server.verify();
    }

    @Test
    void aClientErrorKeepsTheMessageOfTheStorageService() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:document"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Unknown route\"}"));

        assertThatThrownBy(() -> client.upload(Fixtures.image("file", "front.png"), "user-service:document"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Unknown route")
                .satisfies(exception -> assertThat(((StorageException) exception).isClientError()).isTrue());
    }

    @Test
    void aServerErrorIsReportedAsUnavailable() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:document"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"bucket exploded\"}"));

        assertThatThrownBy(() -> client.upload(Fixtures.image("file", "front.png"), "user-service:document"))
                .isInstanceOf(StorageException.class)
                .satisfies(exception -> assertThat(((StorageException) exception).isClientError()).isFalse());
    }

    @Test
    void anErrorBodyThatIsNotJsonFallsBackToTheStatus() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:document"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("<html>bad</html>"));

        assertThatThrownBy(() -> client.upload(Fixtures.image("file", "front.png"), "user-service:document"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("400");
    }

    @Test
    void anUnreachableServiceIsReportedAsUnavailable() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:document"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.upload(Fixtures.image("file", "front.png"), "user-service:document"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("not reachable");
    }

    @Test
    void aMissingRouteIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> client.upload(Fixtures.image("file", "front.png"), "  "))
                .isInstanceOf(StorageException.class)
                .satisfies(exception -> assertThat(((StorageException) exception).isClientError()).isTrue());
        server.verify();
    }

    @Test
    void deletesAnObject() {
        server.expect(requestTo("http://storage.test:8081/files?route=user-service:document&key=documents/front.png"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.delete("user-service:document", "documents/front.png");

        server.verify();
    }

    @Test
    void aBlankKeyIsNotDeleted() {
        client.delete("user-service:document", "  ");

        server.verify();
    }

    @Test
    void readsThePreSignedUrl() {
        server.expect(requestTo("http://storage.test:8081/files/url?route=user-service:document&key=documents/front.png"))
                .andRespond(withSuccess("{\"url\":\"https://s3/front.png\"}", MediaType.APPLICATION_JSON));

        assertThat(client.presignedUrl("user-service:document", "documents/front.png"))
                .isEqualTo("https://s3/front.png");
    }

    @Test
    void aBlankKeyHasNoPreSignedUrl() {
        assertThat(client.presignedUrl("user-service:document", null)).isNull();
        server.verify();
    }

    @Test
    void aFailingPreSignedUrlCallIsReported() {
        server.expect(requestTo("http://storage.test:8081/files/url?route=user-service:document&key=documents/front.png"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Object not found\"}"));

        assertThatThrownBy(() -> client.presignedUrl("user-service:document", "documents/front.png"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Object not found");
    }
}
