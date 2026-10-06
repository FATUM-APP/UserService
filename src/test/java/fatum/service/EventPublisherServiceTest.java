package fatum.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What this service tells the rest of the platform.
 *
 * <p>Each event is checked twice: the {@code detail-type} it travels under, because that is what a
 * rule filters on, and the payload, because that is all the consumer has to work with.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class EventPublisherServiceTest {

    private static final String BUS = "fatum-bus";
    private static final String SOURCE = "fatum.userservice";
    private static final String USER_ID = "aws-user-1";
    private static final String EMAIL = "ana@fatum.co";

    private final EventBridgeClient client = Mockito.mock(EventBridgeClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventPublisherService publisher = new EventPublisherService(client, BUS, mapper);

    private void theBusAccepts() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder().failedEntryCount(0).entries(List.of()).build());
    }

    private Map<String, Object> theEventPublishedAs(String detailType) throws Exception {
        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client).putEvents(captor.capture());
        PutEventsRequestEntry entry = captor.getValue().entries().getFirst();

        assertThat(entry.eventBusName()).isEqualTo(BUS);
        assertThat(entry.source()).isEqualTo(SOURCE);
        assertThat(entry.detailType()).isEqualTo(detailType);
        return mapper.readValue(entry.detail(), new TypeReference<>() {
        });
    }

    @Test
    void theVerificationStatusTravelsUnderItsOwnTypeAndKey() throws Exception {
        theBusAccepts();

        publisher.verificationStatusChanged(USER_ID, EMAIL, VerificationStatus.VERIFIED);

        Map<String, Object> detail = theEventPublishedAs(EventPublisherService.VERIFICATION_CHANGED);
        assertThat(detail).containsEntry("userEmail", EMAIL);
        assertThat(detail).containsEntry("verificationStatus", "VERIFIED");
        assertThat(detail).doesNotContainKey("userRole");
    }

    @Test
    void becomingAProfessionalCarriesTheNewRole() throws Exception {
        theBusAccepts();

        publisher.userBecameProfessional(USER_ID, EMAIL);

        Map<String, Object> detail = theEventPublishedAs(EventPublisherService.BECAME_PROFESSIONAL);
        assertThat(detail).containsEntry("userRole", "PROFESSIONAL");
    }

    @Test
    void goingBackToClientCarriesItsOwnType() throws Exception {
        theBusAccepts();

        publisher.professionalBecameClient(USER_ID, EMAIL);

        Map<String, Object> detail = theEventPublishedAs(EventPublisherService.BECAME_CLIENT);
        assertThat(detail).containsEntry("userRole", "CLIENT");
    }

    @Test
    void theActiveStatusCarriesTheRoleSoTheGroupsCanBeRestored() throws Exception {
        theBusAccepts();

        publisher.activeStatusChanged(USER_ID, EMAIL, true, UserRole.PROFESSIONAL);

        Map<String, Object> detail = theEventPublishedAs(EventPublisherService.ACTIVE_STATUS_CHANGED);
        assertThat(detail).containsEntry("isActive", true);
        assertThat(detail).containsEntry("userRole", "PROFESSIONAL");
    }

    @Test
    void thePrincipalAddressEventCarriesItsAlias() throws Exception {
        theBusAccepts();

        publisher.professionalPrincipalAddressChanged(USER_ID, EMAIL, "casa");

        Map<String, Object> detail = theEventPublishedAs(EventPublisherService.PRINCIPAL_ADDRESS_CHANGED);
        assertThat(detail).containsEntry("addressAlias", "casa");
    }

    /**
     * EventBridge answers 200 even when it rejected an entry, so the failure has to be read from the
     * response and reported. Throwing is not an option: the transaction already wrote the state, and
     * the announcement cannot undo it.
     */
    @Test
    void aRejectedEventIsReportedWithoutFailingTheCall(CapturedOutput output) {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(PutEventsResponse.builder()
                .failedEntryCount(1)
                .entries(PutEventsResultEntry.builder()
                        .errorCode("AccessDeniedException")
                        .errorMessage("Not authorized")
                        .build())
                .build());

        assertThatCode(() -> publisher.verificationStatusChanged(USER_ID, EMAIL, VerificationStatus.VERIFIED))
                .doesNotThrowAnyException();

        assertThat(output)
                .contains(EventPublisherService.VERIFICATION_CHANGED)
                .contains("AccessDeniedException");
    }
}