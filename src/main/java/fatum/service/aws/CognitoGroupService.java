package fatum.service.aws;

import fatum.configuration.CognitoProperties;
import fatum.exception.FatumUserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;

/**
 * Keeps the Cognito groups in sync with what the service has decided.
 *
 * <p>Two groups matter today: the verified account and the professional account. The calls are
 * idempotent in Cognito (adding a user that already belongs to a group succeeds), so they can be
 * repeated safely.</p>
 */
@Service
public class CognitoGroupService {

    private static final Logger log = LoggerFactory.getLogger(CognitoGroupService.class);

    private final CognitoIdentityProviderClient cognitoClient;
    private final CognitoProperties properties;

    public CognitoGroupService(CognitoIdentityProviderClient cognitoClient, CognitoProperties properties) {
        this.cognitoClient = cognitoClient;
        this.properties = properties;
    }

    /** Adds the user to the VERIFIED group, used as soon as the identity is confirmed. */
    public void grantVerified(String userAwsId) throws FatumUserException {
        addToGroup(userAwsId, properties.getVerifiedGroup());
    }

    /** Adds the user to the PROFESSIONAL group when the role changes. */
    public void grantProfessional(String userAwsId) throws FatumUserException {
        addToGroup(userAwsId, properties.getProfessionalGroup());
    }

    public void addToGroup(String userAwsId, String groupName) throws FatumUserException {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getUserPoolId())) {
            log.debug("Cognito group sync is disabled; skipping group {} for {}", groupName, userAwsId);
            return;
        }
        if (!StringUtils.hasText(groupName)) {
            return;
        }
        try {
            cognitoClient.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(properties.getUserPoolId())
                    .username(userAwsId)
                    .groupName(groupName)
                    .build());
            log.info("User {} added to the Cognito group {}", userAwsId, groupName);
        } catch (SdkException exception) {
            log.error("The user {} could not be added to the Cognito group {}", userAwsId, groupName, exception);
            if (properties.isStrict()) {
                throw new FatumUserException(FatumUserException.COGNITO_GROUP_FAILURE);
            }
        }
    }
}
