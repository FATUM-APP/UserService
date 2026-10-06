package fatum.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import java.util.Map;

@Service
public class EventPublisherService {

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

    public void verificationStatusChanged(String awsId,String userEmail, VerificationStatus status) {
        Map<String, Object> payloadMap = Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "userRole", status.name()
        );
        String detailJson = serialize(payloadMap);
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("fatum.userservice")
                .detailType("USER_STATUS_CHANGED")
                .detail(detailJson)
                .build();

        eventBridgeClient.putEvents(PutEventsRequest.builder().entries(entry).build());
    }

    public void userBecameProfessional(String awsId,String userEmail) {
        Map<String, Object> payloadMap = Map.of(
                "awsId", awsId,
                "userEmail", userEmail
        );
        String detailJson = serialize(payloadMap);
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("fatum.userservice")
                .detailType("USER_BECAME_PROFESSIONAL")
                .detail(detailJson)
                .build();

        eventBridgeClient.putEvents(PutEventsRequest.builder().entries(entry).build());
    }

    public void professionalBecameClient(String awsId,String userEmail) {
        Map<String, Object> payloadMap = Map.of(
                "awsId", awsId,
                "userEmail", userEmail
        );
        String detailJson = serialize(payloadMap);
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("fatum.userservice")
                .detailType("PROFESSIONAL_BECAME_CLIENT")
                .detail(detailJson)
                .build();
        eventBridgeClient.putEvents(PutEventsRequest.builder().entries(entry).build());
    }

    public void activeStatusChanged(String awsId,String userEmail, boolean isActive) {
        Map<String, Object> payloadMap = Map.of(
                "awsId", awsId,
                "userEmail", userEmail,
                "isActive", isActive
        );
        String detailJson = serialize(payloadMap);
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("fatum.userservice")
                .detailType("USER_STATUS_CHANGED")
                .detail(detailJson)
                .build();
        eventBridgeClient.putEvents(PutEventsRequest.builder().entries(entry).build());
    }


    private String serialize(Map<String, Object> payload) {
        try{
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error al serializar el evento de desactivación de usuario", e);
        }
    }
}