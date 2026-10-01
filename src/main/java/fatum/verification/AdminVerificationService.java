package fatum.verification;

import fatum.dto.AdminReviewRequest;
import fatum.dto.PendingVerificationResponse;
import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.StorageEventReason;
import fatum.model.constant.StoredFileType;
import fatum.model.constant.VerificationAttemptType;
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
import fatum.service.LivenessService;
import fatum.service.ProfileImageService;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

/**
 * Manual review of the cases the automatic pipeline could not resolve.
 *
 * <p>An administrator sees the document that was kept for the review and decides. When the identity is
 * confirmed, the administrator uploads the picture that will represent the user: it becomes the profile
 * picture <em>and</em> the liveness reference, so the account keeps a single trusted image until the user
 * replaces it with another picture that still matches.</p>
 */
@Service
public class AdminVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AdminVerificationService.class);

    private final UserRepository userRepository;
    private final VerificationAttemptRepository attemptRepository;
    private final DocumentFileRepository documentFileRepository;
    private final LivenessFileRepository livenessFileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;
    private final ProfileImageService profileImageService;
    private final LivenessService livenessService;
    private final VerificationRetentionService retentionService;
    private final CognitoGroupService cognitoGroupService;

    public AdminVerificationService(
            UserRepository userRepository,
            VerificationAttemptRepository attemptRepository,
            DocumentFileRepository documentFileRepository,
            LivenessFileRepository livenessFileRepository,
            ProfileImageRepository profileImageRepository,
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties,
            ProfileImageService profileImageService,
            LivenessService livenessService,
            VerificationRetentionService retentionService,
            CognitoGroupService cognitoGroupService) {
        this.userRepository = userRepository;
        this.attemptRepository = attemptRepository;
        this.documentFileRepository = documentFileRepository;
        this.livenessFileRepository = livenessFileRepository;
        this.profileImageRepository = profileImageRepository;
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
        this.profileImageService = profileImageService;
        this.livenessService = livenessService;
        this.retentionService = retentionService;
        this.cognitoGroupService = cognitoGroupService;
    }

    /** Cases waiting for a human decision: rejected outright or left in manual review. */
    public List<PendingVerificationResponse> pending() {
        return userRepository.findByVerificationStatusIn(
                        List.of(VerificationStatus.MANUAL_REVIEW, VerificationStatus.REJECTED))
                .stream()
                .map(this::toPendingResponse)
                .toList();
    }

    /**
     * Applies the decision of an administrator.
     *
     * @param adminSubject  identifier of the administrator, kept in the attempt
     * @param request       decision and notes
     * @param profilePhoto  picture to adopt when the identity is confirmed; required in that case
     */
    @Transactional
    public VerificationReport review(
            String adminSubject,
            AdminReviewRequest request,
            MultipartFile profilePhoto) throws FatumUserException {

        if (request == null || !StringUtils.hasText(request.userAwsId())) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByAwsId(request.userAwsId().trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        List<VerificationAttempt> attempts = attemptRepository
                .findByUserAwsIdAndTypeOrderByAttemptNumberAsc(user.getAwsId(), VerificationAttemptType.FULL);
        int attemptNumber = attempts.size() + 1;
        double score = attempts.isEmpty() ? 0d : attempts.get(attempts.size() - 1).getScore();

        String adoptedKey = null;
        if (request.verified()) {
            if (profilePhoto == null || profilePhoto.isEmpty()) {
                throw new FatumUserException(FatumUserException.ADMIN_REVIEW_PHOTO_REQUIRED);
            }
            StoredFile uploaded = fileStorageClient.upload(profilePhoto, storageProperties.getProfileImageRoute());
            profileImageService.adoptAsProfilePicture(user, uploaded);
            livenessService.adoptAsReference(user, uploaded);
            adoptedKey = uploaded.key();
            retentionService.recordKept(user.getAwsId(), StoredFileType.PROFILE_IMAGE, adoptedKey,
                    StorageEventReason.ADMIN_VERIFIED, "Picture adopted as profile image and liveness reference");
            retentionService.recordKept(user.getAwsId(), StoredFileType.LIVENESS, adoptedKey,
                    StorageEventReason.ADMIN_VERIFIED, "Picture adopted as profile image and liveness reference");
        }

        VerificationOutcome outcome = request.verified()
                ? VerificationOutcome.VERIFIED
                : VerificationOutcome.REJECTED;
        VerificationBand band = request.verified() ? VerificationBand.VERIFIED : VerificationBand.REJECTED;

        DocumentFile document = documentFileRepository.findByUserAwsId(user.getAwsId()).orElse(null);
        LivenessFile liveness = livenessFileRepository.findByUserAwsId(user.getAwsId()).orElse(null);
        ProfileImage profileImage = profileImageRepository.findActive(user.getAwsId()).orElse(null);

        VerificationAttempt attempt = new VerificationAttempt(
                user,
                VerificationAttemptType.FULL,
                attemptNumber,
                band,
                outcome,
                VerificationDecision.ADMIN,
                score,
                document == null ? 0d : 100d,
                0d,
                0d,
                0d,
                "Manual review by " + adminSubject + ": " + outcome,
                "",
                document == null ? null : document.getFrontKey(),
                document == null ? null : document.getBackKey(),
                liveness == null ? null : liveness.getLivenessKey(),
                profileImage == null ? adoptedKey : profileImage.getImageKey());
        attempt.decidedByAdmin(adminSubject, request.notes());
        attemptRepository.save(attempt);

        user.markVerificationStatus(outcome.toUserStatus());
        userRepository.save(user);
        if (outcome == VerificationOutcome.VERIFIED) {
            cognitoGroupService.grantVerified(user.getUsername());
        }
        log.info("Administrator {} reviewed the verification of {} with outcome {}", adminSubject, user.getAwsId(), outcome);

        return new VerificationReport(
                user.getAwsId(),
                attemptNumber,
                attemptNumber,
                0,
                score,
                attempt.getDocumentMatch(),
                0d,
                0d,
                null,
                0d,
                band,
                outcome,
                outcome.toUserStatus(),
                List.of("manual-review"),
                attempt.getSummary(),
                attempt.getCreatedAt() == null ? Instant.now() : attempt.getCreatedAt());
    }

    private PendingVerificationResponse toPendingResponse(User user) {
        List<VerificationAttempt> attempts = attemptRepository
                .findByUserAwsIdAndTypeOrderByAttemptNumberAsc(user.getAwsId(), VerificationAttemptType.FULL);
        VerificationAttempt last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        return new PendingVerificationResponse(
                user.getAwsId(),
                user.getName(),
                user.getUsername(),
                user.getVerificationStatus(),
                attempts.size(),
                last == null ? null : last.getOutcome(),
                last == null ? null : last.getScore(),
                last == null ? null : last.getSummary(),
                last == null ? null : last.getCreatedAt());
    }
}
