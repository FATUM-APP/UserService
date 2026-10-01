package fatum.verification;

import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.LivenessCheckStatus;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.DocumentFileRepository;
import fatum.repository.LivenessCheckRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.service.CognitoGroupService;
import fatum.storage.FileStorageProperties;
import fatum.verification.analyzer.DocumentAnalyzer;
import fatum.verification.analyzer.DocumentFieldMatcher;
import fatum.verification.analyzer.ExtractedDocument;
import fatum.verification.analyzer.FaceComparator;
import fatum.verification.analyzer.FaceMatch;
import fatum.verification.analyzer.FraudAnalyzer;
import fatum.verification.analyzer.FraudAssessment;
import fatum.verification.analyzer.LivenessResult;
import fatum.verification.analyzer.LivenessStatus;
import fatum.verification.analyzer.ScoreComponent;
import fatum.verification.analyzer.VerificationScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the identity verification, which runs in two phases.
 *
 * <h2>First phase: everything that is free</h2>
 * <p>The document is read with Textract, its fields are compared with what the user registered, the
 * picture of the document is compared with the profile picture using Rekognition and the document is
 * checked for signs of forgery with Bedrock. Only when this phase reaches the verified threshold is
 * the proof of life requested, because that is the step that costs money on every attempt.</p>
 *
 * <h2>Second phase: the proof of life</h2>
 * <p>Rekognition runs it, and the reference picture it produces is then compared with the document.
 * Passing the proof of life proves a live person was in front of the camera; the comparison proves
 * that person owns the document. Both are required to reach {@code VERIFIED}; a failure in either
 * sends the case to a human without another attempt.</p>
 *
 * <p>Every collaborator is an interface, so the whole flow is testable without a single AWS call.</p>
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final UserRepository userRepository;
    private final DocumentFileRepository documentFileRepository;
    private final LivenessCheckRepository livenessCheckRepository;
    private final ProfileImageRepository profileImageRepository;
    private final VerificationAttemptRepository attemptRepository;
    private final DocumentAnalyzer documentAnalyzer;
    private final DocumentFieldMatcher fieldMatcher;
    private final FaceComparator faceComparator;
    private final FraudAnalyzer fraudAnalyzer;
    private final VerificationScoreCalculator scoreCalculator;
    private final VerificationPolicy policy;
    private final VerificationProperties properties;
    private final VerificationRetentionService retentionService;
    private final CognitoGroupService cognitoGroupService;
    private final FileContentFetcher fileContentFetcher;
    private final FileStorageProperties storageProperties;

    public VerificationService(
            UserRepository userRepository,
            DocumentFileRepository documentFileRepository,
            LivenessCheckRepository livenessCheckRepository,
            ProfileImageRepository profileImageRepository,
            VerificationAttemptRepository attemptRepository,
            DocumentAnalyzer documentAnalyzer,
            DocumentFieldMatcher fieldMatcher,
            FaceComparator faceComparator,
            FraudAnalyzer fraudAnalyzer,
            VerificationScoreCalculator scoreCalculator,
            VerificationPolicy policy,
            VerificationProperties properties,
            VerificationRetentionService retentionService,
            CognitoGroupService cognitoGroupService,
            FileContentFetcher fileContentFetcher,
            FileStorageProperties storageProperties) {
        this.userRepository = userRepository;
        this.documentFileRepository = documentFileRepository;
        this.livenessCheckRepository = livenessCheckRepository;
        this.profileImageRepository = profileImageRepository;
        this.attemptRepository = attemptRepository;
        this.documentAnalyzer = documentAnalyzer;
        this.fieldMatcher = fieldMatcher;
        this.faceComparator = faceComparator;
        this.fraudAnalyzer = fraudAnalyzer;
        this.scoreCalculator = scoreCalculator;
        this.policy = policy;
        this.properties = properties;
        this.retentionService = retentionService;
        this.cognitoGroupService = cognitoGroupService;
        this.fileContentFetcher = fileContentFetcher;
        this.storageProperties = storageProperties;
    }

    /** Runs the free phase for a user that already uploaded a document and a profile picture. */
    @Transactional
    public VerificationReport submit(String awsId) throws FatumUserException {
        if (!properties.isEnabled()) {
            throw new FatumUserException(FatumUserException.VERIFICATION_DISABLED);
        }
        User user = getActiveUser(awsId);
        if (user.getVerificationStatus() == VerificationStatus.VERIFIED) {
            throw new FatumUserException(FatumUserException.VERIFICATION_ALREADY_COMPLETED);
        }
        if (user.getVerificationStatus() != VerificationStatus.UNVERIFIED) {
            throw new FatumUserException(FatumUserException.NO_ATTEMPTS_LEFT);
        }
        DocumentFile document = documentFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.INCOMPLETE_VERIFICATION_MATERIAL));
        ProfileImage profileImage = profileImageRepository.findActive(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.INCOMPLETE_VERIFICATION_MATERIAL));

        List<VerificationAttempt> previous = attemptRepository
                .findByUserAwsIdAndTypeOrderByAttemptNumberAsc(awsId, VerificationAttemptType.FULL);
        List<ScoredAttempt> history = policy.toHistory(previous);
        if (!policy.canStartAttempt(history)) {
            throw new FatumUserException(FatumUserException.NO_ATTEMPTS_LEFT);
        }

        byte[] documentFront = fileContentFetcher.fetch(storageProperties.getDocumentRoute(), document.getFrontKey());
        byte[] documentBack = document.hasBackSide()
                ? fileContentFetcher.fetch(storageProperties.getDocumentRoute(), document.getBackKey())
                : null;
        byte[] profilePicture = fileContentFetcher.fetch(
                storageProperties.getProfileImageRoute(),
                profileImage.getImageKey());

        ExtractedDocument extracted = documentAnalyzer.extract(user.getDocumentType(), documentFront, documentBack);
        DocumentFieldMatcher.MatchResult match = fieldMatcher.match(extracted, user);
        FaceMatch documentProfile = faceComparator.compare(documentFront, profilePicture);
        FraudAssessment fraud = fraudAnalyzer.assess(extracted, user, match.score());

        double score = scoreCalculator.composite(components(match, documentProfile, fraud));
        boolean forged = fraud.evaluated() && fraud.risk() >= properties.getFraudRiskThreshold();
        boolean documentNotTheUser = documentProfile.evaluated()
                && documentProfile.similarity() < properties.getMinFaceMatch();
        VerificationBand band = policy.band(score, forged || documentNotTheUser);
        VerificationOutcome outcome = policy.decideAfterFirstPhase(band, score, history);

        int attemptNumber = previous.size() + 1;
        List<String> flags = collectFlags(match, documentProfile, fraud, forged, documentNotTheUser);
        String summary = buildFirstPhaseSummary(attemptNumber, score, match, documentProfile, fraud, extracted, band, outcome);

        VerificationAttempt attempt = new VerificationAttempt(
                user,
                VerificationAttemptType.FULL,
                attemptNumber,
                band,
                outcome,
                VerificationDecision.SYSTEM,
                score,
                match.score(),
                documentProfile.similarity(),
                0d,
                fraud.risk(),
                summary,
                String.join(",", flags),
                document.getFrontKey(),
                document.getBackKey(),
                null,
                profileImage.getImageKey());
        attemptRepository.save(attempt);

        // The policy is applied to every outcome, including the one that keeps everything: in that case
        // it writes down why the document and the picture are still there.
        retentionService.apply(outcome, awsId);
        if (outcome == VerificationOutcome.AWAITING_LIVENESS) {
            log.info("Verification attempt {} of {} passed the free phase with score {}; the proof of life is required",
                    attemptNumber, awsId, score);
        } else {
            user.markVerificationStatus(outcome.toUserStatus());
            userRepository.save(user);
            log.info("Verification attempt {} of {} finished with band {} and outcome {} (score {})",
                    attemptNumber, awsId, band, outcome, score);
        }

        return new VerificationReport(
                awsId,
                attemptNumber,
                attemptNumber,
                policy.remainingAttempts(attemptNumber),
                score,
                match.score(),
                documentProfile.similarity(),
                0d,
                null,
                fraud.risk(),
                band,
                outcome,
                outcome.toUserStatus(),
                flags,
                summary,
                attempt.getCreatedAt() == null ? Instant.now() : attempt.getCreatedAt());
    }

    /**
     * Closes an attempt that was waiting for the proof of life.
     *
     * <p>It is called by the liveness service once Rekognition has answered, and it is where the last
     * comparison of the pipeline happens: the picture Rekognition produced against the picture of the
     * document. That is what stops somebody from passing the first phase with the photographs of
     * another person and then showing their own face to the camera.</p>
     *
     * <p>An unreachable storage service aborts with a service error and leaves the attempt open, so the
     * client can report the session again; it never turns a technical problem into a rejection.</p>
     */
    @Transactional
    public VerificationReport finalizeWithLiveness(User user, VerificationAttempt attempt, LivenessResult liveness)
            throws FatumUserException {
        String awsId = user.getAwsId();
        DocumentFile document = documentFileRepository.findByUserAwsId(awsId).orElse(null);

        FaceMatch referenceMatch = FaceMatch.notEvaluated(
                document == null ? "missing-document" : "liveness-without-reference");
        if (liveness.succeeded() && liveness.hasReferenceImage() && document != null) {
            byte[] documentFront = fileContentFetcher.fetch(
                    storageProperties.getDocumentRoute(),
                    document.getFrontKey());
            referenceMatch = faceComparator.compareWithStoredObject(
                    documentFront,
                    liveness.referenceBucket(),
                    liveness.referenceKey());
        }

        VerificationOutcome outcome = policy.decideLiveness(
                liveness.status(),
                liveness.confidence(),
                referenceMatch.evaluated(),
                referenceMatch.similarity());
        VerificationBand band = outcome == VerificationOutcome.VERIFIED
                ? VerificationBand.VERIFIED
                : VerificationBand.MANUAL;

        List<String> flags = collectLivenessFlags(liveness, referenceMatch, outcome);
        String summary = buildLivenessSummary(attempt, liveness, referenceMatch, outcome);

        attempt.resolve(
                band,
                outcome,
                liveness.confidence(),
                referenceMatch.evaluated() ? referenceMatch.similarity() : 0d,
                summary,
                String.join(",", flags),
                liveness.referenceKey());
        attemptRepository.save(attempt);

        retentionService.apply(outcome, awsId);
        user.markVerificationStatus(outcome.toUserStatus());
        userRepository.save(user);
        if (outcome == VerificationOutcome.VERIFIED) {
            cognitoGroupService.grantVerified(user.getUsername());
        }

        log.info("Verification attempt {} of {} closed by the proof of life with outcome {}",
                attempt.getAttemptNumber(), awsId, outcome);

        return new VerificationReport(
                awsId,
                attempt.getAttemptNumber(),
                attempt.getAttemptNumber(),
                policy.remainingAttempts(attempt.getAttemptNumber()),
                attempt.getScore(),
                attempt.getDocumentMatch(),
                attempt.getDocumentProfileMatch(),
                referenceMatch.evaluated() ? referenceMatch.similarity() : 0d,
                liveness.confidence(),
                attempt.getFraudRisk(),
                band,
                outcome,
                outcome.toUserStatus(),
                flags,
                summary,
                Instant.now());
    }

    /** The attempt that is waiting for its proof of life, if there is one. */
    public Optional<VerificationAttempt> awaitingLiveness(String awsId) {
        return attemptRepository.findFirstByUserAwsIdAndTypeAndOutcomeOrderByCreatedAtDesc(
                awsId,
                VerificationAttemptType.FULL,
                VerificationOutcome.AWAITING_LIVENESS);
    }

    /** Current state of the process, used by the client to drive the next screen. */
    public VerificationState state(String awsId) throws FatumUserException {
        User user = getActiveUser(awsId);
        List<VerificationAttempt> attempts = attemptRepository
                .findByUserAwsIdAndTypeOrderByAttemptNumberAsc(awsId, VerificationAttemptType.FULL);
        List<ScoredAttempt> history = policy.toHistory(attempts);
        boolean livenessCompleted = livenessCheckRepository
                .findFirstByUserAwsIdAndStatusOrderByCreatedAtDesc(awsId, LivenessCheckStatus.SUCCEEDED)
                .isPresent();
        boolean livenessRequired = awaitingLiveness(awsId).isPresent();
        return new VerificationState(
                user.getVerificationStatus(),
                attempts.size(),
                policy.remainingAttempts(attempts.size()),
                // While a proof of life is open the attempt cannot be repeated: the paid step is
                // already running and a second one would be paid twice.
                !livenessRequired && policy.canStartAttempt(history),
                documentFileRepository.findByUserAwsId(awsId).isPresent(),
                livenessCompleted,
                profileImageRepository.findActive(awsId).isPresent(),
                livenessRequired,
                lastAttempt(attempts));
    }

    /** History of the identity attempts, newest first. */
    public List<VerificationAttempt> history(String awsId) throws FatumUserException {
        getActiveUser(awsId);
        return attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberDesc(awsId, VerificationAttemptType.FULL);
    }

    private VerificationAttempt lastAttempt(List<VerificationAttempt> attempts) {
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
    }

    private List<ScoreComponent> components(
            DocumentFieldMatcher.MatchResult match,
            FaceMatch documentProfile,
            FraudAssessment fraud) {
        VerificationProperties.Weights weights = properties.getWeights();
        List<ScoreComponent> components = new ArrayList<>();
        components.add(match.evaluated()
                ? ScoreComponent.of("documentMatch", match.score(), weights.getDocumentMatch())
                : ScoreComponent.notEvaluated("documentMatch", weights.getDocumentMatch()));
        components.add(documentProfile.evaluated()
                ? ScoreComponent.of("documentProfileMatch", documentProfile.similarity(), weights.getDocumentLivenessMatch())
                : ScoreComponent.notEvaluated("documentProfileMatch", weights.getDocumentLivenessMatch()));
        components.add(fraud.evaluated()
                ? ScoreComponent.of("authenticity", 100d - fraud.risk(), weights.getAuthenticity())
                : ScoreComponent.notEvaluated("authenticity", weights.getAuthenticity()));
        return components;
    }

    private List<String> collectFlags(
            DocumentFieldMatcher.MatchResult match,
            FaceMatch documentProfile,
            FraudAssessment fraud,
            boolean forged,
            boolean documentNotTheUser) {
        List<String> flags = new ArrayList<>();
        match.mismatched().forEach(field -> flags.add("mismatch:" + field));
        fraud.flags().forEach(flag -> flags.add("fraud:" + flag));
        if (forged) {
            flags.add("forged-document");
        }
        if (documentNotTheUser) {
            flags.add("document-does-not-match-profile-picture");
        }
        if (documentProfile.evaluated() && documentProfile.detail() != null) {
            flags.add("document-profile:" + documentProfile.detail());
        }
        if (!fraud.evaluated()) {
            flags.add("authenticity-not-evaluated");
        }
        return flags;
    }

    private List<String> collectLivenessFlags(LivenessResult liveness, FaceMatch referenceMatch, VerificationOutcome outcome) {
        List<String> flags = new ArrayList<>();
        flags.add("liveness-" + liveness.status().name().toLowerCase());
        if (liveness.detail() != null) {
            flags.add("liveness-detail:" + liveness.detail());
        }
        if (!referenceMatch.evaluated()) {
            flags.add("reference-not-evaluated:" + referenceMatch.detail());
        } else if (referenceMatch.similarity() < properties.getFaceSimilarityThreshold()) {
            flags.add("reference-does-not-match-document");
        }
        if (outcome == VerificationOutcome.MANUAL_REVIEW) {
            flags.add("no-second-chance");
        }
        return flags;
    }

    private String buildFirstPhaseSummary(
            int attemptNumber,
            double score,
            DocumentFieldMatcher.MatchResult match,
            FaceMatch documentProfile,
            FraudAssessment fraud,
            ExtractedDocument extracted,
            VerificationBand band,
            VerificationOutcome outcome) {
        return "Attempt %d/%d score %.2f | document match %.2f | document-profile %.2f | fraud risk %.2f | band %s | outcome %s | %s".formatted(
                attemptNumber,
                properties.getMaxAttempts(),
                score,
                match.score(),
                documentProfile.similarity(),
                fraud.risk(),
                band,
                outcome,
                extracted.summary());
    }

    private String buildLivenessSummary(
            VerificationAttempt attempt,
            LivenessResult liveness,
            FaceMatch referenceMatch,
            VerificationOutcome outcome) {
        return "Attempt %d score %.2f | proof of life %s (confidence %.2f) | reference-document %.2f | outcome %s".formatted(
                attempt.getAttemptNumber(),
                attempt.getScore(),
                liveness.status(),
                liveness.confidence(),
                referenceMatch.similarity(),
                outcome);
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

    /** Everything the client needs to know about the verification process. */
    public record VerificationState(
            VerificationStatus status,
            int attemptsUsed,
            int attemptsRemaining,
            boolean canAttempt,
            boolean documentUploaded,
            boolean livenessCompleted,
            boolean profileImageUploaded,
            boolean livenessRequired,
            VerificationAttempt lastAttempt) {
    }
}
