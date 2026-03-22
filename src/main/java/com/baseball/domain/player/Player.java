package com.baseball.domain.player;

import com.baseball.domain.team.Team;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "players")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Position position;

    private Integer backNumber;

    private LocalDate birthDate;

    @Column(length = 50)
    private String nationality;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void update(String name, Position position, Integer backNumber, LocalDate birthDate, String nationality) {
        if (name != null) this.name = name;
        if (position != null) this.position = position;
        if (backNumber != null) this.backNumber = backNumber;
        if (birthDate != null) this.birthDate = birthDate;
        if (nationality != null) this.nationality = nationality;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public enum Position {
        PITCHER("투수"),
        CATCHER("포수"),
        FIRST_BASE("1루수"),
        SECOND_BASE("2루수"),
        THIRD_BASE("3루수"),
        SHORTSTOP("유격수"),
        LEFT_FIELD("좌익수"),
        CENTER_FIELD("중견수"),
        RIGHT_FIELD("우익수"),
        DESIGNATED_HITTER("지명타자");

        private final String displayName;

        Position(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
