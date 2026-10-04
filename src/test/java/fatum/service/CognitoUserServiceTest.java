package fatum.service;

import fatum.configuration.CognitoProperties;
import fatum.exception.FatumUserException;
import fatum.service.cognito.CognitoUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminEnableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUserGlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoUserServiceTest {

    private final CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

    private CognitoProperties properties;
    private CognitoUserService service;

    @BeforeEach
    void setUp() {
        properties = new CognitoProperties();
        properties.setUserPoolId("us-east-1_pool");
        service = new CognitoUserService(cognitoClient, properties);
    }

    /** Leaves the pool answering with exactly these groups for the user. */
    private void theUserBelongsTo(String... groupNames) {
        List<GroupType> groups = Arrays.stream(groupNames)
                .map(name -> GroupType.builder().groupName(name).build())
                .toList();
        when(cognitoClient.adminListGroupsForUser(any(AdminListGroupsForUserRequest.class)))
                .thenReturn(AdminListGroupsForUserResponse.builder().groups(groups).build());
    }

    @Test
    void disablingAnAccountAlsoKillsItsSessions() throws Exception {
        service.disableUser("aws-1");

        ArgumentCaptor<AdminDisableUserRequest> disable = ArgumentCaptor.forClass(AdminDisableUserRequest.class);
        verify(cognitoClient).adminDisableUser(disable.capture());
        assertThat(disable.getValue().userPoolId()).isEqualTo("us-east-1_pool");
        assertThat(disable.getValue().username()).isEqualTo("aws-1");

        ArgumentCaptor<AdminUserGlobalSignOutRequest> signOut =
                ArgumentCaptor.forClass(AdminUserGlobalSignOutRequest.class);
        verify(cognitoClient).adminUserGlobalSignOut(signOut.capture());
        assertThat(signOut.getValue().userPoolId()).isEqualTo("us-east-1_pool");
        assertThat(signOut.getValue().username()).isEqualTo("aws-1");
    }

    @Test
    void enablingAnAccountIsTheCounterpart() throws Exception {
        service.enableUser("aws-1");

        ArgumentCaptor<AdminEnableUserRequest> enable = ArgumentCaptor.forClass(AdminEnableUserRequest.class);
        verify(cognitoClient).adminEnableUser(enable.capture());
        assertThat(enable.getValue().username()).isEqualTo("aws-1");
    }

    @Test
    void revokingAccessDisablesTheAccountAndEmptiesItsGroups() throws Exception {
        theUserBelongsTo("VERIFIED", "PROFESSIONAL");

        service.revokeAccess("aws-1");

        verify(cognitoClient).adminDisableUser(any(AdminDisableUserRequest.class));
        verify(cognitoClient).adminUserGlobalSignOut(any(AdminUserGlobalSignOutRequest.class));

        ArgumentCaptor<AdminRemoveUserFromGroupRequest> removals =
                ArgumentCaptor.forClass(AdminRemoveUserFromGroupRequest.class);
        verify(cognitoClient, times(2)).adminRemoveUserFromGroup(removals.capture());
        assertThat(removals.getAllValues())
                .extracting(AdminRemoveUserFromGroupRequest::groupName)
                .containsExactlyInAnyOrder("VERIFIED", "PROFESSIONAL");
        assertThat(removals.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.userPoolId()).isEqualTo("us-east-1_pool");
                    assertThat(request.username()).isEqualTo("aws-1");
                });
    }

    @Test
    void aGroupCreatedOutsideThisServiceIsRemovedToo() throws Exception {
        theUserBelongsTo("SOME_OTHER_GROUP");

        service.revokeAccess("aws-1");

        ArgumentCaptor<AdminRemoveUserFromGroupRequest> removals =
                ArgumentCaptor.forClass(AdminRemoveUserFromGroupRequest.class);
        verify(cognitoClient).adminRemoveUserFromGroup(removals.capture());
        assertThat(removals.getValue().groupName()).isEqualTo("SOME_OTHER_GROUP");
    }

    @Test
    void everyPageOfGroupsIsWalked() throws Exception {
        when(cognitoClient.adminListGroupsForUser(any(AdminListGroupsForUserRequest.class)))
                .thenReturn(AdminListGroupsForUserResponse.builder()
                        .groups(List.of(GroupType.builder().groupName("VERIFIED").build()))
                        .nextToken("page-2")
                        .build())
                .thenReturn(AdminListGroupsForUserResponse.builder()
                        .groups(List.of(GroupType.builder().groupName("PROFESSIONAL").build()))
                        .build());

        service.revokeAccess("aws-1");

        ArgumentCaptor<AdminRemoveUserFromGroupRequest> removals =
                ArgumentCaptor.forClass(AdminRemoveUserFromGroupRequest.class);
        verify(cognitoClient, times(2)).adminRemoveUserFromGroup(removals.capture());
        assertThat(removals.getAllValues())
                .extracting(AdminRemoveUserFromGroupRequest::groupName)
                .containsExactly("VERIFIED", "PROFESSIONAL");
    }

    @Test
    void anAccountWithoutGroupsIsOnlyDisabled() throws Exception {
        theUserBelongsTo();

        service.revokeAccess("aws-1");

        verify(cognitoClient).adminDisableUser(any(AdminDisableUserRequest.class));
        verify(cognitoClient, never()).adminRemoveUserFromGroup(any(AdminRemoveUserFromGroupRequest.class));
    }

    @Test
    void nothingIsSentWhenTheSynchronizationIsDisabled() throws Exception {
        properties.setEnabled(false);

        service.revokeAccess("aws-1");

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void nothingIsSentWithoutAUserPool() throws Exception {
        properties.setUserPoolId("");

        service.revokeAccess("aws-1");

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void aCognitoFailureDoesNotBreakTheOperationByDefault() throws Exception {
        theUserBelongsTo("VERIFIED");
        when(cognitoClient.adminRemoveUserFromGroup(any(AdminRemoveUserFromGroupRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("cognito down").build());

        service.revokeAccess("aws-1");
    }

    @Test
    void aCognitoFailureIsReportedWhenTheServiceIsConfiguredAsStrict() {
        properties.setStrict(true);
        when(cognitoClient.adminDisableUser(any(AdminDisableUserRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("cognito down").build());

        assertThatThrownBy(() -> service.revokeAccess("aws-1"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.COGNITO_GROUP_FAILURE);
    }
}
