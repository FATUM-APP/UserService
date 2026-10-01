package fatum.verification;

import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.DocumentFileRepository;
import fatum.repository.LivenessFileRepository;
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
import fatum.verification.analyzer.FraudAssessment;
import fatum.verification.analyzer.FraudAnalyzer;
import fatum.verification.analyzer.ScoreComponent;
import fatum.verification.analyzer.VerificationScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one verification attempt.
 *
 * <p>The pipeline is: read the document (Textract), compare what it says with the registered data,
 * compare faces (Rekognition: document against liveness, profile picture against liveness), look for
 * signs of forgery (Bedrock), combine everything into a score, let {@link VerificationPolicy} decide,
 * record the attempt and apply the retention policy to the evidence.</p>
 *
 * <p>Every collaborator is an interface, so the whole flow is testable without a single AWS call.</p>
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final UserRepository userRepository;
    private final DocumentFileRepository documentFileRepository;
    private final LivenessFileRepository livenessFileRepository;
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
            LivenessFileRepository livenessFileRepository,
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
        this.livenessFileRepository = livenessFileRepository;
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

    /** Runs an attempt for a user that already uploaded the document, the liveness and a picture. */
    @Transactional
    public VerificationReport submit(String awsId) throws FatumUserException {
        if (!properties.isEnabled()) {
            throw new FatumUserException(FatumUserException.VERIFICATION_DISABLED);
        }
        User user = getActiveUser(awsId);
        if (user.getVerificationStatus() == VerificationStatus.VERIFIED) {
            throw new FatumUserException(FatumUserException.VERIFICATION_ALREADY_COMPLETED);
        }
        DocumentFile document = documentFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.INCOMPLETE_VERIFICATION_MATERIAL));
        LivenessFile liveness = livenessFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.INCOMPLETE_VERIFICATION_MATERIAL));
        ProfileImage profileImage = profileImageRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.INCOMPLETE_VERIFICATION_MATERIAL));

        List<VerificationAttempt> previousAttempts = attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(awsId);
        List<ScoredAttempt> history = policy.toHistory(previousAttempts);
        if (!policy.canStartAttempt(history)) {
            throw new FatumUserException(FatumUserException.NO_ATTEMPTS_LEFT);
        }

        Evidence evidence = loadEvidence(document, liveness, profileImage);
        ExtractedDocument extracted = documentAnalyzer.extract(
                user.getDocumentType(),
                evidence.documentFront(),
                evidence.documentBack());
        DocumentFieldMatcher.MatchResult match = fieldMatcher.match(extracted, user);
        FaceMatch documentFace = faceComparator.compare(evidence.liveness(), evidence.documentFront());
        FaceMatch profileFace = faceComparator.compare(evidence.liveness(), evidence.profileImage());
        FraudAssessment fraud = fraudAnalyzer.assess(extracted, user, match.score());

        double score = scoreCalculator.composite(components(match, documentFace, profileFace, fraud));
        boolean forged = fraud.evaluated() && fraud.risk() >= properties.getFraudRiskThreshold();
        boolean documentNotTheUser = documentFace.evaluated()
                && documentFace.similarity() < properties.getMinFaceMatch();
        VerificationBand band = policy.band(score, forged || documentNotTheUser);

        int attemptNumber = previousAttempts.size() + 1;
        List<ScoredAttempt> updatedHistory = new ArrayList<>(history);
        updatedHistory.add(new ScoredAttempt(band, score));
        VerificationOutcome outcome = policy.decide(updatedHistory);

        List<String> flags = collectFlags(match, documentFace, profileFace, fraud, forged, documentNotTheUser);
        String summary = buildSummary(attemptNumber, score, match, documentFace, profileFace, fraud, extracted, band, outcome);

        VerificationAttempt attempt = new VerificationAttempt(
                user,
                attemptNumber,
                band,
                outcome,
                VerificationDecision.SYSTEM,
                score,
                match.score(),
                documentFace.similarity(),
                profileFace.similarity(),
                fraud.risk(),
                summary,
                String.join(",", flags),
                document.getFrontKey(),
                document.getBackKey(),
                liveness.getLivenessKey(),
                profileImage.getImageKey());
        attemptRepository.save(attempt);

        retentionService.apply(outcome, awsId);
        user.markVerificationStatus(outcome.toUserStatus());
        userRepository.save(user);
        if (outcome == VerificationOutcome.VERIFIED) {
            cognitoGroupService.grantVerified(user.getUsername());
        }

        log.info("Verification attempt {} of {} finished with band {} and outcome {} (score {})",
                attemptNumber, awsId, band, outcome, score);

        return new VerificationReport(
                awsId,
                attemptNumber,
                attemptNumber,
                policy.remainingAttempts(attemptNumber),
                score,
                match.score(),
                documentFace.similarity(),
                profileFace.similarity(),
                fraud.risk(),
                band,
                outcome,
                outcome.toUserStatus(),
                flags,
                summary,
                attempt.getCreatedAt() == null ? Instant.now() : attempt.getCreatedAt());
    }

    /** Current state of the process, used by the client to drive the next screen. */
    public VerificationState state(String awsId) throws FatumUserException {
        User user = getActiveUser(awsId);
        List<VerificationAttempt> attempts = attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(awsId);
        List<ScoredAttempt> history = policy.toHistory(attempts);
        return new VerificationState(
                user.getVerificationStatus(),
                attempts.size(),
                policy.remainingAttempts(attempts.size()),
                policy.canStartAttempt(history),
                documentFileRepository.findByUserAwsId(awsId).isPresent(),
                livenessFileRepository.findByUserAwsId(awsId).isPresent(),
                profileImageRepository.findByUserAwsId(awsId).isPresent(),
                lastAttempt(attempts));
    }

    public List<VerificationAttempt> history(String awsId) throws FatumUserException {
        getActiveUser(awsId);
        return attemptRepository.findByUserAwsIdOrderByAttemptNumberDesc(awsId);
    }

    private VerificationAttempt lastAttempt(List<VerificationAttempt> attempts) {
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
    }

    private Evidence loadEvidence(DocumentFile document, LivenessFile liveness, ProfileImage profileImage) {
        String documentRoute = storageProperties.getDocumentRoute();
        return new Evidence(
                fileContentFetcher.fetch(documentRoute, document.getFrontKey()),
                document.hasBackSide() ? fileContentFetcher.fetch(documentRoute, document.getBackKey()) : null,
                fileContentFetcher.fetch(storageProperties.getLivenessRoute(), liveness.getLivenessKey()),
                fileContentFetcher.fetch(storageProperties.getProfileImageRoute(), profileImage.getImageKey()));
    }

    private List<ScoreComponent> components(
            DocumentFieldMatcher.MatchResult match,
            FaceMatch documentFace,
            FaceMatch profileFace,
            FraudAssessment fraud) {
        VerificationProperties.Weights weights = properties.getWeights();
        List<ScoreComponent> components = new ArrayList<>();
        components.add(match.evaluated()
                ? ScoreComponent.of("documentMatch", match.score(), weights.getDocumentMatch())
                : ScoreComponent.notEvaluated("documentMatch", weights.getDocumentMatch()));
        components.add(documentFace.evaluated()
                ? ScoreComponent.of("documentLivenessMatch", documentFace.similarity(), weights.getDocumentLivenessMatch())
                : ScoreComponent.notEvaluated("documentLivenessMatch", weights.getDocumentLivenessMatch()));
        components.add(profileFace.evaluated()
                ? ScoreComponent.of("profileLivenessMatch", profileFace.similarity(), weights.getProfileLivenessMatch())
                : ScoreComponent.notEvaluated("profileLivenessMatch", weights.getProfileLivenessMatch()));
        components.add(fraud.evaluated()
                ? ScoreComponent.of("authenticity", 100d - fraud.risk(), weights.getAuthenticity())
                : ScoreComponent.notEvaluated("authenticity", weights.getAuthenticity()));
        return components;
    }

    private List<String> collectFlags(
            DocumentFieldMatcher.MatchResult match,
            FaceMatch documentFace,
            FaceMatch profileFace,
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
            flags.add("document-does-not-match-liveness");
        }
        if (documentFace.evaluated() && documentFace.detail() != null) {
            flags.add("document-face:" + documentFace.detail());
        }
        if (profileFace.evaluated() && profileFace.detail() != null) {
            flags.add("profile-face:" + profileFace.detail());
        }
        if (!fraud.evaluated()) {
            flags.add("authenticity-not-evaluated");
        }
        return flags;
    }

    private String buildSummary(
            int attemptNumber,
            double score,
            DocumentFieldMatcher.MatchResult match,
            FaceMatch documentFace,
            FaceMatch profileFace,
            FraudAssessment fraud,
            ExtractedDocument extracted,
            VerificationBand band,
            VerificationOutcome outcome) {
        return "Attempt %d/%d score %.2f | document match %.2f | document-liveness %.2f | profile-liveness %.2f | fraud risk %.2f | band %s | outcome %s | %s".formatted(
                attemptNumber,
                properties.getMaxAttempts(),
                score,
                match.score(),
                documentFace.similarity(),
                profileFace.similarity(),
                fraud.risk(),
                band,
                outcome,
                extracted.summary());
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

    /** Evidence downloaded for the attempt. */
    private record Evidence(byte[] documentFront, byte[] documentBack, byte[] liveness, byte[] profileImage) {
    }

    /** Everything the client needs to know about the verification process. */
    public record VerificationState(
            VerificationStatus status,
            int attemptsUsed,
            int attemptsRemaining,
            boolean canAttempt,
            boolean documentUploaded,
            boolean livenessUploaded,
            boolean profileImageUploaded,
            VerificationAttempt lastAttempt) {
    }
}
