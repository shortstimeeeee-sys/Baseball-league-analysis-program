package com.baseball.domain.league;

import jakarta.persistence.*;
import lombok.*;

import java.time.Year;

@Entity
@Table(name = "seasons", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"league_id", "season_year"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "league_id", nullable = false)
    private League league;

    @Column(name = "season_year", nullable = false)
    private Year year;

    @Column(length = 200)
    private String name;

    public void update(String name) {
        if (name != null) this.name = name;
    }

    void setLeague(League league) {
        this.league = league;
    }
}
