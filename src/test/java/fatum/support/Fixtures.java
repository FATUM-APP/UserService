package fatum.support;

import fatum.model.DocumentFile;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

/** Shared builders so every test describes a user the same way. */
public final class Fixtures {

    public static final String USER_ID = "aws-user-1";

    private Fixtures() {
    }

    public static User user() {
        return user(USER_ID);
    }

    public static User user(String awsId) {
        return new User(
                awsId,
                awsId + "@fatum.com",
                "Jane Doe",
                "+57300" + Math.abs(awsId.hashCode() % 1000000),
                LocalDate.of(1998, 5, 10),
                "jane" + Math.abs(awsId.hashCode() % 1000),
                "1020" + Math.abs(awsId.hashCode() % 100000),
                DocumentType.ID,
                Gender.FEMALE);
    }

    public static DocumentFile document(User user) {
        return document(user, "documents/2026/10/01/front.png", "documents/2026/10/01/back.png");
    }

    /** Same user, different birth date: the entity has no setter, it is built once. */
    public static User user(String awsId, LocalDate birthDate) {
        User base = user(awsId);
        return new User(
                base.getAwsId(),
                base.getEmail(),
                base.getName(),
                base.getPhoneNumber(),
                birthDate,
                base.getUsername(),
                base.getDocument(),
                base.getDocumentType(),
                base.getGender());
    }

    /** Same user, different email, used to reproduce a duplicate. */
    public static User userWithEmail(String awsId, String email) {
        User base = user(awsId);
        return new User(
                base.getAwsId(),
                email,
                base.getName(),
                base.getPhoneNumber(),
                base.getBirthDate(),
                base.getUsername(),
                base.getDocument(),
                base.getDocumentType(),
                base.getGender());
    }

    public static DocumentFile document(User user, String frontKey, String backKey) {
        return new DocumentFile(
                frontKey,
                backKey,
                "front.png",
                backKey == null ? null : "back.png",
                "image/png",
                1024L,
                backKey == null ? null : 2048L,
                user);
    }

    public static LivenessFile liveness(User user) {
        return liveness(user, "liveness/2026/10/01/frame.png");
    }

    public static LivenessFile liveness(User user, String key) {
        return new LivenessFile(key, "frame.png", "image/png", 512L, user);
    }

    public static ProfileImage profileImage(User user) {
        return profileImage(user, "profile-images/2026/10/01/avatar.png");
    }

    public static ProfileImage profileImage(User user, String key) {
        return new ProfileImage(key, "avatar.png", "image/png", 256L, user);
    }

    public static MockMultipartFile image(String filename) {
        return new MockMultipartFile("file", filename, "image/png", new byte[]{1, 2, 3, 4});
    }

    public static MockMultipartFile image(String partName, String filename) {
        return new MockMultipartFile(partName, filename, "image/png", new byte[]{1, 2, 3, 4});
    }
}
