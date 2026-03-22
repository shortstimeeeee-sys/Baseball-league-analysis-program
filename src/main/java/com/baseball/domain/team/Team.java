package com.baseball.domain.team;

import com.baseball.domain.league.League;
import com.baseball.domain.player.Player;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "teams")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String shortName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "league_id")
    private League league;

    private Integer foundedYear;

    @Column(length = 200)
    private String homeStadium;

    @Column(length = 500)
    private String logoUrl;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Player> players = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void update(String name, String shortName, Integer foundedYear, String homeStadium, String logoUrl) {
        if (name != null) this.name = name;
        if (shortName != null) this.shortName = shortName;
        if (foundedYear != null) this.foundedYear = foundedYear;
        if (homeStadium != null) this.homeStadium = homeStadium;
        if (logoUrl != null) this.logoUrl = logoUrl;
    }

    public void setLeague(League league) {
        this.league = league;
    }

    public void addPlayer(Player player) {
        if (player == null) {
            return;
        }
        players.add(player);
        player.setTeam(this);
    }

    public void removePlayer(Player player) {
        if (player == null) {
            return;
        }
        players.remove(player);
        if (player.getTeam() == this) {
            player.setTeam(null);
        }
    }
}
