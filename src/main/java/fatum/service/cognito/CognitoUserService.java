package fatum.service.cognito;

import fatum.configuration.CognitoProperties;
import fatum.exception.FatumUserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminEnableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUserGlobalSignOutRequest;

@Service
public class CognitoUserService {

    private static final Logger log = LoggerFactory.getLogger(CognitoUserService.class);

    private final CognitoIdentityProviderClient cognitoClient;
    private final CognitoProperties properties;

    public CognitoUserService(CognitoIdentityProviderClient cognitoClient, CognitoProperties properties) {
        this.cognitoClient = cognitoClient;
        this.properties = properties;
    }

    /** Disables the account and kills all active sessions/refresh tokens. */
    public void disableUser(String userAwsId) throws FatumUserException {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getUserPoolId())) {
            log.debug("Cognito sync is disabled; skipping disable for {}", userAwsId);
            return;
        }
        try {
            cognitoClient.adminDisableUser(AdminDisableUserRequest.builder()
                    .userPoolId(properties.getUserPoolId())
                    .username(userAwsId)
                    .build());
            cognitoClient.adminUserGlobalSignOut(AdminUserGlobalSignOutRequest.builder()
                    .userPoolId(properties.getUserPoolId())
                    .username(userAwsId)
                    .build());
            log.info("User {} disabled and signed out of Cognito", userAwsId);
        } catch (SdkException exception) {
            log.error("The user {} could not be disabled in Cognito", userAwsId, exception);
            if (properties.isStrict()) {
                throw new FatumUserException(FatumUserException.COGNITO_GROUP_FAILURE);
            }
        }
    }

    public void enableUser(String userAwsId) throws FatumUserException {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getUserPoolId())) {
            log.debug("Cognito sync is disabled; skipping enable for {}", userAwsId);
            return;
        }
        try {
            cognitoClient.adminEnableUser(AdminEnableUserRequest.builder()
                    .userPoolId(properties.getUserPoolId())
                    .username(userAwsId)
                    .build());
            log.info("User {} enabled in Cognito", userAwsId);
        } catch (SdkException exception) {
            log.error("The user {} could not be enabled in Cognito", userAwsId, exception);
            if (properties.isStrict()) {
                throw new FatumUserException(FatumUserException.COGNITO_GROUP_FAILURE);
            }
        }
    }
}