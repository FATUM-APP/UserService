package fatum.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "ADDRESSES",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "UK_USER_RESIDENCE",
                        columnNames = {"USER_USERNAME", "RESIDENCE"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "RESIDENCE", nullable = false, length = 255)
    private String residence;

    @Column(name = "ALIAS", nullable = false, length = 255)
    private String alias;

    @Column(name = "CITY", nullable = false, length = 50)
    private String city;

    @Column(name = "STATE", nullable = false, length = 50)
    private String state;

    @Column(name = "COUNTRY", nullable = false, length = 50)
    private String country;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "USER_USERNAME", referencedColumnName = "USERNAME", nullable = false)
    private User user;

    public Address(String address, String alias, String city, String country, User user, String state) {
        this.residence = address.trim().toLowerCase();
        this.alias = alias != null ? alias.trim().toLowerCase() : residence;
        this.state = state;
        this.city = city;
        this.country = country;
        this.user = user;
    }
}