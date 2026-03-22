package com.baseball.domain.game;

import jakarta.persistence.*;
import lombok.*;

/**
 * 경기 중 교체 기록(투수·야수). 투수 교체 시 퇴장 투수가 해당 반 이닝에서 상대한 타자 수(battersFaced)를 저장.
 * 야수 교체({@link SubstitutionKind#FIELD})는 battersFaced=0, {@link #positionLabel}에 포지션명.
 */
@Entity
@Table(name = "pitcher_substitutions", indexes = {
    @Index(name = "idx_pitcher_sub_game", columnList = "game_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PitcherSubstitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(nullable = false)
    private Integer inning;

    @Column(name = "is_top", nullable = false)
    private Boolean isTop;

    /** null이면 기존 데이터 호환으로 {@link SubstitutionKind#PITCHER} */
    @Getter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "substitution_kind", length = 20)
    private SubstitutionKind kind;

    /** FIELD일 때만 (예: 유격수, 3루수) */
    @Column(name = "position_label", length = 40)
    private String positionLabel;

    /** 같은 타석 직후 여러 교체일 때 표시 순서 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    public SubstitutionKind getKind() {
        return kind != null ? kind : SubstitutionKind.PITCHER;
    }

    @Column(name = "pitcher_out_name", nullable = false, length = 100)
    private String pitcherOutName;

    @Column(name = "pitcher_in_name", nullable = false, length = 100)
    private String pitcherInName;

    /** 퇴장 투수가 해당 반 이닝에서 상대한 타자 수 (FIELD는 0) */
    @Column(name = "batters_faced", nullable = false)
    private Integer battersFaced;

    /** 이 타석(sequenceOrder) 다음에 표시. null 또는 0이면 해당 반 이닝 첫 타석 전에 표시 */
    @Column(name = "after_pa_sequence_order")
    private Integer afterPaSequenceOrder;

    void setGame(Game game) {
        this.game = game;
    }

    public void setBattersFaced(Integer battersFaced) {
        this.battersFaced = battersFaced != null ? battersFaced : 0;
    }
}
