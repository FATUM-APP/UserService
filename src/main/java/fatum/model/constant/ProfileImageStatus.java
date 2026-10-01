package fatum.model.constant;

/**
 * Whether a profile picture is the one other users see.
 *
 * <p>A verified account can only change its picture to a photograph of the same person, and that is
 * checked by comparing it with the live reference. Until the comparison finishes the new picture is
 * {@link #PENDING}: it is stored, nobody sees it, and the previous one stays {@link #ACTIVE}.</p>
 */
public enum ProfileImageStatus {

    ACTIVE,
    PENDING
}