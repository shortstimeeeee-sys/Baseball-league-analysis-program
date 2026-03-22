package com.baseball.domain.league;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "leagues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class League {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String country;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "league", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Season> seasons = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void update(String name, String country, String description) {
        if (name != null) this.name = name;
        if (country != null) this.country = country;
        if (description != null) this.description = description;
    }

    public void addSeason(Season season) {
        if (season == null) {
            return;
        }
        seasons.add(season);
        season.setLeague(this);
    }

    public void removeSeason(Season season) {
        if (season == null) {
            return;
        }
        seasons.remove(season);
        if (season.getLeague() == this) {
            season.setLeague(null);
        }
    }
}
