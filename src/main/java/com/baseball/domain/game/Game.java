package com.baseball.domain.game;

import com.baseball.domain.team.Team;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "games")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_team_id", nullable = false)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_team_id", nullable = false)
    private Team awayTeam;

    @Column(nullable = false)
    private java.time.LocalDateTime gameDateTime;

    @Column(length = 200)
    private String venue;

    private Integer homeScore;

    private Integer awayScore;

    /** 박스스코어: 홈팀 안타 수 */
    private Integer homeHits;
    /** 박스스코어: 원정팀 안타 수 */
    private Integer awayHits;
    /** 박스스코어: 홈팀 실책 */
    private Integer homeErrors;
    /** 박스스코어: 원정팀 실책 */
    private Integer awayErrors;
    /** 박스스코어: 홈팀 볼넷 */
    private Integer homeWalks;
    /** 박스스코어: 원정팀 볼넷 */
    private Integer awayWalks;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GameStatus status;

    @Column(length = 500)
    private String memo;

    /**
     * 회초/말 종료 후(해당 반 마지막 타석 뒤) 표시할 메모. 한 줄당 한 반.
     * 형식 예: {@code 1초|공: 새 공 사용 | 교체: 포수 A→B} (공/교체는 비워도 됨)
     */
    @Column(name = "half_inning_break_notes", length = 10000)
    private String halfInningBreakNotes;

    /**
     * 초·말 교대 등 안내 멘트. 중계 탭 상단 별도 카드로만 표시 (타석 목록과 구분).
     */
    @Column(name = "half_inning_transition_notes", length = 10000)
    private String halfInningTransitionNotes;

    /**
     * 파싱 기록 병합 시 덮어쓰지 않을 반 이닝 키(쉼표 구분). 형식 {@code 이닝_true|false} (true=회초).
     * 확정된 반은 이후 가져오기에서 타석·투수교체가 삭제·갱신되지 않음.
     */
    @Column(name = "record_confirmed_half_keys", length = 4000)
    private String recordConfirmedHalfKeys;

    /** 승리 투수 이름 (파싱/수동 입력) */
    @Column(name = "winning_pitcher_name", length = 100)
    private String winningPitcherName;
    /** 패전 투수 이름 */
    @Column(name = "losing_pitcher_name", length = 100)
    private String losingPitcherName;
    /** 세이브 투수 이름 */
    @Column(name = "save_pitcher_name", length = 100)
    private String savePitcherName;

    /**
     * 더블헤더 2차전 등 동일 일자·동일 매치업의 두 번째 경기 여부.
     * false(기본)인 경기가 같은 날 같은 팀 대결의 "첫 번째 슬롯"으로 병합(업데이트) 대상이 됨.
     * DB 컬럼명은 H2에서 DOUBLE 키워드와 충돌을 피하기 위해 dh_flag 사용.
     */
    @Column(name = "dh_flag", nullable = false)
    @Builder.Default
    private boolean doubleheader = false;

    /** 시범 경기 여부 */
    @Column(name = "exhibition_flag", nullable = false)
    @Builder.Default
    private boolean exhibition = false;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("inning")
    @Builder.Default
    private List<InningScore> inningScores = new ArrayList<>();

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("inning, isTop, sequenceOrder")
    @Builder.Default
    private List<PlateAppearance> plateAppearances = new ArrayList<>();

    public void updateResult(Integer homeScore, Integer awayScore, GameStatus status, String memo) {
        if (homeScore != null) this.homeScore = homeScore;
        if (awayScore != null) this.awayScore = awayScore;
        if (status != null) this.status = status;
        if (memo != null) this.memo = memo;
    }

    public void updateBoxScore(Integer homeHits, Integer awayHits, Integer homeErrors, Integer awayErrors,
                               Integer homeWalks, Integer awayWalks) {
        if (homeHits != null) this.homeHits = homeHits;
        if (awayHits != null) this.awayHits = awayHits;
        if (homeErrors != null) this.homeErrors = homeErrors;
        if (awayErrors != null) this.awayErrors = awayErrors;
        if (homeWalks != null) this.homeWalks = homeWalks;
        if (awayWalks != null) this.awayWalks = awayWalks;
    }

    public void updatePitcherResults(String winningPitcherName, String losingPitcherName, String savePitcherName) {
        if (winningPitcherName != null) this.winningPitcherName = winningPitcherName;
        if (losingPitcherName != null) this.losingPitcherName = losingPitcherName;
        if (savePitcherName != null) this.savePitcherName = savePitcherName;
    }

    /** 세이브만 비우기(승·세 중복 제거 등). */
    public void setSavePitcherName(String savePitcherName) {
        this.savePitcherName = savePitcherName;
    }

    /**
     * 승리 투수와 세이브 투수는 동일 인물이 될 수 없음(기록 규칙).
     * 마무리 투수가 승리 투수 자격도 있으면 승만 기록하고 세이브는 부여하지 않는다.
     */
    public void clearSavePitcherWhenSameAsWinningPitcher() {
        if (samePitcherNameForRecord(this.winningPitcherName, this.savePitcherName)) {
            this.savePitcherName = null;
        }
    }

    /**
     * 화면 표시용: 승리 투수와 동일 인물이면 세이브는 표시하지 않음(DB에 잘못 남은 값도 가림).
     */
    public String getDisplaySavePitcherName() {
        if (savePitcherName == null || savePitcherName.isBlank()) {
            return null;
        }
        if (samePitcherNameForRecord(this.winningPitcherName, this.savePitcherName)) {
            return null;
        }
        return savePitcherName;
    }

    private static boolean samePitcherNameForRecord(String a, String b) {
        return PitcherNameNormalizer.samePitcher(a, b);
    }

    public void updateSchedule(String venue, LocalDateTime gameDateTime) {
        if (venue != null) this.venue = venue;
        if (gameDateTime != null) this.gameDateTime = gameDateTime;
    }

    public void setDoubleheader(boolean doubleheader) {
        this.doubleheader = doubleheader;
    }

    public void setExhibition(boolean exhibition) {
        this.exhibition = exhibition;
    }

    public void setHalfInningBreakNotes(String halfInningBreakNotes) {
        this.halfInningBreakNotes = halfInningBreakNotes;
    }

    public void setHalfInningTransitionNotes(String halfInningTransitionNotes) {
        this.halfInningTransitionNotes = halfInningTransitionNotes;
    }

    public void setRecordConfirmedHalfKeys(String recordConfirmedHalfKeys) {
        this.recordConfirmedHalfKeys = recordConfirmedHalfKeys;
    }

    /** 확정된 반 이닝 키 집합 (파싱 병합 시 잠금) */
    public Set<String> getRecordConfirmedHalfKeySet() {
        if (recordConfirmedHalfKeys == null || recordConfirmedHalfKeys.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : recordConfirmedHalfKeys.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public void replaceRecordConfirmedHalfKeySet(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            this.recordConfirmedHalfKeys = null;
            return;
        }
        this.recordConfirmedHalfKeys = String.join(",", keys);
    }

    public void addRecordConfirmedHalfKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> s = new LinkedHashSet<>(getRecordConfirmedHalfKeySet());
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                s.add(k.trim());
            }
        }
        replaceRecordConfirmedHalfKeySet(s);
    }

    public void removeRecordConfirmedHalfKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> s = new LinkedHashSet<>(getRecordConfirmedHalfKeySet());
        for (String k : keys) {
            if (k != null) {
                s.remove(k.trim());
            }
        }
        replaceRecordConfirmedHalfKeySet(s);
    }

    public boolean isRecordHalfKeyConfirmed(String halfKey) {
        return halfKey != null && getRecordConfirmedHalfKeySet().contains(halfKey);
    }

    /** 중계 기록 초기화 시 기록성 요약값도 함께 비움 */
    public void clearRecordSummary() {
        this.homeScore = null;
        this.awayScore = null;
        this.homeHits = null;
        this.awayHits = null;
        this.homeErrors = null;
        this.awayErrors = null;
        this.homeWalks = null;
        this.awayWalks = null;
        this.winningPitcherName = null;
        this.losingPitcherName = null;
        this.savePitcherName = null;
        this.status = GameStatus.SCHEDULED;
    }

    public void addInningScore(InningScore inningScore) {
        if (inningScore == null) {
            return;
        }
        inningScores.add(inningScore);
        inningScore.setGame(this);
    }

    public void removeInningScore(InningScore inningScore) {
        if (inningScore == null) {
            return;
        }
        inningScores.remove(inningScore);
        if (inningScore.getGame() == this) {
            inningScore.setGame(null);
        }
    }

    public void addPlateAppearance(PlateAppearance plateAppearance) {
        if (plateAppearance == null) {
            return;
        }
        plateAppearances.add(plateAppearance);
        plateAppearance.setGame(this);
    }

    public void removePlateAppearance(PlateAppearance plateAppearance) {
        if (plateAppearance == null) {
            return;
        }
        plateAppearances.remove(plateAppearance);
        if (plateAppearance.getGame() == this) {
            plateAppearance.setGame(null);
        }
    }

    public enum GameStatus {
        SCHEDULED("예정"),
        IN_PROGRESS("진행중"),
        COMPLETED("종료"),
        POSTPONED("연기"),
        CANCELLED("취소");

        private final String displayName;

        GameStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
