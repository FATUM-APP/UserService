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
    public static final String DOCUMENT_NOT_MUTABLE = "You cannot change the document type or number";
    public static final String DOCUMENT_TYPE_REQUIRED = "Document type and number are required";
    public static final String FILE_NOT_FOUND = "File not found";
    public static final String INVALID_DOCUMENT = "Document file is required";
    public static final String INVALID_DOCUMENT_TYPE = "Only PDF and image document files are allowed";

    public FatumUserException(String message) {
        super(message);
    }
}
