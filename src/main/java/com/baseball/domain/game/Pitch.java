package com.baseball.domain.game;

import jakarta.persistence.*;
import lombok.*;

/**
 * 투구 1개 기록 (구종, 속도, 결과, 볼/스트라이크 카운트).
 */
@Entity
@Table(name = "pitches", indexes = {
    @Index(name = "idx_pitches_plate_app", columnList = "plate_appearance_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Pitch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plate_appearance_id", nullable = false)
    private PlateAppearance plateAppearance;

    /** 타석 내 N구 */
    @Column(name = "pitch_order", nullable = false)
    private Integer pitchOrder;

    /** 투구 후 볼 카운트 */
    @Column(name = "ball_count_after")
    private Integer ballCountAfter;

    /** 투구 후 스트라이크 카운트 */
    @Column(name = "strike_count_after")
    private Integer strikeCountAfter;

    /** 구종 (직구, 커터, 슬라이더 등) */
    @Column(name = "pitch_type", length = 50)
    private String pitchType;

    /** 구속 (km/h). null 가능 */
    @Column(name = "speed_kmh")
    private Integer speedKmh;

    /** 투구 결과 설명 (예: "1구볼", "2구스트라이크", "3구파울") */
    @Column(name = "result_text", length = 100)
    private String resultText;

    public void setPlateAppearance(PlateAppearance plateAppearance) {
        this.plateAppearance = plateAppearance;
    }
}
