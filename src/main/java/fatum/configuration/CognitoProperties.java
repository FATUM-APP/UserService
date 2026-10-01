package fatum.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cognito settings used to keep the groups of the user pool in sync with the service.
 *
 * <p>Groups are the contract the rest of the platform reads (a professional account, a verified
 * account), so they are configuration instead of hard-coded names.</p>
 */
@ConfigurationProperties(prefix = "fatum.cognito")
public class CognitoProperties {

    private boolean enabled = true;

    private String userPoolId = "";

    /** Group added when the identity of the user is verified. */
    private String verifiedGroup = "VERIFIED";

    /** Group added when the user becomes a professional. */
    private String professionalGroup = "PROFESSIONAL";

    /**
     * Whether the user name in the pool matches the {@code username} attribute (false) or the
     * {@code sub} (true). Cognito accepts both; the pool configuration decides.
     */
    private boolean useSubjectAsUsername = false;

    /**
     * When true a failure to update the group aborts the operation. It stays false by default so a
     * Cognito outage never blocks a verification that the database already recorded.
     */
    private boolean strict = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserPoolId() {
        return userPoolId;
    }

    public void setUserPoolId(String userPoolId) {
        this.userPoolId = userPoolId;
    }

    public String getVerifiedGroup() {
        return verifiedGroup;
    }

    public void setVerifiedGroup(String verifiedGroup) {
        this.verifiedGroup = verifiedGroup;
    }

    public String getProfessionalGroup() {
        return professionalGroup;
    }

    public void setProfessionalGroup(String professionalGroup) {
        this.professionalGroup = professionalGroup;
    }

    public boolean isUseSubjectAsUsername() {
        return useSubjectAsUsername;
    }

    public void setUseSubjectAsUsername(boolean useSubjectAsUsername) {
        this.useSubjectAsUsername = useSubjectAsUsername;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }
}
