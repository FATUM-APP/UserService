package fatum.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP implementation of {@link FileStorageClient}.
 *
 * <p>Every call is synchronous: the user service needs the identifier of the stored object before it
 * can commit its own row, so an asynchronous contract would only add reconciliation work.</p>
 */
@Component
public class HttpFileStorageClient implements FileStorageClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFileStorageClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpFileStorageClient(RestClient fileStorageRestClient, ObjectMapper objectMapper) {
        this.restClient = fileStorageRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public StoredFile upload(MultipartFile file, String route) {
        requireRoute(route);
        return upload(readBytes(file), resolveFilename(file), file.getContentType(), route);
    }

    @Override
    public StoredFile upload(byte[] content, String filename, String contentType, String route) {
        requireRoute(route);
        if (content == null || content.length == 0) {
            throw StorageException.rejected("The content to store is empty");
        }

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        MultipartBodyBuilder.PartBuilder part = body
                .part("file", new ByteArrayResource(content))
                .filename(StringUtils.hasText(filename) ? filename : "file");
        MediaType mediaType = parseContentType(contentType);
        if (mediaType != null) {
            part.contentType(mediaType);
        }

        try {
            return restClient.post()
                    .uri(builder -> builder.path("/files").queryParam("route", route).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(StoredFile.class);
        } catch (RestClientResponseException exception) {
            throw translate(exception, "upload to " + route);
        } catch (RestClientException exception) {
            throw StorageException.unavailable("The storage service is not reachable", exception);
        }
    }

    @Override
    public void delete(String route, String objectKey) {
        requireRoute(route);
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            restClient.delete()
                    .uri(builder -> builder.path("/files")
                            .queryParam("route", route)
                            .queryParam("key", objectKey)
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw translate(exception, "delete from " + route);
        } catch (RestClientException exception) {
            throw StorageException.unavailable("The storage service is not reachable", exception);
        }
    }

    @Override
    public String presignedUrl(String route, String objectKey) {
        requireRoute(route);
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            Map<?, ?> response = restClient.get()
                    .uri(builder -> builder.path("/files/url")
                            .queryParam("route", route)
                            .queryParam("key", objectKey)
                            .build())
                    .retrieve()
                    .body(Map.class);
            return response == null ? null : (String) response.get("url");
        } catch (RestClientResponseException exception) {
            throw translate(exception, "presign from " + route);
        } catch (RestClientException exception) {
            throw StorageException.unavailable("The storage service is not reachable", exception);
        }
    }

    private void requireRoute(String route) {
        if (!StringUtils.hasText(route)) {
            throw StorageException.rejected("A storage route is required");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw StorageException.unavailable("The uploaded file could not be read", exception);
        }
    }

    private String resolveFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return StringUtils.hasText(filename) ? filename : "file";
    }

    private MediaType parseContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Keeps the client error semantics of the storage service instead of flattening everything to 500. */
    private StorageException translate(RestClientResponseException exception, String operation) {
        String message = extractMessage(exception);
        if (exception.getStatusCode().is4xxClientError()) {
            log.warn("Storage service rejected the {}: {}", operation, message);
            return StorageException.rejected(message);
        }
        log.error("Storage service failed the {}: {}", operation, message, exception);
        return StorageException.unavailable(message, exception);
    }

    private String extractMessage(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (StringUtils.hasText(body)) {
            try {
                JsonNode node = objectMapper.readTree(body);
                String message = node.path("message").asText(null);
                if (StringUtils.hasText(message)) {
                    return message;
                }
            } catch (IOException ignored) {
                log.debug("The storage error body is not JSON", ignored);
            }
        }
        return "The storage service returned " + exception.getStatusCode().value();
    }
}