package fatum.dto;

/**
 * Decision of an administrator over a case that the system could not resolve.
 *
 * @param userAwsId subject of the user under review
 * @param verified  true to confirm the identity, false to reject it
 * @param notes     free text kept in the attempt for the record
 */
public record AdminReviewRequest(String userAwsId, boolean verified, String notes) {
}
