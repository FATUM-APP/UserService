package fatum.verification;

import fatum.dto.LivenessResultResponse;
import fatum.dto.LivenessSessionResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessCheck;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.LivenessCheckStatus;
import fatum.model.constant.VerificationOutcome;
import fatum.repository.LivenessCheckRepository;
import fatum.repository.UserRepository;
import fatum.service.LivenessService;
import fatum.verification.analyzer.FaceLivenessClient;
import fatum.verification.analyzer.LivenessResult;
import fatum.verification.analyzer.LivenessSession;
import fatum.verification.analyzer.LivenessStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * The proof of life, which is the second and last phase of a verification.
 *
 * <p>It is the only paid step of the pipeline, so it is guarded on both sides: a session can only be
 * opened while an attempt is waiting for one, which means the free phase already reached the verified
 * threshold, and an attempt can only spend a few sessions.</p>
 *
 * <p>The client never sends the video here. It streams it straight into Rekognition, and this service
 * only opens the session and asks for the verdict afterwards.</p>
 */
@Service
public class LivenessSessionService {

    private static final Logger log = LoggerFactory.getLogger(LivenessSessionService.class);

    private final UserRepository userRepository;
    private final LivenessCheckRepository livenessCheckRepository;
    private final FaceLivenessClient faceLivenessClient;
    private final VerificationService verificationService;
    private final LivenessService livenessService;
    private final VerificationProperties properties;

    public LivenessSessionService(
            UserRepository userRepository,
            LivenessCheckRepository livenessCheckRepository,
            FaceLivenessClient faceLivenessClient,
            VerificationService verificationService,
            LivenessService livenessService,
            VerificationProperties properties) {
        this.userRepository = userRepository;
        this.livenessCheckRepository = livenessCheckRepository;
        this.faceLivenessClient = faceLivenessClient;
        this.verificationService = verificationService;
        this.livenessService = livenessService;
        this.properties = properties;
    }

    /**
     * Opens a session for the user, or hands back one that is still alive.
     *
     * <p>Reusing the live session keeps a client that retried after a network error from spending a
     * second check.</p>
     */
    @Transactional
    public LivenessSessionResponse start(String awsId) throws FatumUserException {
        if (!properties.isEnabled()) {
            throw new FatumUserException(FatumUserException.VERIFICATION_DISABLED);
        }
        VerificationProperties.Liveness liveness = properties.getLiveness();
        if (!liveness.isEnabled()) {
            throw new FatumUserException(FatumUserException.LIVENESS_DISABLED);
        }
        User user = getActiveUser(awsId);
        VerificationAttempt attempt = verificationService.awaitingLiveness(user.getAwsId())
                .orElseThrow(() -> new FatumUserException(FatumUserException.LIVENESS_NOT_REQUIRED));

        LivenessCheck alive = aliveSession(user.getAwsId(), liveness);
        if (alive != null) {
            return new LivenessSessionResponse(
                    alive.getSessionId(),
                    alive.getCreatedAt().plus(liveness.getSessionTtl()),
                    true);
        }
        if (livenessCheckRepository.countByAttemptId(attempt.getId()) >= liveness.getMaxSessionsPerAttempt()) {
            throw new FatumUserException(FatumUserException.LIVENESS_TOO_MANY_SESSIONS);
        }
        LivenessSession session = faceLivenessClient.createSession(user.getAwsId());
        if (session == null) {
            throw new FatumUserException(FatumUserException.LIVENESS_UNAVAILABLE);
        }
        livenessCheckRepository.save(new LivenessCheck(user, attempt, session.sessionId()));
        log.info("Proof of life session {} opened for attempt {} of {}",
                session.sessionId(), attempt.getAttemptNumber(), user.getAwsId());
        return new LivenessSessionResponse(session.sessionId(), session.expiresAt(), false);
    }

    /**
     * Asks Rekognition for the verdict and closes the attempt with it.
     *
     * <p>It is idempotent: reporting the same session twice returns the recorded decision instead of
     * deciding again. A technical problem aborts with a service error and leaves the attempt open, so
     * the client can report the session again.</p>
     */
    @Transactional
    public LivenessResultResponse complete(String awsId, String sessionId) throws FatumUserException {
        User user = getActiveUser(awsId);
        if (!StringUtils.hasText(sessionId)) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        LivenessCheck check = livenessCheckRepository.findBySessionId(sessionId.trim())
                .orElseThrow(() -> new FatumUserException(FatumUserException.LIVENESS_SESSION_NOT_FOUND));
        if (!check.getUser().getAwsId().equals(user.getAwsId())) {
            throw new FatumUserException(FatumUserException.FORBIDDEN);
        }
        if (!check.isOpen()) {
            return alreadyDecided(check);
        }

        LivenessResult result = faceLivenessClient.result(check.getSessionId());
        if (result.status() == LivenessStatus.PENDING) {
            return new LivenessResultResponse(
                    check.getSessionId(),
                    LivenessStatus.PENDING,
                    null,
                    false,
                    user.getVerificationStatus(),
                    VerificationOutcome.AWAITING_LIVENESS,
                    0d,
                    List.of("liveness-in-progress"),
                    "Rekognition is still processing the proof of life");
        }
        if (result.status() == LivenessStatus.UNAVAILABLE) {
            throw new FatumUserException(FatumUserException.LIVENESS_UNAVAILABLE);
        }

        if (result.succeeded() && result.hasReferenceImage()) {
            check.succeed(result.confidence(), result.referenceBucket(), result.referenceKey());
            livenessService.adoptRekognitionReference(user, result.referenceBucket(), result.referenceKey());
        } else if (result.succeeded()) {
            // A proof of life without a reference picture cannot be compared with the document, so it
            // is not enough to confirm an identity.
            check.fail("Rekognition did not produce a reference picture");
        } else {
            check.fail(result.detail());
        }
        livenessCheckRepository.save(check);

        VerificationReport report = verificationService.finalizeWithLiveness(user, check.getAttempt(), result);
        log.info("Proof of life session {} of {} answered {} and the attempt ended as {}",
                check.getSessionId(), user.getAwsId(), result.status(), report.outcome());
        return new LivenessResultResponse(
                check.getSessionId(),
                result.status(),
                check.getConfidence(),
                report.outcome() == VerificationOutcome.VERIFIED,
                report.userStatus(),
                report.outcome(),
                report.referenceDocumentMatch(),
                report.flags(),
                report.summary());
    }

    private LivenessResultResponse alreadyDecided(LivenessCheck check) {
        VerificationAttempt attempt = check.getAttempt();
        return new LivenessResultResponse(
                check.getSessionId(),
                statusOf(check),
                check.getConfidence(),
                attempt.getOutcome() == VerificationOutcome.VERIFIED,
                attempt.getUser().getVerificationStatus(),
                attempt.getOutcome(),
                attempt.getReferenceDocumentMatch(),
                splitFlags(attempt.getFlags()),
                attempt.getSummary());
    }

    private LivenessCheck aliveSession(String awsId, VerificationProperties.Liveness liveness) {
        Instant oldestUseful = Instant.now().minus(liveness.getSessionTtl());
        return livenessCheckRepository
                .findByUserAwsIdAndStatusOrderByCreatedAtDesc(awsId, LivenessCheckStatus.CREATED)
                .stream()
                .filter(check -> check.getCreatedAt() != null && check.getCreatedAt().isAfter(oldestUseful))
                .findFirst()
                .orElse(null);
    }

    private LivenessStatus statusOf(LivenessCheck check) {
        return switch (check.getStatus()) {
            case SUCCEEDED -> LivenessStatus.SUCCEEDED;
            case FAILED -> LivenessStatus.FAILED;
            case EXPIRED -> LivenessStatus.EXPIRED;
            case CREATED -> LivenessStatus.PENDING;
        };
    }

    private List<String> splitFlags(String flags) {
        if (flags == null || flags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(flags.split(","))
                .map(String::trim)
                .filter(flag -> !flag.isEmpty())
                .toList();
    }

    private User getActiveUser(String awsId) throws FatumUserException {
        if (awsId == null || awsId.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByAwsId(awsId.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }
}