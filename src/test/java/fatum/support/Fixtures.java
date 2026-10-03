package fatum.support;

import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
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
        return build(awsId, awsId + "@fatum.com", LocalDate.of(1998, 5, 10));
    }

    /** Same user, different birth date: the entity is built once, it has no setter for it. */
    public static User user(String awsId, LocalDate birthDate) {
        return build(awsId, awsId + "@fatum.com", birthDate);
    }

    /** Same user, different email, used to reproduce a duplicate. */
    public static User userWithEmail(String awsId, String email) {
        return build(awsId, email, LocalDate.of(1998, 5, 10));
    }

    private static User build(String awsId, String email, LocalDate birthDate) {
        int seed = Math.abs(awsId.hashCode());
        try {
            return new User(
                    awsId,
                    email,
                    "Jane Doe",
                    "+57300" + seed % 1000000,
                    birthDate,
                    "jane" + seed % 1000,
                    "1020" + seed % 100000,
                    DocumentType.ID,
                    Gender.FEMALE);
        } catch (FatumUserException exception) {
            throw new IllegalStateException("The fixture user must be valid", exception);
        }
    }

    public static DocumentFile document(User user) {
        return document(user, "documents/2026/10/03/id.pdf");
    }

    public static DocumentFile document(User user, String key) {
        return new DocumentFile(key, "id.pdf", "application/pdf", 2048L, user);
    }

    public static ProfileImage profileImage(User user) {
        return profileImage(user, "profile-images/2026/10/03/avatar.png");
    }

    public static ProfileImage profileImage(User user, String key) {
        return new ProfileImage(key, "avatar.png", "image/png", 256L, user);
    }

    public static MockMultipartFile image(String filename) {
        return image("image", filename);
    }

    public static MockMultipartFile image(String partName, String filename) {
        return file(partName, filename, "image/png");
    }

    public static MockMultipartFile file(String partName, String filename, String contentType) {
        return new MockMultipartFile(partName, filename, contentType, new byte[]{1, 2, 3, 4});
    }
}