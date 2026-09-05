package fatum.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "PROFILE_IMAGES")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProfileImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private String id;

    @Column(name = "IMAGE_KEY", nullable = false, length = 255)
    private String imageKey;

    @Transient
    @Setter
    private String presignedUrl;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "USER_AUTH0_ID", nullable = false, unique = true)
    @JsonIgnore
    private User user;

    public ProfileImage(String imageKey, User user) {
        this.imageKey = imageKey;
        this.user = user;
    }

    public void replaceImageKey(String imageKey) {
        this.imageKey = imageKey;
    }
}
