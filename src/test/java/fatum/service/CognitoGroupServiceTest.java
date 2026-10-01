package fatum.service;

import fatum.configuration.CognitoProperties;
import fatum.exception.FatumUserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoGroupServiceTest {

    private final CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

    private CognitoProperties properties;
    private CognitoGroupService service;

    @BeforeEach
    void setUp() {
        properties = new CognitoProperties();
        properties.setUserPoolId("us-east-1_pool");
        service = new CognitoGroupService(cognitoClient, properties);
    }

    @Test
    void addsTheUserToTheVerifiedGroup() throws Exception {
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());

        service.grantVerified("aws-1");

        ArgumentCaptor<AdminAddUserToGroupRequest> request = ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(cognitoClient).adminAddUserToGroup(request.capture());
        assertThat(request.getValue().userPoolId()).isEqualTo("us-east-1_pool");
        assertThat(request.getValue().username()).isEqualTo("aws-1");
        assertThat(request.getValue().groupName()).isEqualTo("VERIFIED");
    }

    @Test
    void addsTheUserToTheProfessionalGroup() throws Exception {
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());

        service.grantProfessional("aws-1");

        ArgumentCaptor<AdminAddUserToGroupRequest> request = ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(cognitoClient).adminAddUserToGroup(request.capture());
        assertThat(request.getValue().groupName()).isEqualTo("PROFESSIONAL");
    }

    @Test
    void theGroupNamesAreConfiguration() throws Exception {
        properties.setVerifiedGroup("IDENTITY_VERIFIED");
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());

        service.grantVerified("aws-1");

        ArgumentCaptor<AdminAddUserToGroupRequest> request = ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(cognitoClient).adminAddUserToGroup(request.capture());
        assertThat(request.getValue().groupName()).isEqualTo("IDENTITY_VERIFIED");
    }

    @Test
    void nothingIsSentWhenTheSynchronizationIsDisabled() throws Exception {
        properties.setEnabled(false);

        service.grantVerified("aws-1");

        verify(cognitoClient, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
    }

    @Test
    void nothingIsSentWithoutAUserPool() throws Exception {
        properties.setUserPoolId("");

        service.grantVerified("aws-1");

        verify(cognitoClient, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
    }

    @Test
    void aCognitoFailureDoesNotBreakTheOperationByDefault() throws Exception {
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("cognito down").build());

        service.grantVerified("aws-1");
    }

    @Test
    void aCognitoFailureIsReportedWhenTheServiceIsConfiguredAsStrict() {
        properties.setStrict(true);
        when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("cognito down").build());

        assertThatThrownBy(() -> service.grantVerified("aws-1"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.COGNITO_GROUP_FAILURE);
    }
}
