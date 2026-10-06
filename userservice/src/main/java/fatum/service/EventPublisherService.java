package fatum.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.util.Map;

/**
 * Announces what changed in this service so the rest of the platform can react.
 *
 * <p>The user pool is not written from here any more. This service keeps the source of truth, which is
 * the database, and publishes the facts: an account was verified, someone became a professional, a
 * professional went back to client, the access of an account was taken away or given back, a
 * professional moved which address represents it. The consumer that owns the user pool listens and
 * does the pool side, so a pool outage no longer sits in the middle of a request.</p>
 *
 * <p>Each fact travels under its own {@code detail-type}, because that is what a rule filters on. Two
 * facts sharing a type cannot be told apart by the consumer without reading the payload and guessing,
 * and a rule that wants only one of them would receive both.</p>
 */
@Service
public class EventPublisherService {

    private static final Logger log = LoggerFactory.getLogger(EventPublisherService.class);

    /** Who publishes. Rules can filter on it as well. */
    private static final String SOURCE = "fatum.userservice";

    /** The verification state of an account changed. */
    public static final String VERIFICATION_CHANGED = "USER_VERIFICATION_CHANGED";
    /** An account became a professional. */
    public static final String BECAME_PROFESSIONAL = "USER_BECAME_PROFESSIONAL";
    /** A professional went back to client. */
    public static final String BECAME_CLIENT = "PROFESSIONAL_BECAME_CLIENT";
    /** The access of an account was taken away or given back. */
    public static final String ACTIVE_STATUS_CHANGED = "USER_ACTIVE_STATUS_CHANGED";
    /** A professional moved which address represents it. */
    public static final String PRINCIPAL_ADDRESS_CHANGED = "PROFESSIONAL_PRINCIPAL_ADDRESS_CHANGED";

    private final EventBridgeClient eventBridgeClient;
    private final String eventBusName;
    private final ObjectMapper objectMapper;

    public EventPublisherService(
            EventBridgeClient eventBridgeClient,
            @Value("${aws.eventbridge.bus-name}") String eventBusName,
            ObjectMapper objectMapper) {
        this.eventBridgeClient = eventBridgeClient;
        this.eventBusName = eventBusName;
        this.objectMapper = objectMapper;
    }

    /**
     * The verification state of the account changed.
     *
     * <p>The status travels under its own key: with a key named after the role, a consumer reading it
     * would get {@code VERIFIED} where it expected {@code PROFESSIONAL}.</p>
     */
    public void verificationStatusChanged(String awsId, String userEmail, VerificationStatus status) {
        publish(VERIFICATION_CHANGED, Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "verificationStatus", status.name()
        ));
    }

    public void userBecameProfessional(String awsId, String userEmail) {
        publish(BECAME_PROFESSIONAL, Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "userRole", UserRole.PROFESSIONAL.name()
        ));
    }

    public void professionalBecameClient(String awsId, String userEmail) {
        publish(BECAME_CLIENT, Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "userRole", UserRole.CLIENT.name()
        ));
    }

    /**
     * The access of the account changed.
     *
     * <p>The role travels with it because giving the access back means restoring the groups, and the
     * consumer has no way to read the role from anywhere else: it only has the event.</p>
     */
    public void activeStatusChanged(String awsId, String userEmail, boolean isActive, UserRole userRole) {
        publish(ACTIVE_STATUS_CHANGED, Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "userRole", userRole.name(),
                "isActive", isActive
        ));
    }

    /** The professional changed which of its addresses is the one that represents it. */
    public void professionalPrincipalAddressChanged(String awsId, String userEmail, String alias) {
        publish(PRINCIPAL_ADDRESS_CHANGED, Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "addressAlias", alias
        ));
    }

    private void publish(String detailType, Map<String, Object> payload) {
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source(SOURCE)
                .detailType(detailType)
                .detail(serialize(payload))
                .build();

        PutEventsResponse response = eventBridgeClient.putEvents(
                PutEventsRequest.builder().entries(entry).build());
        reportFailures(detailType, response);
    }

    /**
     * Reads the failures of the call.
     *
     * <p>EventBridge answers with a 200 even when an entry was rejected: the reasons travel inside the
     * response, one per entry. Not reading them is how an event disappears without a trace.</p>
     */
    private void reportFailures(String detailType, PutEventsResponse response) {
        if (response.failedEntryCount() == null || response.failedEntryCount() == 0) {
            return;
        }
        for (PutEventsResultEntry entry : response.entries()) {
            if (entry.errorCode() != null) {
                log.error("The {} event was rejected: {} {}",
                        detailType, entry.errorCode(), entry.errorMessage());
            }
        }
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "The event could not be serialized: " + payload.keySet(), e);
        }
    }
}