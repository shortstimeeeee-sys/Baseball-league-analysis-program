package com.baseball.domain.game;

import jakarta.persistence.*;
import lombok.*;

/**
 * 경기별 이닝(1~9회) 점수.
 * 각 이닝에서 홈/원정 팀이 낸 득점을 저장합니다.
 */
@Entity
@Table(name = "inning_scores", indexes = {
    @Index(name = "idx_inning_scores_game", columnList = "game_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class InningScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    /** 이닝 번호 (1~9, 연장 시 10 이상) */
    @Column(nullable = false)
    private Integer inning;

    /** 해당 이닝에서 홈팀 득점 */
    @Column(name = "home_runs", nullable = false)
    private Integer homeRunsInInning;

    /** 해당 이닝에서 원정팀 득점 */
    @Column(name = "away_runs", nullable = false)
    private Integer awayRunsInInning;

    void setGame(Game game) {
        this.game = game;
    }
}
