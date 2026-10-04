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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminEnableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUserGlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;

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

    /**
     * Takes away every access of the account: it is disabled, its open sessions are killed and it is
     * removed from every group of the user pool.
     *
     * <p>Emptying the groups is what makes the revocation complete. Any permission the platform
     * grants by reading a group disappears with the membership, and the groups are listed instead of
     * assumed, so a membership created outside this service is removed as well. This is the call the
     * user service makes when an account is deactivated.</p>
     */
    public void revokeAccess(String userAwsId) throws FatumUserException {
        disableUser(userAwsId);
        removeFromAllGroups(userAwsId);
    }

    /**
     * Empties the groups of the account.
     *
     * <p>Cognito paginates the listing, so the loop follows the token until the last page; stopping
     * at the first one would leave access behind for a user with many memberships.</p>
     */
    private void removeFromAllGroups(String userAwsId) throws FatumUserException {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getUserPoolId())) {
            log.debug("Cognito sync is disabled; skipping the group cleanup for {}", userAwsId);
            return;
        }
        try {
            String nextToken = null;
            do {
                AdminListGroupsForUserResponse page = cognitoClient.adminListGroupsForUser(
                        AdminListGroupsForUserRequest.builder()
                                .userPoolId(properties.getUserPoolId())
                                .username(userAwsId)
                                .nextToken(nextToken)
                                .build());
                for (GroupType group : page.groups()) {
                    removeFromGroup(userAwsId, group.groupName());
                }
                nextToken = page.nextToken();
            } while (StringUtils.hasText(nextToken));
        } catch (SdkException exception) {
            log.error("The groups of the user {} could not be cleared in Cognito", userAwsId, exception);
            if (properties.isStrict()) {
                throw new FatumUserException(FatumUserException.COGNITO_GROUP_FAILURE);
            }
        }
    }

    private void removeFromGroup(String userAwsId, String groupName) {
        if (!StringUtils.hasText(groupName)) {
            return;
        }
        cognitoClient.adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                .userPoolId(properties.getUserPoolId())
                .username(userAwsId)
                .groupName(groupName)
                .build());
        log.info("User {} removed from the Cognito group {}", userAwsId, groupName);
    }
}
