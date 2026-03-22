package com.baseball.domain.game;

import com.baseball.domain.player.Player;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 타석 기록 (한 타자의 타석 결과).
 * 1회초/1회말 등 이닝·공격팀, 타자/투수, 결과, 승리확률, 투구 목록을 가집니다.
 */
@Entity
@Table(name = "plate_appearances", indexes = {
        @Index(name = "idx_plate_app_game_inning", columnList = "game_id, inning, is_top")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PlateAppearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    /** 이닝 (1~9) */
    @Column(nullable = false)
    private Integer inning;

    /** true = 초(원정 공격), false = 말(홈 공격) */
    @Column(name = "is_top", nullable = false)
    private Boolean isTop;

    /** 해당 이닝 내 타석 순서 */
    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Column(name = "batter_name", nullable = false, length = 100)
    private String batterName;

    @Column(name = "pitcher_name", nullable = false, length = 100)
    private String pitcherName;

    /** 타순 (1~9). 없으면 null */
    private Integer batterOrder;

    /** 타자 구분: true=주전(해당 팀/타순 첫 등장), false=후보/교체 */
    @Column(name = "batter_is_starter")
    private Boolean batterIsStarter;

    /** 타자 교체 유형: 대타/대주자 구분 */
    @Enumerated(EnumType.STRING)
    @Column(name = "batter_substitution_type", length = 20)
    private BatterSubstitutionType batterSubstitutionType;

    /** 투수 구분: true=선발(해당 팀 수비 첫 등장), false=교체 */
    @Column(name = "pitcher_is_starter")
    private Boolean pitcherIsStarter;

    /** 타석 결과 요약 (예: "삼진 아웃", "1루수 땅볼 아웃") */
    @Column(name = "result_text", length = 200)
    private String resultText;

    /** 타석 전 승리 확률 (공격팀 기준, 0~100). null 가능 */
    @Column(name = "win_probability_before")
    private Double winProbabilityBefore;

    /** 타석 후 승리 확률 (공격팀 기준). null 가능 */
    @Column(name = "win_probability_after")
    private Double winProbabilityAfter;

    /** 주자 진루·아웃 등 (한 줄씩 \n 구분). null 가능 */
    @Column(name = "runner_plays_text", length = 2000)
    private String runnerPlaysText;

    /** DB에 등록된 타자 선수 (선택) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batter_id")
    private Player batter;

    /** DB에 등록된 투수 선수 (선택) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pitcher_id")
    private Player pitcher;

    @OneToMany(mappedBy = "plateAppearance", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pitchOrder")
    @Builder.Default
    private List<Pitch> pitches = new ArrayList<>();

    public void addPitch(Pitch pitch) {
        pitches.add(pitch);
        pitch.setPlateAppearance(this);
    }

    /** 주자 플레이 목록 (화면 표시용, runnerPlaysText를 줄 단위로 분리) */
    public List<String> getRunnerPlaysList() {
        if (runnerPlaysText == null || runnerPlaysText.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(runnerPlaysText.split("\n"));
    }

    /** 득점(홈인/득점) 발생 타석 여부. 기록 탭 '득점' 필터용 */
    public boolean isRunScoring() {
        if (resultText != null && (resultText.contains("홈인") || resultText.contains("득점"))) {
            return true;
        }
        for (String play : getRunnerPlaysList()) {
            if (play != null && (play.contains("홈인") || play.contains("득점"))) {
                return true;
            }
        }
        return false;
    }

    private static final String OUT_EMPHASIS_HTML = "<strong class=\"out-emphasis\">아웃</strong>";
    private static final String ERROR_EMPHASIS_HTML = "<strong class=\"error-emphasis\">실책</strong>";
    /** 홈인: 부모 .pa-away / .pa-home 에 따라 팀 색 적용 */
    private static final String RUN_EMPHASIS_HTML = "<strong class=\"run-emphasis\">홈인</strong>";

    private static final String OUT_TOKEN = "아웃";

    /**
     * 화면에 표시할 타석 순서(이닝·초말·타순 정렬 후 목록)대로 넘기면,
     * 각 회초·말마다 아웃 카운트를 1부터 올려 {@code 1아웃}, {@code 2아웃}, {@code 3아웃} 형태로 강조 HTML을 만든다.
     * (타석 결과 줄 → 그 타석의 주자 플레이 줄 순, 타석 간에는 카운트 유지)
     */
    public record EmphasisHtml(Map<Long, String> resultById, Map<Long, List<String>> runnerLinesById) {}

    public static EmphasisHtml buildEmphasisHtml(List<PlateAppearance> displayOrder) {
        Map<Long, String> res = new LinkedHashMap<>();
        Map<Long, List<String>> runners = new LinkedHashMap<>();
        if (displayOrder == null || displayOrder.isEmpty()) {
            return new EmphasisHtml(Map.of(), Map.of());
        }
        int[] outsInHalf = {0};
        PlateAppearance prev = null;
        for (PlateAppearance pa : displayOrder) {
            resetOutCountIfNewHalf(pa, prev, outsInHalf);
            if (pa.getId() != null) {
                res.put(pa.getId(), emphasizeResultAndRunnerLine(pa.getResultText(), outsInHalf));
                List<String> lines = pa.getRunnerPlaysList();
                if (lines.isEmpty()) {
                    runners.put(pa.getId(), List.of());
                } else {
                    List<String> built = new ArrayList<>(lines.size());
                    for (String line : lines) {
                        built.add(emphasizeResultAndRunnerLine(line, outsInHalf));
                    }
                    runners.put(pa.getId(), built);
                }
            }
            prev = pa;
        }
        return new EmphasisHtml(res, runners);
    }

    private static void resetOutCountIfNewHalf(PlateAppearance pa, PlateAppearance prev, int[] outsInHalf) {
        if (pa.getInning() == null || pa.getIsTop() == null) {
            return;
        }
        if (prev == null) {
            outsInHalf[0] = 0;
            return;
        }
        if (prev.getInning() == null || prev.getIsTop() == null) {
            return;
        }
        if (!Objects.equals(prev.getInning(), pa.getInning()) || !Objects.equals(prev.getIsTop(), pa.getIsTop())) {
            outsInHalf[0] = 0;
        }
    }

    private static String emphasizeResultAndRunnerLine(String text, int[] outsInHalf) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = replaceOutsWithNumberedEmphasis(text, outsInHalf);
        return s.replace("실책", ERROR_EMPHASIS_HTML).replace("홈인", RUN_EMPHASIS_HTML);
    }

    /**
     * 한 줄 안 "아웃"이 한 번이면 N아웃만 붙이고, 두 번 이상이면
     * <strong>첫 번째는 번호 없이 아웃만</strong> 강조하고 나머지는 N아웃(같은 이닝 카운트)으로 감싼다.
     * (예: "땅볼 아웃 (1루 터치아웃)" → 땅볼 아웃 + 터치1아웃)
     */
    private static String replaceOutsWithNumberedEmphasis(String text, int[] outsInHalf) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!text.contains(OUT_TOKEN)) {
            return text;
        }
        outsInHalf[0]++;
        int n = outsInHalf[0];
        String numberedRepl = "<strong class=\"out-emphasis\">" + n + "아웃</strong>";
        List<Integer> positions = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = text.indexOf(OUT_TOKEN, from);
            if (idx < 0) {
                break;
            }
            positions.add(idx);
            from = idx + OUT_TOKEN.length();
        }
        if (positions.isEmpty()) {
            return text;
        }
        if (positions.size() == 1) {
            return text.replace(OUT_TOKEN, numberedRepl);
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = positions.size() - 1; i >= 0; i--) {
            int pos = positions.get(i);
            String repl = (i == 0) ? OUT_EMPHASIS_HTML : numberedRepl;
            sb.replace(pos, pos + OUT_TOKEN.length(), repl);
        }
        return sb.toString();
    }

    /** 타석 결과 문구에서 '아웃'·'실책'·'홈인'을 강조용 HTML로 감싼 문자열 (화면용, th:utext 사용) */
    public String getResultTextWithOutEmphasis() {
        if (resultText == null || resultText.isEmpty()) {
            return "";
        }
        return resultText.replace(OUT_TOKEN, OUT_EMPHASIS_HTML)
                .replace("실책", ERROR_EMPHASIS_HTML)
                .replace("홈인", RUN_EMPHASIS_HTML);
    }

    /** 주자 플레이 목록에서 '아웃'·'실책'·'홈인'을 강조용 HTML로 감싼 목록 (화면용, th:utext 사용) */
    public List<String> getRunnerPlaysListWithOutEmphasis() {
        return getRunnerPlaysList().stream()
                .map(play -> play.replace(OUT_TOKEN, OUT_EMPHASIS_HTML)
                        .replace("실책", ERROR_EMPHASIS_HTML)
                        .replace("홈인", RUN_EMPHASIS_HTML))
                .collect(Collectors.toList());
    }

    void setGame(Game game) {
        this.game = game;
    }
}
