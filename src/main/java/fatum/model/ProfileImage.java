package fatum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "PROFILE_IMAGES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProfileImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "IMAGE_KEY", nullable = false, length = 255)
    private String imageKey;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_AUTH0_ID", nullable = false, unique = true)
    private User user;

    ProfileImage(String imageKey, User user) {
        this.imageKey = requireImageKey(imageKey);
        this.user = user;
    }

    void replaceImageKey(String imageKey) {
        this.imageKey = requireImageKey(imageKey);
    }

    private static String requireImageKey(String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            throw new IllegalArgumentException("imageKey is required");
        }
        return imageKey.trim();
    }
}
