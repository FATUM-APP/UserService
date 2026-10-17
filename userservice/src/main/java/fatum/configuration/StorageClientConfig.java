package fatum.configuration;

import fatum.storage.FileStorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * HTTP client used to reach the shared storage service.
 *
 * <p>Timeouts are explicit on purpose: an upload that hangs must not hold a database transaction
 * forever, and the caller has to receive a clear 502 instead of a stalled request.</p>
 */
@Configuration
public class StorageClientConfig {

    @Bean
    public RestClient fileStorageRestClient(FileStorageProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory);
        if (StringUtils.hasText(properties.getInternalSecret())) {
            builder.defaultHeader("X-Storage-Key", properties.getInternalSecret());
        }
        return builder.build();
    }
}
