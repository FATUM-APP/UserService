package fatum.exception;

/**
 * Domain exception used to signal user business-rule violations.
 */
public class FatumUserException extends Exception {

    public static final String NULL_VALUE = "A required value is null";
    public static final String USER_NOT_FOUND = "User not found";
    public static final String USER_ALREADY_EXISTS = "User already exists";
    public static final String EMAIL_EXISTS = "A user with that email already exists";
    public static final String PROFESSIONAL_CITY = "If user is professional, city must be set";
    public static final String USERNAME_EXISTS = "A user with that username already exists";
    public static final String PHONE_EXISTS = "A user with that phone number already exists";
    public static final String UNDERAGE_USER = "User must be at least 18 years old";
    public static final String DOCUMENT_EXISTS = "A user with that document already exists";
    public static final String DOCUMENT_NOT_AUTHENTICATED = "Document is null or has no type";
    public static final String INVALID_IMAGE = "Profile image is required";
    public static final String INVALID_IMAGE_TYPE = "Only image files are allowed";
    public static final String INACTIVE = "User is inactive";
    public static final String FILE_NOT_FOUND = "File not found";
    public static final String INVALID_DOCUMENT = "Document file is required";
    public static final String INVALID_DOCUMENT_TYPE = "Only image document files are allowed";
    public static final String DOCUMENT_BACK_REQUIRED = "Both sides of the document are required for this document type";
    public static final String INCOMPLETE_VERIFICATION_MATERIAL =
            "A profile picture and an identity document are required to verify the identity";
    public static final String VERIFICATION_ALREADY_COMPLETED = "The identity of the user is already verified";
    public static final String VERIFICATION_DISABLED = "Identity verification is disabled";
    public static final String NO_ATTEMPTS_LEFT =
            "No verification attempts left; the case is waiting for an administrator";
    public static final String LIVENESS_NOT_REQUIRED =
            "The proof of life can only be requested after the document and the profile picture have been checked";
    public static final String LIVENESS_DISABLED = "The proof of life is disabled";
    public static final String LIVENESS_UNAVAILABLE =
            "The proof of life service is unavailable; the case is still open, try again in a moment";
    public static final String LIVENESS_SESSION_NOT_FOUND = "Proof of life session not found";
    public static final String LIVENESS_TOO_MANY_SESSIONS =
            "This attempt has already opened too many proofs of life";
    public static final String PROFILE_REFERENCE_MISSING =
            "The account has no live reference to compare the new profile picture with";
    public static final String PROFILE_PHOTO_TOO_MANY_CHANGES =
            "Too many profile picture changes have been requested today";
    public static final String ADMIN_REVIEW_PHOTO_REQUIRED =
            "A new profile picture is required to verify the identity manually";
    public static final String ATTEMPT_NOT_FOUND = "Verification attempt not found";
    public static final String COGNITO_GROUP_FAILURE = "The user group could not be updated";
    public static final String FORBIDDEN = "You are not allowed to perform this action";

    public FatumUserException(String message) {
        super(message);
    }
}
