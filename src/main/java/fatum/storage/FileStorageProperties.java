package fatum.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings and route names of the shared storage service.
 *
 * <p>The routes are configuration, not constants: if a purpose has to move to another bucket, only
 * the storage service configuration changes; if a purpose has to move to another <em>route name</em>,
 * only this file does.</p>
 */
@ConfigurationProperties(prefix = "fatum.file-service")
public class FileStorageProperties {

    /** Base URL of the shared storage service. */
    private String baseUrl = "http://localhost:8081";

    /** Shared secret sent in the {@code X-Storage-Key} header. Blank disables the header. */
    private String internalSecret = "";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(20);

    private String profileImageRoute = "user-service:profile-image";

    private String livenessRoute = "user-service:liveness";

    private String documentRoute = "user-service:document";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalSecret() {
        return internalSecret;
    }

    public void setInternalSecret(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getProfileImageRoute() {
        return profileImageRoute;
    }

    public void setProfileImageRoute(String profileImageRoute) {
        this.profileImageRoute = profileImageRoute;
    }

    public String getLivenessRoute() {
        return livenessRoute;
    }

    public void setLivenessRoute(String livenessRoute) {
        this.livenessRoute = livenessRoute;
    }

    public String getDocumentRoute() {
        return documentRoute;
    }

    public void setDocumentRoute(String documentRoute) {
        this.documentRoute = documentRoute;
    }
}
