package fatum.verification;

import fatum.storage.FileStorageClient;
import fatum.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;

/**
 * Downloads the object through the pre-signed URL the storage service hands out.
 *
 * <p>Nothing is cached: evidence is deleted as soon as a decision is taken, so holding the bytes in
 * memory longer than the verification itself would defeat the retention policy.</p>
 */
@Component
public class HttpFileContentFetcher implements FileContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFileContentFetcher.class);

    private final FileStorageClient fileStorageClient;
    private final RestClient restClient;

    public HttpFileContentFetcher(FileStorageClient fileStorageClient, RestClient fileStorageRestClient) {
        this.fileStorageClient = fileStorageClient;
        this.restClient = fileStorageRestClient;
    }

    @Override
    public byte[] fetch(String route, String key) {
        if (!StringUtils.hasText(key)) {
            throw StorageException.rejected("An object key is required to read a stored file");
        }
        String url = fileStorageClient.presignedUrl(route, key);
        if (!StringUtils.hasText(url)) {
            throw StorageException.unavailable("The storage service did not return a download URL", null);
        }
        try {
            byte[] content = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(byte[].class);
            if (content == null || content.length == 0) {
                throw StorageException.unavailable("The stored file is empty or unreadable", null);
            }
            return content;
        } catch (RestClientResponseException exception) {
            log.error("The stored object {} could not be downloaded: {}", key, exception.getStatusCode());
            throw StorageException.unavailable("The stored file could not be downloaded", exception);
        } catch (RestClientException exception) {
            throw StorageException.unavailable("The stored file could not be downloaded", exception);
        }
    }
}
